package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.probes.workspace.common.SatelliteControls
import dev.nucleusframework.lab.probes.workspace.common.SatellitesReadout
import dev.nucleusframework.lab.probes.workspace.common.SavedLayout
import dev.nucleusframework.lab.probes.workspace.common.SessionActions
import dev.nucleusframework.lab.probes.workspace.common.SessionIntent
import dev.nucleusframework.lab.probes.workspace.common.SessionNotice
import dev.nucleusframework.lab.probes.workspace.common.optionsPending
import dev.nucleusframework.lab.probes.workspace.common.sessionCapability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SatellitesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Satellites & docking",
            domain = Domain.Workspace,
            summary = "Do palettes follow their owner document, dock into its DockLayout and float out again intact?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "focus",
                        "Focusing the other document hands the floating satellites over without moving them; they then follow it",
                    ),
                    Check(
                        "pin",
                        "Pinned to A, focusing B leaves the satellites on A; Follow focus hands them over again",
                    ),
                    Check(
                        "dock-drag",
                        "Dragging a satellite toward a document edge lights that zone, and the release docks it exactly there",
                    ),
                    Check(
                        "tear-out",
                        "Dragging a docked panel's header out floats it under the pointer; Tools keeps its tool and brush size",
                    ),
                    Check(
                        "refusal",
                        "A side the Inspector is not declared for never lights up and Dock on it changes nothing; fixed Tools refuses Float",
                    ),
                    Check(
                        "positioner",
                        "Owner against the right screen edge: right edge + None overhangs, Flip puts the Inspector on the left",
                    ),
                    Check(
                        "fills",
                        "With hide-while-owner-fills on, maximizing the owner hides the floating satellites; restoring shows them",
                    ),
                    Check("snapshot", "Save → rearrange → Restore brings back every placement, side, rank and extent"),
                ),
            keywords = listOf("SatelliteWorkspace", "DockLayout", "palette", "inspector", "pinTo", "dock"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SatellitesViewModel>()
        val state by vm.state.collectAsState()
        val open = state.open
        val options = state.data.options
        val live = state.live
        val setOptions = { next: SatellitesOptions -> vm.act(SatellitesIntent.SetOptions(next)) }
        val setLive = { next: SatellitesLive -> vm.onIntent(SessionIntent.SetLive(next)) }
        ProbeLayout(
            capabilities = listOf(sessionCapability(open)),
            controls = {
                SessionActions(state, vm::onIntent, optionsPending = state.optionsPending)
                SubHeading("Declaration (applied on open / reset)")
                SwitchRow("Follow focus", options.followFocus) { setOptions(options.copy(followFocus = it)) }
                ChoiceRow("Inspector sides", InspectorSides.entries, options.inspectorSides, { it.label }) {
                    setOptions(options.copy(inspectorSides = it))
                }
                SwitchRow("Inspector resizable", options.inspectorResizable) {
                    setOptions(options.copy(inspectorResizable = it))
                }
                SwitchRow("Tools floatable", options.toolsFloatable) { setOptions(options.copy(toolsFloatable = it)) }
                SwitchRow("Tools reorderable", options.toolsReorderable) {
                    setOptions(options.copy(toolsReorderable = it))
                }
                SubHeading("Live")
                SwitchRow("Second document", live.secondDocument) { setLive(live.copy(secondDocument = it)) }
                SwitchRow("Show all satellites", live.showAll) { setLive(live.copy(showAll = it)) }
                SwitchRow("Hide while owner fills", live.hideWhileOwnerFills) {
                    setLive(live.copy(hideWhileOwnerFills = it))
                }
                ChoiceRow("Inspector anchor", AnchorPreset.entries, live.anchor, { it.label }) {
                    setLive(live.copy(anchor = it))
                }
                ChoiceRow("Off-screen", AdjustmentPreset.entries, live.adjustment, { it.label }) {
                    setLive(live.copy(adjustment = it))
                }
                SliderRow("Gap", live.gapDp, 0f..64f, format = { "${it.toInt()} dp" }) {
                    setLive(live.copy(gapDp = it))
                }
                SubHeading("Owner")
                Actions {
                    SecondaryAction("Follow focus", enabled = open) { vm.act(SatellitesIntent.Pin(null)) }
                    DocumentId.entries.forEach { doc ->
                        SecondaryAction("Pin to ${doc.title}", enabled = open) { vm.act(SatellitesIntent.Pin(doc)) }
                    }
                    SecondaryAction("Reanchor Inspector", enabled = open) { vm.act(SatellitesIntent.Reanchor) }
                }
                for (id in listOf(SatellitesSessionModel.INSPECTOR_ID, SatellitesSessionModel.TOOLS_ID)) {
                    SatelliteControls(
                        id = id,
                        enabled = open,
                        observation =
                            state.data.observation.satellites
                                .firstOrNull { it.id == id },
                        onToggle = { vm.act(SatellitesIntent.Toggle(id)) },
                        onUndock = { vm.act(SatellitesIntent.Undock(id)) },
                        onDock = { side -> vm.act(SatellitesIntent.Dock(id, side)) },
                    )
                }
                Actions {
                    SecondaryAction("Save layout", enabled = open) { vm.act(SatellitesIntent.SaveLayout) }
                    SecondaryAction("Restore layout", enabled = open && state.data.savedLayoutJson != null) {
                        vm.act(SatellitesIntent.RestoreLayout)
                    }
                }
                SessionNotice(state)
            },
            observed = {
                SatellitesReadout(state.data.observation)
                Readout("changes seen", state.data.changes.toString())
                Readout("last change", state.data.lastChange)
                SavedLayout("Saved layout", state.data.savedLayoutJson)
            },
        )
    }

    companion object {
        val ID = ProbeId("workspace.satellites")
    }
}
