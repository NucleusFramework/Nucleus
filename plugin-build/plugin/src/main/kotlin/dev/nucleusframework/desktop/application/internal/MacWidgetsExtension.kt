package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.MacAppExtension
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import dev.nucleusframework.desktop.application.tasks.AbstractPrepareMacWidgetsExtensionTask
import dev.nucleusframework.internal.utils.Arch
import dev.nucleusframework.internal.utils.jdkArch
import dev.nucleusframework.internal.utils.uppercaseFirstChar
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** The task assembling the widget extension of `macOS { widgets { } }`, and its `appExtensions` entry. */
internal class MacWidgetsExtension(
    val task: TaskProvider<AbstractPrepareMacWidgetsExtensionTask>,
    val extension: MacAppExtension,
)

/**
 * The widget extension of this build type, registered on first use (every macOS packaging path
 * asks), or `null` when no widget is declared.
 */
internal fun JvmApplicationContext.macWidgetsExtension(): MacWidgetsExtension? {
    val widgets = app.nativeDistributions.macOS.widgets
    if (widgets.definitions.isEmpty()) return null
    val outputDir = appTmpDir.get().dir("macos-widgets").asFile
    val name = "prepare${buildType.classifier.uppercaseFirstChar()}MacWidgetsExtension"
    val task =
        if (name in project.tasks.names) {
            project.tasks.named(name, AbstractPrepareMacWidgetsExtensionTask::class.java)
        } else {
            tasks.register<AbstractPrepareMacWidgetsExtensionTask>(
                taskNameAction = "prepare",
                taskNameObject = "macWidgetsExtension",
            ) {
                description = "Assembles the WidgetKit extension of macOS { widgets { } }"
                this.widgets.set(widgets)
                appBundleId.set(macAppBundleId())
                appVersion.set(packageVersionFor(TargetFormat.Dmg))
                arch.set(
                    app.javaHomeProvider.map {
                        if (jdkArch(File(it)) == Arch.Arm64) "aarch64" else "x64"
                    },
                )
                useAppRuntimeFiles { (runtimeJars, _) -> this.runtimeJars.from(runtimeJars) }
                appEntitlements.set(app.nativeDistributions.macOS.entitlementsFile)
                this.outputDir.set(outputDir)
            }
        }
    return MacWidgetsExtension(task, MacWidgetsPackaging.appExtension(outputDir))
}

/** The app's bundle id as the packaging tasks write it: `bundleID`, else the main class's package. */
private fun JvmApplicationContext.macAppBundleId() =
    project.provider {
        app.nativeDistributions.macOS.bundleID
            ?: app.mainClass?.substringBeforeLast(".")
            ?: error("macOS widgets need macOS { bundleID } (or a main class in a package)")
    }

/** The app extensions to embed: the DSL's, plus the widget extension when widgets are declared. */
internal fun JvmApplicationContext.macAppExtensionsWithWidgets(): List<MacAppExtension> =
    app.nativeDistributions.macOS.appExtensions.extensions + listOfNotNull(macWidgetsExtension()?.extension)
