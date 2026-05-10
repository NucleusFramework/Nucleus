package dev.nucleusframework.nucleus.scheduler

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A calendar-based schedule expression that maps to systemd `OnCalendar=` syntax.
 *
 * Use the companion factory methods to create common schedules.
 *
 * @property expression the systemd OnCalendar value (e.g. `*-*-* 09:00:00`)
 */
public class CronExpression private constructor(
    public val expression: String,
) {
    override fun toString(): String = expression

    override fun equals(other: Any?): Boolean = other is CronExpression && expression == other.expression

    override fun hashCode(): Int = expression.hashCode()

    public companion object {
        private const val DAY_ABBREV_LENGTH = 3

        private fun LocalTime.toCalendarSuffix(): String = "%02d:%02d:00".format(hour, minute)

        /**
         * Every day at the given [time].
         *
         * Example: `everyDayAt(LocalTime.of(9, 0))` → `*-*-* 09:00:00`
         */
        public fun everyDayAt(time: LocalTime): CronExpression = CronExpression("*-*-* ${time.toCalendarSuffix()}")

        /**
         * Every specified day of the week at the given [time].
         *
         * Example: `everyWeekdayAt(DayOfWeek.MONDAY, LocalTime.of(9, 0))` → `Mon *-*-* 09:00:00`
         */
        public fun everyWeekdayAt(
            day: DayOfWeek,
            time: LocalTime,
        ): CronExpression {
            val dayName =
                day.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
                    .take(DAY_ABBREV_LENGTH)
            return CronExpression("$dayName *-*-* ${time.toCalendarSuffix()}")
        }

        /**
         * Monday through Friday at the given [time].
         *
         * Example: `everyWeekdayAt(LocalTime.of(18, 0))` → `Mon..Fri *-*-* 18:00:00`
         */
        public fun everyWeekdayAt(time: LocalTime): CronExpression =
            CronExpression("Mon..Fri *-*-* ${time.toCalendarSuffix()}")

        /**
         * Fires at the wall-clock top of every hour (00:00, 01:00, …, 23:00) — **not**
         * "1 hour after enqueue". For a fixed-interval cadence relative to enqueue time,
         * use `TaskRequest.periodic(id, 1.hours)` instead.
         *
         * Example: `everyHour()` → `*-*-* *:00:00`
         */
        public fun everyHour(): CronExpression = CronExpression("*-*-* *:00:00")
    }
}
