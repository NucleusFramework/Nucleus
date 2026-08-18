package dev.nucleusframework.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test
    fun `exponential backoff doubles until the shift cap`() {
        val policy = RetryPolicy.ExponentialBackoff(initialDelay = 30.minutes, maxAttempts = 5)

        assertEquals(5, policy.maxAttempts)
        assertEquals(30.minutes, policy.delayForRetry(0))
        assertEquals(60.minutes, policy.delayForRetry(1))
        assertEquals(120.minutes, policy.delayForRetry(2))
        assertEquals(240.minutes, policy.delayForRetry(3))

        val atCap = policy.delayForRetry(30)
        val pastCap = policy.delayForRetry(40)
        assertEquals(30.minutes * (1 shl 30), atCap)
        assertEquals(atCap, pastCap)
    }

    @Test
    fun `linear policy always returns the same delay`() {
        val policy = RetryPolicy.Linear(delay = 15.seconds, maxAttempts = 4)

        assertEquals(4, policy.maxAttempts)
        assertEquals(15.seconds, policy.delayForRetry(0))
        assertEquals(15.seconds, policy.delayForRetry(7))
    }

    @Test
    fun `default constructors keep documented defaults`() {
        val exponential = RetryPolicy.ExponentialBackoff()
        val linear = RetryPolicy.Linear()

        assertEquals(3, exponential.maxAttempts)
        assertEquals(30.minutes, exponential.delayForRetry(0))
        assertEquals(3, linear.maxAttempts)
        assertEquals(15.minutes, linear.delayForRetry(0))
    }
}
