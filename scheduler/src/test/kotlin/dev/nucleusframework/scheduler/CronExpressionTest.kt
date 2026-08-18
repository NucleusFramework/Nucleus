package dev.nucleusframework.scheduler

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CronExpressionTest {
    @Test
    fun `everyDayAt formats hour and minute with seconds zeroed`() {
        val expression = CronExpression.everyDayAt(LocalTime.of(9, 5))
        assertEquals("*-*-* 09:05:00", expression.expression)
        assertEquals("*-*-* 09:05:00", expression.toString())
    }

    @Test
    fun `everyDayAt midnight and late evening`() {
        assertEquals("*-*-* 00:00:00", CronExpression.everyDayAt(LocalTime.MIDNIGHT).expression)
        assertEquals("*-*-* 23:59:00", CronExpression.everyDayAt(LocalTime.of(23, 59)).expression)
    }

    @Test
    fun `everyHour is wall-clock hourly`() {
        assertEquals("*-*-* *:00:00", CronExpression.everyHour().expression)
    }

    @Test
    fun `weekday range factory is Monday through Friday`() {
        val expression = CronExpression.everyWeekdayAt(LocalTime.of(18, 30))
        assertEquals("Mon..Fri *-*-* 18:30:00", expression.expression)
    }

    @Test
    fun `everyWeekdayAt maps each DayOfWeek to a three-letter prefix`() {
        val expected =
            mapOf(
                DayOfWeek.MONDAY to "Mon",
                DayOfWeek.TUESDAY to "Tue",
                DayOfWeek.WEDNESDAY to "Wed",
                DayOfWeek.THURSDAY to "Thu",
                DayOfWeek.FRIDAY to "Fri",
                DayOfWeek.SATURDAY to "Sat",
                DayOfWeek.SUNDAY to "Sun",
            )
        val time = LocalTime.of(8, 15)
        for ((day, abbrev) in expected) {
            assertEquals(
                "$abbrev *-*-* 08:15:00",
                CronExpression.everyWeekdayAt(day, time).expression,
                "day $day",
            )
        }
    }

    @Test
    fun `equality and hashCode are based on the expression string`() {
        val a = CronExpression.everyDayAt(LocalTime.of(9, 0))
        val b = CronExpression.everyDayAt(LocalTime.of(9, 0))
        val c = CronExpression.everyHour()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertNotEquals<Any>(a, "*-*-* 09:00:00")
        assertFalse(a.equals(null))
    }
}
