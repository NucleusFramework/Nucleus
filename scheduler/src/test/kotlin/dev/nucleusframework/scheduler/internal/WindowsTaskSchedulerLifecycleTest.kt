package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import java.time.DayOfWeek
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
class WindowsTaskSchedulerLifecycleTest {
    private val created = mutableListOf<TaskId>()

    @AfterTest
    fun tearDown() {
        created.forEach { id ->
            runCatching { WindowsTaskScheduler.cancel(id) }
            TaskMetadataStore.delete(NucleusApp.appId, id)
        }
        created.clear()
    }

    @Test
    fun `missing tasks report as not scheduled`() {
        if (!windowsReady()) return
        val missing = TaskId("kovercov-win-missing")
        assertFalse(WindowsTaskScheduler.isScheduled(missing))
        assertNull(WindowsTaskScheduler.getTaskInfo(missing))
        assertFalse(WindowsTaskScheduler.cancel(missing))
        assertTrue(WindowsTaskScheduler.getAllTasks().none { it.taskId == missing })
    }

    @Test
    fun `enqueue periodic calendar and on-boot then cancel`() {
        if (!windowsReady()) return

        val periodic = TaskId("kovercov-win-periodic")
        val calendar = TaskId("kovercov-win-calendar")
        val weekday = TaskId("kovercov-win-weekday")
        val boot = TaskId("kovercov-win-boot")
        created += listOf(periodic, calendar, weekday, boot)

        assertTrue(
            WindowsTaskScheduler.enqueue(
                TaskRequest.periodic(periodic, 15.minutes) {
                    runImmediately()
                    inputData("payload")
                },
            ),
            "periodic enqueue must succeed when Task Scheduler COM is available",
        )
        assertTrue(
            WindowsTaskScheduler.enqueue(
                TaskRequest.calendar(calendar, CronExpression.everyDayAt(LocalTime.of(7, 45))),
            ),
            "daily calendar enqueue must succeed",
        )
        assertTrue(
            WindowsTaskScheduler.enqueue(
                TaskRequest.calendar(
                    weekday,
                    CronExpression.everyWeekdayAt(DayOfWeek.TUESDAY, LocalTime.of(11, 5)),
                ),
            ),
            "weekly calendar enqueue must succeed",
        )
        assertTrue(
            WindowsTaskScheduler.enqueue(TaskRequest.onBoot(boot)),
            "logon/on-boot enqueue must succeed",
        )

        assertTrue(WindowsTaskScheduler.isScheduled(periodic))
        val info = WindowsTaskScheduler.getTaskInfo(periodic)
        assertNotNull(info)
        assertEquals(periodic, info.taskId)
        assertTrue(WindowsTaskScheduler.getAllTasks().any { it.taskId == periodic })
        assertEquals("PERIODIC", TaskMetadataStore.loadTaskType(NucleusApp.appId, periodic))
        assertEquals("CALENDAR", TaskMetadataStore.loadTaskType(NucleusApp.appId, calendar))
        assertTrue(WindowsTaskScheduler.isScheduled(boot))

        val keep =
            TaskRequest.periodic(periodic, 30.minutes) {
                existingTaskPolicy(ExistingTaskPolicy.KEEP)
            }
        assertTrue(WindowsTaskScheduler.enqueue(keep))

        created.toList().forEach { id ->
            assertTrue(WindowsTaskScheduler.cancel(id), "cancel $id")
            assertFalse(WindowsTaskScheduler.isScheduled(id))
        }
    }

    @Test
    fun `unsupported calendar expression is not scheduled`() {
        if (!windowsReady()) return
        val ctor = CronExpression::class.java.getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        val monthly = ctor.newInstance("*-*-01 00:00:00") as CronExpression
        val taskId = TaskId("kovercov-win-badcal")
        created += taskId
        assertFalse(WindowsTaskScheduler.enqueue(TaskRequest.calendar(taskId, monthly)))
        assertFalse(WindowsTaskScheduler.isScheduled(taskId))
        created.remove(taskId)
    }

    @Test
    fun `replace recreates an existing task`() {
        if (!windowsReady()) return
        val taskId = TaskId("kovercov-win-replace")
        created += taskId
        assertTrue(
            WindowsTaskScheduler.enqueue(TaskRequest.periodic(taskId, 15.minutes)),
            "first enqueue",
        )
        assertTrue(
            WindowsTaskScheduler.enqueue(
                TaskRequest.periodic(taskId, 20.minutes) {
                    existingTaskPolicy(ExistingTaskPolicy.REPLACE)
                    runImmediately()
                },
            ),
            "REPLACE enqueue",
        )
        assertTrue(WindowsTaskScheduler.isScheduled(taskId))
        assertTrue(WindowsTaskScheduler.cancel(taskId))
        created.remove(taskId)
    }

    @Test
    fun `scheduleRetry writes a one-shot task when native is loaded`() {
        if (!windowsReady()) return
        val taskId = TaskId("kovercov-win-retry")
        created += taskId
        assertTrue(WindowsTaskScheduler.enqueue(TaskRequest.periodic(taskId, 15.minutes)))
        assertTrue(WindowsTaskScheduler.scheduleRetry(taskId, 30))
        assertTrue(WindowsTaskScheduler.cancel(taskId))
        created.remove(taskId)
    }

    private fun windowsReady(): Boolean = Platform.Current == Platform.Windows && WindowsTaskScheduler.isAvailable
}
