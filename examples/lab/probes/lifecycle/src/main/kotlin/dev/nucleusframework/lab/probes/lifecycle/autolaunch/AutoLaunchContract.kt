package dev.nucleusframework.lab.probes.lifecycle.autolaunch

import androidx.compose.runtime.Immutable
import dev.nucleusframework.autolaunch.AutoLaunchResult
import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append

@Immutable
data class AutoLaunchAttempt(
    val epochMillis: Long,
    val action: String,
    val result: AutoLaunchResult?,
    val stateAfter: AutoLaunchState?,
    val error: String? = null,
)

@Immutable
data class AutoLaunchProbeState(
    val state: AutoLaunchState? = null,
    val backend: String? = null,
    val diagnostic: String = "",
    val startedAtLogin: Boolean? = null,
    val autostartArgument: String? = null,
    val executableType: String = "",
    /** Changes seen by polling, including the ones made in System Settings / Task Manager. */
    val stateChanges: List<Pair<Long, AutoLaunchState>> = emptyList(),
    val attempts: List<AutoLaunchAttempt> = emptyList(),
    val settingsOpened: Boolean? = null,
)

sealed interface AutoLaunchIntent {
    data object Enable : AutoLaunchIntent

    data object Disable : AutoLaunchIntent

    data object OpenSettings : AutoLaunchIntent

    data object Refresh : AutoLaunchIntent
}

sealed interface AutoLaunchEvent {
    data class Read(
        val state: AutoLaunchState,
        val backend: String,
        val diagnostic: String,
        val startedAtLogin: Boolean,
        val at: Long,
    ) : AutoLaunchEvent {
        override fun toString(): String = "Read(state=$state, backend=$backend, startedAtLogin=$startedAtLogin)"
    }

    data class Attempted(
        val attempt: AutoLaunchAttempt,
    ) : AutoLaunchEvent

    data class SettingsOpened(
        val opened: Boolean,
    ) : AutoLaunchEvent
}

class AutoLaunchReducer(
    private val autostartArgument: String?,
    private val executableType: String,
) : Reducer<AutoLaunchProbeState, AutoLaunchEvent> {
    override fun reduce(
        state: AutoLaunchProbeState,
        event: AutoLaunchEvent,
    ): AutoLaunchProbeState =
        when (event) {
            is AutoLaunchEvent.Read -> {
                val changed = state.state != event.state
                state.copy(
                    state = event.state,
                    backend = event.backend,
                    diagnostic = event.diagnostic,
                    startedAtLogin = event.startedAtLogin,
                    autostartArgument = autostartArgument,
                    executableType = executableType,
                    stateChanges =
                        if (changed) {
                            state.stateChanges.append(
                                event.at to event.state,
                            )
                        } else {
                            state.stateChanges
                        },
                )
            }
            is AutoLaunchEvent.Attempted ->
                state.copy(
                    attempts = state.attempts.append(event.attempt),
                    state = event.attempt.stateAfter ?: state.state,
                )
            is AutoLaunchEvent.SettingsOpened -> state.copy(settingsOpened = event.opened)
        }
}
