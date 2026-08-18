package dev.nucleusframework.scheduler.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CalendarConfigEqualityTest {
    @Test
    fun `calendar config equals and hashCode include the day array`() {
        val a =
            MacOSLaunchdScheduler.CalendarConfig(
                day = 1,
                hour = 9,
                minute = 30,
                days = intArrayOf(1, 2, 3),
            )
        val b =
            MacOSLaunchdScheduler.CalendarConfig(
                day = 1,
                hour = 9,
                minute = 30,
                days = intArrayOf(1, 2, 3),
            )
        val c =
            MacOSLaunchdScheduler.CalendarConfig(
                day = 1,
                hour = 9,
                minute = 30,
                days = intArrayOf(1, 2, 4),
            )
        val d = MacOSLaunchdScheduler.CalendarConfig(hour = 9, minute = 0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertFalse(a.equals(null))
        assertFalse(a.equals("nope"))
        assertEquals(a, a)
        assertTrue(d.days == null)
        assertEquals(
            MacOSLaunchdScheduler.parseCalendarConfig("*-*-* *:00:00"),
            MacOSLaunchdScheduler.CalendarConfig(minute = 0),
        )
    }
}