package dev.nucleusframework.lab.probes.workspace.common

import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.TabLayoutSnapshot

/**
 * Shared by the composed probes (tab satellites, reader dock): tab moves and per-window
 * satellite changes reach the timeline the same way; only the session differs.
 */
abstract class ComposedViewModel<L : Any, M : ComposedModel<L>>(
    initialLive: L,
    host: SessionHost,
    timeline: Timeline,
    source: ProbeId,
) : SessionModelViewModel<L, ComposedData, ComposedIntent, ComposedEvent, M>(
        SessionState(live = initialLive, data = ComposedData()),
        ComposedReducer(),
        host,
        timeline,
        source,
    ) {
    private var savedTabs: TabLayoutSnapshot? = null
    private val savedDocks = mutableMapOf<String, SatelliteLayoutSnapshot>()

    override suspend fun handleProbe(intent: ComposedIntent) {
        when (intent) {
            ComposedIntent.AddTab -> withModel { openDocument() }
            ComposedIntent.TearOffSelected ->
                withModel {
                    val tab = (tabs.activeGroup ?: tabs.groups.firstOrNull())?.selectedId
                    if (tab == null || !tabs.tearOffFromItsWindow(tab)) {
                        refuse("no selected tab with a window frame to tear from")
                    }
                }
            ComposedIntent.MergeAll -> withModel { tabs.mergeAll() }
            ComposedIntent.SaveTabs ->
                withModel {
                    val snapshot = tabs.snapshot()
                    savedTabs = snapshot
                    dispatchProbe(ComposedEvent.TabsSaved(snapshot.toJson()))
                }
            ComposedIntent.RestoreTabs -> withModel { savedTabs?.let(tabs::restore) ?: refuse("no tab layout saved") }
            is ComposedIntent.SaveDock ->
                withModel {
                    savedDocks[intent.group] = docks.of(intent.group).snapshot()
                    dispatchProbe(ComposedEvent.DockSaved(intent.group))
                }
            is ComposedIntent.RestoreDock ->
                withModel {
                    savedDocks[intent.group]?.let { docks.of(intent.group).restore(it) }
                        ?: refuse("no dock saved for ${intent.group}")
                }
            is ComposedIntent.ResetDock -> withModel { resetDock(intent.group) }
        }
    }

    override fun createModel(state: ComposedState<L>): M = newModel(state.live)

    protected abstract fun newModel(live: L): M

    override fun onOpened(model: M) {
        // Dock snapshots name the windows of one session: a new session has none of them.
        savedDocks.clear()
        var previousTabs = TabsObservation()
        var previousDocks = emptyMap<String, SatellitesObservation>()
        mirror({ model.tabs.observe() to observeDocks(model) }) { (tabs, docks) ->
            diffTabs(previousTabs, tabs).forEach { dispatchProbe(ComposedEvent.Changed(it.toString())) }
            for ((group, dock) in docks) {
                val before = previousDocks[group] ?: continue
                diffSatellites(before, dock).forEach {
                    dispatchProbe(ComposedEvent.Changed("${windowLabel(group)}: $it"))
                }
            }
            previousTabs = tabs
            previousDocks = docks
            reduceProbeSilently(ComposedEvent.Observed(tabs, docks))
        }
    }

    private fun observeDocks(model: M): Map<String, SatellitesObservation> =
        model.docks.all.mapValues { (_, workspace) ->
            workspace.observe { window -> model.tabs.groupOf(window)?.let { windowLabel(it.id) } }
        }
}
