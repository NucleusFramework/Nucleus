@file:Suppress("ktlint:standard:filename")

package io.github.kdroidfilter.nucleus.desktop.application.internal

import io.github.kdroidfilter.nucleus.desktop.application.dsl.FileAssociation
import io.github.kdroidfilter.nucleus.desktop.application.dsl.GraalvmSettings
import io.github.kdroidfilter.nucleus.desktop.application.dsl.PackagingBackend
import io.github.kdroidfilter.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistListValue
import io.github.kdroidfilter.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistMapValue
import io.github.kdroidfilter.nucleus.desktop.application.internal.InfoPlistBuilder.InfoPlistValue.InfoPlistStringValue
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractElectronBuilderPackageTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractNotarizationTask
import io.github.kdroidfilter.nucleus.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import io.github.kdroidfilter.nucleus.internal.utils.Arch
import io.github.kdroidfilter.nucleus.internal.utils.OS
import io.github.kdroidfilter.nucleus.internal.utils.currentArch
import io.github.kdroidfilter.nucleus.internal.utils.currentOS
import io.github.kdroidfilter.nucleus.internal.utils.executableName
import io.github.kdroidfilter.nucleus.internal.utils.uppercaseFirstChar
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
    validateGraalvmOptimizationSettings(graalvm)
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

                    val tempDir =
                        agentTempDir
                            .get()
                            .asFile
                            .apply { mkdirs() }
                            .absolutePath
                    add("-agentlib:native-image-agent=config-output-dir=$tempDir")
                }

            args = app.args

            // After the agent finishes, merge results into the real config
            doLast {
                val targetDir =
                    if (nativeImageConfigDir.isPresent) {
                        nativeImageConfigDir.get().asFile
                    } else {
                        project.layout.projectDirectory
                            .dir("src/main/resources/META-INF/native-image")
                            .asFile
                    }
                val agentDir = agentTempDir.get().asFile

                mergeReachabilityMetadata(agentDir, targetDir)

                // Also merge individual config files the agent may produce
                listOf(
                    "reflect-config.json",
                    "jni-config.json",
                    "resource-config.json",
                    "proxy-config.json",
                    "serialization-config.json",
                ).forEach { fileName ->
                    mergeJsonArrayConfig(
                        agentFile = File(agentDir, fileName),
                        targetFile = File(targetDir, fileName),
                    )
                }

                // Deduplicate: remove entries already provided by library JARs,
                // plugin platform metadata (L3), and native-image.properties resource patterns.
                val runtimeClasspath = classpath?.files ?: emptySet()
                val platform =
                    when (currentOS) {
                        OS.Windows -> "windows"
                        OS.MacOS -> "macos"
                        OS.Linux -> "linux"
                    }
                deduplicateAgainstLibraryMetadata(runtimeClasspath, targetDir, platform, mainClassName)

                logger.lifecycle("Native-image agent config merged into: $targetDir")
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
            val winPkgVersion = provider { app.nativeDistributions.packageVersion ?: "1.0.0" }
            val winCopyright = provider { app.nativeDistributions.copyright ?: "" }
            val winDescription = provider { app.nativeDistributions.description ?: packageNameProvider.get() }
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
                inputs.property("description", winDescription)
                inputs.property("imageName", imageName)

                doLast {
                    val rcDir = rcFile.get().asFile.parentFile
                    rcDir.mkdirs()

                    val pkgName = winPkgName.get()
                    val pkgVersion = winPkgVersion.get()
                    val copyright = winCopyright.get()
                    val taskDescription = winDescription.get()
                    val versionParts = pkgVersion.split(".").map { it.toIntOrNull() ?: 0 }
                    val v1 = versionParts.getOrElse(0) { 0 }
                    val v2 = versionParts.getOrElse(1) { 0 }
                    val v3 = versionParts.getOrElse(2) { 0 }
                    val v4 = versionParts.getOrElse(3) { 0 }

                    // Generate DPI-aware application manifest
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
                        |    </asmv3:windowsSettings>
                        |  </asmv3:application>
                        |</assembly>
                        """.trimMargin(),
                    )

                    val rcContent =
                        buildString {
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
                            appendLine("      VALUE \"FileDescription\", \"$taskDescription\"")
                            appendLine("      VALUE \"FileVersion\", \"$pkgVersion\"")
                            appendLine("      VALUE \"InternalName\", \"$pkgName\"")
                            appendLine("      VALUE \"LegalCopyright\", \"$copyright\"")
                            appendLine("      VALUE \"OriginalFilename\", \"${imageName.get()}.exe\"")
                            appendLine("      VALUE \"ProductName\", \"$pkgName\"")
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

            doFirst {
                outputDir.mkdirs()
            }

            args =
                buildList {
                    add("-jar")
                    add(uberJarFile.get().asFile.absolutePath)
                    add("-o")
                    add(File(outputDir, imageName.get()).absolutePath)
                    add("-march=${graalvm.march.get()}")

                    // macOS: link C stubs
                    if (currentOS == OS.MacOS && compileStubs != null) {
                        val stubObj =
                            appTmpDir
                                .get()
                                .file("graalvm/cursor_stub.o")
                                .asFile.absolutePath
                        add("-H:NativeLinkerOption=$stubObj")
                    }

                    // Windows: link .res for icon + version info, configure subsystem
                    if (currentOS == OS.Windows && generateWindowsResources != null) {
                        val resFile =
                            appTmpDir
                                .get()
                                .file("graalvm/icon.res")
                                .asFile.absolutePath
                        add("-H:NativeLinkerOption=$resFile")
                        add("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS")
                        add("-H:NativeLinkerOption=/ENTRY:mainCRTStartup")
                    }

                    // Pass the native-image configuration directory so reflection/JNI/resource
                    // metadata is picked up even when it is not bundled inside the uber JAR.
                    val configDir =
                        if (nativeImageConfigDir.isPresent) {
                            nativeImageConfigDir.get().asFile
                        } else {
                            project.layout.projectDirectory
                                .dir("src/main/resources/META-INF/native-image")
                                .asFile
                        }
                    if (configDir.exists()) {
                        add("-H:ConfigurationFileDirectories=$configDir")
                    }

                    // Include platform-specific AWT/Java2D metadata
                    // Always add — the directory is created by generateGraalvmPlatformMetadata
                    // which runs before this task via dependsOn.
                    add("-H:ConfigurationFileDirectories=${platformMetadataDir.get().asFile}")

                    // Include metadata from Oracle Reachability Metadata Repository
                    val dirsFile = metadataRepoDirsFile.get().asFile
                    if (dirsFile.exists()) {
                        val dirs = dirsFile.readText().trim()
                        if (dirs.isNotEmpty()) {
                            for (dir in dirs.lines()) {
                                if (dir.isNotBlank()) {
                                    add("-H:ConfigurationFileDirectories=$dir")
                                }
                            }
                        }
                    }

                    // Optimization flags from DSL
                    if (graalvm.optimization.optimizeForSize.get()) add("-Os")
                    if (graalvm.optimization.skipFlow.get()) {
                        add("-H:+TrackPrimitiveValues")
                        add("-H:+UsePredicates")
                    }

                    addAll(graalvm.buildArgs.get())
                }
        }

    // ── UPX compression ──

    val upxCompress: TaskProvider<Exec>? =
        if (graalvm.upx.isEnabled.get()) {
            val upxExe =
                if (graalvm.upx.executablePath.isPresent) {
                    graalvm.upx.executablePath.get()
                } else {
                    resolveFromPath("upx")
                        ?: error(
                            "UPX executable not found on PATH. " +
                                "Install UPX or set graalvm.upx.executablePath explicitly.",
                        )
                }
            val level = graalvm.upx.compressionLevel.get()
            val levelArg = if (level == UPX_BEST_LEVEL) "--best" else "-$level"

            tasks.register<Exec>(
                taskNameAction = "upx",
                taskNameObject = "graalvmBinary",
            ) {
                description = "Compress native binary with UPX"
                dependsOn(nativeImageCompile)
                val binary = nativeCompileDir.map { it.file(binaryName.get()).asFile }
                commandLine(
                    buildList {
                        add(upxExe)
                        if (currentOS == OS.MacOS) add("--force-macos")
                        add(levelArg)
                        add(binary.get().absolutePath)
                    },
                )
            }
        } else {
            null
        }

    // ── Platform-specific packaging ──

    // The compile dependency for copy tasks: UPX (if enabled) must run before packaging copies the binary.
    val compileOrUpx: TaskProvider<out DefaultTask> = upxCompress ?: nativeImageCompile

    val packageGraalvmNative: TaskProvider<out DefaultTask> =
        when (currentOS) {
            OS.MacOS ->
                configureMacOsGraalvmPackaging(
                    graalvm,
                    graalvmHome,
                    compileOrUpx,
                    nativeCompileDir,
                    imageName,
                    unpackDefaultResources,
                    packageUberJar,
                )
            OS.Windows ->
                configureWindowsGraalvmPackaging(
                    graalvmHome,
                    compileOrUpx,
                    nativeCompileDir,
                    imageName,
                    packageUberJar,
                )
            OS.Linux ->
                configureLinuxGraalvmPackaging(
                    graalvmHome,
                    compileOrUpx,
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
                            it.dir("graalvm/output/${packageNameProvider.get()}.app/Contents/MacOS")
                        }
                    dir.map { it.file(imageName.get()) }
                }
                OS.Windows -> {
                    val dir =
                        appTmpDir.map {
                            it.dir("graalvm/output/${packageNameProvider.get()}")
                        }
                    dir.map { it.file(binaryName.get()) }
                }
                OS.Linux -> {
                    val dir =
                        appTmpDir.map {
                            it.dir("graalvm/output/${packageNameProvider.get()}")
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
    nativeImageCompile: TaskProvider<out DefaultTask>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val appBundleName = packageNameProvider.map { "$it.app" }
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
            description = "Copy libjawt.dylib to lib/ subdir for Skiko"
            dependsOn(nativeImageCompile, cleanAppBundle)
            doNotTrackState("Output directory is modified by downstream strip/codesign tasks")
            from("${graalvmHome.get()}/lib") {
                include("libjawt.dylib")
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
        tasks.register<Exec>(
            taskNameAction = "strip",
            taskNameObject = "graalvmDylibs",
        ) {
            description = "Strip debug symbols from dylibs"
            dependsOn(copyAwtDylibs)
            val macosDir = appBundleDir.map { it.dir("MacOS") }
            commandLine("bash", "-c", "strip -x '${macosDir.get().asFile.absolutePath}'/*.dylib")
        }

    val codesignDylibs =
        tasks.register<Exec>(
            taskNameAction = "codesign",
            taskNameObject = "graalvmDylibs",
        ) {
            description = "Re-sign dylibs after stripping (ad-hoc)"
            dependsOn(stripDylibs)
            val macosDir = appBundleDir.map { it.dir("MacOS") }
            commandLine("bash", "-c", "codesign --force --sign - '${macosDir.get().asFile.absolutePath}'/*.dylib")
        }

    val fixRpath =
        tasks.register<Exec>(
            taskNameAction = "fix",
            taskNameObject = "graalvmRpath",
        ) {
            description = "Add @executable_path rpath to native image"
            dependsOn(copyBinary)
            val binary = appBundleDir.map { it.file("MacOS/${imageName.get()}") }
            commandLine("install_name_tool", "-add_rpath", "@executable_path/.", binary.get().asFile.absolutePath)
            isIgnoreExitValue = true
        }

    // Generate Info.plist — all DSL values are captured at configuration time
    // to avoid serializing Project/SourceSet references into the configuration cache.
    val plistBundleName: String = app.nativeDistributions.packageName ?: project.name
    val plistBundleID: String? = app.nativeDistributions.macOS.bundleID
    val plistVersion: String =
        app.nativeDistributions.packageVersion
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

            doLast {
                val plist = InfoPlistBuilder()
                plist[PlistKeys.CFBundleName] = plistBundleName
                plist[PlistKeys.CFBundleIdentifier] = plistBundleID
                plist[PlistKeys.CFBundleVersion] = plistVersion
                plist[PlistKeys.CFBundleShortVersionString] = plistVersion
                plist[PlistKeys.CFBundleExecutable] = imageName.get()
                plist[PlistKeys.CFBundlePackageType] = "APPL"
                plist[PlistKeys.CFBundleInfoDictionaryVersion] = "6.0"
                plist[PlistKeys.NSHighResolutionCapable] = true
                plist[PlistKeys.NSSupportsAutomaticGraphicsSwitching] = true
                plist[PlistKeys.LSMinimumSystemVersion] = plistMinSystemVersion.get()

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
            dependsOn(codesignDylibs, copyBinary, fixRpath, copyInfoPlist, copyJawtToLib, copySkikoLib, copyIcon)
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
    graalvmHome: org.gradle.api.provider.Provider<String>,
    nativeImageCompile: TaskProvider<out DefaultTask>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val outputDir = appTmpDir.map { it.dir("graalvm/output/${packageNameProvider.get()}") }

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

    return tasks.register<DefaultTask>(
        taskNameAction = "package",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build native image and package with DLLs"
        dependsOn(copyBinary, copyAwtDlls, copyJvmDll, copyJawtToBin, copySkikoLib)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Linux packaging
// ═══════════════════════════════════════════════════════════════════

@Suppress("LongParameterList")
private fun JvmApplicationContext.configureLinuxGraalvmPackaging(
    graalvmHome: org.gradle.api.provider.Provider<String>,
    nativeImageCompile: TaskProvider<out DefaultTask>,
    nativeCompileDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    imageName: org.gradle.api.provider.Provider<String>,
    packageUberJar: TaskProvider<Jar>,
): TaskProvider<DefaultTask> {
    val outputDir = appTmpDir.map { it.dir("graalvm/output/${packageNameProvider.get()}") }

    val copyBinary =
        tasks.register<Copy>(
            taskNameAction = "copy",
            taskNameObject = "graalvmBinaryToOutput",
        ) {
            description = "Copy native binary into output directory"
            dependsOn(nativeImageCompile)
            from(nativeCompileDir.map { it.file(imageName.get()) })
            into(outputDir)
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

    return tasks.register<DefaultTask>(
        taskNameAction = "package",
        taskNameObject = "graalvmNative",
    ) {
        description = "Build native image and package with .so libs"
        dependsOn(copyBinary, copyAwtSoLibs, copyJvmSo, copyJawtToLib, copySkikoLib, fixRpath, fixSoRpath, stripSoLibs)
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

                packageName.set(packageNameProvider)
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

// ═══════════════════════════════════════════════════════════════════
// Validation
// ═══════════════════════════════════════════════════════════════════

private const val UPX_MIN_LEVEL = 1
private const val UPX_BEST_LEVEL = 10

private fun JvmApplicationContext.validateGraalvmOptimizationSettings(graalvm: GraalvmSettings) {
    val level = graalvm.upx.compressionLevel.get()
    require(level in UPX_MIN_LEVEL..UPX_BEST_LEVEL) {
        "upx.compressionLevel must be between $UPX_MIN_LEVEL and $UPX_BEST_LEVEL (10 = --best), got $level"
    }
    if (graalvm.optimization.skipFlow.get()) {
        project.logger.lifecycle(
            "GraalVM SkipFlow optimizations enabled (-H:+TrackPrimitiveValues -H:+UsePredicates). " +
                "This is an experimental feature.",
        )
    }
}

/** Resolve an executable name from the system PATH, returning the absolute path or null. */
private fun resolveFromPath(name: String): String? =
    System
        .getenv("PATH")
        ?.splitToSequence(File.pathSeparator)
        ?.map { File(it, name) }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath
