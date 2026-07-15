@file:Suppress("ktlint:standard:filename")

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.FileAssociation
import dev.nucleusframework.desktop.application.dsl.GraalvmSettings
import dev.nucleusframework.desktop.application.dsl.PackagingBackend
import dev.nucleusframework.desktop.application.dsl.UrlProtocol
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import dev.nucleusframework.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import dev.nucleusframework.desktop.application.tasks.AbstractElectronBuilderPackageTask
import dev.nucleusframework.desktop.application.tasks.AbstractNotarizationTask
import dev.nucleusframework.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import dev.nucleusframework.internal.utils.executableName
import dev.nucleusframework.internal.utils.uppercaseFirstChar
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.File

private val graalvmDefaultJvmArgs: List<String> =
    buildList {
        add("-D$CONFIGURE_SWING_GLOBALS=true")
        if (currentOS == OS.MacOS) {
            add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
            add("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
            add("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
    }

@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun JvmApplicationContext.configureGraalvmApplication() {
    val graalvm = app.graalvm
    val javaToolchains = project.extensions.getByType(JavaToolchainService::class.java)

    val graalvmLauncher =
        javaToolchains.launcherFor { spec ->
            spec.languageVersion.set(JavaLanguageVersion.of(graalvm.javaLanguageVersion.get()))
            if (graalvm.jvmVendor.isPresent) {
                spec.vendor.set(graalvm.jvmVendor)
            }
        }

    val graalvmHome =
        graalvmLauncher.map { launcher ->
            launcher.metadata.installationPath.asFile.absolutePath
        }

    val nativeImageConfigDir = graalvm.nativeImageConfigBaseDir
    val mainClassName = app.mainClass

    // ── Uber JAR (reuse existing task) ──

    // We need the uber JAR from the existing pipeline (respects build type classifier)
    val uberJarTaskName = "package${buildType.classifier.uppercaseFirstChar()}UberJarForCurrentOS"
    val packageUberJar = project.tasks.named(uberJarTaskName, Jar::class.java)

    // ── runWithNativeAgent ──
    // Agent writes to a temp dir, then automatically merges into the real config
    // without overwriting manually enriched entries (e.g. allDeclaredFields).

    val agentTempDir = appTmpDir.map { it.dir("graalvm/agentOutput") }

    val runWithNativeAgent =
        tasks.register<JavaExec>(
            taskNameAction = "run",
            taskNameObject = "withNativeAgent",
        ) {
            description = "Run the app with the GraalVM native-image-agent to collect reflection metadata"

            mainClass.set(app.mainClass)
            setExecutable(
                graalvmLauncher
                    .get()
                    .executablePath.asFile.absolutePath,
            )

            useAppRuntimeFiles { (runtimeJars, _) ->
                classpath = runtimeJars
            }

            jvmArgs =
                buildList {
                    addAll(graalvmDefaultJvmArgs)
                    addAll(
                        app.jvmArgs.filter { arg ->
                            // Exclude jpackage-specific artificial args
                            !arg.startsWith("-splash:\$APPDIR/") &&
                                !arg.startsWith("-D$APP_EXECUTABLE_TYPE=") &&
                                !arg.startsWith("-D$APP_RESOURCES_DIR=")
                        },
                    )

                    if (currentOS == OS.MacOS) {
                        val dockName =
                            app.nativeDistributions.appName
                                ?: app.nativeDistributions.packageName
                                ?: project.name
                        add("-Dapple.awt.application.name=$dockName")
                    }

                    val tempDir =
                        agentTempDir
                            .get()
                            .asFile
                            .apply { mkdirs() }
                            .absolutePath
                    add("-agentlib:native-image-agent=config-output-dir=$tempDir")
                }

            args = app.args

            // Capture all values at configuration time to avoid serializing
            // JvmApplicationContext into the configuration cache.
            val resolvedTargetDir: File =
                if (nativeImageConfigDir.isPresent) {
                    nativeImageConfigDir.get().asFile
                } else {
                    project.layout.projectDirectory
                        .dir("graalvm")
                        .asFile
                }
            val resolvedAgentDir: File = agentTempDir.get().asFile
            val resolvedPlatform: String =
                when (currentOS) {
                    OS.Windows -> "windows"
                    OS.MacOS -> "macos"
                    OS.Linux -> "linux"
                }
            val resolvedMainClass: String? = mainClassName
            val resolvedRepoDirsFile: File = appTmpDir.get().file("graalvm/metadataRepoDirs.txt").asFile
            val resolvedStaticDir: File = appTmpDir.get().dir("graalvm/staticAnalysis").asFile
            val resolvedLibraryMetadataDir: File = appTmpDir.get().dir("graalvm/libraryMetadata").asFile

            // After the agent finishes, merge results into the real config
            doLast {
                mergeReachabilityMetadata(resolvedAgentDir, resolvedTargetDir)

                // Also merge individual config files the agent may produce
                listOf(
                    "reflect-config.json",
                    "jni-config.json",
                    "resource-config.json",
                    "proxy-config.json",
                    "serialization-config.json",
                ).forEach { fileName ->
                    mergeJsonArrayConfig(
                        agentFile = File(resolvedAgentDir, fileName),
                        targetFile = File(resolvedTargetDir, fileName),
                    )
                }

                // Deduplicate: remove entries already provided by library JARs (L1),
                // plugin platform metadata (L3), Oracle repo (L2), static analysis,
                // and native-image.properties resource patterns.
                val runtimeClasspath = classpath.files

                // Collect extra metadata directories: Oracle repo (L2), static analysis, library metadata (L1)
                val extraDirs = mutableListOf<File>()
                if (resolvedRepoDirsFile.exists()) {
                    resolvedRepoDirsFile.readLines().filter { it.isNotBlank() }.forEach { extraDirs.add(File(it)) }
                }
                if (resolvedStaticDir.isDirectory) {
                    extraDirs.add(resolvedStaticDir)
                }
                if (resolvedLibraryMetadataDir.isDirectory) {
                    extraDirs.add(resolvedLibraryMetadataDir)
                }

                deduplicateAgainstLibraryMetadata(runtimeClasspath, resolvedTargetDir, resolvedPlatform, resolvedMainClass, extraDirs)

                logger.lifecycle("Native-image agent config merged into: $resolvedTargetDir")
            }
        }

    // ── Platform-specific pre-compile tasks ──

    val nativeCompileDir = appTmpDir.map { it.dir("graalvm/nativeCompile") }
    val imageName = graalvm.imageName.orElse(packageNameProvider)
    val binaryName = imageName.map { executableName(it) }

    // macOS: compile C stubs (built-in stub unless user overrides via cStubsSrc)
    val compileStubs =
        if (currentOS == OS.MacOS) {
            // Resolve the stub source file and output at configuration time to
            // avoid capturing DSL/Project references in the doLast closure,
            // which would break the configuration cache.
            val resolvedStubSrc: File? =
                graalvm.macOS.cStubsSrc.orNull
                    ?.asFile
            val stubOutFile: File = appTmpDir.get().asFile.resolve("graalvm/cursor_stub.o")
            val stubCFile: File = appTmpDir.get().asFile.resolve("graalvm/cursor_stub.c")

            tasks.register<DefaultTask>(
                taskNameAction = "compile",
                taskNameObject = "graalvmStubs",
            ) {
                description = "Compile C stubs for symbols referenced by AWT flat-namespace dylibs"

                outputs.file(stubOutFile)
                if (resolvedStubSrc != null) {
                    inputs.file(resolvedStubSrc)
                }

                doLast {
                    val srcFile =
                        resolvedStubSrc ?: run {
                            // Generate the default no-op stub in the temp dir.
                            stubCFile.parentFile.mkdirs()
                            stubCFile.writeText(
                                """
                                /* Stub for the removed java.awt.Cursor.finalizeImpl() native method.
                                   libawt.dylib was compiled with -flat_namespace and references this symbol.
                                   A no-op stub exports the symbol so dyld can satisfy the reference at load time. */
                                void Java_java_awt_Cursor_finalizeImpl(void) {}

                                /* Initialize the JDK platform ("JNU") encoding on the *bundled* libjava.dylib.
                                   Oracle GraalVM's native image links its own static libjava, but the bundled
                                   AWT dylibs (libawt/libfontmanager) load @rpath/libjava.dylib — a separate
                                   copy whose C `fastEncoding` global is never initialized, so the first AWT
                                   JNI_OnLoad C->Java string conversion aborts with "platform encoding not
                                   initialized". We dlopen the *same* bundled dylib (dyld shares it by path
                                   with the one the AWT libs load) and call its exported InitializeEncoding,
                                   using the JNIEnv passed from SubstrateVM. Compiled into the image and linked
                                   via -H:NativeLinkerOption; invoked from PlatformEncodingInitializer. */
                                #include <dlfcn.h>
                                typedef void (*nucleus_init_enc_t)(void *env, const char *name);
                                void nucleus_init_platform_encoding(void *env) {
                                    nucleus_init_enc_t fn = (nucleus_init_enc_t) dlsym(RTLD_DEFAULT, "InitializeEncoding");
                                    if (!fn) {
                                        void *h = dlopen("@executable_path/libjava.dylib", RTLD_GLOBAL | RTLD_NOW);
                                        if (h) fn = (nucleus_init_enc_t) dlsym(h, "InitializeEncoding");
                                    }
                                    if (fn) fn(env, "UTF-8");
                                }
                                """.trimIndent(),
                            )
                            stubCFile
                        }

                    stubOutFile.parentFile.mkdirs()
                    val process =
                        ProcessBuilder("clang", "-c", srcFile.absolutePath, "-o", stubOutFile.absolutePath)
                            .inheritIO()
                            .start()
                    check(process.waitFor() == 0) { "clang failed compiling $srcFile" }
                }
            }
        } else {
            null
        }

    // ── Default resources (icons, entitlements) — reuse the one from configureJvmApplication ──

    val unpackDefaultResources =
        project.tasks.named(
            "unpackDefaultComposeDesktopJvmApplicationResources",
            AbstractUnpackDefaultApplicationResourcesTask::class.java,
        )

    // Windows: generate .rc resource and compile to .res
    val generateWindowsResources =
        if (currentOS == OS.Windows) {
            // Capture all DSL values at configuration time to avoid serializing
            // Project/SourceSet references into the configuration cache.
            val winPkgName = packageNameProvider
            val winPkgVersion =
                provider {
                    app.nativeDistributions.windows.exePackageVersion
                        ?: app.nativeDistributions.windows.packageVersion
                        ?: app.nativeDistributions.packageVersion
                        ?: "1.0.0"
                }
            val winCopyright = provider { app.nativeDistributions.copyright ?: "" }
            // FileDescription is the string Windows Task Manager shows as the process
            // "Name", so it must carry the human app name (appName), not the description.
            // Falls back to packageName when appName is unset.
            val winDisplayName =
                provider {
                    app.nativeDistributions.appName
                        ?: app.nativeDistributions.packageName
                        ?: packageNameProvider.get()
                }
            val winIconFile =
                app.nativeDistributions.windows.iconFile
                    .orElse(unpackDefaultResources.flatMap { it.resources.windowsIcon })

            tasks.register<DefaultTask>(
                taskNameAction = "generate",
                taskNameObject = "graalvmWindowsResources",
            ) {
                dependsOn(unpackDefaultResources)
                description = "Generate and compile Windows resource file (.rc -> .res) for native image icon and version info"

                val rcFile = appTmpDir.map { it.file("graalvm/icon.rc") }
                val resFile = appTmpDir.map { it.file("graalvm/icon.res") }

                outputs.file(resFile)
                inputs.file(winIconFile)
                inputs.property("pkgName", winPkgName)
                inputs.property("pkgVersion", winPkgVersion)
                inputs.property("copyright", winCopyright)
                inputs.property("displayName", winDisplayName)
                inputs.property("imageName", imageName)

                doLast {
                    val rcDir = rcFile.get().asFile.parentFile
                    rcDir.mkdirs()

                    val pkgName = winPkgName.get()
                    val pkgVersion = winPkgVersion.get()
                    val copyright = winCopyright.get()
                    val displayName = winDisplayName.get()
                    val versionParts = pkgVersion.split(".").map { it.toIntOrNull() ?: 0 }
                    val v1 = versionParts.getOrElse(0) { 0 }
                    val v2 = versionParts.getOrElse(1) { 0 }
                    val v3 = versionParts.getOrElse(2) { 0 }
                    val v4 = versionParts.getOrElse(3) { 0 }

                    // Generate Windows side-by-side fusion manifest:
                    //  - DPI awareness (Per-Monitor V2)
                    //  - activeCodePage = UTF-8 (Windows 10 1903+) — works around a SubstrateVM
                    //    bug where LoadLibraryA receives UTF-8 bytes interpreted as the system
                    //    ANSI codepage, breaking native-image apps installed under non-ASCII
                    //    paths (Hebrew/Arabic/Cyrillic/CJK usernames). See oracle/graal#8095
                    //    and #10237 — the GraalVM team explicitly recommends this manifest.
                    //  - longPathAware: opt into >MAX_PATH paths.
                    val manifestFile = File(rcDir, "dpiaware.manifest")
                    manifestFile.writeText(
                        """
                        |<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        |<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0"
                        |          xmlns:asmv3="urn:schemas-microsoft-com:asm.v3">
                        |  <asmv3:application>
                        |    <asmv3:windowsSettings>
                        |      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/PM</dpiAware>
                        |      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2,PerMonitor</dpiAwareness>
                        |      <activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>
                        |      <longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware>
                        |    </asmv3:windowsSettings>
                        |  </asmv3:application>
                        |</assembly>
                        """.trimMargin(),
                    )

                    val rcContent =
                        buildString {
                            // The .rc is written as UTF-8 below; tell rc.exe to parse it as
                            // UTF-8 instead of the system ANSI codepage, otherwise non-ASCII
                            // VERSIONINFO strings (Hebrew/Arabic/CJK app names) become mojibake.
                            appendLine("#pragma code_page(65001)")
                            appendLine()
                            appendLine("1 ICON \"${winIconFile.get().asFile.absolutePath.replace("\\", "\\\\")}\"")
                            appendLine()
                            // Embed DPI-aware manifest (RT_MANIFEST = 24)
                            appendLine("1 24 \"${manifestFile.absolutePath.replace("\\", "\\\\")}\"")
                            appendLine()
                            appendLine("1 VERSIONINFO")
                            appendLine("FILEVERSION $v1,$v2,$v3,$v4")
                            appendLine("PRODUCTVERSION $v1,$v2,$v3,$v4")
                            appendLine("BEGIN")
                            appendLine("  BLOCK \"StringFileInfo\"")
                            appendLine("  BEGIN")
                            appendLine("    BLOCK \"040904B0\"")
                            appendLine("    BEGIN")
                            appendLine("      VALUE \"FileDescription\", \"$displayName\"")
                            appendLine("      VALUE \"FileVersion\", \"$pkgVersion\"")
                            appendLine("      VALUE \"InternalName\", \"$pkgName\"")
                            appendLine("      VALUE \"LegalCopyright\", \"$copyright\"")
                            appendLine("      VALUE \"OriginalFilename\", \"${imageName.get()}.exe\"")
                            appendLine("      VALUE \"ProductName\", \"$displayName\"")
                            appendLine("      VALUE \"ProductVersion\", \"$pkgVersion\"")
                            appendLine("    END")
                            appendLine("  END")
                            appendLine("  BLOCK \"VarFileInfo\"")
                            appendLine("  BEGIN")
                            appendLine("    VALUE \"Translation\", 0x0409, 0x04B0")
                            appendLine("  END")
                            appendLine("END")
                        }
                    rcFile.get().asFile.writeText(rcContent)

                    // Compile .rc to .res using rc.exe
                    val arch =
                        when (currentArch) {
                            Arch.X64 -> "x64"
                            Arch.Arm64 -> "arm64"
                        }
                    val rcExe =
                        WindowsKitsLocator.locateRc(arch)
                            ?: error(
                                "Could not locate rc.exe from Windows SDK. " +
                                    "Ensure Windows SDK is installed.",
                            )

                    val processBuilder =
                        ProcessBuilder(
                            rcExe.absolutePath,
                            "/fo",
                            resFile.get().asFile.absolutePath,
                            rcFile.get().asFile.absolutePath,
                        )
                    processBuilder.inheritIO()
                    val process = processBuilder.start()
                    val exitCode = process.waitFor()
                    check(exitCode == 0) { "rc.exe failed with exit code $exitCode" }
                }
            }
        } else {
            null
        }

    // ── Platform metadata (Level 3) ──
    // Write AWT/Java2D/font reflection entries for the current OS into a build directory
    // so native-image picks them up alongside the project's own metadata.

    val platformMetadataDir = appTmpDir.map { it.dir("graalvm/platformMetadata") }

    val generatePlatformMetadata =
        tasks.register<DefaultTask>(
            taskNameAction = "generate",
            taskNameObject = "graalvmPlatformMetadata",
        ) {
            description = "Generate platform-specific GraalVM metadata for AWT/Java2D and main class"
            inputs.property("mainClass", mainClassName ?: "")
            outputs.dir(platformMetadataDir)

            doLast {
                val platform =
                    when (currentOS) {
                        OS.Windows -> "windows"
                        OS.MacOS -> "macos"
                        OS.Linux -> "linux"
                    }
                writePlatformMetadata(platform, platformMetadataDir.get().asFile, mainClassName)
                logger.lifecycle("Platform metadata ($platform) written to: ${platformMetadataDir.get().asFile}")
            }
        }

    // ── Oracle GraalVM Reachability Metadata Repository ──
    // Resolves metadata from the community-maintained repository for runtime dependencies.
    // Uses a custom task that resolves both the ZIP and the runtime classpath at execution time.

    val metadataRepoDirsFile = appTmpDir.map { it.file("graalvm/metadataRepoDirs.txt") }
    val metadataRepoOutputDir = appTmpDir.map { it.dir("graalvm/metadataRepository") }

    // Wire the metadata ZIP via a detached configuration (FileCollection is config-cache safe)
    val metadataZipDep =
        project.dependencies.create(
            "org.graalvm.buildtools:graalvm-reachability-metadata:${graalvm.metadataRepository.version.get()}:repository@zip",
        )
    val metadataZipConfig =
        project.configurations
            .detachedConfiguration(metadataZipDep)
            .apply { isTransitive = false }

    // Wire runtime classpath (FileCollection is config-cache safe)
    val runtimeCfg =
        project.configurations.findByName("jvmRuntimeClasspath")
            ?: project.configurations.findByName("desktopRuntimeClasspath")
            ?: project.configurations.findByName("runtimeClasspath")

    val resolveReachabilityMetadata =
        project.tasks
            .register(
                "resolveGraalvmReachabilityMetadata",
                ResolveReachabilityMetadataTask::class.java,
            ).apply {
                configure { task ->
                    task.description =
                        "Resolve GraalVM reachability metadata from Oracle repository for runtime dependencies"
                    task.group = NUCLEUS_TASK_GROUP

                    task.repoEnabled.set(graalvm.metadataRepository.enabled)
                    task.repoVersion.set(graalvm.metadataRepository.version)
                    task.excludedModules.set(graalvm.metadataRepository.excludedModules)
                    task.moduleToConfigVersion.set(graalvm.metadataRepository.moduleToConfigVersion)
                    task.outputDirsFile.set(metadataRepoDirsFile.map { it.asFile })
                    task.extractionDir.set(metadataRepoOutputDir.map { it.asFile })

                    task.metadataZipFiles.from(metadataZipConfig)
                    if (runtimeCfg != null) {
                        task.runtimeClasspath.from(runtimeCfg)
                    }
                }
            }

    // ── Static bytecode analysis ──
    // Scans all runtime classpath JARs to auto-detect reflection, JNI, and resource
    // metadata that can be discovered statically (Class.forName, ServiceLoader,
    // native methods, etc.). Output is passed as an additional config directory.

    val staticMetadataDir = appTmpDir.map { it.dir("graalvm/staticAnalysis") }

    val analyzeStaticMetadata =
        project.tasks
            .register(
                "analyzeGraalvmStaticMetadata",
                AnalyzeStaticMetadataTask::class.java,
            ).apply {
                configure { task ->
                    task.description =
                        "Statically analyze bytecode to detect GraalVM reflection/JNI/resource metadata"
                    task.group = NUCLEUS_TASK_GROUP
                    task.outputDir.set(staticMetadataDir)
                    if (runtimeCfg != null) {
                        task.runtimeClasspath.from(runtimeCfg)
                    }
                    // Include the project's own compiled classes (not just dependency JARs)
                    // KMP projects use jvmMainClasses, standard JVM uses main sourceSet
                    val jvmMainClasses =
                        project.tasks.findByName("jvmMainClasses")
                            ?: project.tasks.findByName("compileKotlinJvm")
                            ?: project.tasks.findByName("compileKotlin")
                    if (jvmMainClasses != null) {
                        task.dependsOn(jvmMainClasses)
                    }
                    project.tasks.findByName("compileJava")?.let { task.dependsOn(it) }
                    // Add class output directories
                    for (dirPath in listOf(
                        "classes/kotlin/jvm/main",
                        "classes/kotlin/main",
                        "classes/java/main",
                    )) {
                        val classDir = project.layout.buildDirectory.dir(dirPath)
                        task.runtimeClasspath.from(classDir)
                    }
                }
            }

    // ── Per-library metadata filtering (L1) ──
    // Reads per-library JSON files from the plugin JAR, includes only those whose
    // matchPackages are found on the runtime classpath, and merges into a single output.

    val libraryMetadataDir = appTmpDir.map { it.dir("graalvm/libraryMetadata") }

    val filterLibraryMetadata =
        project.tasks
            .register(
                "filterGraalvmLibraryMetadata",
                FilterLibraryMetadataTask::class.java,
            ).apply {
                configure { task ->
                    task.description =
                        "Filter and merge per-library GraalVM metadata based on runtime classpath"
                    task.group = NUCLEUS_TASK_GROUP
                    task.outputDir.set(libraryMetadataDir)
                    if (runtimeCfg != null) {
                        task.runtimeClasspath.from(runtimeCfg)
                    }
                }
            }

    // ── Cleanup manual metadata ──
    // Removes entries from the project's reachability-metadata.json that are already
    // covered by L1 (library JARs), L2 (Oracle repo), L3 (platform), or static analysis.

    project.tasks
        .register(
            "cleanupGraalvmMetadata",
            CleanupGraalvmMetadataTask::class.java,
        ).apply {
            configure { task ->
                task.description =
                    "Remove entries from manual reachability-metadata.json that are already managed by Nucleus"
                task.group = NUCLEUS_TASK_GROUP
                task.dependsOn(resolveReachabilityMetadata)
                task.dependsOn(analyzeStaticMetadata)
                task.dependsOn(filterLibraryMetadata)

                if (runtimeCfg != null) {
                    task.runtimeClasspath.from(runtimeCfg)
                }
                task.metadataRepoDirsFile.set(project.layout.file(metadataRepoDirsFile.map { it.asFile }))
                task.staticAnalysisDir.from(staticMetadataDir)
                task.staticAnalysisDir.from(libraryMetadataDir)
                task.platformName.set(
                    when (currentOS) {
                        OS.Windows -> "windows"
                        OS.MacOS -> "macos"
                        OS.Linux -> "linux"
                    },
                )
                task.mainClass.set(mainClassName ?: "")
                task.configDir.set(
                    if (nativeImageConfigDir.isPresent) {
                        nativeImageConfigDir.get().asFile
                    } else {
                        project.layout.projectDirectory
                            .dir("graalvm")
                            .asFile
                    },
                )
            }
        }

    // ── nativeImageCompile ──

    val nativeImageCompile =
        tasks.register<Exec>(
            taskNameAction = "nativeImage",
            taskNameObject = "compile",
        ) {
            description = "Compile the application into a GraalVM native image"

            dependsOn(packageUberJar)
            dependsOn(generatePlatformMetadata)
            dependsOn(resolveReachabilityMetadata)
            dependsOn(analyzeStaticMetadata)
            dependsOn(filterLibraryMetadata)
            compileStubs?.let { dependsOn(it) }
            generateWindowsResources?.let { dependsOn(it) }

            val uberJarFile = packageUberJar.flatMap { it.archiveFile }
            inputs.file(uberJarFile)
            inputs.file(metadataRepoDirsFile).optional()
            val outputDir = nativeCompileDir.get().asFile
            outputs.dir(outputDir)

            val nativeImageExe =
                graalvmHome.map { home ->
                    val binDir = File(home).resolve("bin")
                    // BellSoft Liberica NIK ships native-image.cmd on Windows;
                    // Oracle GraalVM ships native-image.exe. Prefer .cmd if present.
                    if (currentOS == OS.Windows) {
                        val cmd = binDir.resolve("native-image.cmd")
                        if (cmd.exists()) {
                            cmd.absolutePath
                        } else {
                            binDir.resolve("native-image.exe").absolutePath
                        }
                    } else {
                        binDir.resolve("native-image").absolutePath
                    }
                }

            executable = nativeImageExe.get()

            // Control the minos in LC_BUILD_VERSION at link time
            if (currentOS == OS.MacOS) {
                environment("MACOSX_DEPLOYMENT_TARGET", graalvm.macOS.minimumSystemVersion.get())
            }

            // Resolve all project / provider references at configuration time
            // (config-cache safe) — only the exists() checks and file reads
            // need to happen at execution time.
            val resolvedConfigDir =
                if (nativeImageConfigDir.isPresent) {
                    nativeImageConfigDir.get().asFile
                } else {
                    project.layout.projectDirectory
                        .dir("graalvm")
                        .asFile
                }
            val resolvedLibraryMetadataDir = libraryMetadataDir.get().asFile
            val resolvedPlatformMetadataDir = platformMetadataDir.get().asFile
            val resolvedStaticMetadataDir = staticMetadataDir.get().asFile
            val resolvedMetadataRepoDirsFile = metadataRepoDirsFile.get().asFile
            val resolvedBuildArgs = graalvm.buildArgs.get()
            val resolvedMarch = graalvm.march.get()
            val resolvedOptimizationFlag = graalvm.optimization.orNull?.flag
            val resolvedAllCharsets = graalvm.allCharsets.get()
            val resolvedMlProfileInference = graalvm.mlProfileInference.get()
            val resolvedImageName = imageName.get()
            val resolvedUberJar = uberJarFile.get().asFile.absolutePath
            val resolvedMacOsMinVersion =
                if (currentOS == OS.MacOS) graalvm.macOS.minimumSystemVersion.get() else null
            val resolvedStubObj =
                if (currentOS == OS.MacOS && compileStubs != null) {
                    appTmpDir
                        .get()
                        .file("graalvm/cursor_stub.o")
                        .asFile.absolutePath
                } else {
                    null
                }
            val resolvedResFile =
                if (currentOS == OS.Windows && generateWindowsResources != null) {
                    appTmpDir
                        .get()
                        .file("graalvm/icon.res")
                        .asFile.absolutePath
                } else {
                    null
                }

            doFirst {
                outputDir.mkdirs()

                // Build args at execution time so that outputs from dependent tasks
                // (static analysis dir, metadata repo dirs file, …) exist on disk.
                args =
                    buildList {
                        add("-jar")
                        add(resolvedUberJar)
                        add("-o")
                        add(File(outputDir, resolvedImageName).absolutePath)
                        add("-march=$resolvedMarch")

                        // Optimization level. Placed before user buildArgs so an explicit -O* in
                        // buildArgs overrides it (native-image honors the last -O* flag).
                        if (resolvedOptimizationFlag != null) {
                            add(resolvedOptimizationFlag)
                        }

                        // Embed all JDK charsets when the app needs legacy encodings.
                        if (resolvedAllCharsets) {
                            add("-H:+AddAllCharsets")
                        }

                        // Opt out of Oracle GraalVM's default ML-inferred PGO profile. Placed before
                        // user buildArgs so an explicit override there still wins.
                        if (!resolvedMlProfileInference) {
                            add("-H:-MLProfileInference")
                        }

                        // macOS: force the link-time deployment target. native-image does NOT
                        // propagate MACOSX_DEPLOYMENT_TARGET to its internal linker, so the link
                        // otherwise defaults to the build SDK (e.g. 26.0). The static linker uses
                        // that target to resolve `$ld$previous$` symbols — classes Apple moved
                        // between dylibs across OS versions. `_OBJC_CLASS_$_NSPort` moved
                        // Foundation→CoreFoundation at macOS 13.0, so a ≥13.0 link binds it to
                        // CoreFoundation. vtool later patches minos down to this value, producing a
                        // binary that claims minos 12.0 but whose symbols resolve for ≥13.0 — it
                        // crashes on macOS 12.x with:
                        //   dyld: Symbol not found: _OBJC_CLASS_$_NSPort  Expected in: CoreFoundation
                        // Pinning the link target to minimumSystemVersion keeps symbol bindings in
                        // sync with the advertised minimum. (Liquid Glass is unaffected: the sdk
                        // field is set separately by the vtool patch.)
                        if (resolvedMacOsMinVersion != null) {
                            add("-H:NativeLinkerOption=-mmacosx-version-min=$resolvedMacOsMinVersion")
                        }

                        // macOS: link C stubs
                        if (resolvedStubObj != null) {
                            add("-H:NativeLinkerOption=$resolvedStubObj")
                        }

                        // Windows: link .res for icon + version info, configure subsystem
                        if (resolvedResFile != null) {
                            add("-H:NativeLinkerOption=$resolvedResFile")
                            add("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS")
                            add("-H:NativeLinkerOption=/ENTRY:mainCRTStartup")
                        }

                        // Pass the native-image configuration directory so reflection/JNI/resource
                        // metadata is picked up even when it is not bundled inside the uber JAR.
                        if (resolvedConfigDir.exists()) {
                            add("-H:ConfigurationFileDirectories=$resolvedConfigDir")
                        }

                        // Include per-library metadata (L1), filtered by runtime classpath
                        add("-H:ConfigurationFileDirectories=$resolvedLibraryMetadataDir")

                        // Include platform-specific AWT/Java2D metadata
                        add("-H:ConfigurationFileDirectories=$resolvedPlatformMetadataDir")

                        // Include statically-analyzed metadata (reflection, JNI, resources
                        // detected from bytecode scanning of runtime classpath JARs)
                        if (resolvedStaticMetadataDir.exists()) {
                            add("-H:ConfigurationFileDirectories=$resolvedStaticMetadataDir")
                        }

                        // Include metadata from Oracle Reachability Metadata Repository
                        if (resolvedMetadataRepoDirsFile.exists()) {
                            val dirs = resolvedMetadataRepoDirsFile.readText().trim()
                            if (dirs.isNotEmpty()) {
                                for (dir in dirs.lines()) {
                                    if (dir.isNotBlank()) {
                                        add("-H:ConfigurationFileDirectories=$dir")
                                    }
                                }
                            }
                        }

                        addAll(resolvedBuildArgs)
                    }
            }
        }

    // ── Platform-specific packaging ──

    val packageGraalvmNative: TaskProvider<out DefaultTask> =
        when (currentOS) {
            OS.MacOS ->
                configureMacOsGraalvmPackaging(
                    graalvm,
                    graalvmHome,
                    nativeImageCompile,
                    nativeCompileDir,
                    imageName,
                    unpackDefaultResources,
                    packageUberJar,
                )
            OS.Windows ->
                configureWindowsGraalvmPackaging(
                    graalvm,
                    graalvmHome,
                    nativeImageCompile,
                    nativeCompileDir,
                    imageName,
                    packageUberJar,
                )
            OS.Linux ->
                configureLinuxGraalvmPackaging(
                    graalvmHome,
                    nativeImageCompile,
                    nativeCompileDir,
                    imageName,
                    packageUberJar,
                )
        }

    // ── Run the native image ──

    tasks.register<Exec>(
        taskNameAction = "run",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build and run the GraalVM native image"
        dependsOn(packageGraalvmNative)

        val binaryFile =
            when (currentOS) {
                OS.MacOS -> {
                    val dir =
                        appTmpDir.map {
                            it.dir("graalvm/output/${resolvedPackageNameProvider().get()}.app/Contents/MacOS")
                        }
                    dir.map { it.file(imageName.get()) }
                }
                OS.Windows -> {
                    val dir =
                        appTmpDir.map {
                            it.dir("graalvm/output/${resolvedPackageNameProvider().get()}")
                        }
                    dir.map { it.file(binaryName.get()) }
                }
                OS.Linux -> {
                    val dir =
                        appTmpDir.map {
                            it.dir("graalvm/output/${resolvedPackageNameProvider().get()}")
                        }
                    dir.map { it.file(imageName.get()) }
                }
            }

        executable = binaryFile.get().asFile.absolutePath
        args = app.args
    }

    // ── Electron-builder integration ──

    configureGraalvmElectronBuilderPackaging(packageGraalvmNative, unpackDefaultResources, imageName)
}

// ═══════════════════════════════════════════════════════════════════
// macOS packaging
// ═══════════════════════════════════════════════════════════════════

@Suppress("LongMethod", "LongParameterList")
private fun JvmApplicationContext.configureMacOsGraalvmPackaging(
    graalvm: GraalvmSettings,
    graalvmHome: org.gradle.api.provider.Provider<String>,
    nativeImageCompile: TaskProvider<Exec>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val appBundleName = resolvedPackageNameProvider().map { "$it.app" }
    val appBundleDir =
        appTmpDir.map { tmpDir ->
            tmpDir.dir("graalvm/output/${appBundleName.get()}/Contents")
        }

    val cleanAppBundle =
        tasks.register<Delete>(
            taskNameAction = "clean",
            taskNameObject = "graalvmAppBundle",
        ) {
            description = "Remove stale .app bundle before rebuilding"
            mustRunAfter(nativeImageCompile)
            delete(appTmpDir.map { it.dir("graalvm/output") })
        }

    val copyBinary =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmBinaryToApp",
        ) {
            description = "Copy native binary into .app bundle"
            dependsOn(nativeImageCompile, cleanAppBundle)
            // strip modifies files in-place after copy, leaving temp files that
            // break Gradle's incremental destination scanning on the next run.
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from(nativeCompileDir.map { it.file(imageName.get()) })
            into(appBundleDir.map { it.dir("MacOS") })
        }

    val copyAwtDylibs =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmAwtDylibs",
        ) {
            description = "Copy AWT dylibs into .app bundle"
            dependsOn(nativeImageCompile, cleanAppBundle)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from("${graalvmHome.get()}/lib") {
                include(
                    "libawt.dylib",
                    "libawt_lwawt.dylib",
                    "libfontmanager.dylib",
                    "libfreetype.dylib",
                    "libjava.dylib",
                    "libjavajpeg.dylib",
                    "libjawt.dylib",
                    "libjsound.dylib",
                    "liblcms.dylib",
                    "libmlib_image.dylib",
                    "libosxapp.dylib",
                    "libsplashscreen.dylib",
                )
            }
            from("${graalvmHome.get()}/lib/server") {
                include("libjvm.dylib")
            }
            into(appBundleDir.map { it.dir("MacOS") })
        }

    val copyJawtToLib =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmJawtToLib",
        ) {
            description = "Copy libjawt.dylib + fontconfig to lib/ subdir for Skiko and AWT font init"
            dependsOn(nativeImageCompile, cleanAppBundle)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from("${graalvmHome.get()}/lib") {
                // fontconfig.bfc: SunFontManager/FontConfiguration reads it from <java.home>/lib at
                // startup; java.home is the executable dir under native image, so without it
                // FontConfiguration.getVersion() throws "Fontconfig head is null" the first time AWT
                // font code runs (e.g. Font.createFont / BufferedImage.createGraphics).
                include("libjawt.dylib", "fontconfig.bfc")
            }
            into(appBundleDir.map { it.dir("MacOS/lib") })
        }

    val skikoLibName = "libskiko-${currentOS.id}-${currentArch.id}.dylib"
    val copySkikoLib =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmSkikoLib",
        ) {
            description = "Extract $skikoLibName from uber JAR into lib/ subdir so Skiko can load it"
            dependsOn(packageUberJar, cleanAppBundle)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from(project.zipTree(packageUberJar.flatMap { it.archiveFile })) {
                include(skikoLibName)
            }
            into(appBundleDir.map { it.dir("MacOS/lib") })
        }

    val stripDylibs =
        tasks.register<DefaultTask>(
            taskNameAction = "strip",
            taskNameObject = "graalvmDylibs",
        ) {
            description = "Strip debug symbols from dylibs"
            dependsOn(copyAwtDylibs)

            doLast {
                val macosDir = appBundleDir.get().dir("MacOS").asFile
                val dylibs =
                    macosDir
                        .listFiles { file -> file.isFile && file.extension == "dylib" }
                        ?.sortedBy { it.name }
                        .orEmpty()

                var successCount = 0
                var failureCount = 0

                dylibs.forEach { dylib ->
                    val stripped = stripMachOFileSafely(dylib, logger)
                    if (stripped) {
                        successCount++
                    } else {
                        failureCount++
                    }
                }

                logger.lifecycle(
                    "stripDylibs summary: total=${dylibs.size}, stripped=$successCount, keptOriginal=$failureCount",
                )
            }
        }

    // Patch LC_BUILD_VERSION on all Mach-O binaries and dylibs so that:
    // - minos matches minimumSystemVersion (e.g. 12.0) for backward compatibility
    // - sdk matches macOsSdkVersion (e.g. 26.0) to enable Liquid Glass
    val patchMinVersion = graalvm.macOS.minimumSystemVersion
    val patchSdkVersion = graalvm.macOS.macOsSdkVersion
    val patchBuildVersion =
        tasks.register<DefaultTask>(
            taskNameAction = "patch",
            taskNameObject = "graalvmBuildVersion",
        ) {
            description = "Patch LC_BUILD_VERSION on native binary and dylibs via vtool"
            dependsOn(copyBinary, stripDylibs, copyJawtToLib, copySkikoLib)

            inputs.property("minVersion", patchMinVersion)
            inputs.property("sdkVersion", patchSdkVersion)

            doLast {
                val minVer = patchMinVersion.get()
                val sdkVer = patchSdkVersion.get()
                val macosDir = appBundleDir.get().dir("MacOS").asFile
                val libDir = appBundleDir.get().dir("MacOS/lib").asFile

                // Patch all Mach-O files: main binary + dylibs in MacOS/ and MacOS/lib/
                sequenceOf(macosDir, libDir)
                    .filter { it.isDirectory }
                    .flatMap { dir -> dir.listFiles()?.asSequence() ?: emptySequence() }
                    .filter { it.isFile && (it.extension == "dylib" || it.canExecute()) }
                    .toList()
                    .also { files ->
                        var successCount = 0
                        var failureCount = 0
                        files.forEach { file ->
                            val patched = patchMachOBuildVersion(file, minVer, sdkVer, logger)
                            if (patched) {
                                successCount++
                            } else {
                                failureCount++
                            }
                        }

                        logger.lifecycle(
                            "patchBuildVersion summary: total=${files.size}, patched=$successCount, keptOriginal=$failureCount",
                        )
                    }
            }
        }

    val codesignDylibs =
        tasks.register<Exec>(
            taskNameAction = "codesign",
            taskNameObject = "graalvmDylibs",
        ) {
            description = "Re-sign dylibs after stripping (ad-hoc)"
            dependsOn(patchBuildVersion)
            val macosDir = appBundleDir.map { it.dir("MacOS") }
            commandLine("bash", "-c", "codesign --force --sign - '${macosDir.get().asFile.absolutePath}'/*.dylib")
        }

    val fixRpath =
        tasks.register<Exec>(
            taskNameAction = "fix",
            taskNameObject = "graalvmRpath",
        ) {
            description = "Add @executable_path rpath to native image"
            dependsOn(patchBuildVersion)
            val binary = appBundleDir.map { it.file("MacOS/${imageName.get()}") }
            commandLine("install_name_tool", "-add_rpath", "@executable_path/.", binary.get().asFile.absolutePath)
            isIgnoreExitValue = true
        }

    // Strip local symbols from the main Mach-O binary (-x keeps external/global symbols needed by
    // the flat-namespace AWT dylibs and the cursor stub). native-image emits no separate debug
    // info without -g, so the local symbol table is dead weight. Must run after the vtool/rpath
    // edits and before the ad-hoc codesign, which re-signs the whole bundle afterwards.
    val stripBinary =
        tasks.register<Exec>(
            taskNameAction = "strip",
            taskNameObject = "graalvmBinary",
        ) {
            description = "Strip local symbols from the native image binary"
            dependsOn(patchBuildVersion, fixRpath)
            val binary = appBundleDir.map { it.file("MacOS/${imageName.get()}") }
            commandLine("strip", "-x", binary.get().asFile.absolutePath)
        }

    // Generate Info.plist — all DSL values are captured at configuration time
    // to avoid serializing Project/SourceSet references into the configuration cache.
    val plistBundleName: String = app.nativeDistributions.appName ?: app.nativeDistributions.packageName ?: project.name
    val plistBundleID: String? = app.nativeDistributions.macOS.bundleID
    val plistVersion: String =
        app.nativeDistributions.macOS.packageVersion
            ?: app.nativeDistributions.packageVersion
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    val plistMinSystemVersion = graalvm.macOS.minimumSystemVersion
    val plistCopyright: String? = app.nativeDistributions.copyright
    val plistIconFileName: String =
        if (app.nativeDistributions.macOS.iconFile.isPresent) {
            app.nativeDistributions.macOS.iconFile
                .get()
                .asFile.name
        } else {
            "default-icon-mac.icns"
        }

    // Capture file associations at configuration time for the Info.plist
    val plistFileAssociations: Set<FileAssociation> =
        app.nativeDistributions.macOS.fileAssociations
            .toSet()

    // Capture URL protocol handlers (deep linking) at configuration time for the Info.plist
    val plistUrlProtocols: List<UrlProtocol> =
        app.nativeDistributions.protocols.toList()

    // Build a mapping from icon File -> unique name inside Resources/ (avoids collisions)
    val fileAssociationIconMapping: Map<File, File> =
        run {
            val icons = plistFileAssociations.mapNotNull { it.iconFile }.distinct()
            if (icons.isEmpty()) return@run emptyMap()
            val usedNames = mutableSetOf(plistIconFileName)
            val mapping = mutableMapOf<File, File>()
            for (icon in icons) {
                if (!icon.exists()) continue
                val name =
                    if (usedNames.add(icon.name)) {
                        icon.name
                    } else {
                        val nameWithoutExtension = icon.nameWithoutExtension
                        val extension = icon.extension
                        var uniqueName = icon.name
                        for (n in 1UL..ULong.MAX_VALUE) {
                            val candidate = "$nameWithoutExtension ($n).$extension"
                            if (usedNames.add(candidate)) {
                                uniqueName = candidate
                                break
                            }
                        }
                        uniqueName
                    }
                mapping[icon] = File(name)
            }
            mapping
        }

    val generateInfoPlist =
        tasks.register<DefaultTask>(
            taskNameAction = "generate",
            taskNameObject = "graalvmInfoPlist",
        ) {
            description = "Generate Info.plist for GraalVM .app bundle"
            val plistFile = appTmpDir.map { it.file("graalvm/Info.plist") }
            outputs.file(plistFile)

            // Wire inputs for up-to-date checks
            inputs.property("bundleName", plistBundleName)
            inputs.property("bundleID", plistBundleID ?: "")
            inputs.property("version", plistVersion)
            inputs.property("imageName", imageName)
            inputs.property("minSystemVersion", plistMinSystemVersion)
            inputs.property("copyright", plistCopyright ?: "")
            inputs.property("iconFileName", plistIconFileName)
            inputs.property("fileAssociations", plistFileAssociations.toString())
            inputs.property("urlProtocols", plistUrlProtocols.toString())

            doLast {
                val plist = InfoPlistBuilder()
                plist[PlistKeys.CFBundleName] = plistBundleName
                plist[PlistKeys.CFBundleDisplayName] = plistBundleName
                plist[PlistKeys.CFBundleIdentifier] = plistBundleID
                plist[PlistKeys.CFBundleVersion] = plistVersion
                plist[PlistKeys.CFBundleShortVersionString] = plistVersion
                plist[PlistKeys.CFBundleExecutable] = imageName.get()
                plist[PlistKeys.CFBundlePackageType] = "APPL"
                plist[PlistKeys.CFBundleInfoDictionaryVersion] = "6.0"
                plist[PlistKeys.NSHighResolutionCapable] = true
                plist[PlistKeys.NSSupportsAutomaticGraphicsSwitching] = true
                plist[PlistKeys.LSMinimumSystemVersion] = plistMinSystemVersion.get()
                plist[PlistKeys.CFBundleDevelopmentRegion] = "English"
                plist[PlistKeys.CFBundleAllowMixedLocalizations] = "true"

                if (plistCopyright != null) {
                    plist[PlistKeys.NSHumanReadableCopyright] = plistCopyright
                }

                plist[PlistKeys.CFBundleIconFile] = plistIconFileName

                if (plistFileAssociations.isNotEmpty()) {
                    plist[PlistKeys.CFBundleDocumentTypes] =
                        plistFileAssociations
                            .groupBy { it.mimeType to it.description }
                            .map { (key, extensions) ->
                                val (mimeType, description) = key
                                val iconPath =
                                    extensions
                                        .firstNotNullOfOrNull { it.iconFile }
                                        ?.let { fileAssociationIconMapping[it]?.name }
                                InfoPlistMapValue(
                                    PlistKeys.CFBundleTypeRole to InfoPlistStringValue("Editor"),
                                    PlistKeys.CFBundleTypeExtensions to
                                        InfoPlistListValue(extensions.map { InfoPlistStringValue(it.extension) }),
                                    PlistKeys.CFBundleTypeIconFile to
                                        InfoPlistStringValue(iconPath ?: plistIconFileName),
                                    PlistKeys.CFBundleTypeMIMETypes to InfoPlistStringValue(mimeType),
                                    PlistKeys.CFBundleTypeName to InfoPlistStringValue(description),
                                    PlistKeys.CFBundleTypeOSTypes to
                                        InfoPlistListValue(InfoPlistStringValue("****")),
                                )
                            }
                }

                if (plistUrlProtocols.isNotEmpty()) {
                    plist[PlistKeys.CFBundleURLTypes] =
                        plistUrlProtocols.map { protocol ->
                            InfoPlistMapValue(
                                PlistKeys.CFBundleURLName to InfoPlistStringValue(protocol.name),
                                PlistKeys.CFBundleURLSchemes to
                                    InfoPlistListValue(protocol.schemes.map { InfoPlistStringValue(it) }),
                            )
                        }
                }

                plistFile
                    .get()
                    .asFile.parentFile
                    .mkdirs()
                plist.writeToFile(plistFile.get().asFile)
            }
        }

    val copyInfoPlist =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmInfoPlist",
        ) {
            description = "Copy Info.plist into .app bundle"
            dependsOn(generateInfoPlist, cleanAppBundle)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from(appTmpDir.map { it.file("graalvm/Info.plist") })
            into(appBundleDir)
        }

    // Copy icon into Resources/ — use custom icon if set, otherwise default
    val copyIcon =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmMacIcon",
        ) {
            description = "Copy app icon into .app bundle Resources"
            dependsOn(cleanAppBundle, unpackDefaultResources)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            val iconFile =
                app.nativeDistributions.macOS.iconFile.orElse(
                    unpackDefaultResources.flatMap { it.resources.macIcon },
                )
            from(iconFile)
            into(appBundleDir.map { it.dir("Resources") })

            // Create Base.lproj so macOS uses the system language for auto-added menu items
            doLast {
                appBundleDir.get().dir("Resources/Base.lproj").asFile.mkdirs()
            }
        }

    // Copy file association icons into Resources/ with unique names
    val copyFileAssociationIcons =
        if (fileAssociationIconMapping.isNotEmpty()) {
            tasks.register<DefaultTask>(
                taskNameAction = "copy",
                taskNameObject = "graalvmFileAssociationIcons",
            ) {
                description = "Copy file association icons into .app bundle Resources"
                dependsOn(cleanAppBundle)
                for (iconFile in fileAssociationIconMapping.keys) {
                    inputs.file(iconFile)
                }
                outputs.dir(appBundleDir.map { it.dir("Resources") })

                doLast {
                    val resourcesDir = appBundleDir.get().dir("Resources").asFile
                    resourcesDir.mkdirs()
                    for ((sourceIcon, targetName) in fileAssociationIconMapping) {
                        if (sourceIcon.exists()) {
                            sourceIcon.copyTo(File(resourcesDir, targetName.name), overwrite = true)
                        }
                    }
                }
            }
        } else {
            null
        }

    val codesignBundle =
        tasks.register<Exec>(
            taskNameAction = "codesign",
            taskNameObject = "graalvmBundle",
        ) {
            description = "Ad-hoc sign the entire .app bundle"
            dependsOn(codesignDylibs, copyBinary, fixRpath, stripBinary, copyInfoPlist, copyJawtToLib, copySkikoLib, copyIcon)
            copyFileAssociationIcons?.let { dependsOn(it) }
            val bundleDir = appTmpDir.map { it.dir("graalvm/output/${appBundleName.get()}") }
            commandLine("codesign", "--force", "--deep", "--sign", "-", bundleDir.get().asFile.absolutePath)
        }

    return tasks.register<DefaultTask>(
        taskNameAction = "package",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build native image and package as macOS .app bundle"
        dependsOn(
            copyBinary,
            copyAwtDylibs,
            copyJawtToLib,
            copySkikoLib,
            stripDylibs,
            stripBinary,
            patchBuildVersion,
            codesignDylibs,
            codesignBundle,
            fixRpath,
            copyInfoPlist,
            copyIcon,
        )
        copyFileAssociationIcons?.let { dependsOn(it) }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Windows packaging
// ═══════════════════════════════════════════════════════════════════

@Suppress("LongParameterList")
private fun JvmApplicationContext.configureWindowsGraalvmPackaging(
    graalvm: GraalvmSettings,
    graalvmHome: org.gradle.api.provider.Provider<String>,
    nativeImageCompile: TaskProvider<Exec>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val outputDir = appTmpDir.map { it.dir("graalvm/output/${resolvedPackageNameProvider().get()}") }

    val copyBinary =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmBinaryToOutput",
        ) {
            description = "Copy native binary into output directory"
            dependsOn(nativeImageCompile)
            from(nativeCompileDir.map { it.file("${imageName.get()}.exe") })
            into(outputDir)
        }

    val copyAwtDlls =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmAwtDlls",
        ) {
            description = "Copy AWT DLLs into output directory"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/bin") {
                include(
                    "awt.dll",
                    "java.dll",
                    "javajpeg.dll",
                    "jsound.dll",
                    "fontmanager.dll",
                    "freetype.dll",
                    "lcms.dll",
                    "mlib_image.dll",
                    "splashscreen.dll",
                    "javaaccessbridge.dll",
                )
            }
            into(outputDir)
        }

    val copyJvmDll =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmJvmDll",
        ) {
            description = "Copy jvm.dll into output directory"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/bin/server") {
                include("jvm.dll")
            }
            into(outputDir)
        }

    val copyJawtToBin =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmJawtToBin",
        ) {
            description = "Copy jawt.dll to bin/ subdir for Skiko"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/bin") {
                include("jawt.dll")
            }
            into(outputDir.map { it.dir("bin") })
        }

    // On Windows, Skiko looks for skiko-windows-*.dll in java.home/bin/ (GraalVmInitializer
    // sets java.home = execDir). Also include icudtl.dat which Skiko uses for ICU text data.
    val skikoLibName = "skiko-${currentOS.id}-${currentArch.id}.dll"
    val copySkikoLib =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmSkikoLib",
        ) {
            description = "Extract $skikoLibName and icudtl.dat from uber JAR into bin/ subdir so Skiko can load them"
            dependsOn(packageUberJar)
            from(project.zipTree(packageUberJar.flatMap { it.archiveFile })) {
                include(skikoLibName, "icudtl.dat")
            }
            into(outputDir.map { it.dir("bin") })
        }

    // fontconfig.bfc: SunFontManager/FontConfiguration reads it from <java.home>/lib at startup;
    // java.home is the executable dir under native image, so without it FontConfiguration.getVersion()
    // throws "Fontconfig head is null" the first time AWT font code runs (e.g. Font.createFont).
    // macOS does the same in copyJawtToLib.
    val copyFontConfig =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmFontConfig",
        ) {
            description = "Copy fontconfig.bfc to lib/ subdir for AWT font init"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/lib") {
                include("fontconfig.bfc")
            }
            into(outputDir.map { it.dir("lib") })
        }

    // Bundle the MSVC C/C++ runtime DLLs next to the executable so the app runs on machines
    // without the Visual C++ Redistributable (otherwise: "VCRUNTIME140.dll not found").
    val copyCRuntime =
        if (graalvm.windows.bundleCRuntime.get()) {
            val requestedDlls = graalvm.windows.dlls.get()
            val cRuntimeSourceDir =
                graalvm.windows.sourceDir
                    .map { it.asFile.absolutePath }
                    .orElse(graalvmHome.map { "$it/bin" })
            tasks.register<Copy>(
                taskNameAction = "copy",
                taskNameObject = "graalvmCRuntimeDlls",
            ) {
                description = "Copy MSVC C/C++ runtime DLLs next to the native executable"
                dependsOn(nativeImageCompile)
                from(cRuntimeSourceDir.get()) {
                    requestedDlls.forEach { include(it) }
                }
                into(outputDir)
                doLast {
                    val present =
                        outputDir.get().asFile.list()?.map { it.lowercase() }?.toSet().orEmpty()
                    val missing = requestedDlls.filterNot { it.lowercase() in present }
                    if (missing.isNotEmpty()) {
                        logger.warn(
                            "[graalvm] C runtime DLLs not found in ${cRuntimeSourceDir.get()}: " +
                                "${missing.joinToString()}. The app may fail to start with a " +
                                "\"DLL not found\" error on machines without the Visual C++ " +
                                "Redistributable. Point graalvm.windows.sourceDir at a directory " +
                                "that contains them (e.g. the MSVC redistributable folder).",
                        )
                    }
                }
            }
        } else {
            null
        }

    return tasks.register<DefaultTask>(
        taskNameAction = "package",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build native image and package with DLLs"
        dependsOn(copyBinary, copyAwtDlls, copyJvmDll, copyJawtToBin, copySkikoLib, copyFontConfig)
        copyCRuntime?.let { dependsOn(it) }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Linux packaging
// ═══════════════════════════════════════════════════════════════════

@Suppress("LongParameterList")
private fun JvmApplicationContext.configureLinuxGraalvmPackaging(
    graalvmHome: org.gradle.api.provider.Provider<String>,
    nativeImageCompile: TaskProvider<Exec>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val outputDir = appTmpDir.map { it.dir("graalvm/output/${resolvedPackageNameProvider().get()}") }

    val copyBinary =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmBinaryToOutput",
        ) {
            description = "Copy native binary into output directory"
            dependsOn(nativeImageCompile)
            from(nativeCompileDir.map { it.file(imageName.get()) })
            into(outputDir)
            // Gradle snapshots the output directory for incremental build tracking.
            // strip(1) creates temporary files in the same directory, causing
            // NoSuchFileException if this task runs in parallel with stripSoLibs.
            doNotTrackState("Shared output directory is modified by strip tasks")
        }

    val copyAwtSoLibs =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmAwtSoLibs",
        ) {
            description = "Copy AWT .so libs into output directory"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/lib") {
                include(
                    "libawt.so",
                    "libawt_headless.so",
                    "libawt_xawt.so",
                    "libfontmanager.so",
                    "libfreetype.so",
                    "libjava.so",
                    "libjavajpeg.so",
                    "libjawt.so",
                    "libjsound.so",
                    "liblcms.so",
                    "libmlib_image.so",
                    "libsplashscreen.so",
                )
            }
            into(outputDir)
        }

    val copyJvmSo =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmJvmSo",
        ) {
            description = "Copy libjvm.so into output directory"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/lib/server") {
                include("libjvm.so")
            }
            into(outputDir)
        }

    val copyJawtToLib =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmJawtToLib",
        ) {
            description = "Copy libjawt.so to lib/ subdir for Skiko"
            dependsOn(nativeImageCompile)
            from("${graalvmHome.get()}/lib") {
                include("libjawt.so")
            }
            into(outputDir.map { it.dir("lib") })
        }

    // Skiko's Library.findAndLoad() looks for libskiko-linux-*.so in java.home/lib/.
    // GraalVmInitializer sets java.home to the executable directory, so the library
    // must be in lib/ alongside the binary. On systems without a ~/.skiko/ cache
    // (e.g. a fresh Lubuntu install), Skiko falls through to resource extraction which
    // fails because the .so is not registered as a native image resource → NPE.
    val skikoLibName = "libskiko-${currentOS.id}-${currentArch.id}.so"
    val skikoLibFile = packageUberJar.flatMap { it.archiveFile }
    val copySkikoLib =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmSkikoLib",
        ) {
            description = "Extract $skikoLibName from uber JAR into lib/ subdir so Skiko can load it"
            dependsOn(packageUberJar)
            from(project.zipTree(skikoLibFile)) {
                include(skikoLibName)
            }
            into(outputDir.map { it.dir("lib") })
        }

    val fixRpath =
        tasks.register<Exec>(
            taskNameAction = "fix",
            taskNameObject = "graalvmRpath",
        ) {
            description = "Set RPATH to \$ORIGIN on the binary so it finds .so libs next to it"
            dependsOn(copyBinary)
            val binary = outputDir.map { it.file(imageName.get()) }
            commandLine("patchelf", "--set-rpath", "\$ORIGIN", binary.get().asFile.absolutePath)
        }

    val fixSoRpath =
        tasks.register<Exec>(
            taskNameAction = "fix",
            taskNameObject = "graalvmSoRpath",
        ) {
            description = "Set RPATH to \$ORIGIN on companion .so libs so inter-library deps resolve"
            dependsOn(copyAwtSoLibs, copyJvmSo)
            val dir = outputDir.get().asFile.absolutePath
            commandLine("bash", "-c", "for f in '$dir'/*.so; do patchelf --set-rpath '\$ORIGIN' \"\$f\"; done")
        }

    val stripSoLibs =
        tasks.register<Exec>(
            taskNameAction = "strip",
            taskNameObject = "graalvmSoLibs",
        ) {
            description = "Strip debug symbols from .so libs"
            dependsOn(copyAwtSoLibs, copyJvmSo, fixSoRpath)
            commandLine("bash", "-c", "strip --strip-debug '${outputDir.get().asFile.absolutePath}'/*.so")
        }

    // Strip the main native-image executable. Unlike the companion .so libs, the ELF binary
    // carries a full .symtab (native-image emits no separate debug info without -g), so a plain
    // strip removes the symbol table and reclaims tens of MB. Runs after patchelf so the RPATH
    // edit is preserved.
    val stripBinary =
        tasks.register<Exec>(
            taskNameAction = "strip",
            taskNameObject = "graalvmBinary",
        ) {
            description = "Strip symbols from the native image executable"
            dependsOn(copyBinary, fixRpath)
            val binary = outputDir.map { it.file(imageName.get()) }
            commandLine("strip", binary.get().asFile.absolutePath)
        }

    return tasks.register<DefaultTask>(
        taskNameAction = "package",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build native image and package with .so libs"
        dependsOn(copyBinary, copyAwtSoLibs, copyJvmSo, copyJawtToLib, copySkikoLib, fixRpath, fixSoRpath, stripSoLibs, stripBinary)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Electron-builder integration
// ═══════════════════════════════════════════════════════════════════

private fun JvmApplicationContext.configureGraalvmElectronBuilderPackaging(
    packageGraalvmNative: TaskProvider<out DefaultTask>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    imageName: org.gradle.api.provider.Provider<String>,
) {
    val ebFormats =
        app.nativeDistributions.targetFormats
            .filter { it.backend == PackagingBackend.ELECTRON_BUILDER && !it.isStoreFormat }

    for (targetFormat in ebFormats) {
        val packageFormat =
            tasks.register<AbstractElectronBuilderPackageTask>(
                taskNameAction = "packageGraalvm",
                taskNameObject = targetFormat.name,
                args = listOf(targetFormat),
            ) {
                enabled = targetFormat.isCompatibleWithCurrentOS
                dependsOn(packageGraalvmNative, unpackDefaultResources)

                // The app image root is the output directory from the native packaging step
                appImageRoot.set(
                    appTmpDir.map { it.dir("graalvm/output") },
                )

                destinationDir.set(
                    app.nativeDistributions.outputBaseDir.map {
                        it.dir("$appDirName/graalvm-${targetFormat.outputDirName}")
                    },
                )

                packageName.set(resolvedPackageNameProvider())
                packageVersion.set(packageVersionFor(targetFormat))

                // Only wire platform-specific icons/entitlements for the current OS
                // to avoid validation errors from missing cross-platform files.
                when (currentOS) {
                    OS.Linux -> {
                        linuxIconFile.set(
                            app.nativeDistributions.linux.iconFile
                                .orElse(unpackDefaultResources.flatMap { it.resources.linuxIcon }),
                        )
                        val startupWMClass =
                            app.nativeDistributions.linux.startupWMClass
                                ?.takeIf { it.isNotBlank() }
                                ?: app.mainClass?.replace('.', '-')
                        if (startupWMClass != null) {
                            this.startupWMClass.set(startupWMClass)
                        }
                    }
                    OS.Windows -> {
                        windowsIconFile.set(
                            app.nativeDistributions.windows.iconFile
                                .orElse(unpackDefaultResources.flatMap { it.resources.windowsIcon }),
                        )
                    }
                    OS.MacOS -> {
                        val mac = app.nativeDistributions.macOS
                        nonValidatedMacSigningSettings = mac.signing
                        nonValidatedMacBundleID.set(mac.bundleID)
                        // PKG is always treated as App Store — ignore the deprecated user setting.
                        macAppStore.set(targetFormat.isStoreFormat)
                        macEntitlementsFile.set(
                            mac.entitlementsFile.orElse(
                                unpackDefaultResources.flatMap { it.resources.defaultEntitlements },
                            ),
                        )
                        macRuntimeEntitlementsFile.set(
                            mac.runtimeEntitlementsFile.orElse(
                                unpackDefaultResources.flatMap { it.resources.defaultEntitlements },
                            ),
                        )
                    }
                }

                executableName.set(imageName)
                customNodePath.set(NucleusProperties.electronBuilderNodePath(project.providers))
                publishMode.set(NucleusProperties.electronBuilderPublishMode(project.providers))
                distributions = app.nativeDistributions
            }

        if (targetFormat.isCompatibleWith(OS.MacOS)) {
            tasks.register<AbstractNotarizationTask>(
                taskNameAction = "notarizeGraalvm",
                taskNameObject = targetFormat.name,
                args = listOf(targetFormat),
            ) {
                dependsOn(packageFormat)
                inputDir.set(packageFormat.flatMap { it.destinationDir })
                configureCommonNotarizationSettings(this)
            }
        }
    }
}
