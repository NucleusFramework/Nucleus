package dev.nucleusframework.lab.probes.lifecycle.scheduler

import dev.nucleusframework.lab.core.LabLog
import dev.nucleusframework.scheduler.DesktopTask
import dev.nucleusframework.scheduler.TaskContext
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRegistry
import dev.nucleusframework.scheduler.TaskResult
import dev.nucleusframework.scheduler.inputData
import kotlinx.serialization.Serializable
import java.time.Instant

/** Input carried by every Lab task: proves `TaskData` survives the trip through the OS scheduler. */
@Serializable
data class LabTaskInput(
    val note: String,
    /** Logged with each run: how long the OS kept the task before running it. */
    val enqueuedAt: Long,
)

/** The tasks the Lab registers; each appends what it did to [runLog] so runs outlive the process. */
enum class LabTask(
    val id: TaskId,
    val behaviour: String,
    private val create: () -> DesktopTask,
) {
    Heartbeat(TaskId("lab-heartbeat"), "succeeds", { LoggingTask { TaskResult.Success } }),
    Flaky(TaskId("lab-flaky"), "retries on odd attempts", {
        LoggingTask { attempt ->
            if (attempt % 2 ==
                1
            ) {
                TaskResult.Retry("odd attempt $attempt")
            } else {
                TaskResult.Success
            }
        }
    }),
    Failing(TaskId("lab-failing"), "always fails", { LoggingTask { TaskResult.Failure("failing on purpose") } }),
    ;

    fun newTask(): DesktopTask = create()

    companion object {
        /** Written by whichever process ran the task (the Lab, a dry run, the OS-started child). */
        val runLog = LabLog("scheduler-runs")

        fun registry(): TaskRegistry =
            entries
                .fold(
                    TaskRegistry.Builder(),
                ) { builder, task -> builder.register(task.id) { task.newTask() } }
                .build()

        fun of(id: TaskId): LabTask? = entries.firstOrNull { it.id == id }
    }
}

private class LoggingTask(
    private val outcome: (attempt: Int) -> TaskResult,
) : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        val input = context.inputData<LabTaskInput>()
        val result = outcome(context.runAttemptCount)
        val enqueued = input?.let { " enqueued=${Instant.ofEpochMilli(it.enqueuedAt)}" }.orEmpty()
        LabTask.runLog.append(
            "${Instant.now()} ${context.taskId} attempt=${context.runAttemptCount} " +
                "pid=${ProcessHandle.current().pid()} note=${input?.note ?: "<no input>"}$enqueued → $result",
        )
        return result
    }
}
