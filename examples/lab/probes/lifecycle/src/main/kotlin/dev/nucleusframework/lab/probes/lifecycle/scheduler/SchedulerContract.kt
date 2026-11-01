package dev.nucleusframework.lab.probes.lifecycle.scheduler

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.scheduler.TaskInfo

@Immutable
data class SchedulerState(
    val available: Boolean? = null,
    val task: LabTask = LabTask.Heartbeat,
    val trigger: Trigger = Trigger.Periodic,
    val note: String = "from the Lab",
    val requiresCharging: Boolean = false,
    val requiresNetwork: Boolean = false,
    val commandLine: List<String> = emptyList(),
    val tasks: List<TaskInfo> = emptyList(),
    val runLog: List<String> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
    val invocations: List<FixtureRun> = emptyList(),
)

sealed interface SchedulerIntent {
    data class Select(
        val task: LabTask,
    ) : SchedulerIntent

    data class SetTrigger(
        val trigger: Trigger,
    ) : SchedulerIntent

    data class EditNote(
        val note: String,
    ) : SchedulerIntent

    data class SetCharging(
        val required: Boolean,
    ) : SchedulerIntent

    data class SetNetwork(
        val required: Boolean,
    ) : SchedulerIntent

    data object Enqueue : SchedulerIntent

    data object Cancel : SchedulerIntent

    data object CancelAll : SchedulerIntent

    /** Runs the task in process through `scheduler-testing`. */
    data object DryRun : SchedulerIntent

    /** Starts the process the OS would start, right now. */
    data object InvokeLikeOs : SchedulerIntent
}

sealed interface SchedulerEvent {
    data class Configured(
        val state: SchedulerState,
    ) : SchedulerEvent {
        override fun toString(): String = "Configured(${state.task}, ${state.trigger})"
    }

    data class Read(
        val available: Boolean,
        val tasks: List<TaskInfo>,
        val runLog: List<String>,
    ) : SchedulerEvent {
        override fun toString(): String =
            "Read(${tasks.joinToString { "${it.taskId}:${it.state}/runs=${it.runCount}/${it.lastResult}" }})"
    }

    data class Called(
        val call: CallRecord,
    ) : SchedulerEvent

    data class Invocations(
        val runs: List<FixtureRun>,
    ) : SchedulerEvent {
        override fun toString(): String = "Invocations(${runs.size})"
    }
}

object SchedulerReducer : Reducer<SchedulerState, SchedulerEvent> {
    override fun reduce(
        state: SchedulerState,
        event: SchedulerEvent,
    ): SchedulerState =
        when (event) {
            is SchedulerEvent.Configured -> event.state
            is SchedulerEvent.Read ->
                state.copy(
                    available = event.available,
                    tasks = event.tasks,
                    runLog = event.runLog,
                )
            is SchedulerEvent.Called -> state.copy(calls = state.calls.append(event.call))
            is SchedulerEvent.Invocations -> state.copy(invocations = event.runs)
        }
}
