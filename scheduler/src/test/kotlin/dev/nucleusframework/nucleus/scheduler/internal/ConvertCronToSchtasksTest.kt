package dev.nucleusframework.nucleus.scheduler.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConvertCronToSchtasksTest {
    private fun parse(expression: String): CronSchedule? = WindowsTaskScheduler.parseCronExpression(expression)

    @Test
    fun `daily at specific time`() {
        val result = parse("*-*-* 09:00:00")
        assertIs<CronSchedule.Daily>(result)
        assertEquals(9, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `daily at midnight`() {
        val result = parse("*-*-* 00:00:00")
        assertIs<CronSchedule.Daily>(result)
        assertEquals(0, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `every hour`() {
        val result = parse("*-*-* *:00:00")
        assertIs<CronSchedule.Hourly>(result)
    }

    @Test
    fun `specific weekday with time`() {
        val result = parse("Mon *-*-* 08:30:00")
        assertIs<CronSchedule.Weekly>(result)
        assertEquals(WindowsTaskSchedulerJni.MONDAY, result.daysOfWeek)
        assertEquals(8, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun `day range Mon to Fri`() {
        val result = parse("Mon..Fri *-*-* 18:00:00")
        assertIs<CronSchedule.Weekly>(result)
        val expected =
            WindowsTaskSchedulerJni.MONDAY or
                WindowsTaskSchedulerJni.TUESDAY or
                WindowsTaskSchedulerJni.WEDNESDAY or
                WindowsTaskSchedulerJni.THURSDAY or
                WindowsTaskSchedulerJni.FRIDAY
        assertEquals(expected, result.daysOfWeek)
        assertEquals(18, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `day range Tue to Thu`() {
        val result = parse("Tue..Thu *-*-* 12:00:00")
        assertIs<CronSchedule.Weekly>(result)
        val expected =
            WindowsTaskSchedulerJni.TUESDAY or
                WindowsTaskSchedulerJni.WEDNESDAY or
                WindowsTaskSchedulerJni.THURSDAY
        assertEquals(expected, result.daysOfWeek)
        assertEquals(12, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `unsupported expression returns null`() {
        assertNull(parse("*-*-01 00:00:00"))
    }

    @Test
    fun `whitespace is trimmed`() {
        val result = parse("  *-*-* 09:00:00  ")
        assertIs<CronSchedule.Daily>(result)
        assertEquals(9, result.hour)
        assertEquals(0, result.minute)
    }

    @Test
    fun `invalid day range returns null`() {
        assertNull(parse("Fri..Mon *-*-* 09:00:00"))
    }
}
