package dev.nucleusframework.nucleus.scheduler

/**
 * Runtime information about a scheduled task.
 *
 * @property taskId the unique task identifier
 * @property state current scheduling state
 * @property lastRunMs epoch millis of the last execution, or `null` if never run
 * @property nextRunMs epoch millis of the next scheduled execution, or `null` if unknown
 * @property runCount total number of completed executions
 * @property lastResult typed outcome of the last run (or `null` if the task has never run)
 */
public data class TaskInfo(
    val taskId: TaskId,
    val state: TaskState,
    val lastRunMs: Long? = null,
    val nextRunMs: Long? = null,
    val runCount: Int = 0,
    val lastResult: LastTaskResult? = null,
)

/**
 * Scheduling state of a task.
 */
public enum class TaskState {
    /** The task is registered and will fire on schedule. */
    SCHEDULED,

    /** The task is currently executing. */
    RUNNING,

    /** The task has been cancelled or is not registered with the OS. */
    INACTIVE,
}
