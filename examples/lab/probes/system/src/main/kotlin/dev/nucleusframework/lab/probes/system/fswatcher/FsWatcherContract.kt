package dev.nucleusframework.lab.probes.system.fswatcher

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.replaceWhere
import dev.nucleusframework.lab.core.time.NANOS_PER_MILLI

/** How `fs-watcher` hands events over: debounced and paired, or raw backend events. */
enum class EventDelivery { Debounced, Raw }

enum class Backend { Auto, NativeOnly, Polling }

@Immutable
data class WatchSettings(
    val recursive: Boolean = true,
    val delivery: EventDelivery = EventDelivery.Debounced,
    val backend: Backend = Backend.Auto,
)

/** Real filesystem operations on the scratch root, each one a timed stimulus. */
enum class FsAction(
    val label: String,
) {
    CreateFile("Create probe.txt"),
    ModifyFile("Append to probe.txt"),
    RenameFile("Rename probe.txt ⇄ probe-renamed.txt"),
    DeleteFile("Delete probe file"),
    CreateNested("Create nested/deep/leaf.txt"),
    RenameDir("Rename nested ⇄ nested-moved"),
    DeleteTree("Delete nested tree"),
    Burst("Burst: 50 files in burst/"),
    Clean("Empty the scratch dir"),
}

/** One backend event as it arrived: what, where (relative to the root), and on which thread. */
@Immutable
data class FsObservation(
    val kind: String,
    val paths: String,
    val isDirectory: Boolean?,
    val needsRescan: Boolean,
    val arrivedNanos: Long,
    val epochMillis: Long,
)

@Immutable
data class ObservedEvent(
    val observation: FsObservation,
    val thread: String,
    val onUiThread: Boolean?,
    /** Since the action it is attributed to; `null` when no action preceded it (an outside change). */
    val latencyMillis: Long?,
    val actionId: Int?,
)

@Immutable
data class ActionRecord(
    val id: Int,
    val action: FsAction,
    val epochMillis: Long,
    val startedNanos: Long,
    val error: String? = null,
    val events: Int = 0,
    val firstLatencyMillis: Long? = null,
)

@Immutable
data class FsWatcherState(
    val available: Availability = Availability.Unknown,
    val root: String = "",
    val settings: WatchSettings = WatchSettings(),
    val watching: Boolean = false,
    val watchError: String? = null,
    val events: List<ObservedEvent> = emptyList(),
    val actions: List<ActionRecord> = emptyList(),
    val errors: List<String> = emptyList(),
)

sealed interface FsWatcherIntent {
    data class ChangeSettings(
        val settings: WatchSettings,
    ) : FsWatcherIntent

    data class SetWatching(
        val watching: Boolean,
    ) : FsWatcherIntent

    data class Perform(
        val action: FsAction,
    ) : FsWatcherIntent

    data object Clear : FsWatcherIntent
}

sealed interface FsWatcherEvent {
    data class Ready(
        val root: String,
    ) : FsWatcherEvent

    data class SettingsChanged(
        val settings: WatchSettings,
        val available: Availability,
    ) : FsWatcherEvent

    data object WatchStarted : FsWatcherEvent

    data object WatchStopped : FsWatcherEvent

    data class WatchFailed(
        val message: String,
    ) : FsWatcherEvent

    data class ActionStarted(
        val record: ActionRecord,
    ) : FsWatcherEvent

    data class ActionFailed(
        val id: Int,
        val message: String,
    ) : FsWatcherEvent

    data class Observed(
        val observation: FsObservation,
        val thread: String,
        val onUiThread: Boolean?,
    ) : FsWatcherEvent {
        override fun toString(): String = "${observation.kind} ${observation.paths}"
    }

    data class WatcherError(
        val message: String,
    ) : FsWatcherEvent

    data object Cleared : FsWatcherEvent
}

object FsWatcherReducer : Reducer<FsWatcherState, FsWatcherEvent> {
    /** Events kept: a burst of 50 files yields several per file, all of which must stay inspectable. */
    const val EVENTS = 200
    const val ACTIONS = 30

    override fun reduce(
        state: FsWatcherState,
        event: FsWatcherEvent,
    ): FsWatcherState =
        when (event) {
            is FsWatcherEvent.Ready -> state.copy(root = event.root)
            is FsWatcherEvent.SettingsChanged -> state.copy(settings = event.settings, available = event.available)
            FsWatcherEvent.WatchStarted -> state.copy(watching = true, watchError = null)
            FsWatcherEvent.WatchStopped -> state.copy(watching = false)
            is FsWatcherEvent.WatchFailed -> state.copy(watching = false, watchError = event.message)
            is FsWatcherEvent.ActionStarted -> state.copy(actions = state.actions.append(event.record, ACTIONS))
            is FsWatcherEvent.ActionFailed ->
                state.copy(actions = state.actions.replaceWhere({ it.id }, event.id) { it.copy(error = event.message) })
            is FsWatcherEvent.Observed -> observe(state, event.observation, event.thread, event.onUiThread)
            is FsWatcherEvent.WatcherError -> state.copy(errors = state.errors.append(event.message, ACTIONS))
            FsWatcherEvent.Cleared -> state.copy(events = emptyList(), actions = emptyList(), errors = emptyList())
        }

    /**
     * Attributes [observation] to the latest action started before it arrived: its latency is the
     * time since that action, and the action counts it. Events with no prior action are outside
     * changes (another process, or the tail of a previous run).
     */
    fun observe(
        state: FsWatcherState,
        observation: FsObservation,
        thread: String,
        onUiThread: Boolean?,
    ): FsWatcherState {
        val action = state.actions.lastOrNull { it.startedNanos <= observation.arrivedNanos }
        val latency = action?.let { (observation.arrivedNanos - it.startedNanos) / NANOS_PER_MILLI }
        val observed = ObservedEvent(observation, thread, onUiThread, latency, action?.id)
        val actions =
            if (action == null) {
                state.actions
            } else {
                state.actions.replaceWhere({ it.id }, action.id) {
                    it.copy(events = it.events + 1, firstLatencyMillis = it.firstLatencyMillis ?: latency)
                }
            }
        return state.copy(events = state.events.append(observed, EVENTS), actions = actions)
    }
}
