package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.dsl.MacWidgetsSettings
import dev.nucleusframework.desktop.application.internal.MacWidgetsPackaging
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Assembles the app's widget extension (`NucleusWidgets.appex`) from `macOS { widgets { } }` and the
 * prebuilt extension of the `nucleus.widget-macos` JAR; see [MacWidgetsPackaging].
 */
@DisableCachingByDefault(because = "Copies a few files out of a JAR")
abstract class AbstractPrepareMacWidgetsExtensionTask : DefaultTask() {
    @get:Internal
    internal abstract val widgets: Property<MacWidgetsSettings>

    /** What [widgets] writes, as the up-to-date check sees it. */
    @get:Input
    internal val widgetsFingerprint: String
        get() =
            widgets.get().let { settings ->
                settings.appGroup + "|" +
                    settings.definitions.joinToString(";") {
                        "${it.kind}:${it.displayName}:${it.description}:${it.families}"
                    }
            }

    @get:Input
    abstract val appBundleId: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    /** `aarch64` or `x64`: the architecture of the app being packaged. */
    @get:Input
    abstract val arch: Property<String>

    @get:Classpath
    abstract val runtimeJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Writes the `.appex` and its entitlements into [outputDir]. */
    @TaskAction
    fun assemble() {
        MacWidgetsPackaging.assemble(
            widgets.get(),
            appBundleId.get(),
            appVersion.get(),
            arch.get(),
            runtimeJars.files,
            outputDir.get().asFile,
        )
    }
}
