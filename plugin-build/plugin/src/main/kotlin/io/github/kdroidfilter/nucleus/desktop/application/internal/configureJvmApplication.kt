/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.nucleus.desktop.application.internal

import io.github.kdroidfilter.nucleus.desktop.application.dsl.PackagingBackend
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import io.github.kdroidfilter.nucleus.desktop.application.internal.validation.validatePackageVersions
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractElectronBuilderPackageTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractExtractNativeLibsTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractGenerateAotCacheTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractGenerateAppPropertiesTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractJLinkTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractJPackageTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractNotarizationTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractPatchCaCertificatesTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractPatchMacJvmTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractProguardTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractRunAppXTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractRunDistributableTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractStripNativeLibsFromJarsTask
import io.github.kdroidfilter.nucleus.desktop.application.tasks.AbstractSuggestModulesTask
import io.github.kdroidfilter.nucleus.desktop.tasks.AbstractJarsFlattenTask
import io.github.kdroidfilter.nucleus.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import io.github.kdroidfilter.nucleus.internal.KOTLIN_JVM_PLUGIN_ID
import io.github.kdroidfilter.nucleus.internal.KOTLIN_MPP_PLUGIN_ID
import io.github.kdroidfilter.nucleus.internal.javaSourceSets
import io.github.kdroidfilter.nucleus.internal.mppExt
import io.github.kdroidfilter.nucleus.internal.utils.Arch
import io.github.kdroidfilter.nucleus.internal.utils.OS
import io.github.kdroidfilter.nucleus.internal.utils.currentOS
import io.github.kdroidfilter.nucleus.internal.utils.dependsOn
import io.github.kdroidfilter.nucleus.internal.utils.detachedComposeGradleDependency
import io.github.kdroidfilter.nucleus.internal.utils.detachedDependency
import io.github.kdroidfilter.nucleus.internal.utils.dir
import io.github.kdroidfilter.nucleus.internal.utils.excludeTransitiveDependencies
import io.github.kdroidfilter.nucleus.internal.utils.file
import io.github.kdroidfilter.nucleus.internal.utils.ioFile
import io.github.kdroidfilter.nucleus.internal.utils.ioFileOrNull
import io.github.kdroidfilter.nucleus.internal.utils.javaExecutable
import io.github.kdroidfilter.nucleus.internal.utils.jdkArch
import io.github.kdroidfilter.nucleus.internal.utils.provider
import org.gradle.api.DefaultTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

