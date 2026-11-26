package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.probes.workspace.tabs.WindowsReadout
import dev.nucleusframework.lab.probes.workspace.tabs.placementCapability

/**
 * The screen both composed probes share: session, tab moves, per-window dock layouts;
 * [liveControls] holds the probe's own knobs.
 */
@Composable
fun <L : Any> ComposedProbeLayout(
    vm: ComposedViewModel<L, *>,
    state: ComposedState<L>,
    liveControls: @Composable ColumnScope.() -> Unit,
) {
    val open = state.open
    val data = state.data
    ProbeLayout(
        capabilities = listOf(sessionCapability(open), placementCapability(data.tabs)),
        controls = {
            SessionActions(state, vm::onIntent)
            liveControls()
            SubHeading("Tabs")
            Actions {
                SecondaryAction("New tab", enabled = open) { vm.act(ComposedIntent.AddTab) }
                SecondaryAction("Tear off selected", enabled = open) { vm.act(ComposedIntent.TearOffSelected) }
                SecondaryAction("Merge all", enabled = open) { vm.act(ComposedIntent.MergeAll) }
                SecondaryAction("Save tab layout", enabled = open) { vm.act(ComposedIntent.SaveTabs) }
                SecondaryAction("Restore tab layout", enabled = open && data.savedTabLayoutJson != null) {
                    vm.act(ComposedIntent.RestoreTabs)
                }
            }
            data.tabs.windows.forEach { window ->
                val group = window.groupId
                SubHeading("Dock of ${windowLabel(group)}")
                Actions {
                    SecondaryAction("Save dock", enabled = open) { vm.act(ComposedIntent.SaveDock(group)) }
                    SecondaryAction("Restore dock", enabled = open && group in data.savedDocks) {
                        vm.act(ComposedIntent.RestoreDock(group))
                    }
                    SecondaryAction("Reset dock", enabled = open) { vm.act(ComposedIntent.ResetDock(group)) }
                }
            }
            SessionNotice(state)
        },
        observed = {
            Readout(
                "tab windows",
                data.tabs.windows.size
                    .toString(),
            )
            Readout("drag kind (tabs)", data.tabs.dragKind)
            Readout("changes seen", data.changes.toString())
            Readout("last change", data.lastChange)
            WindowsReadout(data.tabs)
            data.docks.entries.sortedBy { it.key }.forEach { (group, dock) ->
                SubHeading("Satellites of ${windowLabel(group)}")
                SatellitesReadout(dock)
            }
            SavedLayout("Saved tab layout", data.savedTabLayoutJson)
        },
    )
}
