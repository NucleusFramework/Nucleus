package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.Constraints
import dev.nucleusframework.scheduler.LastTaskResult
import dev.nucleusframework.scheduler.NetworkType
import dev.nucleusframework.scheduler.TaskData
import dev.nucleusframework.scheduler.TaskId
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskMetadataStoreTest {
    private val appId = "nucleus-kover-metadata-store"
    private val taskId = TaskId("meta-task")

    @AfterTest
    fun cleanup() {
        TaskMetadataStore.deleteAll(appId)
        TaskMetadataStore.storeDir(appId).delete()
    }

    @Serializable
    private data class Payload(
        val token: String,
    )

    @Test
    fun `storeDir is namespaced by appId`() {
        val dir = TaskMetadataStore.storeDir(appId)
        assertTrue(dir.path.contains("nucleus"))
        assertTrue(dir.path.contains("scheduler"))
        assertTrue(dir.path.contains(appId))
    }

    @Test
    fun `save and loadContext round-trip input data`() {
        TaskMetadataStore.save(appId, taskId, TaskData.of(Payload(token = "abc")))
        val context = TaskMetadataStore.loadContext(appId, taskId)
        assertEquals(taskId, context.taskId)
        assertEquals(Payload(token = "abc"), context.rawInputData.decode(Payload.serializer()))
        assertEquals(1, context.runAttemptCount)
    }

    @Test
    fun `save empty TaskData removes a previous payload`() {
        TaskMetadataStore.save(appId, taskId, TaskData.of(Payload(token = "keep")))
        TaskMetadataStore.save(appId, taskId, TaskData.EMPTY)
        assertTrue(TaskMetadataStore.loadContext(appId, taskId).rawInputData.isEmpty())
    }

    @Test
    fun `recordSuccess increments run count and resets attempt`() {
        TaskMetadataStore.recordRetry(appId, taskId, "try again")
        TaskMetadataStore.recordSuccess(appId, taskId)

        assertEquals(1, TaskMetadataStore.getRunCount(appId, taskId))
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, taskId))
        assertEquals(LastTaskResult.Success, TaskMetadataStore.getLastResult(appId, taskId))
        assertNotNull(TaskMetadataStore.getLastRunMs(appId, taskId))
    }

    @Test
    fun `recordFailure resets attempt without incrementing run count`() {
        TaskMetadataStore.recordRetry(appId, taskId, "once")
        TaskMetadataStore.recordFailure(appId, taskId, "fatal")

        assertEquals(0, TaskMetadataStore.getRunCount(appId, taskId))
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, taskId))
        val result = TaskMetadataStore.getLastResult(appId, taskId)
        assertTrue(result is LastTaskResult.Failure)
        assertEquals("fatal", result.message)
    }

    @Test
    fun `recordRetry increments the attempt counter`() {
        TaskMetadataStore.recordRetry(appId, taskId, "first")
        TaskMetadataStore.recordRetry(appId, taskId, "second")

        assertEquals(3, TaskMetadataStore.getRunAttemptCount(appId, taskId))
        val result = TaskMetadataStore.getLastResult(appId, taskId)
        assertTrue(result is LastTaskResult.Retry)
        assertEquals("second", result.message)
    }

    @Test
    fun `missing metadata uses the documented defaults`() {
        val missing = TaskId("never-written")
        assertEquals(0, TaskMetadataStore.getRunCount(appId, missing))
        assertNull(TaskMetadataStore.getLastRunMs(appId, missing))
        assertNull(TaskMetadataStore.getLastResult(appId, missing))
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, missing))
        assertEquals(Constraints.NONE, TaskMetadataStore.loadConstraints(appId, missing))
        assertNull(TaskMetadataStore.loadTaskType(appId, missing))
        assertNull(TaskMetadataStore.loadScheduleHint(appId, missing))
    }

    @Test
    fun `corrupt last result is treated as missing`() {
        TaskMetadataStore.recordSuccess(appId, taskId)
        val file = TaskMetadataStore.storeDir(appId).resolve("${taskId.value}.properties")
        file.writeText("_lastResult=not-json\n")
        assertNull(TaskMetadataStore.getLastResult(appId, taskId))
    }

    @Test
    fun `corrupt properties file is ignored and treated as empty`() {
        val file = TaskMetadataStore.storeDir(appId).resolve("${taskId.value}.properties")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0, 1, 2, 3, 0xFF.toByte()))
        assertEquals(0, TaskMetadataStore.getRunCount(appId, taskId))
    }

    @Test
    fun `schedule hint persists calendar days and can drop them`() {
        val withDays =
            TaskMetadataStore.ScheduleHint(
                intervalSeconds = 0,
                calendarDay = -1,
                calendarHour = 9,
                calendarMinute = 30,
                calendarDays = intArrayOf(1, 2, 3, 4, 5),
            )
        TaskMetadataStore.saveScheduleHint(appId, taskId, withDays)
        assertEquals(withDays, TaskMetadataStore.loadScheduleHint(appId, taskId))

        val withoutDays = withDays.copy(calendarDays = null)
        TaskMetadataStore.saveScheduleHint(appId, taskId, withoutDays)
        val loaded = TaskMetadataStore.loadScheduleHint(appId, taskId)
        assertEquals(withoutDays, loaded)
        assertNull(loaded!!.calendarDays)
    }

    @Test
    fun `ScheduleHint equality is content based including the days array`() {
        val a = TaskMetadataStore.ScheduleHint(3600, 1, 8, 0, intArrayOf(1, 2))
        val b = TaskMetadataStore.ScheduleHint(3600, 1, 8, 0, intArrayOf(1, 2))
        val c = TaskMetadataStore.ScheduleHint(3600, 1, 8, 0, intArrayOf(1, 3))
        val d = TaskMetadataStore.ScheduleHint(3600, 1, 8, 0, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertEquals(a, a)
        assertNotEquals<Any>(a, "other")
    }

    @Test
    fun `task type and constraints persist`() {
        TaskMetadataStore.saveTaskType(appId, taskId, "CALENDAR")
        assertEquals("CALENDAR", TaskMetadataStore.loadTaskType(appId, taskId))

        val constraints =
            Constraints(
                requiredNetworkType = NetworkType.UNMETERED,
                requiresBatteryNotLow = true,
                requiresCharging = true,
                requiresDeviceIdle = true,
                minimumStorageBytes = 4096L,
            )
        TaskMetadataStore.saveConstraints(appId, taskId, constraints)
        assertEquals(constraints, TaskMetadataStore.loadConstraints(appId, taskId))

        TaskMetadataStore.saveConstraints(appId, taskId, Constraints.NONE)
        assertEquals(Constraints.NONE, TaskMetadataStore.loadConstraints(appId, taskId))
    }

    @Test
    fun `invalid stored network type falls back to not required`() {
        TaskMetadataStore.saveConstraints(appId, taskId, Constraints(requiredNetworkType = NetworkType.CONNECTED))
        val file = TaskMetadataStore.storeDir(appId).resolve("${taskId.value}.properties")
        val rewritten =
            file
                .readText()
                .replace("CONNECTED", "NOT_A_REAL_TYPE")
                .replace("true", "maybe")
        file.writeText(rewritten)

        val loaded = TaskMetadataStore.loadConstraints(appId, taskId)
        assertEquals(NetworkType.NOT_REQUIRED, loaded.requiredNetworkType)
        assertEquals(false, loaded.requiresBatteryNotLow)
        assertEquals(false, loaded.requiresCharging)
        assertEquals(false, loaded.requiresDeviceIdle)
    }

    @Test
    fun `constraint skip can increment the attempt counter`() {
        TaskMetadataStore.recordConstraintSkip(appId, taskId, setOf("storage"), incrementAttempt = false)
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, taskId))
        TaskMetadataStore.recordConstraintSkip(appId, taskId, setOf("storage"), incrementAttempt = true)
        assertEquals(2, TaskMetadataStore.getRunAttemptCount(appId, taskId))
        val result = TaskMetadataStore.getLastResult(appId, taskId)
        assertTrue(result is LastTaskResult.ConstraintsNotMet)
        assertEquals(setOf("storage"), result.unsatisfied)
    }

    @Test
    fun `listTaskIds skips invalid filenames and delete removes one task`() {
        TaskMetadataStore.save(appId, taskId, TaskData.of("x"))
        val junk = TaskMetadataStore.storeDir(appId).resolve("not a valid id.properties")
        junk.writeText("k=v\n")
        val other = TaskMetadataStore.storeDir(appId).resolve("notes.txt")
        other.writeText("ignore")

        assertEquals(listOf(taskId), TaskMetadataStore.listTaskIds(appId))
        TaskMetadataStore.delete(appId, taskId)
        assertTrue(TaskMetadataStore.listTaskIds(appId).isEmpty())
    }

    @Test
    fun `listTaskIds is empty when the store directory is missing`() {
        assertTrue(TaskMetadataStore.listTaskIds("nucleus-kover-missing-dir").isEmpty())
    }

    @Test
    fun `schedule hint with a blank days list stores an empty array`() {
        TaskMetadataStore.saveScheduleHint(
            appId,
            taskId,
            TaskMetadataStore.ScheduleHint(0, -1, 8, 0, intArrayOf(1, 2)),
        )
        val file = TaskMetadataStore.storeDir(appId).resolve("${taskId.value}.properties")
        val rewritten =
            file.readLines().joinToString("\n") { line ->
                if (line.startsWith("_schedCalDays=")) "_schedCalDays=  , x , 3" else line
            }
        file.writeText(rewritten + "\n")
        val loaded = TaskMetadataStore.loadScheduleHint(appId, taskId)
        assertEquals(listOf(3), loaded!!.calendarDays?.toList())
    }

    @Test
    fun `invalid numeric metadata falls back to defaults`() {
        val file = TaskMetadataStore.storeDir(appId).resolve("${taskId.value}.properties")
        file.parentFile.mkdirs()
        file.writeText("_runCount=nope\n_lastRunMs=soon\n_runAttemptCount=maybe\n")
        assertEquals(0, TaskMetadataStore.getRunCount(appId, taskId))
        assertNull(TaskMetadataStore.getLastRunMs(appId, taskId))
        assertEquals(1, TaskMetadataStore.getRunAttemptCount(appId, taskId))
    }

    @Test
    fun `deleteAll removes every properties file`() {
        TaskMetadataStore.save(appId, TaskId("one"), TaskData.of("1"))
        TaskMetadataStore.save(appId, TaskId("two"), TaskData.of("2"))
        TaskMetadataStore.deleteAll(appId)
        assertTrue(TaskMetadataStore.listTaskIds(appId).isEmpty())
    }
}
