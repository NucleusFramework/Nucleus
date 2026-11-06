package dev.nucleusframework.window.tao.event

import kotlin.test.Test
import kotlin.test.assertEquals

class TaoTouchEventTimeTest {
    @Test
    fun delayedDeliveryPreservesSampleIntervals() {
        val clock = TaoTouchEventTime()

        assertEquals(10_000L, clock.toMillis(1_000L, 10_000L))
        assertEquals(10_020L, clock.toMillis(1_020L, 11_000L))
        assertEquals(10_040L, clock.toMillis(1_040L, 11_001L))
    }

    @Test
    fun samplesWithTheSameTimestampRemainSimultaneous() {
        val clock = TaoTouchEventTime()

        assertEquals(10_000L, clock.toMillis(1_000L, 10_000L))
        assertEquals(10_000L, clock.toMillis(1_000L, 10_100L))
    }

    @Test
    fun unsignedClockWraparoundPreservesSampleIntervals() {
        val clock = TaoTouchEventTime()

        assertEquals(10_000L, clock.toMillis(0xFFFF_FFFEL, 10_000L))
        assertEquals(10_004L, clock.toMillis(2L, 10_100L))
    }

    @Test
    fun signedClockBoundaryPreservesSampleIntervals() {
        val clock = TaoTouchEventTime()

        assertEquals(10_000L, clock.toMillis(0x7FFF_FFFEL, 10_000L))
        assertEquals(10_004L, clock.toMillis(0x8000_0002L, 10_100L))
    }

    @Test
    fun missingTimestampFallsBackAndReanchorsTheNextSample() {
        val clock = TaoTouchEventTime()

        assertEquals(10_000L, clock.toMillis(1_000L, 10_000L))
        assertEquals(11_000L, clock.toMillis(0L, 11_000L))
        assertEquals(11_001L, clock.toMillis(1_040L, 11_001L))
        assertEquals(11_021L, clock.toMillis(1_060L, 11_002L))
    }

    @Test
    fun idleLongerThanTheUnambiguousClockRangeReanchors() {
        val clock = TaoTouchEventTime()
        val afterIdle = 10_000L + Int.MAX_VALUE + 1L

        clock.toMillis(1_000L, 10_000L)
        assertEquals(afterIdle, clock.toMillis(0x8000_03E8L, afterIdle))
        assertEquals(afterIdle + 20L, clock.toMillis(0x8000_03FCL, afterIdle + 1L))
    }
}
