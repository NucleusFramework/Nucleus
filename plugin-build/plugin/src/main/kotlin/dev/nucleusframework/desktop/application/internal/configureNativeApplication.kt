/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.NativeApplication
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.tasks.AbstractNativeMacApplicationPackageAppDirTask
import dev.nucleusframework.desktop.application.tasks.AbstractNativeMacApplicationPackageDmgTask
import dev.nucleusframework.desktop.application.tasks.AbstractNativeMacApplicationPackageTask
import dev.nucleusframework.desktop.application.internal.validation.validateMacBundleName
import dev.nucleusframework.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import dev.nucleusframework.internal.utils.OS
import dev.nucleusframework.internal.utils.currentOS
import dev.nucleusframework.internal.utils.joinLowerCamelCase
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBinary
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind
import java.util.*

internal fun configureNativeApplication(
    project: Project,
    app: NativeApplication,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
) {
    if (currentOS != OS.MacOS) return

    validateMacBundleName(project, app.distributions, app.distributions.macOS)

    for (target in app._targets) {
        configureNativeApplication(project, app, target, unpackDefaultResources)
    }
}

private fun configureNativeApplication(
    project: Project,
    app: NativeApplication,
    target: KotlinNativeTarget,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
) {
    for (binary in target.binaries) {
        if (binary.outputKind == NativeOutputKind.EXECUTABLE) {
            configureNativeApplication(project, app, binary, unpackDefaultResources)
        }
    }
}

private fun configureNativeApplication(
    project: Project,
    app: NativeApplication,
    binary: NativeBinary,
    unpackDefaultResources: TaskProvider<AbstractUnpackDefaultApplicationResourcesTask>,
) {
    val createDistributable =
        project.tasks.composeDesktopNativeTask<AbstractNativeMacApplicationPackageAppDirTask>(
            desktopNativeTaskName("createDistributableNative", binary),
        ) {
            configureNativePackageTask(app, binary, TargetFormat.RawAppImage)

            dependsOn(unpackDefaultResources)
            val macIcon = app.distributions.macOS.iconFile
            val defaultIcon = unpackDefaultResources.flatMap { it.resources.macIcon }
            iconFile.set(macIcon.orElse(defaultIcon))

            dependsOn(binary.linkTaskProvider)
            executable.set(project.layout.file(binary.linkTaskProvider.map { it.binary.outputFile }))
            appCategory.set(project.provider { app.distributions.macOS.appCategory ?: "Unknown" })
            copyright.set(
                project.provider {
                    app.distributions.copyright ?: "Copyright (C) ${Calendar.getInstance().get(Calendar.YEAR)}"
                },
            )
            if (binary.outputKind == NativeOutputKind.EXECUTABLE) {
                val binaryResources =
                    (binary.compilation.associatedCompilations + binary.compilation).flatMap { compilation ->
                        compilation.allKotlinSourceSets.map { it.resources }
                    }
                composeResourcesDirs.setFrom(binaryResources)
            }
            macLayeredIcons.set(app.distributions.macOS.layeredIconDir)
        }

    if (TargetFormat.Dmg in app.distributions.targetFormats) {
        val packageDmg =
            project.tasks.composeDesktopNativeTask<AbstractNativeMacApplicationPackageDmgTask>(
                desktopNativeTaskName("packageDmgNative", binary),
            ) {
                configureNativePackageTask(app, binary, TargetFormat.Dmg)

                dependsOn(createDistributable)
                appDir.set(createDistributable.flatMap { it.destinationDir })

                installDir.set(
                    project.provider {
                        app.distributions.macOS.installationPath ?: "/Applications"
                    },
                )

                val dmgDsl = app.distributions.macOS.dmg
                dmgDsl.format?.let { dmgFormat.set(it) }
                dmgDsl.iconSize?.let { dmgIconSize.set(it) }
                dmgDsl.window.x?.let { dmgWindowX.set(it) }
                dmgDsl.window.y?.let { dmgWindowY.set(it) }
                dmgDsl.window.width?.let { dmgWindowWidth.set(it) }
                dmgDsl.window.height?.let { dmgWindowHeight.set(it) }
                dmgDsl.title?.let { dmgTitle.set(it) }
                dmgDsl.backgroundColor?.let { dmgBackgroundColor.set(it) }
                if (dmgDsl.background.isPresent) {
                    dmgBackgroundImage.set(dmgDsl.background)
                }
                if (dmgDsl.contents.isNotEmpty()) {
                    dmgContents.set(dmgDsl.contents.toList())
                }
            }
    }
}

private fun AbstractNativeMacApplicationPackageTask.configureNativePackageTask(
    app: NativeApplication,
    binary: NativeBinary,
    format: TargetFormat,
) {
    packageName.set(
        project.provider {
            app.distributions.macOS.packageName
                ?: app.distributions.packageName
                ?: project.name
        },
    )

    bundleName.set(
        project.provider {
            resolveMacBundleName(app.distributions, app.distributions.macOS, project.name)
        },
    )

    // todo: dmg package version
    packageVersion.set(
        project.provider {
            app.distributions.macOS.packageVersion
                ?: app.distributions.packageVersion
                ?: project.version.toString().takeIf { it != "unspecified" }
                ?: "1.0.0"
        },
    )

    destinationDir.set(
        app.distributions.outputBaseDir.dir(
            "${app.name}/native-${binary.target.name}-${binary.buildType.name.lowercase()}-${format.id}",
        ),
    )
}

private fun desktopNativeTaskName(
    action: String,
    binary: NativeBinary,
): String = joinLowerCamelCase(action, binary.buildType.name.lowercase(), binary.target.name)

private inline fun <reified T : Task> TaskContainer.composeDesktopNativeTask(
    name: String,
    args: List<Any> = emptyList(),
    noinline configureFn: T.() -> Unit = {},
) = register(name, T::class.java, *args.toTypedArray()).apply {
    configure {
        it.group = "nucleus (native)"
        it.configureFn()
    }
}
