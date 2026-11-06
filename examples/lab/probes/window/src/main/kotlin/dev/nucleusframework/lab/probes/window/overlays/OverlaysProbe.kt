package dev.nucleusframework.lab.probes.window.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.lab.probes.window.shared.describe
import dev.nucleusframework.lab.probes.window.shared.expectedTopLeft
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class OverlaysProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Overlays: watermark & widget",
            domain = Domain.Window,
            summary =
                "Do transparent, click-through, top- and bottom-stacked overlay windows behave like overlays — on " +
                    "every desktop, out of the dock, never in the way?",
            modules = listOf("decorated-window-tao", "nucleus-application", "sf-symbols"),
            checks =
                listOf(
                    Check("transparent", "Only the pill / the card are visible; the desktop shows everywhere else"),
                    Check(
                        "click-through",
                        "Clicking the watermark reaches the window under it; presses-while-click-through stays 0",
                    ),
                    Check(
                        "corner",
                        "Each corner choice moves the watermark into that corner of the work area (not under the taskbar / menu bar)",
                    ),
                    Check(
                        "no-dock",
                        "Neither overlay appears in the taskbar / Dock / Alt+Tab app list as a separate entry",
                    ),
                    Check("bottom", "The widget stays below every window, also after clicking it"),
                    Check("drag", "Dragging the widget card moves it; locked, it stays put"),
                    Check(
                        "menu",
                        "Right-click on the widget opens the native menu with SF Symbol icons (macOS) and each pick lands in the log",
                    ),
                ),
            keywords =
                listOf(
                    "transparent",
                    "click through",
                    "always on top",
                    "always on bottom",
                    "desktop widget",
                    "forceX11",
                ),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<OverlaysViewModel>()
        val state by vm.state.collectAsState()
        val w = state.watermark
        val g = state.widget

        ProbeLayout(
            capabilities = capabilities(state),
            controls = {
                SubHeading("Watermark (above everything)")
                Actions { OpenClose(state, Overlay.Watermark, vm) }
                ChoiceRow(
                    "Corner",
                    Corner.entries,
                    w.corner,
                ) { vm.onIntent(OverlaysIntent.SetWatermark(w.copy(corner = it))) }
                SwitchRow(
                    "Click-through",
                    w.clickThrough,
                ) { vm.onIntent(OverlaysIntent.SetWatermark(w.copy(clickThrough = it))) }
                SwitchRow(
                    "Always on top",
                    w.alwaysOnTop,
                ) { vm.onIntent(OverlaysIntent.SetWatermark(w.copy(alwaysOnTop = it))) }
                SwitchRow(
                    "All workspaces",
                    w.allWorkspaces,
                ) { vm.onIntent(OverlaysIntent.SetWatermark(w.copy(allWorkspaces = it))) }
                SwitchRow(
                    "forceX11 (Linux, reopens)",
                    w.forceX11,
                ) { vm.onIntent(OverlaysIntent.SetWatermark(w.copy(forceX11 = it))) }
                SubHeading("Widget (below everything)")
                Actions { OpenClose(state, Overlay.Widget, vm) }
                SwitchRow(
                    "Always on bottom",
                    g.alwaysOnBottom,
                ) { vm.onIntent(OverlaysIntent.SetWidget(g.copy(alwaysOnBottom = it))) }
                SwitchRow("Locked", g.locked) { vm.onIntent(OverlaysIntent.SetWidget(g.copy(locked = it))) }
                SwitchRow(
                    "All workspaces",
                    g.allWorkspaces,
                ) { vm.onIntent(OverlaysIntent.SetWidget(g.copy(allWorkspaces = it))) }
                SwitchRow(
                    "forceX11 (Linux, reopens)",
                    g.forceX11,
                ) { vm.onIntent(OverlaysIntent.SetWidget(g.copy(forceX11 = it))) }
            },
            observed = {
                SubHeading("Watermark")
                val ws = state.snapshots[Overlay.Watermark]
                OverlayReadouts(ws, Overlay.Watermark in state.open)
                Readout("Distance to corner", ws?.let { cornerDistance(it, w.corner) })
                Readout("Presses received", state.watermarkPresses.toString())
                Readout(
                    "…while click-through",
                    state.watermarkPressesWhileClickThrough.toString(),
                    tone = if (state.watermarkPressesWhileClickThrough > 0) Tone.Error else Tone.Ok,
                )
                Readout(
                    "Focused (must stay false)",
                    ws?.focused?.toString(),
                    tone = if (ws?.focused == true) Tone.Error else Tone.Neutral,
                )
                SubHeading("Widget")
                OverlayReadouts(state.snapshots[Overlay.Widget], Overlay.Widget in state.open)
                Readout("Drags started", state.widgetDrags.toString())
                Readout("Menu picks", state.widgetMenuPicks.joinToString(" · ").ifEmpty { null })
            },
        )
    }

    @Composable
    private fun OpenClose(
        state: OverlaysState,
        overlay: Overlay,
        vm: OverlaysViewModel,
    ) {
        if (overlay in state.open) {
            SecondaryAction("Close ${overlay.name.lowercase()}") { vm.onIntent(OverlaysIntent.Close(overlay)) }
        } else {
            PrimaryAction("Show ${overlay.name.lowercase()}") { vm.onIntent(OverlaysIntent.Open(overlay)) }
        }
    }

    @Composable
    private fun OverlayReadouts(
        snapshot: WindowSnapshot?,
        open: Boolean,
    ) {
        if (!open) {
            EmptyState("Hidden.")
            return
        }
        Readout("Surface", snapshot?.surface, tone = if (snapshot?.surface == "Wayland") Tone.Warning else Tone.Neutral)
        Readout("Outer bounds", snapshot?.outerPx?.describe())
        Readout("Monitor", snapshot?.monitor)
    }

    /** How far the outer frame sits from where the corner alignment puts it. */
    private fun cornerDistance(
        snapshot: WindowSnapshot,
        corner: Corner,
    ): String? {
        val outer = snapshot.outerPx ?: return null
        val work = snapshot.workAreaPx ?: return null
        val (x, y) = expectedTopLeft(corner.alignment, IntSize(outer.width, outer.height), work)
        return "Δ ${outer.left - x}, ${outer.top - y} px from ${corner.name}"
    }

    private fun capabilities(state: OverlaysState): List<Capability> {
        val wayland = state.snapshots.values.any { it.surface == "Wayland" }
        return listOf(
            Capability("Per-pixel transparency", Availability.Available),
            Capability(
                "Stacking & placement",
                Availability.of(!wayland) { "an overlay landed on a Wayland surface: forceX11 found no XWayland" },
                "Linux needs X11 semantics; forceX11 gives the overlay its own XWayland surface",
            ),
            Capability(
                "SF Symbol menu icons",
                Availability.of(Platform.Current == Platform.MacOS) {
                    "NSMenu icons are macOS only; the flyout shows labels"
                },
            ),
        )
    }

    companion object {
        val ID = ProbeId("window.overlays")
    }
}
