package dev.nucleusframework.lab.probes.workspace.tabs

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
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.workspace.common.SavedLayout
import dev.nucleusframework.lab.probes.workspace.common.SessionActions
import dev.nucleusframework.lab.probes.workspace.common.SessionIntent
import dev.nucleusframework.lab.probes.workspace.common.SessionNotice
import dev.nucleusframework.lab.probes.workspace.common.TabsObservation
import dev.nucleusframework.lab.probes.workspace.common.optionsPending
import dev.nucleusframework.lab.probes.workspace.common.sessionCapability
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class TabsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Tabs",
            domain = Domain.Workspace,
            summary =
                "Do Chrome-like tabs tear off, merge, reorder and carry their state between windows, " +
                    "under Jewel's TabStrip or Nucleus' stock one?",
            modules = listOf("decorated-window-tao", "nucleus-application", "decorated-window-jewel"),
            checks = TabsChecks,
            keywords =
                listOf(
                    "TabWindows",
                    "TabWorkspace",
                    "TabStrip",
                    "tear off",
                    "merge",
                    "snapshot",
                    "chrome",
                    "jewel",
                    "intellij",
                    "TabData.Editor",
                    "tabs",
                ),
        )

    @Composable
    override fun Content() {
        TabsProbeContent(metroViewModel<TabsViewModel>())
    }

    companion object {
        val ID = ProbeId("workspace.tabs")
    }
}

private val TabsChecks =
    listOf(
        Check(
            "tear-off",
            "Dragging a tab out of a multi-tab strip onto the desktop opens a window holding it (timeline: TornOff)",
        ),
        Check(
            "merge",
            "Dragging the only tab of a window carries the window; dropped on a strip it merges and the empty window closes (Merged)",
        ),
        Check(
            "reorder",
            "Reordering in a strip (stock strip): neighbours slide aside, the released tab glides into its slot — no jump, no flicker",
        ),
        Check(
            "state",
            "After a move the draft, the saveable counter and the scroll position come back; the plain counter is 0",
        ),
        Check(
            "snapshot",
            "Save layout → Merge all → Restore layout brings back every window, tab order, selection and position",
        ),
        Check(
            "rtl",
            "Right to left (stock strip): a drag over a strip opens the slot on the pointer's side and the index never flips back and forth",
        ),
        Check(
            "grip",
            "A press anywhere on a Jewel tab — label or padding — selects it and starts the drag (Jewel)",
        ),
        Check(
            "drop-slot",
            "A tab dragged from another window opens a Jewel-tab-shaped slot holding the ghost card (Jewel)",
        ),
        Check(
            "hover",
            "Resting on a tab shows the Jewel-coloured card with the draft's first line and a thumbnail (Jewel)",
        ),
        Check("last-window", "Closing the last workspace window ends the session only — the Lab stays up"),
    )

@Composable
private fun TabsProbeContent(vm: TabsViewModel) {
    val state by vm.state.collectAsState()
    val open = state.open
    val options = state.data.options
    val live = state.live
    val obs = state.data.observation
    val chrome =
        when (state.data.appliedOptions?.strip ?: options.strip) {
            TabStripChrome.Jewel -> "Jewel TabStrip, editorTabStyle"
            TabStripChrome.Stock -> "stock TabStrip, Lab title bar style"
        }
    ProbeLayout(
        capabilities = listOf(sessionCapability(open, detail = chrome), placementCapability(obs)),
        controls = {
            SessionActions(state, vm::onIntent, optionsPending = state.optionsPending)
            SubHeading("Declaration (applied on open / reset)")
            ChoiceRow("Strip", TabStripChrome.entries, options.strip, name = { it.label }) {
                vm.act(TabsIntent.SetOptions(options.copy(strip = it)))
            }
            ChoiceRow("Initial tabs", listOf(1, 3, 5, 8), options.initialTabs) {
                vm.act(TabsIntent.SetOptions(options.copy(initialTabs = it)))
            }
            SwitchRow("Capture thumbnails", options.captureThumbnails) {
                vm.act(TabsIntent.SetOptions(options.copy(captureThumbnails = it)))
            }
            SubHeading("Strip (live)")
            SwitchRow("Hover preview", live.hoverPreview) {
                vm.onIntent(SessionIntent.SetLive(live.copy(hoverPreview = it)))
            }
            SwitchRow("Animate reorder (stock strip)", live.animateReorder) {
                vm.onIntent(SessionIntent.SetLive(live.copy(animateReorder = it)))
            }
            SwitchRow("Right to left", live.rightToLeft) {
                vm.onIntent(SessionIntent.SetLive(live.copy(rightToLeft = it)))
            }
            SubHeading("Drive the workspace")
            Actions {
                SecondaryAction("+1 tab", enabled = open) { vm.act(TabsIntent.AddTabs(1)) }
                SecondaryAction("+5 tabs", enabled = open) { vm.act(TabsIntent.AddTabs(5)) }
                SecondaryAction("Tear off selected", enabled = open) { vm.act(TabsIntent.TearOffSelected) }
                SecondaryAction("Spread into 3 windows", enabled = open) { vm.act(TabsIntent.SpreadInto(3)) }
                SecondaryAction("Merge all", enabled = open) { vm.act(TabsIntent.MergeAll) }
            }
            Actions {
                SecondaryAction("Save layout", enabled = open) { vm.act(TabsIntent.SaveLayout) }
                SecondaryAction("Restore layout", enabled = open && state.data.savedLayoutJson != null) {
                    vm.act(TabsIntent.RestoreLayout)
                }
            }
            SessionNotice(state)
        },
        observed = {
            Readout("windows", obs.windows.size.toString())
            Readout("tabs", obs.tabCount.toString())
            Readout("thumbnails kept", obs.hasThumbnails.toString())
            Readout("drag kind", obs.dragKind, tone = if (obs.dragKind != null) Tone.Warning else Tone.Neutral)
            Readout("dragged tab", obs.draggedTab)
            Readout("drop preview", obs.dropPreview)
            Readout("changes seen", state.data.changes.toString())
            Readout("last change", state.data.lastChange)
            WindowsReadout(obs)
            SavedLayout("Saved layout", state.data.savedLayoutJson)
        },
    )
}

/** One block per window: placement, size, and its strip with the selected tab marked. */
@Composable
internal fun WindowsReadout(obs: TabsObservation) {
    obs.windows.forEach { window ->
        SubHeading("window ${window.groupId}")
        Readout("position / size", "${window.position ?: "platform-placed"} · ${window.size} dp")
        Readout(
            "canPlaceOnScreen",
            window.canPlaceOnScreen?.toString() ?: "no native window yet",
            tone = if (window.canPlaceOnScreen == false) Tone.Warning else Tone.Neutral,
        )
        Readout(
            "strip",
            window.tabIds.joinToString("  ") { id ->
                (if (id == window.selectedId) "●" else "○") +
                    obs.title(id)
            },
        )
    }
}

/** On native Wayland a drag is a platform DnD session instead of a window following the pointer. */
internal fun placementCapability(obs: TabsObservation): Capability {
    val known = obs.windows.mapNotNull { it.canPlaceOnScreen }
    return Capability(
        "App-placed windows",
        when {
            known.isEmpty() -> Availability.Unknown
            known.all { it } -> Availability.Available
            else -> Availability.Unavailable("compositor-placed (native Wayland): drags go through Transfer")
        },
    )
}
