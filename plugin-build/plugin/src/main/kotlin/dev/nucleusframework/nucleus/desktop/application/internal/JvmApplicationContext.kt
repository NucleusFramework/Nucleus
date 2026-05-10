/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.nucleus.desktop.application.internal

import dev.nucleusframework.nucleus.desktop.application.dsl.JvmApplicationBuildType
import dev.nucleusframework.nucleus.internal.KOTLIN_JVM_PLUGIN_ID
import dev.nucleusframework.nucleus.internal.KOTLIN_MPP_PLUGIN_ID
import dev.nucleusframework.nucleus.internal.javaSourceSets
import dev.nucleusframework.nucleus.internal.mppExt
import dev.nucleusframework.nucleus.internal.utils.OS
import dev.nucleusframework.nucleus.internal.utils.Target
import dev.nucleusframework.nucleus.internal.utils.currentOS
import dev.nucleusframework.nucleus.internal.utils.jdkArch
import dev.nucleusframework.nucleus.internal.utils.joinDashLowercaseNonEmpty
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

    /** Architecture of the configured JDK (may differ from the Gradle daemon's arch when cross-building). */
    val targetArch by lazy { jdkArch(java.io.File(app.javaHome)) }

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
                                "Specify, which target to use by using " +
                                "`compose.desktop.application.from(kotlinMppTarget)`",
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
