package dev.nucleusframework.nucleus.scheduler

/**
 * Controls what happens when [DesktopTaskScheduler.enqueue] is called
 * for a task whose [TaskId] is already scheduled with the OS.
 *
 * The three modes split along two dimensions: whether the OS-level
 * schedule is touched, and whether the persisted [TaskData] / [Constraints]
 * are refreshed.
 */
public enum class ExistingTaskPolicy {
    /**
     * Strict no-op: the new request is ignored entirely. The OS-level schedule,
     * the persisted [TaskData] and the persisted [Constraints] are all left as-is.
     *
     * This is the default and the safe choice for "ensure this task exists" calls
     * made on every app startup.
     */
    KEEP,

    /**
     * Keep the existing OS-level schedule but refresh the persisted [TaskData],
     * [Constraints] and task type with the values from the new request.
     *
     * Use this when you want to push a fresh payload (e.g. updated config) into
     * an already-registered task without re-creating it at the OS layer.
     */
    UPDATE_DATA,

    /**
     * Unload the existing task at the OS layer and re-create it from scratch
     * with the new configuration. Use this when the schedule itself
     * (interval, cron expression, run-immediately flag) has changed.
     */
    REPLACE,
}