private val defaultJvmArgs: List<String> =
    buildList {
        add("-D$CONFIGURE_SWING_GLOBALS=true")
        if (currentOS == OS.MacOS) {
            // The decorated-window module uses reflection on java.desktop internals
            // (sun.awt.AWTAccessor) to obtain the native NSWindow pointer.
            // These --add-opens ensure setAccessible works in modular JDKs.
            add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
            add("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
            add("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
    }
internal const val NUCLEUS_TASK_GROUP = "nucleus"

// todo: multiple launchers
// todo: file associations
// todo: use workers
internal fun JvmApplicationContext.configureJvmApplication() {
    if (app.isDefaultConfigurationEnabled) {
        configureDefaultApp()
    }

    if (app.nativeDistributions.cleanupNativeLibs) {
        registerCleanNativeLibsTransform(project)
    }

    validatePackageVersions()
    val commonTasks = configureCommonJvmDesktopTasks()
    configurePackagingTasks(commonTasks)
    copy(buildType = app.buildTypes.release).configurePackagingTasks(commonTasks)
}

@Suppress("LongParameterList")
internal class CommonJvmDesktopTasks(
    val unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    val checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>,
    val suggestRuntimeModules: TaskProvider<AbstractSuggestModulesTask>,
    val prepareAppResources: TaskProvider<Sync>,
    val prepareSandboxedAppResources: TaskProvider<Sync>?,
    val createRuntimeImage: TaskProvider<AbstractJLinkTask>,
    val patchCaCertificates: TaskProvider<AbstractPatchCaCertificatesTask>?,
)

@Suppress("LongMethod")
private fun JvmApplicationContext.configureCommonJvmDesktopTasks(): CommonJvmDesktopTasks {
    val unpackDefaultResources =
        tasks.register<AbstractUnpackDefaultApplicationResourcesTask>(
            taskNameAction = "unpack",
            taskNameObject = "DefaultComposeDesktopJvmApplicationResources",
        ) {}

    // Generate nucleus/nucleus-app.properties into a resource directory
    val generateAppProperties =
        tasks.register<AbstractGenerateAppPropertiesTask>(
            taskNameAction = "generate",
            taskNameObject = "appProperties",
        ) {
            appId.set(resolvedAppIdProvider())
            val resolvedAppName = app.nativeDistributions.appName
                ?: app.nativeDistributions.packageName
                ?: project.name
            appName.set(resolvedAppName)
            // AUMID must match the electron-builder appId (used in NSIS/MSI shortcut properties)
            val resolvedAumid = app.nativeDistributions.packageName?.let { "com.app.$it" }
            resolvedAumid?.let { appAumid.set(it) }
            app.nativeDistributions.packageVersion?.let { appVersion.set(it) }
            app.nativeDistributions.vendor?.let { appVendor.set(it) }
            app.nativeDistributions.description?.let { appDescription.set(it) }
            // Store the computed StartupWMClass so graalvm-runtime can use it as
            // WM_CLASS and GNOME can match the window to the .desktop file icon.
            val wmClass =
                app.nativeDistributions.linux.startupWMClass
                    ?.takeIf { it.isNotBlank() }
                    ?: app.mainClass?.replace('.', '-')
            wmClass?.let { startupWmClass.set(it) }
            // Resolve AppX StartupTask TaskId (MSIX auto-launch injection).
            // electron-builder hardcodes TaskId="SlackStartup" in the injected manifest
            // (legacy from its Slack origins) — this is the TaskId the runtime must use
            // to match the installed MSIX package. Custom overrides are possible but
            // require the user to patch the generated manifest themselves.
            val appxSettings = app.nativeDistributions.windows.appx
            if (appxSettings.addAutoLaunchExtension) {
                val taskId = appxSettings.startupTaskId ?: "SlackStartup"
                startupTaskId.set(taskId)
            }
            outputDir.set(appTmpDir.dir("app-properties"))
        }

    // Add the generated properties directory to the resource source set
    val appPropertiesOutputDir = generateAppProperties.flatMap { it.outputDir }
    if (project.plugins.hasPlugin(KOTLIN_MPP_PLUGIN_ID)) {
        project.mppExt.targets.all { target ->
            if (target is org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget) {
                target.compilations
                    .getByName("main")
                    .defaultSourceSet.resources
                    .srcDir(appPropertiesOutputDir)
            }
        }
    } else if (project.plugins.hasPlugin(KOTLIN_JVM_PLUGIN_ID)) {
        project.javaSourceSets
            .getByName("main")
            .resources
            .srcDir(appPropertiesOutputDir)
    }

    val checkRuntime =
        tasks.register<AbstractCheckNativeDistributionRuntime>(
            taskNameAction = "check",
            taskNameObject = "runtime",
        ) {
            jdkHome.set(app.javaHomeProvider)
            checkJdkVendor.set(NucleusProperties.checkJdkVendor(project.providers))
            jdkVersionProbeJar.from(
                project
                    .detachedComposeGradleDependency(
                        artifactId = "gradle-plugin-internal-jdk-version-probe",
                    ).excludeTransitiveDependencies(),
            )
        }

    val suggestRuntimeModules =
        tasks.register<AbstractSuggestModulesTask>(
            taskNameAction = "suggest",
            taskNameObject = "runtimeModules",
        ) {
            dependsOn(checkRuntime)
            javaHome.set(app.javaHomeProvider)
            modules.set(provider { app.nativeDistributions.modules })

            useAppRuntimeFiles { (jarFiles, mainJar) ->
                files.from(jarFiles)
                launcherMainJar.set(mainJar)
            }
        }

    val extractNativeLibs =
        if (app.nativeDistributions.hasStoreFormats) {
            val osName =
                when (currentOS) {
                    OS.Windows -> "windows"
                    OS.Linux -> "linux"
                    OS.MacOS -> "macos"
                }
            val archName =
                when (targetArch) {
                    Arch.X64 -> "x64"
                    Arch.Arm64 -> "arm64"
                }

            tasks.register<AbstractExtractNativeLibsTask>(
                taskNameAction = "extract",
                taskNameObject = "nativeLibsForSandboxing",
            ) {
                targetOs.set(osName)
                targetArch.set(archName)
                outputDir.set(appTmpDir.dir("sandboxing-native-libs"))

                // Use original JARs (without cleanupNativeLibs transform) so we always
                // get the native libs for the current arch even if cleanup is also enabled.
                useAppRuntimeFiles { (runtimeJars, _) ->
                    inputJars.from(runtimeJars)
                }
            }
        } else {
            null
        }

    val prepareAppResources =
        tasks.register<Sync>(
            taskNameAction = "prepare",
            taskNameObject = "appResources",
        ) {
            val appResourcesRootDir = app.nativeDistributions.appResourcesRootDir
            if (appResourcesRootDir.isPresent) {
                from(appResourcesRootDir.dir("common"))
                from(appResourcesRootDir.dir(currentOS.id))
                from(appResourcesRootDir.dir(targetTarget.id))
            }

            into(jvmTmpDirForTask())
        }

    // Separate resources task for the sandboxed pipeline, including extracted native libs
    val prepareSandboxedAppResources =
        if (extractNativeLibs != null) {
            tasks.register<Sync>(
                taskNameAction = "prepare",
                taskNameObject = "sandboxedAppResources",
            ) {
                val appResourcesRootDir = app.nativeDistributions.appResourcesRootDir
                if (appResourcesRootDir.isPresent) {
                    from(appResourcesRootDir.dir("common"))
                    from(appResourcesRootDir.dir(currentOS.id))
                    from(appResourcesRootDir.dir(targetTarget.id))
                }

                dependsOn(extractNativeLibs)
                from(extractNativeLibs.flatMap { it.outputDir })

                into(jvmTmpDirForTask())
            }
        } else {
            null
        }

    val createRuntimeImage =
        tasks.register<AbstractJLinkTask>(
            taskNameAction = "create",
            taskNameObject = "runtimeImage",
        ) {
            dependsOn(checkRuntime)
            javaHome.set(app.javaHomeProvider)
            modules.set(provider { app.nativeDistributions.modules })
            includeAllModules.set(provider { app.nativeDistributions.includeAllModules })
            javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
            destinationDir.set(appTmpDir.dir("runtime"))
        }

    val patchCaCertificates =
        if (!app.nativeDistributions.trustedCertificates.isEmpty) {
            tasks.register<AbstractPatchCaCertificatesTask>(
                taskNameAction = "patch",
                taskNameObject = "CaCertificates",
            ) {
                dependsOn(createRuntimeImage)
                runtimeImageDir.set(createRuntimeImage.flatMap { it.destinationDir })
                javaHome.set(app.javaHomeProvider)
                certificates.from(app.nativeDistributions.trustedCertificates)
                destinationDir.set(appTmpDir.dir("runtime-patched"))
            }
        } else {
            null
        }

    return CommonJvmDesktopTasks(
        unpackDefaultResources,
        checkRuntime,
        suggestRuntimeModules,
        prepareAppResources,
        prepareSandboxedAppResources,
        createRuntimeImage,
        patchCaCertificates,
    )
}

@Suppress("LongMethod")
private fun JvmApplicationContext.configurePackagingTasks(commonTasks: CommonJvmDesktopTasks) {
    val runProguard =
        if (buildType.proguard.isEnabled.orNull == true) {
            tasks.register<AbstractProguardTask>(
                taskNameAction = "proguard",
                taskNameObject = "Jars",
            ) {
                configureProguardTask(this, commonTasks.unpackDefaultResources)
            }
        } else {
            null
        }

    val hasStoreFormats = app.nativeDistributions.hasStoreFormats
    val allEbFormats =
        app.nativeDistributions.targetFormats
            .filter { it.backend == PackagingBackend.ELECTRON_BUILDER }
    val nonStoreFormats = allEbFormats.filter { !it.isStoreFormat }
    val storeFormats = allEbFormats.filter { it.isStoreFormat }

    // Strip native libs from JARs for the sandboxed pipeline (store formats only).
    val stripNativeLibsFromJars =
        if (hasStoreFormats) {
            tasks.register<AbstractStripNativeLibsFromJarsTask>(
                taskNameAction = "strip",
                taskNameObject = "nativeLibsFromJars",
            ) {
                outputDir.set(appTmpDir.dir("sandboxing-stripped-jars"))
                if (runProguard != null) {
                    dependsOn(runProguard)
                    inputJars.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
                    mainJarName.set(runProguard.flatMap { it.mainJarBaseName })
                } else {
                    useAppRuntimeFiles { (runtimeJars, mainJar) ->
                        inputJars.from(runtimeJars)
                        mainJarName.set(mainJar.map { it.asFile.name })
                    }
                }
            }
        } else {
            null
        }

    // === Non-sandboxed pipeline (direct distribution formats: DMG, ZIP, NSIS, etc.) ===

    val createDistributable =
        tasks.register<AbstractJPackageTask>(
            taskNameAction = "create",
            taskNameObject = "distributable",
            args = listOf(TargetFormat.RawAppImage),
        ) {
            configurePackageTask(
                this,
                createRuntimeImage = commonTasks.createRuntimeImage,
                prepareAppResources = commonTasks.prepareAppResources,
                checkRuntime = commonTasks.checkRuntime,
                unpackDefaultResources = commonTasks.unpackDefaultResources,
                runProguard = runProguard,
                patchCaCertificates = commonTasks.patchCaCertificates,
                sandboxed = false,
            )
        }

    val generateAotCache =
        if (app.nativeDistributions.enableAotCache) {
            tasks.register<AbstractGenerateAotCacheTask>(
                taskNameAction = "generate",
                taskNameObject = "AotCache",
            ) {
                dependsOn(createDistributable)
                distributableDir.set(createDistributable.flatMap { it.destinationDir })
                javaHome.set(app.javaHomeProvider)
                javaRuntimePropertiesFile.set(commonTasks.checkRuntime.flatMap { it.javaRuntimePropertiesFile })
                if (currentOS == OS.MacOS) {
                    val mac = app.nativeDistributions.macOS
                    val defaultResources = commonTasks.unpackDefaultResources
                    macRuntimeEntitlementsFile.set(
                        mac.runtimeEntitlementsFile.orElse(defaultResources.get { defaultEntitlements }),
                    )
                }
            }
        } else {
            null
        }

    val nonStoreNotarizeTasks = mutableListOf<TaskProvider<AbstractNotarizationTask>>()

    val nonStorePackageFormats =
        nonStoreFormats.map { targetFormat ->
            val packageFormat =
                tasks.register<AbstractElectronBuilderPackageTask>(
                    taskNameAction = "package",
                    taskNameObject = targetFormat.name,
                    args = listOf(targetFormat),
                ) {
                    configureElectronBuilderPackageTask(
                        this,
                        createDistributable = createDistributable,
                        unpackDefaultResources = commonTasks.unpackDefaultResources,
                    )
                    generateAotCache?.let { dependsOn(it) }
                }

            if (targetFormat.isCompatibleWith(OS.MacOS)) {
                val notarizeTask =
                    tasks.register<AbstractNotarizationTask>(
                        taskNameAction = "notarize",
                        taskNameObject = targetFormat.name,
                        args = listOf(targetFormat),
                    ) {
                        dependsOn(packageFormat)
                        inputDir.set(packageFormat.flatMap { it.destinationDir })
                        configureCommonNotarizationSettings(this)
                    }
                nonStoreNotarizeTasks.add(notarizeTask)
            }

            packageFormat
        }

    // === Sandboxed pipeline (store formats: PKG, AppX, Flatpak) ===

    val storeNotarizeTasks = mutableListOf<TaskProvider<AbstractNotarizationTask>>()

    val storePackageFormats =
        if (hasStoreFormats) {
            val createSandboxedDistributable =
                tasks.register<AbstractJPackageTask>(
                    taskNameAction = "create",
                    taskNameObject = "sandboxedDistributable",
                    args = listOf(TargetFormat.RawAppImage),
                ) {
                    configurePackageTask(
                        this,
                        createRuntimeImage = commonTasks.createRuntimeImage,
                        prepareAppResources =
                            commonTasks.prepareSandboxedAppResources
                                ?: commonTasks.prepareAppResources,
                        checkRuntime = commonTasks.checkRuntime,
                        unpackDefaultResources = commonTasks.unpackDefaultResources,
                        runProguard = runProguard,
                        stripNativeLibs = stripNativeLibsFromJars,
                        patchCaCertificates = commonTasks.patchCaCertificates,
                        sandboxed = true,
                    )
                }

            val generateSandboxedAotCache =
                if (app.nativeDistributions.enableAotCache) {
                    tasks.register<AbstractGenerateAotCacheTask>(
                        taskNameAction = "generate",
                        taskNameObject = "sandboxedAotCache",
                    ) {
                        dependsOn(createSandboxedDistributable)
                        distributableDir.set(createSandboxedDistributable.flatMap { it.destinationDir })
                        javaHome.set(app.javaHomeProvider)
                        javaRuntimePropertiesFile.set(commonTasks.checkRuntime.flatMap { it.javaRuntimePropertiesFile })
                        if (currentOS == OS.MacOS) {
                            val mac = app.nativeDistributions.macOS
                            val defaultResources = commonTasks.unpackDefaultResources
                            macRuntimeEntitlementsFile.set(
                                mac.runtimeEntitlementsFile.orElse(
                                    defaultResources.get { defaultSandboxRuntimeEntitlements },
                                ),
                            )
                        }
                    }
                } else {
                    null
                }

            storeFormats.map { targetFormat ->
                val packageFormat =
                    tasks.register<AbstractElectronBuilderPackageTask>(
                        taskNameAction = "package",
                        taskNameObject = targetFormat.name,
                        args = listOf(targetFormat),
                    ) {
                        configureElectronBuilderPackageTask(
                            this,
                            createDistributable = createSandboxedDistributable,
                            unpackDefaultResources = commonTasks.unpackDefaultResources,
                        )
                        generateSandboxedAotCache?.let { dependsOn(it) }
                    }

                if (targetFormat.isCompatibleWith(OS.MacOS)) {
                    val notarizeTask =
                        tasks.register<AbstractNotarizationTask>(
                            taskNameAction = "notarize",
                            taskNameObject = targetFormat.name,
                            args = listOf(targetFormat),
                        ) {
                            dependsOn(packageFormat)
                            inputDir.set(packageFormat.flatMap { it.destinationDir })
                            configureCommonNotarizationSettings(this)
                        }
                    storeNotarizeTasks.add(notarizeTask)
                }

                packageFormat
            }
        } else {
            emptyList()
        }

    val packageFormats = nonStorePackageFormats + storePackageFormats
    val allNotarizeTasks = nonStoreNotarizeTasks + storeNotarizeTasks

    val notarizeForCurrentOS =
        if (allNotarizeTasks.isNotEmpty()) {
            tasks.register<DefaultTask>(
                taskNameAction = "notarize",
                taskNameObject = "distributionForCurrentOS",
            ) {
                dependsOn(allNotarizeTasks)
            }
        } else {
            null
        }

    val packageForCurrentOS =
        tasks.register<DefaultTask>(
            taskNameAction = "package",
            taskNameObject = "distributionForCurrentOS",
        ) {
            dependsOn(packageFormats)
            notarizeForCurrentOS?.let { dependsOn(it) }
        }

    if (buildType === app.buildTypes.default) {
        // todo: remove
        tasks.register<DefaultTask>("package") {
            dependsOn(packageForCurrentOS)

            doLast {
                it.logger.error(
                    "'${it.name}' task is deprecated and will be removed in next releases. " +
                        "Use '${packageForCurrentOS.get().name}' task instead",
                )
            }
        }
    }

    val flattenJars =
        tasks.register<AbstractJarsFlattenTask>(
            taskNameAction = "flatten",
            taskNameObject = "Jars",
        ) {
            configureFlattenJars(this, runProguard)
        }

    val packageUberJarForCurrentOS =
        tasks.register<Jar>(
            taskNameAction = "package",
            taskNameObject = "uberJarForCurrentOS",
        ) {
            configurePackageUberJarForCurrentOS(this, flattenJars)
        }

    // runDistributable always uses the non-sandboxed distributable (most relevant for local dev/test)
    val runDistributable =
        tasks.register<AbstractRunDistributableTask>(
            taskNameAction = "run",
            taskNameObject = "distributable",
            args = listOf(createDistributable),
        )
    if (generateAotCache != null) {
        runDistributable.dependsOn(generateAotCache)
    }

    // runAppX: sideload and launch AppX package for local testing (Windows only)
    if (currentOS == OS.Windows && hasStoreFormats) {
        val appxPackageTask =
            storePackageFormats.firstOrNull { task ->
                task.map { it.targetFormat }.orNull == TargetFormat.AppX
            }
        if (appxPackageTask != null) {
            val appxSettings = app.nativeDistributions.windows.appx
            tasks.register<AbstractRunAppXTask>(
                taskNameAction = "run",
                taskNameObject = "appX",
            ) {
                dependsOn(appxPackageTask)
                appxDir.set(appxPackageTask.flatMap { it.destinationDir })
                identityName.set(
                    project.provider {
                        appxSettings.identityName
                            ?: error("appx.identityName must be set to use runAppX")
                    },
                )
                applicationId.set(
                    project.provider {
                        appxSettings.applicationId ?: "App"
                    },
                )
            }
        }
    }

    // Register the patch task eagerly so it's available for the run task's
    // lazy configuration (Gradle forbids task registration from within
    // another task's configuration action).
    val patchMacJvmTask: TaskProvider<AbstractPatchMacJvmTask>? =
        if (currentOS == OS.MacOS && app.nativeDistributions.macOS.macOsSdkVersion != null) {
            registerPatchMacJvmTask(
                javaHome = app.javaHome,
                minVersion = app.nativeDistributions.macOS.minimumSystemVersion ?: "10.13",
                sdkVersion = app.nativeDistributions.macOS.macOsSdkVersion!!,
            )
        } else {
            null
        }

    val run =
        tasks.register<JavaExec>(taskNameAction = "run") {
            configureRunTask(this, commonTasks.prepareAppResources, runProguard, patchMacJvmTask)
        }
}

private fun JvmApplicationContext.configureProguardTask(
    proguard: AbstractProguardTask,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
): AbstractProguardTask =
    proguard.apply {
        val settings = buildType.proguard
        mainClass.set(app.mainClass)
        proguardVersion.set(settings.version)
        proguardFiles.from(
            proguardVersion.map { proguardVersion ->
                project.detachedDependency(
                    groupId = "com.guardsquare",
                    artifactId = "proguard-gradle",
                    version = proguardVersion,
                )
            },
        )
        configurationFiles.from(settings.configurationFiles)
        // ProGuard uses -dontobfuscate option to turn off obfuscation, which is enabled by default
        // We want to disable obfuscation by default, because often
        // it is not needed, but makes troubleshooting much harder.
        // If obfuscation is turned off by default,
        // enabling (`isObfuscationEnabled.set(true)`) seems much better,
        // than disabling obfuscation disabling (`dontObfuscate.set(false)`).
        // That's why a task property is follows ProGuard design,
        // when our DSL does the opposite.
        dontobfuscate.set(settings.obfuscate.map { !it })
        dontoptimize.set(settings.optimize.map { !it })

        joinOutputJars.set(settings.joinOutputJars)

        dependsOn(unpackDefaultResources)
        defaultComposeRulesFile.set(unpackDefaultResources.flatMap { it.resources.defaultComposeProguardRules })

        maxHeapSize.set(settings.maxHeapSize)
        destinationDir.set(appTmpDir.dir("proguard"))
        javaHome.set(app.javaHomeProvider)

        useAppRuntimeFiles { files ->
            inputFiles.from(files.allRuntimeJars)
            mainJar.set(files.mainJar)
            mainJarBaseName.set(files.mainJar.map { it.asFile.name })
        }
    }

@Suppress("LongParameterList")
private fun JvmApplicationContext.configurePackageTask(
    packageTask: AbstractJPackageTask,
    createRuntimeImage: TaskProvider<AbstractJLinkTask>? = null,
    prepareAppResources: TaskProvider<Sync>? = null,
    checkRuntime: TaskProvider<AbstractCheckNativeDistributionRuntime>? = null,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    runProguard: Provider<AbstractProguardTask>? = null,
    stripNativeLibs: TaskProvider<AbstractStripNativeLibsFromJarsTask>? = null,
    patchCaCertificates: TaskProvider<AbstractPatchCaCertificatesTask>? = null,
    sandboxed: Boolean = false,
) {
    packageTask.enabled = packageTask.targetFormat.isCompatibleWithCurrentOS

    createRuntimeImage?.let { createRuntimeImage ->
        packageTask.dependsOn(createRuntimeImage)
        if (patchCaCertificates != null) {
            packageTask.dependsOn(patchCaCertificates)
            packageTask.runtimeImage.set(patchCaCertificates.flatMap { it.destinationDir })
        } else {
            packageTask.runtimeImage.set(createRuntimeImage.flatMap { it.destinationDir })
        }
    }

    prepareAppResources?.let { prepareResources ->
        packageTask.dependsOn(prepareResources)
        val resourcesDir = packageTask.project.layout.dir(prepareResources.map { it.destinationDir })
        packageTask.appResourcesDir.set(resourcesDir)
    }

    checkRuntime?.let { checkRuntime ->
        packageTask.dependsOn(checkRuntime)
        packageTask.javaRuntimePropertiesFile.set(checkRuntime.flatMap { it.javaRuntimePropertiesFile })
    }

    this.configurePlatformSettings(packageTask, unpackDefaultResources, sandboxed)

    app.nativeDistributions.let { executables ->
        packageTask.packageName.set(packageNameProvider)
        packageTask.appName.set(project.provider { executables.appName })
        packageTask.packageDescription.set(executables.description)
        packageTask.packageCopyright.set(executables.copyright)
        packageTask.packageVendor.set(executables.vendor)
        // jpackage app-image: use the jpackage-safe version.
        packageTask.packageVersion.set(jpackageVersionFor(packageTask.targetFormat))
    }

    val dirSuffix = if (sandboxed) "-sandboxed" else ""
    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.targetFormat.outputDirName}$dirSuffix")
        },
    )
    packageTask.javaHome.set(app.javaHomeProvider)

    when {
        stripNativeLibs != null -> {
            packageTask.dependsOn(stripNativeLibs)
            // Wire through declared output properties only (outputDir, mainJarName)
            // to avoid serializing task references in the configuration cache.
            val strippedOutputDir = stripNativeLibs.flatMap { it.outputDir }
            packageTask.files.from(
                strippedOutputDir.map { dir ->
                    dir.asFileTree.matching { it.exclude(".main-jar-name") }
                },
            )
            val strippedMainJarName = stripNativeLibs.flatMap { it.mainJarName }
            packageTask.launcherMainJar.fileProvider(
                strippedOutputDir.zip(strippedMainJarName) { dir, mainJarName ->
                    val metaFile = dir.asFile.resolve(".main-jar-name")
                    if (metaFile.exists()) {
                        dir.asFile.resolve(metaFile.readText().trim())
                    } else {
                        dir.asFile.resolve(mainJarName)
                    }
                },
            )
            // Strip task already mangles filenames for deduplication
            packageTask.mangleJarFilesNames.set(false)
            if (runProguard != null) {
                packageTask.packageFromUberJar.set(runProguard.flatMap { it.joinOutputJars })
            }
        }
        runProguard != null -> {
            packageTask.dependsOn(runProguard)
            packageTask.files.from(project.fileTree(runProguard.flatMap { it.destinationDir }))
            packageTask.launcherMainJar.set(runProguard.flatMap { it.mainJarInDestinationDir })
            packageTask.mangleJarFilesNames.set(false)
            packageTask.packageFromUberJar.set(runProguard.flatMap { it.joinOutputJars })
        }
        else -> {
            packageTask.useAppRuntimeFiles { (runtimeJars, mainJar) ->
                files.from(runtimeJars)
                launcherMainJar.set(mainJar)
            }
        }
    }

    packageTask.launcherMainClass.set(app.mainClass)
    packageTask.sandboxingEnabled.set(sandboxed)
    packageTask.launcherJvmArgs.set(
        provider {
            val executableTypeArg = "-D$APP_EXECUTABLE_TYPE=${packageTask.targetFormat.executableTypeValue}"
            val appIdArg = "-D$APP_ID=${resolvedAppIdProvider().get()}"
            var args = defaultJvmArgs + executableTypeArg + appIdArg + app.jvmArgs
            val splash = app.nativeDistributions.splashImage
            if (splash != null) {
                args = args + "-splash:\$APPDIR/resources/$splash"
            }
            if (sandboxed) {
                val nativeLibPath = if (currentOS == OS.MacOS) "\$APPDIR/../Frameworks" else "\$APPDIR/resources"
                args = args + sandboxingJvmArgs(nativeLibPath)
            }
            args
        },
    )
    packageTask.launcherArgs.set(provider { app.args })
}

