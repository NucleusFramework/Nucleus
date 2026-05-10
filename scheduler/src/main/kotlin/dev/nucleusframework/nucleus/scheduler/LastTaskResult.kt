package dev.nucleusframework.nucleus.scheduler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Last recorded outcome of a scheduled task, exposed by [TaskInfo.lastResult].
 *
 * Distinct from [TaskResult] because it carries an additional [ConstraintsNotMet] state
 * for runs that were skipped before [DesktopTask.doWork] could be invoked.
 */
@Serializable
public sealed interface LastTaskResult {
    /** The task completed successfully on its last run. */
    @Serializable
    @SerialName("success")
    public data object Success : LastTaskResult

    /** The task ended with [TaskResult.Failure] on its last run. */
    @Serializable
    @SerialName("failure")
    public data class Failure(
        val message: String,
    ) : LastTaskResult

    /** The task ended with [TaskResult.Retry] on its last run. */
    @Serializable
    @SerialName("retry")
    public data class Retry(
        val message: String,
    ) : LastTaskResult

    /** The task was skipped because at least one [Constraints] check was not satisfied. */
    @Serializable
    @SerialName("constraints_not_met")
    public data class ConstraintsNotMet(
        val unsatisfied: Set<String>,
    ) : LastTaskResult
}
