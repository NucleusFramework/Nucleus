package dev.nucleusframework.lab.probes.system.zstd

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class ZstdProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Native library extraction",
            domain = Domain.System,
            summary =
                "Does a third-party extract-and-load JNI library (zstd-kmp) load and work " +
                    "here, including in a sandboxed store build?",
            modules = listOf("core-runtime"),
            checks =
                listOf(
                    Check("roundtrip", "A round trip of every size ends identical, with a sensible compression ratio"),
                    Check(
                        "dev-temp",
                        "Unpackaged / DMG / NSIS: the library is loaded from a zstd-kmp*.tmp file in the temp dir",
                    ),
                    Check(
                        "sandbox-marker",
                        "Sandboxed Pkg / AppX / Flatpak: the JAR entry is a marker and the manifest names a bundled library",
                    ),
                    Check(
                        "sandbox-loaded",
                        "Sandboxed Pkg: the module list shows the bundled copy inside the app (Contents/Frameworks), not a temp file",
                    ),
                    Check("no-error", "No UnsatisfiedLinkError in the timeline on any package format"),
                ),
            keywords =
                listOf(
                    "zstd",
                    "jni",
                    "sandbox",
                    "NucleusSandboxLoader",
                    "plugin",
                    "pkg",
                    "app store",
                    "System.load",
                    "#317",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ZstdViewModel>()
        val state by vm.state.collectAsState()
        var sizeKib by remember { mutableIntStateOf(SIZES.first()) }

        ProbeLayout(
            capabilities = capabilities(state),
            controls = {
                ChoiceRow("Payload", SIZES, sizeKib, name = ::payloadName) { sizeKib = it }
                Actions {
                    PrimaryAction(
                        "Round trip",
                        enabled = !state.running,
                    ) { vm.onIntent(ZstdIntent.RunRoundTrip(sizeKib)) }
                    SecondaryAction("Re-inspect JAR + manifest") { vm.onIntent(ZstdIntent.Inspect) }
                    SecondaryAction("Read module list") { vm.onIntent(ZstdIntent.ReadMappings) }
                }
                SubHeading("Why it matters")
                Hint(
                    "zstd-kmp copies its library from the JAR to a temp file and System.load()s it. " +
                        "A sandboxed macOS Pkg (App Store) refuses unsigned code from a temp dir, so the Nucleus " +
                        "pipeline strips the library to a marker, bundles a signed copy, and rewrites System.load " +
                        "to NucleusSandboxLoader. This probe shows which of the two paths ran.",
                )
                state.runtime?.let { runtime ->
                    Readout("executable type", runtime.executableType)
                    Readout("sandboxed", runtime.sandboxed.toString())
                    if (runtime.nativeImage) {
                        Hint("GraalVM native image: the JAR resource is embedded in the image.")
                    }
                }
            },
            observed = { ZstdObserved(state) },
        )
    }

    companion object {
        val ID = ProbeId("system.zstd")
        val SIZES = listOf(64, 1024, 16 * 1024)
    }
}

private fun payloadName(kib: Int): String = if (kib >= 1024) "${kib / 1024} MiB" else "$kib KiB"

private fun capabilities(state: ZstdState): List<Capability> {
    val resource = state.resource
    val jar =
        when {
            resource == null -> Availability.Unknown
            resource.url == null ->
                Availability.Unavailable(
                    "${resource.path} not found: zstd-kmp ships no library for this arch",
                )
            else -> Availability.Available
        }
    val loaded =
        when {
            state.loadError != null -> Availability.Unavailable(state.loadError)
            state.load != null -> Availability.Available
            else -> Availability.Unknown
        }
    return listOf(Capability("Library in JAR", jar), Capability("Loaded", loaded))
}
