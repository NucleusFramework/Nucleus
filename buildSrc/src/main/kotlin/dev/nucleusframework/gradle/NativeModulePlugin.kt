package dev.nucleusframework.gradle

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
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

    /**
     * Declares libraries a dependency of this module ships under `nucleus/native/` and this
     * module loads through `NativeLibraryLoader`, so the Nucleus Gradle plugin moves them out of
     * that dependency's JAR together with the module's own.
     *
     * @param library library file name, e.g. `libGLESv2.dll`
     * @param target the platform the dependency ships it for
     */
    fun dependencyLibraries(
        target: NativeTarget,
        vararg library: String,
    ) {
        nativeLibrariesManifest.configure {
            dependencyEntries.addAll(target.resourceDirs.flatMap { dir -> library.map { "nucleus/native/$dir/$it" } })
        }
    }

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
                // Vendored tao is patched in-tree (#533 discrete scroll lives
                // there). Other vendor trees (accesskit forks, ANGLE headers)
                // are large and rarely change independently of `src/**`.
                include("vendor/tao/**")
                exclude("**/target/**", "vendor/accesskit_*/**", "vendor/angle-headers/**")
                // The build scripts drop their intermediates next to the sources
                // (cl.exe writes .obj into the working directory, cargo leaves
                // marker files in the vendor trees). Tracking them as inputs made
                // every native task out-of-date on the run right after it built.
                exclude(GENERATED_ARTIFACTS)
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
                inputs
                    .file(project.rootProject.layout.projectDirectory.file("native-common/nucleus_jni.h"))
                    .withPropertyName("nucleusJniHeader")
                    .optional()
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
        // Kotlin Multiplatform: the JVM target's resources, see [jvmResources].
        project.tasks.matching { it.name == KMP_JVM_PROCESS_RESOURCES }.configureEach { dependsOn(task) }
        // Registered by the publishing plugin, which may not be applied yet.
        project.tasks.matching { it.name == "sourcesJar" || it.name == KMP_JVM_SOURCES_JAR }.configureEach {
            dependsOn(task)
        }
        nativeLibrariesManifest.configure { dependsOn(task) }

        return task
    }

    /**
     * What a Kotlin Multiplatform module adds to its JVM target's resources, since only
     * `java`-based modules get it wired automatically:
     *
     * ```kotlin
     * kotlin.sourceSets.jvmMain { resources.srcDirs(nucleusNative.jvmResources) }
     * ```
     *
     * The libraries stay in `src/main/resources/nucleus/native` for every module, which is
     * where the CI workflows download and verify them.
     */
    val jvmResources: FileCollection
        get() = project.files(project.layout.projectDirectory.dir(NATIVE_RESOURCE_ROOT), nativeLibrariesManifest)

    /**
     * Lists the module's libraries under `META-INF/nucleus/native-libraries/`, so the Nucleus
     * Gradle plugin moves those — and only those — out of the JARs of a packaged application.
     * The file name is unique per module so the list survives the GraalVM uber JAR's merge.
     */
    private val nativeLibrariesManifest: TaskProvider<NativeLibrariesManifestTask> by lazy {
        val manifest =
            project.tasks.register<NativeLibrariesManifestTask>("generateNativeLibrariesManifest") {
                nativeLibraries.from(
                    project.fileTree(project.layout.projectDirectory.dir(NATIVE_RESOURCE_PATH)) {
                        include("*/*")
                        exclude("**/.*")
                    },
                )
                manifestName.set("nucleus.${project.name}")
                outputDir.set(project.layout.buildDirectory.dir("generated/nucleus-native-libraries"))
            }
        project.plugins.withType<JavaPlugin>().configureEach {
            project.extensions
                .getByType<JavaPluginExtension>()
                .sourceSets
                .named(SourceSet.MAIN_SOURCE_SET_NAME)
                .configure { resources.srcDir(manifest) }
        }
        manifest
    }

    /**
     * Mirrors `NativeLibraryLoader.defaultCacheDir()` in `core-runtime`.
     *
     * Deliberately only the platform default: an application that relocates its
     * cache (`NativeLibraryLoader.CACHE_DIR_PROPERTY` / `cacheDirectory`) does so
     * in its own JVM, which this build never sees, so guessing an override here
     * would evict a directory nothing reads and leave the real one untouched.
     * Developers running with a relocated cache clear it themselves.
     */
    private fun loaderCacheDir(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val userHome = System.getProperty("user.home")

        // Blank or relative values are ignored, exactly as the loader does:
        // evicting a relative directory would miss the cache actually in use.
        fun envDir(name: String): File? =
            project.providers
                .environmentVariable(name)
                .orNull
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isAbsolute }

        val base =
            when {
                os.contains("win") -> envDir("LOCALAPPDATA") ?: File(userHome, "AppData/Local")
                os.contains("mac") -> File(userHome, "Library/Caches")
                else -> envDir("XDG_CACHE_HOME") ?: File(userHome, ".cache")
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

private const val NATIVE_RESOURCE_ROOT = "src/main/resources"
private const val NATIVE_RESOURCE_PATH = "$NATIVE_RESOURCE_ROOT/nucleus/native"
private const val KMP_JVM_PROCESS_RESOURCES = "jvmProcessResources"
private const val KMP_JVM_SOURCES_JAR = "jvmSourcesJar"

/**
 * Writes `META-INF/nucleus/native-libraries/<manifestName>`: one `nucleus/native/<arch>/<file>`
 * JAR entry per line, for every library the module ships (sidecars included) plus the
 * [dependencyEntries] it loads from a dependency's JAR.
 */
abstract class NativeLibrariesManifestTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeLibraries: ConfigurableFileCollection

    /** `nucleus/native/<arch>/<file>` entries shipped by a dependency, see `dependencyLibraries`. */
    @get:Input
    abstract val dependencyEntries: ListProperty<String>

    @get:Input
    abstract val manifestName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Rewrites the manifest from the libraries currently in the module resources. */
    @TaskAction
    fun generate() {
        val entries =
            nativeLibraries.asFileTree.files
                .map { "nucleus/native/${it.parentFile.name}/${it.name}" }
                .plus(dependencyEntries.get())
                .distinct()
                .sorted()
        val root = outputDir.get().asFile
        root.deleteRecursively()
        File(root, "META-INF/nucleus/native-libraries/${manifestName.get()}").apply {
            parentFile.mkdirs()
            writeText(entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n"))
        }
    }
}

/**
 * Build by-products the native scripts leave inside `src/main/native`. They are
 * derived from the sources, never edited, and must not take part in the
 * up-to-date check.
 */
private val GENERATED_ARTIFACTS =
    listOf(
        "**/*.obj",
        "**/*.o",
        "**/*.lib",
        "**/*.exp",
        "**/*.pdb",
        "**/*.ilk",
        "**/*.d",
        "**/*.dll",
        "**/*.so",
        "**/*.dylib",
        "**/build_log.txt",
        "**/.cargo-ok",
        "**/.cargo_vcs_info.json",
    )

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
