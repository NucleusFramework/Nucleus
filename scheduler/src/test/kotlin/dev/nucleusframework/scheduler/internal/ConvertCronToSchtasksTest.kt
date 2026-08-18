package dev.nucleusframework.scheduler.internal

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

    @Test
    fun `unknown weekday returns null`() {
        assertNull(parse("Xxx *-*-* 09:00:00"))
    }

    @Test
    fun `unknown day range endpoint returns null`() {
        assertNull(parse("Mon..Xxx *-*-* 09:00:00"))
    }

    @Test
    fun `saturday and sunday single days`() {
        val saturday = parse("Sat *-*-* 11:00:00")
        assertIs<CronSchedule.Weekly>(saturday)
        assertEquals(WindowsTaskSchedulerJni.SATURDAY, saturday.daysOfWeek)
        assertEquals(11, saturday.hour)

        val sunday = parse("Sun *-*-* 07:45:00")
        assertIs<CronSchedule.Weekly>(sunday)
        assertEquals(WindowsTaskSchedulerJni.SUNDAY, sunday.daysOfWeek)
        assertEquals(7, sunday.hour)
        assertEquals(45, sunday.minute)
    }

    @Test
    fun `weekend range Sat to Sun`() {
        val result = parse("Sat..Sun *-*-* 06:15:00")
        assertIs<CronSchedule.Weekly>(result)
        assertEquals(
            WindowsTaskSchedulerJni.SATURDAY or WindowsTaskSchedulerJni.SUNDAY,
            result.daysOfWeek,
        )
        assertEquals(6, result.hour)
        assertEquals(15, result.minute)
    }

    @Test
    fun `single-day range collapses to that day`() {
        val result = parse("Wed..Wed *-*-* 13:00:00")
        assertIs<CronSchedule.Weekly>(result)
        assertEquals(WindowsTaskSchedulerJni.WEDNESDAY, result.daysOfWeek)
    }

    @Test
    fun `remaining single weekdays produce the matching bit`() {
        assertEquals(WindowsTaskSchedulerJni.TUESDAY, (parse("Tue *-*-* 01:00:00") as CronSchedule.Weekly).daysOfWeek)
        assertEquals(WindowsTaskSchedulerJni.WEDNESDAY, (parse("Wed *-*-* 02:00:00") as CronSchedule.Weekly).daysOfWeek)
        assertEquals(WindowsTaskSchedulerJni.THURSDAY, (parse("Thu *-*-* 03:00:00") as CronSchedule.Weekly).daysOfWeek)
        assertEquals(WindowsTaskSchedulerJni.FRIDAY, (parse("Fri *-*-* 04:00:00") as CronSchedule.Weekly).daysOfWeek)
    }

    @Test
    fun `empty and malformed expressions return null`() {
        assertNull(parse(""))
        assertNull(parse("not a cron"))
        assertNull(parse("*-*-* 9:00:00"))
        assertNull(parse("*-*-* 09:00"))
    }
}
