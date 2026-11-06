package dev.nucleusframework.lab.probes.workspace.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.window.tao.TabWorkspace

/**
 * A session composing both archetypes: tab windows, each joining a satellite workspace of
 * its own. [L] is the probe's live knobs.
 */
interface ComposedModel<L> : SessionModel<L> {
    val tabs: TabWorkspace
    val docks: PerWindowSatellites

    fun openDocument()

    /** Every satellite of window [groupId] back to where it was declared. */
    fun resetDock(groupId: String)
}

@Immutable
data class ComposedData(
    val tabs: TabsObservation = TabsObservation(),
    /** Per tab window (group id): its satellite workspace. */
    val docks: Map<String, SatellitesObservation> = emptyMap(),
    val savedTabLayoutJson: String? = null,
    /** Group ids with a saved dock layout. */
    val savedDocks: Set<String> = emptySet(),
    val changes: Int = 0,
    val lastChange: String? = null,
)

typealias ComposedState<L> = SessionState<L, ComposedData>

sealed interface ComposedIntent {
    data object AddTab : ComposedIntent

    data object TearOffSelected : ComposedIntent

    data object MergeAll : ComposedIntent

    data object SaveTabs : ComposedIntent

    data object RestoreTabs : ComposedIntent

    data class SaveDock(
        val group: String,
    ) : ComposedIntent

    data class RestoreDock(
        val group: String,
    ) : ComposedIntent

    data class ResetDock(
        val group: String,
    ) : ComposedIntent
}

sealed interface ComposedEvent {
    data class Observed(
        val tabs: TabsObservation,
        val docks: Map<String, SatellitesObservation>,
    ) : ComposedEvent

    /** A structural change, already worded: `TornOff(...)`, `window g1: Docked(...)`. */
    data class Changed(
        val what: String,
    ) : ComposedEvent

    data class TabsSaved(
        val json: String,
    ) : ComposedEvent {
        override fun toString(): String = "TabsSaved(${json.length} chars)"
    }

    data class DockSaved(
        val group: String,
    ) : ComposedEvent
}

class ComposedReducer<L> : SessionReducer<L, ComposedData, ComposedEvent>() {
    override fun opened(data: ComposedData) = data.copy(savedDocks = emptySet())

    override fun ended(data: ComposedData) =
        data.copy(tabs = TabsObservation(), docks = emptyMap(), savedDocks = emptySet())

    override fun reduceProbe(
        state: ComposedState<L>,
        event: ComposedEvent,
    ): ComposedState<L> {
        val data = state.data
        return when (event) {
            // A dock saved for a window that is gone cannot be restored: drop it.
            is ComposedEvent.Observed ->
                state.copy(
                    data =
                        data.copy(
                            tabs = event.tabs,
                            docks = event.docks,
                            savedDocks =
                                data.savedDocks intersect event.docks.keys,
                        ),
                )
            is ComposedEvent.Changed ->
                state.copy(
                    data = data.copy(changes = data.changes + 1, lastChange = event.what),
                )
            is ComposedEvent.TabsSaved -> state.copy(data = data.copy(savedTabLayoutJson = event.json), notice = null)
            is ComposedEvent.DockSaved ->
                state.copy(
                    data = data.copy(savedDocks = data.savedDocks + event.group),
                    notice = null,
                )
        }
    }
}
