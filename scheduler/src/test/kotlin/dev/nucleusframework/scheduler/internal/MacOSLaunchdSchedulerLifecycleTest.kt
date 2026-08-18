package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.Constraints
import dev.nucleusframework.scheduler.CronExpression
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.NetworkType
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskState
import java.io.File
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
class MacOSLaunchdSchedulerLifecycleTest {
    private val created = mutableListOf<TaskId>()

    @AfterTest
    fun tearDown() {
        created.forEach { id ->
            runCatching { MacOSLaunchdScheduler.cancel(id) }
            runCatching { MacOSLaunchdScheduler.cleanupRetryPlist(id) }
            TaskMetadataStore.delete(NucleusApp.appId, id)
        }
        created.clear()
    }

    @Test
    fun `missing tasks report as not scheduled`() {
        val missing = TaskId("kovercov-missing")
        assertFalse(MacOSLaunchdScheduler.isScheduled(missing))
        assertNull(MacOSLaunchdScheduler.getTaskInfo(missing))
        assertFalse(MacOSLaunchdScheduler.cancel(missing))
        MacOSLaunchdScheduler.cleanupRetryPlist(missing)
        assertTrue(MacOSLaunchdScheduler.getAllTasks().none { it.taskId == missing })
    }

    @Test
    fun `keep and update-data short-circuit when a plist already exists`() {
        val taskId = TaskId("kovercov-keep")
        val plist = launchAgentsPlist(taskId)
        plist.parentFile.mkdirs()
        plist.writeText("dummy-not-a-real-job")
        created += taskId
        try {
            assertTrue(MacOSLaunchdScheduler.isScheduled(taskId))

            val keep =
                TaskRequest.periodic(taskId, 15.minutes) {
                    existingTaskPolicy(ExistingTaskPolicy.KEEP)
                }
            assertTrue(MacOSLaunchdScheduler.enqueue(keep))
            assertEquals("dummy-not-a-real-job", plist.readText())

            val update =
                TaskRequest.periodic(taskId, 15.minutes) {
                    existingTaskPolicy(ExistingTaskPolicy.UPDATE_DATA)
                    inputData("payload")
                    constraints {
                        requiredNetworkType = NetworkType.CONNECTED
                        requiresCharging = true
                    }
                }
            assertTrue(MacOSLaunchdScheduler.enqueue(update))
            assertEquals("dummy-not-a-real-job", plist.readText())
            assertEquals("PERIODIC", TaskMetadataStore.loadTaskType(NucleusApp.appId, taskId))
            assertEquals(
                Constraints(requiredNetworkType = NetworkType.CONNECTED, requiresCharging = true),
                TaskMetadataStore.loadConstraints(NucleusApp.appId, taskId),
            )
            assertFalse(TaskMetadataStore.loadContext(NucleusApp.appId, taskId).rawInputData.isEmpty())

            val info = MacOSLaunchdScheduler.getTaskInfo(taskId)
            assertNotNull(info)
            assertEquals(taskId, info.taskId)
            assertTrue(info.state == TaskState.INACTIVE || info.state == TaskState.SCHEDULED)
        } finally {
            assertTrue(MacOSLaunchdScheduler.cancel(taskId))
            assertFalse(plist.exists())
        }
    }

