package dev.nucleusframework.lab.probes.system.storeupdates

import androidx.compose.runtime.Immutable
import dev.nucleusframework.iap.StoreUpdateCheck
import dev.nucleusframework.iap.StoreUpdateProgress
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.plusCall

@Immutable
data class StoreUpdatesState(
    val supported: Availability = Availability.Unknown,
    val check: StoreUpdateCheck? = null,
    val installing: Boolean = false,
    val progress: List<StoreUpdateProgress> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
)

sealed interface StoreUpdatesIntent {
    data object Check : StoreUpdatesIntent

    /** [hwnd] parents the Store's dialog; `0` lets the module pick the Lab's foreground window. */
    data class Install(
        val silent: Boolean,
        val hwnd: Long,
    ) : StoreUpdatesIntent

    data object OpenStorePage : StoreUpdatesIntent
}

sealed interface StoreUpdatesEvent {
    data class Ready(
        val supported: Availability,
    ) : StoreUpdatesEvent

    data class Checked(
        val check: StoreUpdateCheck,
    ) : StoreUpdatesEvent

    data object InstallStarted : StoreUpdatesEvent

    data class Progressed(
        val progress: StoreUpdateProgress,
    ) : StoreUpdatesEvent

    data object InstallEnded : StoreUpdatesEvent

    data class Called(
        val call: String,
        val outcome: CallOutcome,
        val returned: String? = null,
    ) : StoreUpdatesEvent
}

/** One line for the probe: `downloading 42 % (1260 of 3000 bytes)`. */
fun StoreUpdateProgress.describe(): String =
    buildString {
        append(state.name.lowercase()).append(' ').append((progress * 100).toInt()).append(" %")
        if (bytesTotal > 0) {
            append(" (")
                .append(bytesDownloaded)
                .append(" of ")
                .append(bytesTotal)
                .append(" bytes)")
        }
    }

object StoreUpdatesReducer : Reducer<StoreUpdatesState, StoreUpdatesEvent> {
    override fun reduce(
        state: StoreUpdatesState,
        event: StoreUpdatesEvent,
    ): StoreUpdatesState =
        when (event) {
            is StoreUpdatesEvent.Ready -> state.copy(supported = event.supported)
            is StoreUpdatesEvent.Checked -> state.copy(check = event.check)
            StoreUpdatesEvent.InstallStarted -> state.copy(installing = true, progress = emptyList())
            is StoreUpdatesEvent.Progressed -> state.copy(progress = state.progress.append(event.progress))
            StoreUpdatesEvent.InstallEnded -> state.copy(installing = false)
            is StoreUpdatesEvent.Called ->
                state.copy(calls = state.calls.plusCall(event.call, event.outcome, event.returned))
        }
}
