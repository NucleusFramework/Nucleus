package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BuildTimerUnitTest {
    @Test
    fun `periodic timer has OnUnitInactiveSec`() {
        val request = TaskRequest.periodic(TaskId("test-task"), 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnUnitInactiveSec=3600s"), "Expected OnUnitInactiveSec=3600s")
    }

    @Test
    fun `periodic timer default OnActiveSec equals interval`() {
        val request = TaskRequest.periodic(TaskId("test-task"), 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnActiveSec=3600s"), "Expected OnActiveSec=3600s (full interval)")
    }

    @Test
    fun `periodic timer runImmediately sets OnActiveSec to 0`() {
        val request =
            TaskRequest.periodic(TaskId("test-task"), 1.hours) {
                runImmediately()
            }
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnActiveSec=0"), "Expected OnActiveSec=0")
        assertFalse(unit.contains("OnActiveSec=3600s"), "Should not contain deferred OnActiveSec")
    }

    @Test
    fun `periodic timer 15min default OnActiveSec`() {
        val request = TaskRequest.periodic(TaskId("test-task"), 15.minutes)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnActiveSec=900s"), "Expected OnActiveSec=900s (full interval)")
    }

    @Test
    fun `calendar timer has OnCalendar`() {
        val request = TaskRequest.calendar(TaskId("daily-report"), CronExpression.everyDayAt(LocalTime.of(9, 0)))
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("OnCalendar=*-*-* 09:00:00"), "Expected OnCalendar expression")
    }

    @Test
    fun `calendar timer weekday expression`() {
        val request = TaskRequest.calendar(TaskId("weekday-task"), CronExpression.everyWeekdayAt(LocalTime.of(18, 0)))
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(
            unit.contains("OnCalendar=Mon..Fri *-*-* 18:00:00"),
            "Expected weekday OnCalendar expression",
        )
    }

    @Test
    fun `timer has Persistent=true`() {
        val request = TaskRequest.periodic(TaskId("test-task"), 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("Persistent=true"))
    }

    @Test
    fun `timer has Install section`() {
        val request = TaskRequest.periodic(TaskId("test-task"), 1.hours)
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertTrue(unit.contains("[Install]"))
        assertTrue(unit.contains("WantedBy=timers.target"))
    }

    @Test
    fun `onBoot timer has no OnCalendar or OnUnitInactiveSec`() {
        val request = TaskRequest.onBoot(TaskId("startup"))
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)

        assertFalse(unit.contains("OnUnitInactiveSec="))
        assertFalse(unit.contains("OnCalendar="))
        assertFalse(unit.contains("OnActiveSec="))
        assertTrue(unit.contains("Persistent=true"))
        assertTrue(unit.contains("Description=Nucleus timer: startup"))
    }

    @Test
    fun `hourly cron calendar lands in OnCalendar`() {
        val request = TaskRequest.calendar(TaskId("hourly"), CronExpression.everyHour())
        val unit = LinuxSystemdScheduler.buildTimerUnit(request)
        assertTrue(unit.contains("OnCalendar=*-*-* *:00:00"))
    }
}