private fun JvmApplicationContext.configureElectronBuilderPackageTask(
    packageTask: AbstractElectronBuilderPackageTask,
    createDistributable: TaskProvider<AbstractJPackageTask>,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
) {
    packageTask.enabled = packageTask.targetFormat.isCompatibleWithCurrentOS
    packageTask.dependsOn(createDistributable)
    packageTask.appImageRoot.set(createDistributable.flatMap { it.destinationDir })

    packageTask.destinationDir.set(
        app.nativeDistributions.outputBaseDir.map {
            it.dir("$appDirName/${packageTask.targetFormat.outputDirName}")
        },
    )

    packageTask.packageName.set(packageNameProvider)
    packageTask.executableName.set(
        project.provider {
            val dist = app.nativeDistributions
            val platformName =
                when (currentOS) {
                    OS.Linux -> dist.linux.packageName
                    OS.Windows -> dist.windows.packageName
                    OS.MacOS -> dist.macOS.packageName
                }
            platformName ?: dist.packageName ?: error(
                "No packageName configured for ${currentOS.name}. " +
                    "Set nativeDistributions { packageName = \"...\" } " +
                    "or the platform-specific packageName.",
            )
        },
    )
    packageTask.packageVersion.set(packageVersionFor(packageTask.targetFormat))
    packageTask.linuxIconFile.set(
        app.nativeDistributions.linux.iconFile
            .orElse(unpackDefaultResources.get { linuxIcon }),
    )
    packageTask.windowsIconFile.set(
        app.nativeDistributions.windows.iconFile
            .orElse(unpackDefaultResources.get { windowsIcon }),
    )
    val startupWMClass =
        app.nativeDistributions.linux.startupWMClass
            ?.takeIf { it.isNotBlank() }
            ?: app.mainClass?.replace('.', '-')
    if (startupWMClass != null) {
        packageTask.startupWMClass.set(startupWMClass)
    }
    packageTask.customNodePath.set(NucleusProperties.electronBuilderNodePath(project.providers))
    packageTask.publishMode.set(NucleusProperties.electronBuilderPublishMode(project.providers))
    packageTask.appxStoreLogo.set(app.nativeDistributions.windows.appx.storeLogo)
    packageTask.appxSquare44x44Logo.set(app.nativeDistributions.windows.appx.square44x44Logo)
    packageTask.appxSquare150x150Logo.set(app.nativeDistributions.windows.appx.square150x150Logo)
    packageTask.appxWide310x150Logo.set(app.nativeDistributions.windows.appx.wide310x150Logo)
    packageTask.distributions = app.nativeDistributions
    packageTask.targetArch.set(app.javaHomeProvider.map { jdkArch(java.io.File(it)).id })

    if (currentOS == OS.MacOS) {
        val mac = app.nativeDistributions.macOS
        packageTask.nonValidatedMacSigningSettings = mac.signing
        packageTask.nonValidatedMacBundleID.set(mac.bundleID)
        // PKG is always treated as App Store — ignore the deprecated user setting for store formats.
        packageTask.macAppStore.set(packageTask.targetFormat.isStoreFormat)
        val sandboxed = packageTask.targetFormat.isStoreFormat
        val defaultAppEntitlements =
            if (sandboxed) {
                unpackDefaultResources.get { defaultSandboxEntitlements }
            } else {
                unpackDefaultResources.get { defaultEntitlements }
            }
        val defaultRuntimeEntitlements =
            if (sandboxed) {
                unpackDefaultResources.get { defaultSandboxRuntimeEntitlements }
            } else {
                unpackDefaultResources.get { defaultEntitlements }
            }
        packageTask.macEntitlementsFile.set(
            mac.entitlementsFile.orElse(defaultAppEntitlements),
        )
        packageTask.macRuntimeEntitlementsFile.set(
            mac.runtimeEntitlementsFile.orElse(defaultRuntimeEntitlements),
        )
    }
}

