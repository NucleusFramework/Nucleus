package dev.nucleusframework.nucleus.scheduler

/**
 * A unit of work that can be scheduled to run in the background,
 * even when the application is closed.
 *
 * Implement this interface and register it via [TaskRegistry] to define
 * what happens when the OS triggers the scheduled task.
 */
public interface DesktopTask {
    /**
     * Performs the background work. Called by [DesktopBootReceiver] when the
     * OS scheduler triggers this task.
     *
     * @param context execution context containing input data and attempt info
     * @return the outcome — [TaskResult.Success], [TaskResult.Failure], or [TaskResult.Retry]
     */
    public suspend fun doWork(context: TaskContext): TaskResult
}
