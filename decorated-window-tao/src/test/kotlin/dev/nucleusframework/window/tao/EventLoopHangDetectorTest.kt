package dev.nucleusframework.window.tao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GRACE_MS = 5_000L

private fun ms(millis: Long): Long = millis * 1_000_000L

/**
 * The watchdog's state machine (#643): a stall must be reported exactly once —
 * a hang that lasts minutes must not fill the log with one SEVERE dump every
 * poll — and a loop that comes back must re-arm, so a second stall is reported
 * again.
 */
class EventLoopHangDetectorTest {
    @Test
    fun `a hang shorter than the grace period is not reported`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        assertNull(detector.sample(hung = true, nowNanos = ms(0)))
        assertNull(detector.sample(hung = true, nowNanos = ms(2_000)))
        assertNull(detector.sample(hung = true, nowNanos = ms(4_999)))
    }

    @Test
    fun `a hang past the grace period is reported once`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        assertNull(detector.sample(hung = true, nowNanos = ms(0)))
        val stalled = detector.sample(hung = true, nowNanos = ms(6_000))
        assertEquals(HangTransition.Stalled(durationMs = 6_000), stalled)

        // Still hung, poll after poll: nothing more, or a permanent deadlock
        // would emit a thread dump every two seconds.
        assertNull(detector.sample(hung = true, nowNanos = ms(8_000)))
        assertNull(detector.sample(hung = true, nowNanos = ms(60_000)))
    }

    @Test
    fun `pumping again after a reported stall reports the recovery`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        detector.sample(hung = true, nowNanos = ms(0))
        detector.sample(hung = true, nowNanos = ms(6_000))

        val recovered = detector.sample(hung = false, nowNanos = ms(9_000))
        assertEquals(HangTransition.Recovered(durationMs = 9_000), recovered)
    }

    @Test
    fun `a hang that never reached the grace period reports no recovery`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        detector.sample(hung = true, nowNanos = ms(0))
        assertNull(detector.sample(hung = false, nowNanos = ms(3_000)))
    }

    @Test
    fun `a second stall after a recovery is reported again`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        detector.sample(hung = true, nowNanos = ms(0))
        detector.sample(hung = true, nowNanos = ms(6_000))
        detector.sample(hung = false, nowNanos = ms(7_000))

        assertNull(detector.sample(hung = true, nowNanos = ms(10_000)))
        val second = detector.sample(hung = true, nowNanos = ms(20_000))
        assertTrue(second is HangTransition.Stalled, "second stall must be reported, was $second")
        // Timed from the new stall, not from the first one.
        assertEquals(10_000, second.durationMs)
    }

    @Test
    fun `a reset closes a reported stall so every unresponsive keeps its responsive`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        detector.sample(hung = true, nowNanos = ms(0))
        detector.sample(hung = true, nowNanos = ms(6_000))

        // What the watchdog does when a sample straddles a system suspend: the
        // episode is abandoned, but a stall the app was told about is closed.
        assertEquals(
            HangTransition.Recovered(durationMs = 7_000),
            detector.reset(nowNanos = ms(7_000)),
        )

        assertNull(detector.sample(hung = false, nowNanos = ms(7_000)))
        // And the next stall is timed from scratch.
        assertNull(detector.sample(hung = true, nowNanos = ms(8_000)))
        assertEquals(
            HangTransition.Stalled(durationMs = 6_000),
            detector.sample(hung = true, nowNanos = ms(14_000)),
        )
    }

    @Test
    fun `a reset with nothing reported claims nothing`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        detector.sample(hung = true, nowNanos = ms(0))
        assertNull(detector.reset(nowNanos = ms(2_000)))
    }

    @Test
    fun `a healthy loop never reports anything`() {
        val detector = EventLoopHangDetector(GRACE_MS)

        repeat(10) { i -> assertNull(detector.sample(hung = false, nowNanos = ms(i * 2_000L))) }
    }
}