internal fun JvmApplicationContext.configureCommonNotarizationSettings(notarizationTask: AbstractNotarizationTask) {
    val notarization = app.nativeDistributions.macOS.notarization
    notarizationTask.nonValidatedNotarizationSettings = notarization
    notarizationTask.onlyIf {
        val hasAppleId =
            !notarization.appleID.orNull.isNullOrEmpty() &&
                !notarization.password.orNull.isNullOrEmpty() &&
                !notarization.teamID.orNull.isNullOrEmpty()
        val hasKeychainProfile = !notarization.keychainProfile.orNull.isNullOrEmpty()
        val hasApiKey =
            !notarization.apiKey.orNull.isNullOrEmpty() &&
                !notarization.apiKeyId.orNull.isNullOrEmpty() &&
                !notarization.apiIssuer.orNull.isNullOrEmpty()
        val configured = hasAppleId || hasKeychainProfile || hasApiKey
        if (!configured) {
            it.logger.info("Notarization skipped: macOS notarization settings are not configured")
        }
        configured
    }
}

private fun <T : Any> TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>.get(
    fn: AbstractUnpackDefaultApplicationResourcesTask.DefaultResourcesProvider.() -> Provider<T>,
) = flatMap { fn(it.resources) }

internal fun JvmApplicationContext.configurePlatformSettings(
    packageTask: AbstractJPackageTask,
    defaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
    sandboxed: Boolean = false,
) {
    packageTask.dependsOn(defaultResources)

    when (currentOS) {
        OS.Linux -> {
            app.nativeDistributions.linux.also { linux ->
                packageTask.iconFile.set(linux.iconFile.orElse(defaultResources.get { linuxIcon }))
                packageTask.fileAssociations.set(linux.fileAssociations)
            }
        }
        OS.Windows -> {
            app.nativeDistributions.windows.also { win ->
                packageTask.winConsole.set(win.console)
                packageTask.iconFile.set(win.iconFile.orElse(defaultResources.get { windowsIcon }))
                packageTask.fileAssociations.set(win.fileAssociations)
            }
        }
        OS.MacOS -> {
            app.nativeDistributions.macOS.also { mac ->
                packageTask.macPackageName.set(mac.packageName)
                packageTask.macDockName.set(
                    if (mac.setDockNameSameAsPackageName) {
                        provider {
                            mac.dockName
                                ?: mac.packageName
                                ?: packageNameProvider.get()
                        }
                    } else {
                        provider {
                            mac.dockName ?: packageNameProvider.get()
                        }
                    },
                )
                // The jpackage task always builds a RawAppImage, so targetFormat.isStoreFormat
                // is always false. Use the sandboxed flag instead: sandboxed distributable feeds
                // store formats (PKG) and must pass --mac-app-store to jpackage so it searches
                // for the correct certificate type ("3rd Party Mac Developer Application").
                packageTask.macAppStore.set(sandboxed)
                packageTask.macAppCategory.set(mac.appCategory)
                packageTask.macMinimumSystemVersion.set(mac.minimumSystemVersion)
                val defaultAppEntitlements =
                    if (sandboxed) {
                        defaultResources.get { defaultSandboxEntitlements }
                    } else {
                        defaultResources.get { defaultEntitlements }
                    }
                val defaultRuntimeEntitlements =
                    if (sandboxed) {
                        defaultResources.get { defaultSandboxRuntimeEntitlements }
                    } else {
                        defaultResources.get { defaultEntitlements }
                    }
                packageTask.macEntitlementsFile.set(
                    mac.entitlementsFile.orElse(defaultAppEntitlements),
                )
                packageTask.macRuntimeEntitlementsFile.set(
                    mac.runtimeEntitlementsFile.orElse(defaultRuntimeEntitlements),
                )
                packageTask.packageBuildVersion.set(packageBuildVersionFor(packageTask.targetFormat))
                packageTask.nonValidatedMacBundleID.set(mac.bundleID)
                packageTask.macProvisioningProfile.set(mac.provisioningProfile)
                packageTask.macRuntimeProvisioningProfile.set(mac.runtimeProvisioningProfile)
                packageTask.macOsSdkVersion.set(mac.macOsSdkVersion)
                packageTask.macExtraPlistKeysRawXml.set(mac.infoPlistSettings.extraKeysRawXml)
                packageTask.nonValidatedMacSigningSettings = app.nativeDistributions.macOS.signing
                packageTask.iconFile.set(mac.iconFile.orElse(defaultResources.get { macIcon }))
                packageTask.fileAssociations.set(mac.fileAssociations)
                packageTask.urlProtocols.set(app.nativeDistributions.protocols)
                packageTask.macLayeredIcons.set(mac.layeredIconDir)
                packageTask.macLaunchAgents.set(mac.launchAgents.agents)
            }
        }
    }
}

