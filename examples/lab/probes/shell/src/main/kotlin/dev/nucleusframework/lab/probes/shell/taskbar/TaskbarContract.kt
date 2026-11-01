package dev.nucleusframework.lab.probes.shell.taskbar

import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.taskbarprogress.TaskbarProgress

@Immutable
data class TaskbarState(
    val availability: Availability = Availability.Unknown,
    val surface: String = "",
    val attached: Boolean = false,
    /** What the bar should show after the last successful call. */
    val shownState: TaskbarProgress.State = TaskbarProgress.State.NO_PROGRESS,
    val shownValue: Double = 0.0,
    val attention: TaskbarProgress.AttentionType? = null,
    val windowFocused: Boolean? = null,
    /** Running a timed 0 → 100 % sweep. */
    val sweeping: Boolean = false,
    val calls: List<CallRecord> = emptyList(),
)

sealed interface TaskbarIntent {
    /** The window the bar belongs to; sent by the screen, which is the one that knows it. */
    data class Attach(
        val window: NucleusWindow,
    ) : TaskbarIntent {
        override fun toString(): String = "Attach(window)"
    }

    data class SetState(
        val state: TaskbarProgress.State,
    ) : TaskbarIntent

    data class SetValue(
        val value: Double,
    ) : TaskbarIntent

    data object Hide : TaskbarIntent

    /** 0 → 100 % over [seconds], then [endState]: what a download looks like. */
    data class Sweep(
        val seconds: Int,
        val endState: TaskbarProgress.State,
    ) : TaskbarIntent

    data object StopSweep : TaskbarIntent

    data class RequestAttention(
        val type: TaskbarProgress.AttentionType,
        val delaySeconds: Int,
    ) : TaskbarIntent

    data object StopAttention : TaskbarIntent
}

sealed interface TaskbarEvent {
    data class Ready(
        val availability: Availability,
        val surface: String,
    ) : TaskbarEvent

    data object Attached : TaskbarEvent

    data class StateApplied(
        val state: TaskbarProgress.State,
        val outcome: CallOutcome,
    ) : TaskbarEvent

    data class ValueApplied(
        val value: Double,
        val outcome: CallOutcome,
    ) : TaskbarEvent

    data class SweepChanged(
        val running: Boolean,
    ) : TaskbarEvent

    data class AttentionApplied(
        val type: TaskbarProgress.AttentionType?,
        val outcome: CallOutcome,
    ) : TaskbarEvent

    data class FocusChanged(
        val focused: Boolean,
    ) : TaskbarEvent
}

object TaskbarReducer : Reducer<TaskbarState, TaskbarEvent> {
    override fun reduce(
        state: TaskbarState,
        event: TaskbarEvent,
    ): TaskbarState =
        when (event) {
            is TaskbarEvent.Ready -> state.copy(availability = event.availability, surface = event.surface)
            TaskbarEvent.Attached -> state.copy(attached = true)
            is TaskbarEvent.StateApplied ->
                state.copy(
                    shownState = if (event.outcome.ok) event.state else state.shownState,
                    calls = state.calls.plusCall("setState(${event.state})", event.outcome),
                )
            is TaskbarEvent.ValueApplied ->
                state.copy(
                    shownValue = if (event.outcome.ok) event.value else state.shownValue,
                    // A sweep would flood the call log: its steps only move the value.
                    calls =
                        if (state.sweeping) {
                            state.calls
                        } else {
                            state.calls.plusCall("setProgress(${event.value.fmt()})", event.outcome)
                        },
                )
            is TaskbarEvent.SweepChanged -> state.copy(sweeping = event.running)
            is TaskbarEvent.AttentionApplied ->
                state.copy(
                    attention = if (event.outcome.ok) event.type else state.attention,
                    calls =
                        state.calls.plusCall(
                            event.type?.let { "requestAttention($it)" } ?: "stopAttention()",
                            event.outcome,
                        ),
                )
            is TaskbarEvent.FocusChanged ->
                state.copy(
                    windowFocused = event.focused,
                    // CRITICAL lasts until the app gets focus; INFORMATIONAL is a short burst.
                    attention = if (event.focused) null else state.attention,
                )
        }
}
