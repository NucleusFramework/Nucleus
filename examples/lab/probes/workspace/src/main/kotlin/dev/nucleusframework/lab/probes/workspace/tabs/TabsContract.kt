package dev.nucleusframework.lab.probes.workspace.tabs

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.probes.workspace.common.Declared
import dev.nucleusframework.lab.probes.workspace.common.SessionReducer
import dev.nucleusframework.lab.probes.workspace.common.SessionState
import dev.nucleusframework.lab.probes.workspace.common.TabChange
import dev.nucleusframework.lab.probes.workspace.common.TabsObservation

/** Which chrome the session's strip wears; both publish the same drop geometry. */
enum class TabStripChrome(
    val label: String,
) {
    /** IntelliJ's own `TabStrip` / `TabData.Editor` through Jewel, the Lab's design system. */
    Jewel("Jewel"),

    /** Nucleus' stock `TabStrip` from decorated-window-tao, coloured by the Lab's title bar style. */
    Stock("Nucleus stock TabStrip"),
}

/** Declaration-time choices: applied when the session is (re)opened. */
@Immutable
data class TabsOptions(
    val strip: TabStripChrome = TabStripChrome.Jewel,
    val initialTabs: Int = 3,
    /** `TabWorkspace(captureThumbnails)`: a picture of each tab for the hover card. */
    val captureThumbnails: Boolean = true,
)

/** Strip knobs applied to the open session as they change. */
@Immutable
data class TabsLive(
    /** The hover card under a resting pointer. */
    val hoverPreview: Boolean = true,
    /** Stock `TabStrip(reorderAnimation)`; off = tabs jump to their slot. Jewel's strip has none. */
    val animateReorder: Boolean = true,
    /** The strip laid out right to left: drop index and motion must mirror. */
    val rightToLeft: Boolean = false,
)

@Immutable
data class TabsData(
    override val options: TabsOptions = TabsOptions(),
    override val appliedOptions: TabsOptions? = null,
    val observation: TabsObservation = TabsObservation(),
    val savedLayoutJson: String? = null,
    val changes: Int = 0,
    val lastChange: String? = null,
) : Declared<TabsOptions>

typealias TabsState = SessionState<TabsLive, TabsData>

sealed interface TabsIntent {
    data class SetOptions(
        val options: TabsOptions,
    ) : TabsIntent

    data class AddTabs(
        val count: Int,
    ) : TabsIntent

    data class SpreadInto(
        val windows: Int,
    ) : TabsIntent

    data object MergeAll : TabsIntent

    data object TearOffSelected : TabsIntent

    data object SaveLayout : TabsIntent

    data object RestoreLayout : TabsIntent
}

sealed interface TabsEvent {
    data class OptionsChanged(
        val options: TabsOptions,
    ) : TabsEvent

    data class Observed(
        val observation: TabsObservation,
    ) : TabsEvent

    data class Changed(
        val change: TabChange,
    ) : TabsEvent

    data class LayoutSaved(
        val json: String,
    ) : TabsEvent {
        override fun toString(): String = "LayoutSaved(${json.length} chars)"
    }

    data class LayoutRestored(
        val windows: Int,
    ) : TabsEvent
}

object TabsReducer : SessionReducer<TabsLive, TabsData, TabsEvent>() {
    override fun opened(data: TabsData) = data.copy(appliedOptions = data.options, observation = TabsObservation())

    override fun ended(data: TabsData) = data.copy(appliedOptions = null, observation = TabsObservation())

    override fun reduceProbe(
        state: TabsState,
        event: TabsEvent,
    ): TabsState {
        val data = state.data
        return when (event) {
            is TabsEvent.OptionsChanged -> state.copy(data = data.copy(options = event.options))
            is TabsEvent.Observed -> state.copy(data = data.copy(observation = event.observation))
            is TabsEvent.Changed ->
                state.copy(data = data.copy(changes = data.changes + 1, lastChange = event.change.toString()))
            is TabsEvent.LayoutSaved -> state.copy(data = data.copy(savedLayoutJson = event.json), notice = null)
            is TabsEvent.LayoutRestored -> state.copy(notice = "restored into ${event.windows} window(s)")
        }
    }
}