private fun JvmApplicationContext.configureRunTask(
    exec: JavaExec,
    prepareAppResources: TaskProvider<Sync>,
    runProguard: Provider<AbstractProguardTask>?,
    patchMacJvmTask: TaskProvider<AbstractPatchMacJvmTask>?,
) {
    exec.dependsOn(prepareAppResources)

    exec.mainClass.set(app.mainClass)
    exec.executable(javaExecutable(app.javaHome))
    if (currentOS == OS.MacOS) {
        val sdkVersion = app.nativeDistributions.macOS.macOsSdkVersion
        if (sdkVersion != null && patchMacJvmTask != null) {
            val javaHome = app.javaHome
            exec.dependsOn(patchMacJvmTask)
            // Route the fork through a vtool-patched copy of the JDK so AppKit
            // gates Liquid Glass on. `javaLauncher` is finalized before
            // `doFirst`, so it must be wired at configuration time — but
            // reading the patch task's output from inside a `.map` chain
            // breaks the configuration cache (Gradle forbids querying a
            // property whose value depends on a task that hasn't completed).
            // Instead, resolve the patched binary path from `project.layout`
            // at config time; `dependsOn` guarantees the file exists by the
            // time the run task fires. Letting JavaExec stay in charge of
            // the fork is what allows IntelliJ's Gradle debugger to inject
            // JDWP and manage the process lifecycle.
            val patchedBinFile = project.layout.buildDirectory
                .file("nucleus/patched-jvm/bin/java")
                .get()
                .asFile
            val patchedJavaHomeFile = patchedBinFile.parentFile.parentFile
            exec.javaLauncher.set(
                PatchedJavaLauncher(
                    patchedJavaBinary = patchedBinFile,
                    patchedJavaHome = patchedJavaHomeFile,
                    sourceJavaHome = java.io.File(javaHome),
                    objects = project.objects,
                ),
            )
            // `executable` isn't Provider-aware in Gradle 9, but it isn't
            // finalized before `doFirst` either — align it with the launcher
            // right before the action runs so the toolchain check passes.
            exec.doFirst {
                (it as JavaExec).executable(patchedBinFile.absolutePath)
            }
        }
    }
    exec.jvmArgs =
        arrayListOf<String>().apply {
            addAll(defaultJvmArgs)
            add("-D$APP_EXECUTABLE_TYPE=$EXECUTABLE_TYPE_DEV")
            add("-D$APP_ID=${resolvedAppIdProvider().get()}")

            if (currentOS == OS.MacOS) {
                val dockName =
                    app.nativeDistributions.appName
                        ?: app.nativeDistributions.packageName
                        ?: project.name
                add("-Dapple.awt.application.name=$dockName")
                val file = app.nativeDistributions.macOS.iconFile.ioFileOrNull
                if (file != null) add("-Xdock:icon=$file")
            }

            addAll(app.jvmArgs)
            val appResourcesDir = prepareAppResources.get().destinationDir
            add("-D$APP_RESOURCES_DIR=${appResourcesDir.absolutePath}")

            app.nativeDistributions.splashImage?.let { splash ->
                val splashFile = appResourcesDir.resolve(splash)
                if (splashFile.exists()) {
                    add("-splash:${splashFile.absolutePath}")
                }
            }

            // Dev mode AOT: ./gradlew run -Paot=train|on|auto|off
            val aotCacheDir =
                project.layout.buildDirectory
                    .dir("compose/aot-cache")
                    .get()
                    .asFile
            val devAotCache = java.io.File(aotCacheDir, "dev.aot")
            when (project.findProperty("aot")?.toString()) {
                "train" -> {
                    aotCacheDir.mkdirs()
                    add("-XX:AOTCacheOutput=${devAotCache.absolutePath}")
                }
                "on" -> {
                    if (devAotCache.exists()) {
                        add("-XX:AOTCache=${devAotCache.absolutePath}")
                    }
                }
                "auto" -> {
                    if (devAotCache.exists()) {
                        add("-XX:AOTCache=${devAotCache.absolutePath}")
                    } else {
                        aotCacheDir.mkdirs()
                        add("-XX:AOTCacheOutput=${devAotCache.absolutePath}")
                    }
                }
                // "off" or absent → no-op
            }
        }
    exec.args = app.args

    if (runProguard != null) {
        exec.dependsOn(runProguard)
        exec.classpath = project.fileTree(runProguard.flatMap { it.destinationDir })
    } else {
        exec.useAppRuntimeFiles { (runtimeJars, _) ->
            classpath = runtimeJars
        }
    }
}

