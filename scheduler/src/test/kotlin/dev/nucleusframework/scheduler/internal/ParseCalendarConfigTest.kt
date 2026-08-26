package dev.nucleusframework.scheduler.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParseCalendarConfigTest {
    @Test
    fun `hourly expression only sets minute`() {
        val config = MacOSLaunchdScheduler.parseCalendarConfig("*-*-* *:00:00")
        assertEquals(-1, config.day)
        assertEquals(-1, config.hour)
        assertEquals(0, config.minute)
        assertNull(config.days)
    }

    @Test
    fun `daily expression sets hour and minute`() {
        val config = MacOSLaunchdScheduler.parseCalendarConfig("  *-*-* 23:05:00  ")
        assertEquals(23, config.hour)
        assertEquals(5, config.minute)
        assertEquals(-1, config.day)
    }

    @Test
    fun `single weekday maps Sunday to zero`() {
        val sunday = MacOSLaunchdScheduler.parseCalendarConfig("Sun *-*-* 10:00:00")
        assertEquals(0, sunday.day)
        assertEquals(10, sunday.hour)
        assertEquals(0, sunday.minute)

        val saturday = MacOSLaunchdScheduler.parseCalendarConfig("Sat *-*-* 11:15:00")
        assertEquals(6, saturday.day)
        assertEquals(11, saturday.hour)
        assertEquals(15, saturday.minute)
    }

    @Test
    fun `day range Sat through Sun expands to launchd weekdays`() {
        val config = MacOSLaunchdScheduler.parseCalendarConfig("Sat..Sun *-*-* 07:00:00")
        assertEquals(7, config.hour)
        assertEquals(0, config.minute)
        assertEquals(intArrayOf(6, 0).toList(), config.days!!.toList())
    }

    @Test
    fun `invalid day range throws`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                MacOSLaunchdScheduler.parseCalendarConfig("Fri..Mon *-*-* 09:00:00")
            }
        assertTrue(error.message!!.contains("Fri..Mon *-*-* 09:00:00"))
    }

    @Test
    fun `unknown weekday throws`() {
        assertFailsWith<IllegalArgumentException> {
            MacOSLaunchdScheduler.parseCalendarConfig("Xxx *-*-* 09:00:00")
        }
    }

    @Test
    fun `monthly expression is unsupported`() {
        assertFailsWith<IllegalArgumentException> {
            MacOSLaunchdScheduler.parseCalendarConfig("*-*-01 00:00:00")
        }
    }

    @Test
    fun `CalendarConfig equality includes the days array`() {
        val a = MacOSLaunchdScheduler.parseCalendarConfig("Mon..Wed *-*-* 08:00:00")
        val b = MacOSLaunchdScheduler.parseCalendarConfig("Mon..Wed *-*-* 08:00:00")
        val c = MacOSLaunchdScheduler.parseCalendarConfig("Mon..Thu *-*-* 08:00:00")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertEquals(a, a)
        assertNotEquals<Any>(a, "other")
    }

    @Test
    fun `every remaining weekday maps onto launchd numbers`() {
        val tue = MacOSLaunchdScheduler.parseCalendarConfig("Tue *-*-* 01:02:00")
        assertEquals(2, tue.day)
        val thu = MacOSLaunchdScheduler.parseCalendarConfig("Thu *-*-* 03:04:00")
        assertEquals(4, thu.day)
        val fri = MacOSLaunchdScheduler.parseCalendarConfig("Fri *-*-* 05:06:00")
        assertEquals(5, fri.day)
        val monFri = MacOSLaunchdScheduler.parseCalendarConfig("Mon..Fri *-*-* 09:00:00")
        assertEquals(intArrayOf(1, 2, 3, 4, 5).toList(), monFri.days!!.toList())
    }

    @Test
    fun `empty and unknown range endpoints throw`() {
        assertFailsWith<IllegalArgumentException> {
            MacOSLaunchdScheduler.parseCalendarConfig("")
        }
        assertFailsWith<IllegalArgumentException> {
            MacOSLaunchdScheduler.parseCalendarConfig("Xxx..Fri *-*-* 09:00:00")
        }
        assertFailsWith<IllegalArgumentException> {
            MacOSLaunchdScheduler.parseCalendarConfig("Mon *-*-* 9:00:00")
        }
    }

    @Test
    fun `label embeds the task id`() {
        val label = MacOSLaunchdScheduler.label(dev.nucleusframework.scheduler.TaskId("reports"))
        assertTrue(label.startsWith("dev.nucleusframework."))
        assertTrue(label.endsWith(".reports"))
    }
}