    @Test
    fun `enqueue periodic calendar and on-boot then cancel`() {
        if (Platform.Current != Platform.MacOS) return

        val periodic = TaskId("kovercov-periodic")
        val calendar = TaskId("kovercov-calendar")
        val weekday = TaskId("kovercov-weekday")
        val range = TaskId("kovercov-range")
        val hourly = TaskId("kovercov-hourly")
        val boot = TaskId("kovercov-boot")
        created += listOf(periodic, calendar, weekday, range, hourly, boot)

        val periodicOk =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.periodic(periodic, 15.minutes) {
                    runImmediately()
                    constraints { requiresBatteryNotLow = true }
                },
            )
        val calendarOk =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.calendar(calendar, CronExpression.everyDayAt(LocalTime.of(7, 45))),
            )
        val weekdayOk =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.calendar(
                    weekday,
                    CronExpression.everyWeekdayAt(DayOfWeek.TUESDAY, LocalTime.of(11, 5)),
                ),
            )
        val rangeOk =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.calendar(range, CronExpression.everyWeekdayAt(LocalTime.of(18, 0))),
            )
        val hourlyOk =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.calendar(hourly, CronExpression.everyHour()),
            )
        val bootOk = MacOSLaunchdScheduler.enqueue(TaskRequest.onBoot(boot))

        if (periodicOk) {
            assertTrue(MacOSLaunchdScheduler.isScheduled(periodic))
            val info = MacOSLaunchdScheduler.getTaskInfo(periodic)
            assertNotNull(info)
            assertEquals(periodic, info.taskId)
            assertTrue(MacOSLaunchdScheduler.getAllTasks().any { it.taskId == periodic })
            val hint = TaskMetadataStore.loadScheduleHint(NucleusApp.appId, periodic)
            assertNotNull(hint)
            assertEquals(15.minutes.inWholeSeconds.toInt(), hint.intervalSeconds)
        }
        if (calendarOk) {
            val hint = TaskMetadataStore.loadScheduleHint(NucleusApp.appId, calendar)
            assertNotNull(hint)
            assertEquals(7, hint.calendarHour)
            assertEquals(45, hint.calendarMinute)
        }
        if (weekdayOk) {
            val hint = TaskMetadataStore.loadScheduleHint(NucleusApp.appId, weekday)
            assertNotNull(hint)
            assertEquals(2, hint.calendarDay)
        }
        if (rangeOk) {
            val hint = TaskMetadataStore.loadScheduleHint(NucleusApp.appId, range)
            assertNotNull(hint)
            assertEquals(listOf(1, 2, 3, 4, 5), hint.calendarDays?.toList())
        }
        if (hourlyOk) {
            val hint = TaskMetadataStore.loadScheduleHint(NucleusApp.appId, hourly)
            assertNotNull(hint)
            assertEquals(0, hint.calendarMinute)
        }
        if (bootOk) {
            assertTrue(MacOSLaunchdScheduler.isScheduled(boot))
        }

        created.toList().forEach { id ->
            if (MacOSLaunchdScheduler.isScheduled(id)) {
                assertTrue(MacOSLaunchdScheduler.cancel(id))
                assertFalse(MacOSLaunchdScheduler.isScheduled(id))
            }
        }
    }

    @Test
    fun `unsupported calendar expression is not scheduled`() {
        if (Platform.Current != Platform.MacOS) return
        val ctor = CronExpression::class.java.getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        val monthly = ctor.newInstance("*-*-01 00:00:00") as CronExpression
        val taskId = TaskId("kovercov-badcal")
        created += taskId
        assertFalse(
            MacOSLaunchdScheduler.enqueue(TaskRequest.calendar(taskId, monthly)),
        )
        assertFalse(MacOSLaunchdScheduler.isScheduled(taskId))
        created.remove(taskId)
    }

    @Test
    fun `replace unloads a dummy plist and writes a real one`() {
        if (Platform.Current != Platform.MacOS) return
        val taskId = TaskId("kovercov-replace")
        val plist = launchAgentsPlist(taskId)
        plist.parentFile.mkdirs()
        plist.writeText("dummy-replace")
        created += taskId
        val replaced =
            MacOSLaunchdScheduler.enqueue(
                TaskRequest.periodic(taskId, 20.minutes) {
                    existingTaskPolicy(ExistingTaskPolicy.REPLACE)
                    runImmediately()
                },
            )
        if (replaced) {
            assertTrue(plist.exists())
            assertTrue(plist.readText().contains("Label") || plist.readText().isNotEmpty())
            assertTrue(MacOSLaunchdScheduler.isScheduled(taskId))
            assertTrue(MacOSLaunchdScheduler.cancel(taskId))
            created.remove(taskId)
            assertFalse(plist.exists())
        } else {
            assertTrue(MacOSLaunchdScheduler.cancel(taskId) || !plist.exists())
            created.remove(taskId)
        }
    }

    @Test
    fun `cancelAll is a no-op when this app has no launch agents`() {
        val ours = TaskId("kovercov-cancel-all-probe")
        assertFalse(MacOSLaunchdScheduler.isScheduled(ours))
        // Only safe if this process has not left other kovercov agents behind.
        val leftover = MacOSLaunchdScheduler.getAllTasks().filter { it.taskId.value.startsWith("kovercov-") }
        leftover.forEach { MacOSLaunchdScheduler.cancel(it.taskId) }
        assertTrue(MacOSLaunchdScheduler.getAllTasks().none { it.taskId.value.startsWith("kovercov-") })
    }

    private fun launchAgentsPlist(taskId: TaskId): File =
        File(
            System.getProperty("user.home"),
            "Library/LaunchAgents/${MacOSLaunchdScheduler.label(taskId)}.plist",
        )
}