private fun JvmApplicationContext.configureFlattenJars(
    flattenJars: AbstractJarsFlattenTask,
    runProguard: Provider<AbstractProguardTask>?,
) {
    if (runProguard != null) {
        flattenJars.dependsOn(runProguard)
        flattenJars.inputFiles.from(runProguard.flatMap { it.destinationDir })
    } else {
        flattenJars.useAppRuntimeFiles { (runtimeJars, _) ->
            inputFiles.from(runtimeJars)
        }
    }

    flattenJars.flattenedJar.set(appTmpDir.file("flattenJars/flattened.jar"))
}

private fun JvmApplicationContext.configurePackageUberJarForCurrentOS(
    jar: Jar,
    flattenJars: Provider<AbstractJarsFlattenTask>,
) {
    jar.dependsOn(flattenJars)
    jar.from(project.zipTree(flattenJars.flatMap { it.flattenedJar }))

    app.mainClass?.let { jar.manifest.attributes["Main-Class"] = it }
    jar.manifest.attributes["Multi-Release"] = "true"
    jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    jar.archiveAppendix.set(targetTarget.id)
    jar.archiveBaseName.set(packageNameProvider)
    jar.archiveVersion.set(packageVersionFor(TargetFormat.RawAppImage))
    jar.archiveClassifier.set(buildType.classifier)
    jar.destinationDirectory.set(
        jar.project.layout.buildDirectory
            .dir("compose/jars"),
    )

    jar.doLast {
        jar.logger.lifecycle("The jar is written to ${jar.archiveFile.ioFile.canonicalPath}")
    }
}

