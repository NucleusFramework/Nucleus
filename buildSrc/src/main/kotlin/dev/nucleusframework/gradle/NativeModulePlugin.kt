package dev.nucleusframework.gradle

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File

/**
 * Owns the build wiring shared by every module that compiles a JNI bridge into
 * `src/main/resources/nucleus/native/<arch>/`.
 *
 * ```kotlin
 * plugins { id("nucleus.native-module") }
 *
 * nucleusNative {
 *     macos("nucleus_darkmode")
 *     linux("nucleus_linux_theme")
 *     windows("nucleus_windows_theme")
 * }
 * ```
 *
 * Each call registers the corresponding `buildNative*` task with the module's
 * inputs/outputs, the host-OS and prebuilt-artifact guards, and the
 * `processResources` / `sourcesJar` dependencies.
 */
class NativeModulePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create<NativeModuleExtension>(NativeModuleExtension.NAME, target)
    }
}

/** The `nucleusNative { }` block contributed by [NativeModulePlugin]. */
open class NativeModuleExtension(
    private val project: Project,
) {
    /**
     * Registers `buildNativeWindows` for the `nucleus_*.dll` produced by
     * `src/main/native/windows/build.bat`.
     *
     * @param library base library name, without the `.dll` extension
     */
    fun windows(
        library: String,
        description: String = NativeTarget.WINDOWS.defaultDescription,
    ): TaskProvider<Exec> = register(NativeTarget.WINDOWS, library, description)

    /**
     * Registers `buildNativeMacOs` for the `libnucleus_*.dylib` produced by
     * `src/main/native/macos/build.sh`.
     *
     * @param library base library name, without the `lib` prefix and `.dylib` extension
     */
    fun macos(
        library: String,
        description: String = NativeTarget.MACOS.defaultDescription,
    ): TaskProvider<Exec> = register(NativeTarget.MACOS, library, description)

    /**
     * Registers `buildNativeLinux` for the `libnucleus_*.so` produced by
     * `src/main/native/linux/build.sh`.
     *
     * @param library base library name, without the `lib` prefix and `.so` extension
     */
    fun linux(
        library: String,
        description: String = NativeTarget.LINUX.defaultDescription,
    ): TaskProvider<Exec> = register(NativeTarget.LINUX, library, description)

    private fun register(
        target: NativeTarget,
        library: String,
        description: String,
    ): TaskProvider<Exec> {
        val nativeRoot = project.layout.projectDirectory.dir("src/main/native")
        val nativeDir = nativeRoot.dir(target.sourceDirName).asFile
        val resourceDir = project.layout.projectDirectory.dir(NATIVE_RESOURCE_PATH)
        val libraryFileName = target.fileNameOf(library)
        val prebuiltCopies = target.resourceDirs.map { File(resourceDir.dir(it).asFile, libraryFileName) }
        val loaderCacheDir = loaderCacheDir()

        // CI downloads every platform's libraries into the resources before the
        // build (see build-natives.yaml), so recompiling them there is pure waste.
        // Locally the guard stays off: editing a native source must rebuild even
        // though the previous artifact is still sitting in the resources.
        val skipWhenPrebuilt = project.providers.environmentVariable("CI").orNull == "true"

        val nativeSources =
            project.fileTree(nativeRoot).apply {
                // Rust modules keep the crate at the root of src/main/native and
                // only the launcher script under the per-OS directory.
                include("Cargo.toml", "Cargo.lock", "build.rs", "src/**")
                include("${target.sourceDirName}/**")
                exclude("target/**", "vendor/**")
            }

        val task =
            project.tasks.register<Exec>(target.taskName) {
                group = "build"
                this.description = description
                workingDir(nativeDir)
                commandLine(target.commandLine(nativeDir))
                inputs
                    .files(nativeSources)
                    .withPropertyName("nativeSources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.dir(resourceDir).withPropertyName("nativeLibraries")
                onlyIf("native build task matches the current host OS") { target.isHost }
                if (skipWhenPrebuilt) {
                    onlyIf("$libraryFileName is already present in the module resources") {
                        prebuiltCopies.none(File::exists)
                    }
                }
                // NativeLibraryLoader extracts into a content-addressed directory,
                // but copies written before that scheme (or by a bare ./build.sh
                // run) can still shadow the library that was just compiled.
                doLast { evictFromLoaderCache(loaderCacheDir, libraryFileName) }
            }

        project.plugins.withType<JavaPlugin>().configureEach {
            project.tasks.named<Task>(JavaPlugin.PROCESS_RESOURCES_TASK_NAME).configure { dependsOn(task) }
        }
        // Registered by the publishing plugin, which may not be applied yet.
        project.tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(task) }

        return task
    }

    /** Mirrors `NativeLibraryLoader.resolveCacheDir()` in `core-runtime`. */
    private fun loaderCacheDir(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val userHome = System.getProperty("user.home")
        val base =
            when {
                os.contains("win") ->
                    project.providers
                        .environmentVariable("LOCALAPPDATA")
                        .orNull
                        ?.let(::File)
                        ?: File(userHome, "AppData/Local")
                os.contains("mac") -> File(userHome, "Library/Caches")
                else ->
                    project.providers
                        .environmentVariable("XDG_CACHE_HOME")
                        .orNull
                        ?.let(::File)
                        ?: File(userHome, ".cache")
            }
        return File(base, "nucleus/native")
    }

    companion object {
        const val NAME = "nucleusNative"
    }
}

