/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.JvmApplicationBuildType
import dev.nucleusframework.internal.KOTLIN_JVM_PLUGIN_ID
import dev.nucleusframework.internal.KOTLIN_MPP_PLUGIN_ID
import dev.nucleusframework.internal.javaSourceSets
import dev.nucleusframework.internal.mppExt
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.Target
import dev.nucleusframework.internal.utils.currentArch
import dev.nucleusframework.internal.utils.currentOS
import dev.nucleusframework.internal.utils.jdkArch
import dev.nucleusframework.internal.utils.joinDashLowercaseNonEmpty
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal data class JvmApplicationContext(
    val project: Project,
    private val appInternal: JvmApplicationInternal,
    val buildType: JvmApplicationBuildType,
    private val taskGroup: String = NUCLEUS_TASK_GROUP,
) {
    val app: JvmApplicationData
        get() = appInternal.data

    val appDirName: String
        get() = joinDashLowercaseNonEmpty(appInternal.name, buildType.classifier)

    val appTmpDir: Provider<Directory>
        get() =
            project.layout.buildDirectory.dir(
                "compose/tmp/$appDirName",
            )

    fun <T : Task> T.useAppRuntimeFiles(fn: T.(JvmApplicationRuntimeFiles) -> Unit) {
        val runtimeFiles =
            app.jvmApplicationRuntimeFilesProvider?.jvmApplicationRuntimeFiles(project)
                ?: JvmApplicationRuntimeFiles(
                    allRuntimeJars = app.fromFiles,
                    mainJar = app.mainJar,
                    taskDependencies = app.dependenciesTaskNames.toTypedArray(),
                )
        runtimeFiles.configureUsageBy(this, fn)
    }

    /**
     * Architecture of the configured JDK (may differ from the Gradle daemon's
     * arch when cross-building). The auto-downloaded OpenJDK 27 matches the
     * host, so we must not realize [JvmApplicationData.javaHomeOverride] here
     * — that would download the JDK at configuration time.
     */
    val targetArch by lazy {
        if (app.javaHomeOverride != null) {
            currentArch
        } else {
            jdkArch(java.io.File(app.javaHome))
        }
    }

    /** Target combining the current OS with the configured JDK's architecture. */
    val targetTarget by lazy { Target(currentOS, targetArch) }

    val tasks = JvmTasks(project, buildType, taskGroup)

    val packageNameProvider: Provider<String>
        get() = project.provider { appInternal.nativeDistributions.packageName ?: project.name }

    /**
     * Resolves the platform-specific application ID:
     * - macOS: bundleID > macOS.packageName > root packageName > project.name
     * - Linux: linux.packageName > root packageName > project.name
     * - Windows: windows.packageName > root packageName > project.name
     */
    fun resolvedAppIdProvider(): Provider<String> =
        project.provider {
            val dist = appInternal.nativeDistributions
            when (currentOS) {
                OS.MacOS -> {
                    val mac = dist.macOS
                    mac.bundleID ?: mac.packageName ?: dist.packageName ?: project.name
                }
                OS.Linux -> dist.linux.packageName ?: dist.packageName ?: project.name
                OS.Windows -> dist.windows.packageName ?: dist.packageName ?: project.name
            }
        }

    /**
     * Resolves the package name for the current OS, honoring platform-specific
     * `packageName` overrides:
     * - macOS: macOS.packageName > root packageName > project.name
     * - Linux: linux.packageName > root packageName > project.name
     * - Windows: windows.packageName > root packageName > project.name
     *
     * This drives the native output directory name on Linux and Windows. It lets the root
     * [packageNameProvider] stay ASCII-safe for Debian/RPM package names while a platform uses a
     * localized name.
     *
     * On macOS it names the launcher and the `.icns` only — the `.app` bundle directory is named by
     * [resolvedMacBundleNameProvider], which every macOS backend shares so the DMG and the ZIP ship
     * the same bundle.
     */
    fun resolvedPackageNameProvider(): Provider<String> =
        project.provider {
            val dist = appInternal.nativeDistributions
            when (currentOS) {
                OS.MacOS -> dist.macOS.packageName ?: dist.packageName ?: project.name
                OS.Linux -> dist.linux.packageName ?: dist.packageName ?: project.name
                OS.Windows -> dist.windows.packageName ?: dist.packageName ?: project.name
            }
        }

    /**
     * Resolves the name of the macOS `.app` bundle directory, shared by every macOS packaging
     * backend so that the DMG, the ZIP, the PKG, the raw app image and the GraalVM bundle all ship
     * the app under the same name. See [resolveMacBundleName].
     *
     * Only meaningful on macOS; on other platforms it degrades to the resolved package name.
     */
    fun resolvedMacBundleNameProvider(): Provider<String> =
        project.provider {
            val dist = appInternal.nativeDistributions
            if (currentOS != OS.MacOS) {
                return@provider resolvedPackageNameProvider().get()
            }
            resolveMacBundleName(dist, dist.macOS, project.name)
        }

    inline fun <reified T : Any> provider(noinline fn: () -> T): Provider<T> = project.provider(fn)

    fun configureDefaultApp() {
        if (project.plugins.hasPlugin(KOTLIN_MPP_PLUGIN_ID)) {
            var isJvmTargetConfigured = false
            project.mppExt.targets.all { target ->
                if (target is KotlinJvmTarget) {
                    if (!isJvmTargetConfigured) {
                        appInternal.from(target)
                        isJvmTargetConfigured = true
                    } else {
                        project.logger.error(
                            "w: Default configuration for Compose Desktop Application is disabled: " +
                                "multiple Kotlin JVM targets definitions are detected. " +
                                "Specify, which target to use by using `compose.desktop.application.from(kotlinMppTarget)`",
                        )
                        appInternal.disableDefaultConfiguration()
                    }
                }
            }
        } else if (project.plugins.hasPlugin(KOTLIN_JVM_PLUGIN_ID)) {
            val mainSourceSet = project.javaSourceSets.getByName("main")
            appInternal.from(mainSourceSet)
        }
    }
}
