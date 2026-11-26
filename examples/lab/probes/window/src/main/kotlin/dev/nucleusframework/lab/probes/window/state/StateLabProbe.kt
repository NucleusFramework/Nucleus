package dev.nucleusframework.lab.probes.window.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.window.shared.describe
import dev.nucleusframework.lab.probes.window.shared.placementCaveat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class StateLabProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Window state & geometry",
            domain = Domain.Window,
            summary =
                "Do placement, position, size, screen and window flags requested through the v2 state land, and " +
                    "does the OS report them back?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "placement",
                        "Floating / Maximized / Fullscreen each verify as Applied and the window visibly follows",
                    ),
                    Check(
                        "position",
                        "The nine position targets put the window at the matching work-area spot (Applied, or NotApplicable on native Wayland)",
                    ),
                    Check("size", "Each size preset is reported back by the v2 state within 2 dp"),
                    Check(
                        "screen",
                        "With two monitors, moving to the other one verifies and the scale readout follows the monitor",
                    ),
                    Check(
                        "stacking",
                        "Always on top keeps the window above the Lab; always on bottom sinks it below every window",
                    ),
                    Check(
                        "flags",
                        "Resizable off removes the resize edges; focusable off keeps it from taking keyboard focus",
                    ),
                    Check("drag", "Dragging the window by its band ends in one settled Snapshot entry, not a flood"),
                ),
            keywords = listOf("maximize", "fullscreen", "monitor", "screen", "always on top", "v2", "bounds"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<StateLabViewModel>()
        val state by vm.state.collectAsState()
        val open = state.sessionOpen
        val flags = state.flags

        fun setFlags(transform: WindowFlags.() -> WindowFlags) = vm.onIntent(StateLabIntent.SetFlags(flags.transform()))

        ProbeLayout(
            capabilities = capabilities(state),
            controls = {
                Actions {
                    if (open) {
                        SecondaryAction("Close window") { vm.onIntent(StateLabIntent.Close) }
                    } else {
                        PrimaryAction("Open state lab window") { vm.onIntent(StateLabIntent.Open) }
                    }
                    SecondaryAction("Focus it", enabled = open) { vm.onIntent(StateLabIntent.Focus) }
                    SecondaryAction("Re-read", enabled = open) { vm.onIntent(StateLabIntent.Refresh) }
                }
                SubHeading("Placement (v2 requestPlacement / requestMinimized)")
                Actions {
                    PlacementChoice.entries.forEach { choice ->
                        SecondaryAction(
                            choice.name,
                            enabled = open,
                        ) { vm.onIntent(StateLabIntent.RequestPlacement(choice)) }
                    }
                    SecondaryAction("Minimize", enabled = open) { vm.onIntent(StateLabIntent.RequestMinimized(true)) }
                }
                SubHeading("Position on the current screen (AlignedToScreen)")
                PositionGrid(enabled = open) { vm.onIntent(StateLabIntent.RequestPosition(it)) }
                SubHeading("Size (requestSize)")
                Actions {
                    SizePreset.entries.forEach { preset ->
                        SecondaryAction(
                            preset.label,
                            enabled = open,
                        ) { vm.onIntent(StateLabIntent.RequestSize(preset)) }
                    }
                }
                SubHeading("Screen (requestScreen ById)")
                Actions {
                    state.monitors.forEach { monitor ->
                        SecondaryAction("${monitor.name}${if (monitor.primary) " ★" else ""}", enabled = open) {
                            vm.onIntent(StateLabIntent.RequestScreen(monitor.id))
                        }
                    }
                }
                SubHeading("Window parameters (reactive unless noted)")
                TextFieldRow("Title", flags.title) { setFlags { copy(title = it) } }
                SwitchRow("Resizable", flags.resizable) { setFlags { copy(resizable = it) } }
                SwitchRow("Minimizable", flags.minimizable) { setFlags { copy(minimizable = it) } }
                SwitchRow("Maximizable", flags.maximizable) { setFlags { copy(maximizable = it) } }
                SwitchRow("Always on top", flags.alwaysOnTop) {
                    setFlags { copy(alwaysOnTop = it, alwaysOnBottom = if (it) false else alwaysOnBottom) }
                }
                SwitchRow("Always on bottom", flags.alwaysOnBottom) {
                    setFlags { copy(alwaysOnBottom = it, alwaysOnTop = if (it) false else alwaysOnTop) }
                }
                SwitchRow("Focusable", flags.focusable) { setFlags { copy(focusable = it) } }
                SwitchRow("Enabled", flags.enabled) { setFlags { copy(enabled = it) } }
                SwitchRow(
                    "All workspaces",
                    flags.visibleOnAllWorkspaces,
                ) { setFlags { copy(visibleOnAllWorkspaces = it) } }
                SwitchRow("Min size 480×320", flags.minSize) { setFlags { copy(minSize = it) } }
                SwitchRow("Max size 1000×700", flags.maxSize) { setFlags { copy(maxSize = it) } }
                SwitchRow("Hidden from dock (reopens)", flags.hiddenFromDock) { setFlags { copy(hiddenFromDock = it) } }
            },
            observed = {
                val snapshot = state.snapshot
                if (!open) EmptyState("Window closed: open it to read anything back.")
                SubHeading("TaoWindow (physical px)")
                Readout("Surface", snapshot?.surface)
                Readout(
                    "canPlaceOnScreen",
                    snapshot?.canPlaceOnScreen?.toString(),
                    tone = if (snapshot?.canPlaceOnScreen == false) Tone.Warning else Tone.Neutral,
                )
                placementCaveat(snapshot)?.let { Readout("Caveat", it, tone = Tone.Warning) }
                Readout("Outer bounds", snapshot?.outerPx?.describe())
                Readout("Scale", snapshot?.scale?.toString())
                Readout("Monitor", snapshot?.monitor)
                Readout("Work area", snapshot?.workAreaPx?.describe())
                Readout(
                    "State",
                    snapshot?.let {
                        listOfNotNull(
                            "maximized".takeIf { _ -> it.maximized },
                            "fullscreen".takeIf { _ -> it.fullscreen },
                            "minimized".takeIf { _ -> it.minimized },
                            "tiled".takeIf { _ -> it.tiled },
                            if (it.focused) "focused" else "unfocused",
                            if (it.resizable) "resizable" else "fixed size",
                        ).joinToString(" · ")
                    },
                )
                SubHeading("v2 WindowState (dp)")
                val v2 = state.v2
                Readout("Initialized", v2?.initialized?.toString())
                Readout("screenId", v2?.screenId)
                Readout("placement", v2?.placement)
                Readout("isMinimized", v2?.minimized?.toString())
                Readout("bounds", v2?.boundsDp)
                SubHeading("Native signals")
                val c = state.counters
                Readout(
                    "Counts",
                    "moves ${c.moves} · resizes ${c.resizes} · focus ${c.focusChanges} · minimize ${c.minimizeChanges}",
                )
                Readout("Last", state.lastSignal)
                SubHeading("Request checks (newest first)")
                EventLog(
                    state.verifications.map { it.toLogEntry() },
                    newestFirst = false,
                    empty = "No request yet.",
                )
                SubHeading("Monitors (TaoMonitors)")
                state.monitors.forEach { m ->
                    Readout(
                        m.name + if (m.primary) " (primary)" else "",
                        "${m.boundsPx.describe()} @${m.scale}x · work ${m.workAreaPx.describe()}",
                    )
                }
            },
        )
    }

    private fun capabilities(state: StateLabState): List<Capability> {
        val wayland = state.snapshot?.canPlaceOnScreen == false
        return listOf(
            Capability(
                "Programmatic placement",
                when {
                    state.snapshot == null -> Availability.Unknown
                    wayland -> Availability.Unavailable("native Wayland: xdg-shell gives clients no position")
                    else -> Availability.Available
                },
            ),
            Capability(
                "Second monitor",
                Availability.of(state.monitors.size > 1) { "${state.monitors.size} monitor attached" },
            ),
            Capability(
                "Stacking (top / bottom)",
                Availability.of(!wayland) { "native Wayland has no client-side stacking" },
            ),
            Capability(
                "All workspaces",
                when {
                    Platform.Current == Platform.Windows ->
                        Availability.Unavailable(
                            "no-op on Windows: a hidden-from-dock window already shows on every desktop",
                        )
                    wayland -> Availability.Unavailable("native Wayland has no workspace protocol")
                    else -> Availability.Available
                },
            ),
        )
    }

    companion object {
        val ID = ProbeId("window.state")
    }
}

private fun Verification.toLogEntry(): LogEntry =
    LogEntry(
        text = "$request: $outcome — $detail",
        epochMillis = epochMillis,
        tone =
            when (outcome) {
                Outcome.Applied -> Tone.Ok
                Outcome.Mismatch -> Tone.Error
                Outcome.NotApplicable -> Tone.Muted
            },
    )

@Composable
private fun PositionGrid(
    enabled: Boolean,
    onPick: (PositionTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        PositionTarget.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
                row.forEach { target ->
                    SecondaryAction(target.label, enabled = enabled, modifier = Modifier.width(56.dp)) {
                        onPick(target)
                    }
                }
            }
        }
    }
}
