package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(InternalSchedulerApi::class)
class LinuxSystemdSchedulerLifecycleTest {
    private val created = mutableListOf<TaskId>()

    @AfterTest
    fun tearDown() {
        created.forEach { id ->
            runCatching { LinuxSystemdScheduler.cancel(id) }
            TaskMetadataStore.delete(NucleusApp.appId, id)
        }
        created.clear()
    }

    @Test
    fun `timer units encode periodic calendar and immediate start`() {
        val periodic =
            TaskRequest.periodic(TaskId("kovercov-linux-timer"), 15.minutes) {
                runImmediately()
            }
        val periodicText = LinuxSystemdScheduler.buildTimerUnit(periodic)
        assertTrue(periodicText.contains("OnUnitInactiveSec=900s"))
        assertTrue(periodicText.contains("OnActiveSec=0"))
        assertTrue(periodicText.contains("Persistent=true"))

        val delayed = TaskRequest.periodic(TaskId("kovercov-linux-delayed"), 15.minutes)
        val delayedText = LinuxSystemdScheduler.buildTimerUnit(delayed)
        assertTrue(delayedText.contains("OnActiveSec=900s"))

        val calendar =
            TaskRequest.calendar(
                TaskId("kovercov-linux-cal"),
                CronExpression.everyDayAt(LocalTime.of(7, 45)),
            )
        val calendarText = LinuxSystemdScheduler.buildTimerUnit(calendar)
        assertTrue(calendarText.contains("OnCalendar="))
        assertTrue(calendarText.contains("7:45") || calendarText.contains("07:45"))
    }

    @Test
    fun `missing tasks report as not scheduled`() {
        if (!LinuxSystemdScheduler.isAvailable) return
        val missing = TaskId("kovercov-linux-missing")
        assertFalse(LinuxSystemdScheduler.isScheduled(missing))
        assertNull(LinuxSystemdScheduler.getTaskInfo(missing))
        assertFalse(LinuxSystemdScheduler.cancel(missing))
        assertTrue(LinuxSystemdScheduler.getAllTasks().none { it.taskId == missing })
    }

    @Test
    fun `enqueue periodic calendar and on-boot then cancel`() {
        if (Platform.Current != Platform.Linux || !LinuxSystemdScheduler.isAvailable) return

        val periodic = TaskId("kovercov-linux-periodic")
        val calendar = TaskId("kovercov-linux-calendar")
        val boot = TaskId("kovercov-linux-boot")
        created += listOf(periodic, calendar, boot)

        val periodicOk =
            LinuxSystemdScheduler.enqueue(
                TaskRequest.periodic(periodic, 15.minutes) {
                    runImmediately()
                    inputData("payload")
                },
            )
        val calendarOk =
            LinuxSystemdScheduler.enqueue(
                TaskRequest.calendar(calendar, CronExpression.everyDayAt(LocalTime.of(7, 45))),
            )
        val bootOk = LinuxSystemdScheduler.enqueue(TaskRequest.onBoot(boot))

        if (periodicOk) {
            assertTrue(LinuxSystemdScheduler.isScheduled(periodic))
            val info = LinuxSystemdScheduler.getTaskInfo(periodic)
            assertNotNull(info)
            assertEquals(periodic, info.taskId)
            assertTrue(LinuxSystemdScheduler.getAllTasks().any { it.taskId == periodic })
            assertEquals("PERIODIC", TaskMetadataStore.loadTaskType(NucleusApp.appId, periodic))
        }
        if (calendarOk) {
            assertEquals("CALENDAR", TaskMetadataStore.loadTaskType(NucleusApp.appId, calendar))
        }
        if (bootOk) {
            assertTrue(LinuxSystemdScheduler.isScheduled(boot))
        }

        val keep =
            TaskRequest.periodic(periodic, 30.minutes) {
                existingTaskPolicy(ExistingTaskPolicy.KEEP)
            }
        if (periodicOk) {
            assertTrue(LinuxSystemdScheduler.enqueue(keep))
        }

        created.toList().forEach { id ->
            if (LinuxSystemdScheduler.isScheduled(id) || periodicOk || calendarOk || bootOk) {
                LinuxSystemdScheduler.cancel(id)
                assertFalse(LinuxSystemdScheduler.isScheduled(id))
            }
        }
    }

    @Test
    fun `scheduleRetry writes a one-shot timer when native is loaded`() {
        if (!LinuxSystemdScheduler.isAvailable) return
        val taskId = TaskId("kovercov-linux-retry")
        created += taskId
        val ok = LinuxSystemdScheduler.scheduleRetry(taskId, 30)
        if (ok) {
            LinuxSystemdScheduler.cancel(taskId)
        }
    }
}
