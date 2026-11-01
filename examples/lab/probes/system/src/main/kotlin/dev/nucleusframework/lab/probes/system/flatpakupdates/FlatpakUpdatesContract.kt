package dev.nucleusframework.lab.probes.system.flatpakupdates

import androidx.compose.runtime.Immutable
import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.window.tao.TaoWindow

@Immutable
data class FlatpakUpdatesState(
    val supported: Availability = Availability.Unknown,
    val appId: String? = null,
    val watching: Boolean = false,
    val status: FlatpakUpdateStatus? = null,
    val installing: Boolean = false,
    val progress: FlatpakUpdateProgress? = null,
    val calls: List<CallRecord> = emptyList(),
)

sealed interface FlatpakUpdatesIntent {
    data object ToggleWatch : FlatpakUpdatesIntent

    /** [window] parents the portal's "Update …?" dialog; `null` leaves it unparented. */
    data class Install(
        val window: TaoWindow?,
    ) : FlatpakUpdatesIntent

    data object Restart : FlatpakUpdatesIntent

    data class OpenStorePage(
        val window: TaoWindow?,
    ) : FlatpakUpdatesIntent
}

sealed interface FlatpakUpdatesEvent {
    data class Ready(
        val supported: Availability,
        val appId: String?,
    ) : FlatpakUpdatesEvent

    data class Watching(
        val watching: Boolean,
    ) : FlatpakUpdatesEvent

    data class Reported(
        val status: FlatpakUpdateStatus,
    ) : FlatpakUpdatesEvent

    data object InstallStarted : FlatpakUpdatesEvent

    data class Progressed(
        val progress: FlatpakUpdateProgress,
    ) : FlatpakUpdatesEvent

    data object InstallEnded : FlatpakUpdatesEvent

    data class Called(
        val call: String,
        val outcome: CallOutcome,
        val returned: String? = null,
    ) : FlatpakUpdatesEvent
}

/** A commit as the probe shows it: its first 12 characters, like `git log --oneline`. */
fun shortCommit(commit: String): String = commit.take(12).ifEmpty { "?" }

/** One line for the probe: `available: a1b2c3d4e5f6 → 0f9e8d7c6b5a`. */
fun FlatpakUpdateStatus.describe(): String =
    when (this) {
        is FlatpakUpdateStatus.Available ->
            "available: ${shortCommit(installedCommit)} → ${shortCommit(remoteCommit)}"
        is FlatpakUpdateStatus.Installed ->
            "installed, restart to run it: ${shortCommit(runningCommit)} → ${shortCommit(installedCommit)}"
        is FlatpakUpdateStatus.UpToDate -> "up to date: ${shortCommit(runningCommit)}"
    }

/** One line for the probe: `installing 42 % (operation 1 of 2)`. */
fun FlatpakUpdateProgress.describe(): String =
    buildString {
        append(state.name.lowercase()).append(' ').append((progress * 100).toInt()).append(" %")
        if (operationCount > 0) {
            append(" (operation ${operation + 1} of $operationCount)")
        }
    }

object FlatpakUpdatesReducer : Reducer<FlatpakUpdatesState, FlatpakUpdatesEvent> {
    override fun reduce(
        state: FlatpakUpdatesState,
        event: FlatpakUpdatesEvent,
    ): FlatpakUpdatesState =
        when (event) {
            is FlatpakUpdatesEvent.Ready -> state.copy(supported = event.supported, appId = event.appId)
            is FlatpakUpdatesEvent.Watching -> state.copy(watching = event.watching)
            is FlatpakUpdatesEvent.Reported -> state.copy(status = event.status)
            FlatpakUpdatesEvent.InstallStarted -> state.copy(installing = true, progress = null)
            is FlatpakUpdatesEvent.Progressed -> state.copy(progress = event.progress)
            FlatpakUpdatesEvent.InstallEnded -> state.copy(installing = false)
            is FlatpakUpdatesEvent.Called ->
                state.copy(calls = state.calls.plusCall(event.call, event.outcome, event.returned))
        }
}