/** The host OS a `buildNative*` task compiles for. */
enum class NativeTarget(
    val taskName: String,
    val sourceDirName: String,
    val resourceDirs: List<String>,
    val defaultDescription: String,
    private val libraryPrefix: String,
    private val librarySuffix: String,
) {
    WINDOWS(
        taskName = "buildNativeWindows",
        sourceDirName = "windows",
        resourceDirs = listOf("win32-x64", "win32-aarch64"),
        defaultDescription = "Compiles the JNI bridge into Windows DLLs (x64 + ARM64)",
        libraryPrefix = "",
        librarySuffix = ".dll",
    ),
    MACOS(
        taskName = "buildNativeMacOs",
        sourceDirName = "macos",
        resourceDirs = listOf("darwin-aarch64", "darwin-x64"),
        defaultDescription = "Compiles the JNI bridge into macOS dylibs (arm64 + x64)",
        libraryPrefix = "lib",
        librarySuffix = ".dylib",
    ),
    LINUX(
        taskName = "buildNativeLinux",
        sourceDirName = "linux",
        resourceDirs = listOf("linux-x64", "linux-aarch64"),
        defaultDescription = "Compiles the JNI bridge into Linux shared libraries (.so)",
        libraryPrefix = "lib",
        librarySuffix = ".so",
    ),
    ;

    /** `nucleus_tao` → `nucleus_tao.dll` / `libnucleus_tao.dylib` / `libnucleus_tao.so`. */
    fun fileNameOf(library: String): String = "$libraryPrefix$library$librarySuffix"

    /** Whether the current machine can run this target's build script. */
    val isHost: Boolean
        get() =
            when (this) {
                WINDOWS -> Os.isFamily(Os.FAMILY_WINDOWS)
                MACOS -> Os.isFamily(Os.FAMILY_MAC)
                LINUX -> Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)
            }

    /**
     * The script invocation. Always an absolute path: `cmd /c build.bat` does not
     * resolve from the working directory on machines that set
     * `NoDefaultCurrentDirectoryInExePath`.
     */
    fun commandLine(nativeDir: File): List<String> =
        when (this) {
            WINDOWS -> listOf("cmd", "/c", File(nativeDir, "build.bat").absolutePath)
            MACOS, LINUX -> listOf("bash", File(nativeDir, "build.sh").absolutePath)
        }
}

private const val NATIVE_RESOURCE_PATH = "src/main/resources/nucleus/native"

private fun evictFromLoaderCache(
    cacheDir: File,
    libraryFileName: String,
) {
    if (!cacheDir.isDirectory) return
    cacheDir
        .walkTopDown()
        .filter { it.isFile && it.name == libraryFileName }
        .forEach { it.delete() }
}