/**
 * Builds the list of JVM args needed for sandboxing (loading native libs from the resources directory
 * instead of extracting them at runtime).
 *
 * JNA-specific args are always included: they are harmless if JNA is not on the classpath
 * (the JVM simply ignores unknown system properties).
 *
 * Native libs are extracted flat into [resourcesPath] (no platform subdirectories),
 * so a single directory entry is sufficient for all lookup mechanisms.
 */
private fun sandboxingJvmArgs(resourcesPath: String): List<String> =
    listOf(
        "-Djava.library.path=$resourcesPath",
        "-Djna.nounpack=true",
        "-Djna.nosys=true",
        "-Djna.boot.library.path=$resourcesPath",
        "-Djna.library.path=$resourcesPath",
    )

/**
 * Registers (or reuses) the per-project task that produces a vtool-patched
 * copy of the source JDK's `java` binary for Liquid Glass. Shared across run
 * tasks of all build types since inputs (javaHome, SDK/min version) are
 * identical at the project level.
 */
private fun JvmApplicationContext.registerPatchMacJvmTask(
    javaHome: String,
    minVersion: String,
    sdkVersion: String,
): TaskProvider<AbstractPatchMacJvmTask> {
    val taskName = "nucleusPatchMacJvm"
    return if (project.tasks.names.contains(taskName)) {
        project.tasks.named(taskName, AbstractPatchMacJvmTask::class.java)
    } else {
        project.tasks.register(taskName, AbstractPatchMacJvmTask::class.java) {
            it.sourceJavaHome.set(javaHome)
            it.minimumSystemVersion.set(minVersion)
            it.sdkVersion.set(sdkVersion)
            it.outputJavaHome.set(project.layout.buildDirectory.dir("nucleus/patched-jvm"))
        }
    }
}
