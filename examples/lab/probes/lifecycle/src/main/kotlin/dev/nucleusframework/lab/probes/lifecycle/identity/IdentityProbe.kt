package dev.nucleusframework.lab.probes.lifecycle.identity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.Readouts
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class IdentityProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "App identity & AOT",
            domain = Domain.Lifecycle,
            summary =
                "Does the packaged app know its id, version, package format and AOT mode — the facts every " +
                    "other integration keys on?",
            modules = listOf("core-runtime", "aot-runtime"),
            checks =
                listOf(
                    Check(
                        "metadata",
                        "Packaged: plugin metadata is true and appId/version match the build's " +
                            "packageName/packageVersion",
                    ),
                    Check(
                        "type",
                        "The executable type matches the installer used (DMG, NSIS, DEB, …); DEV only under " +
                            "./gradlew run",
                    ),
                    Check(
                        "aumid",
                        "Windows: the AUMID equals the installer's appId (the one toasts and jump lists use)",
                    ),
                    Check(
                        "aot",
                        "A build with the AOT cache reports RUNTIME and a -XX:AOTCache argument; cold start feels " +
                            "faster",
                    ),
                    Check("sandbox", "The App Store PKG reports sandboxed=true; every other format false"),
                ),
            keywords = listOf("NucleusApp", "ExecutableRuntime", "version", "Leyden", "AOT cache"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<IdentityViewModel>()
        val state by vm.state.collectAsState()
        val identity = state.identity ?: return

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Plugin metadata",
                        Availability.of(identity.pluginMetadata) {
                            "no nucleus.app.* property nor nucleus-app.properties resource"
                        },
                    ),
                    Capability(
                        "AOT cache",
                        Availability.of(identity.aotMode == "RUNTIME") { "mode ${identity.aotMode}: no cache loaded" },
                    ),
                ),
            controls = {
                Actions { SecondaryAction("Read again") { vm.onIntent(IdentityIntent.Refresh) } }
                Hint("Install a package of the Lab, then compare with the build configuration.")
            },
            observed = {
                SubHeading("NucleusApp")
                Readouts(
                    listOf(
                        "appId" to identity.appId,
                        "appName" to identity.appName,
                        "version" to identity.version,
                        "vendor" to identity.vendor,
                        "description" to identity.description,
                        "aumid" to identity.aumid,
                        "startupTaskId" to identity.startupTaskId,
                    ),
                )
                SubHeading("ExecutableRuntime")
                Readouts(
                    listOf(
                        "type()" to identity.executableType,
                        "nucleus.executable.type" to identity.typeProperty,
                        "markerVersion()" to identity.markerVersion,
                        "isSandboxed()" to identity.sandboxed.toString(),
                        "native image" to identity.nativeImage.toString(),
                        "jpackage.app-version" to identity.jpackageVersion,
                        "launcher" to identity.launcherPath,
                        "java.home" to identity.javaHome,
                    ),
                )
                SubHeading("AOT")
                Readout(
                    "AotRuntime.mode()",
                    identity.aotMode,
                    tone = if (identity.aotMode == "RUNTIME") Tone.Ok else Tone.Neutral,
                )
                Readout("cache argument", identity.aotCacheArgument ?: "none")
                Readout("JVM up for", "${formatDurationMillis(identity.uptimeMillis)} at first read")
                state.drift.forEach { Readout("drift", it, tone = Tone.Error) }
            },
        )
    }

    companion object {
        val ID = ProbeId("lifecycle.identity")
    }
}
