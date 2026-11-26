package dev.nucleusframework.lab.probes.window.popups

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
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.window.shared.describe
import dev.nucleusframework.lab.probes.window.shared.placementCaveat
import dev.nucleusframework.lab.probes.window.state.PositionTarget
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class PopupsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Popups & menus",
            domain = Domain.Window,
            summary =
                "Do menus, popups and dialogs land on screen next to their anchor — clamped to the display, not to " +
                    "the window — with native layers and native menus on or off?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "edge-menus",
                        "Window parked in each corner: the matching corner menu opens fully on screen (inside the work area)",
                    ),
                    Check(
                        "oversized",
                        "Native layers: the oversized panel escapes the window; inline: it is clipped to it",
                    ),
                    Check("dialog", "The dialog stays centred on the window wherever the window is parked"),
                    Check(
                        "context",
                        "Right-click shows the context menu at the pointer: NSMenu / Fluent / Adwaita with native menus, Compose's otherwise",
                    ),
                    Check("text-menu", "Right-click in the text field shows Cut / Copy / Paste and they work"),
                    Check("dismiss", "Clicking outside, Esc, or switching app closes an open menu"),
                ),
            keywords = listOf("dropdown", "context menu", "nativePopupLayers", "nativeContextMenu", "#569"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<PopupsViewModel>()
        val state by vm.state.collectAsState()
        val c = state.config
        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Parking the window",
                        when (state.snapshot?.canPlaceOnScreen) {
                            null -> Availability.Unknown
                            false -> Availability.Unavailable("native Wayland: the compositor places windows")
                            true -> Availability.Available
                        },
                    ),
                ),
            controls = {
                Actions {
                    if (state.sessionOpen) {
                        SecondaryAction("Close window") { vm.onIntent(PopupsIntent.Close) }
                    } else {
                        PrimaryAction("Open popups lab window") { vm.onIntent(PopupsIntent.Open) }
                    }
                }
                SubHeading("Creation-time (reopens the window)")
                SwitchRow("Native popup layers", c.nativePopupLayers) {
                    vm.onIntent(PopupsIntent.SetConfig(c.copy(nativePopupLayers = it)))
                }
                SwitchRow("Native context menu", c.nativeContextMenu) {
                    vm.onIntent(PopupsIntent.SetConfig(c.copy(nativeContextMenu = it)))
                }
                SubHeading("Park the window against its work area")
                Actions {
                    listOf(
                        PositionTarget.TopStart,
                        PositionTarget.TopEnd,
                        PositionTarget.Center,
                        PositionTarget.BottomStart,
                        PositionTarget.BottomEnd,
                    ).forEach { target ->
                        SecondaryAction("${target.label} ${target.name}", enabled = state.sessionOpen) {
                            vm.onIntent(PopupsIntent.Park(target))
                        }
                    }
                }
            },
            observed = {
                Readout(
                    "Window",
                    state.snapshot?.describe() ?: "closed",
                    tone = if (state.snapshot == null) Tone.Muted else Tone.Neutral,
                )
                Readout("Work area", state.snapshot?.workAreaPx?.describe())
                placementCaveat(state.snapshot)?.let { Readout("Caveat", it, tone = Tone.Warning) }
                SubHeading("Last placement of each popup")
                if (state.popups.isEmpty()) EmptyState("Open a menu or the panel.")
                state.popups.values.forEach { report ->
                    Readout(
                        report.label,
                        report.describe(),
                        tone = if (report.fitsWorkArea == false) Tone.Error else Tone.Neutral,
                    )
                }
                Hint("Screen positions are ≈: they assume the host's outer origin is its content origin.")
                SubHeading("Picks")
                Readout("Last", state.picks.joinToString(" · ").ifEmpty { null })
            },
        )
    }

    companion object {
        val ID = ProbeId("window.popups")
    }
}
