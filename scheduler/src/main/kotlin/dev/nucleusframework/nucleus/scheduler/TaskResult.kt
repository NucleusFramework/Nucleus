package dev.nucleusframework.nucleus.scheduler

/**
 * The outcome of a [DesktopTask.doWork] invocation.
 */
public sealed class TaskResult {
    /** The task completed successfully. */
    public data object Success : TaskResult()

    /**
     * The task failed permanently and should not be retried.
     *
     * @property message description of the failure (use `""` if you really have nothing to say)
     */
    public data class Failure(
        val message: String,
    ) : TaskResult()

    /**
     * The task failed temporarily and should be retried according to its [RetryPolicy].
     *
     * @property message description of why a retry is needed
     */
    public data class Retry(
        val message: String,
    ) : TaskResult()
}
