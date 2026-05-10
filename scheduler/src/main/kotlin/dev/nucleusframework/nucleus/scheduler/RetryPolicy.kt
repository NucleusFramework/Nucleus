package dev.nucleusframework.nucleus.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Defines how a task is retried when [DesktopTask.doWork] returns [TaskResult.Retry].
 */
public sealed class RetryPolicy {
    /** Maximum number of total attempts (including the initial run). */
    public abstract val maxAttempts: Int

    /** Returns the delay before the Nth retry (0-based retry index). */
    internal abstract fun delayForRetry(retryIndex: Int): Duration

    /**
     * Exponential backoff: delay doubles after each retry.
     *
     * @property initialDelay delay before the first retry (default 30 minutes)
     * @property maxAttempts total attempts including the initial run (default 3)
     */
    public data class ExponentialBackoff(
        val initialDelay: Duration = 30.minutes,
        override val maxAttempts: Int = 3,
    ) : RetryPolicy() {
        override fun delayForRetry(retryIndex: Int): Duration {
            val multiplier = 1 shl retryIndex.coerceAtMost(MAX_SHIFT)
            return initialDelay * multiplier
        }

        private companion object {
            const val MAX_SHIFT = 30
        }
    }

    /**
     * Fixed delay between each retry.
     *
     * @property delay constant delay before each retry (default 15 minutes)
     * @property maxAttempts total attempts including the initial run (default 3)
     */
    public data class Linear(
        val delay: Duration = 15.minutes,
        override val maxAttempts: Int = 3,
    ) : RetryPolicy() {
        override fun delayForRetry(retryIndex: Int): Duration = delay
    }
}
