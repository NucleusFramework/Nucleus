package dev.nucleusframework.scheduler

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskInfoAndResultTest {
    @Test
    fun `TaskInfo defaults omit last run details`() {
        val info = TaskInfo(taskId = TaskId("sync"), state = TaskState.SCHEDULED)
        assertNull(info.lastRunMs)
        assertNull(info.nextRunMs)
        assertEquals(0, info.runCount)
        assertNull(info.lastResult)
    }

    @Test
    fun `TaskInfo equality includes last result`() {
        val a =
            TaskInfo(
                taskId = TaskId("sync"),
                state = TaskState.RUNNING,
                lastRunMs = 10L,
                nextRunMs = 20L,
                runCount = 2,
                lastResult = LastTaskResult.Success,
            )
        val b = a.copy()
        val c = a.copy(state = TaskState.INACTIVE)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `TaskState has the three documented values`() {
        assertEquals(
            listOf(TaskState.SCHEDULED, TaskState.RUNNING, TaskState.INACTIVE),
            TaskState.entries,
        )
    }

    @Test
    fun `ExistingTaskPolicy has keep update and replace`() {
        assertEquals(
            listOf(ExistingTaskPolicy.KEEP, ExistingTaskPolicy.UPDATE_DATA, ExistingTaskPolicy.REPLACE),
            ExistingTaskPolicy.entries,
        )
    }

    @Test
    fun `TaskResult variants compare by type and message`() {
        assertEquals(TaskResult.Success, TaskResult.Success)
        assertEquals(TaskResult.Failure("boom"), TaskResult.Failure("boom"))
        assertNotEquals(TaskResult.Failure("a"), TaskResult.Failure("b"))
        assertEquals(TaskResult.Retry("later"), TaskResult.Retry("later"))
        assertNotEquals<TaskResult>(TaskResult.Retry("later"), TaskResult.Failure("later"))
    }

    @Test
    fun `LastTaskResult serializes each variant`() {
        val json = Json { encodeDefaults = true }
        val samples: List<LastTaskResult> =
            listOf(
                LastTaskResult.Success,
                LastTaskResult.Failure("nope"),
                LastTaskResult.Retry("again"),
                LastTaskResult.ConstraintsNotMet(setOf("network", "storage")),
            )
        for (sample in samples) {
            val encoded = json.encodeToString(LastTaskResult.serializer(), sample)
            val decoded = json.decodeFromString(LastTaskResult.serializer(), encoded)
            assertEquals(sample, decoded, encoded)
        }
    }

    @Test
    fun `TaskContext inputData decodes attached payload`() {
        val context =
            TaskContext(
                taskId = TaskId("echo"),
                rawInputData = TaskData.of("hello"),
                runAttemptCount = 3,
            )
        assertEquals("hello", context.inputData<String>())
        assertEquals("hello", context.inputData(kotlinx.serialization.serializer<String>()))
        assertEquals(3, context.runAttemptCount)
    }

    @Test
    fun `TaskContext without payload decodes to null`() {
        val context = TaskContext(taskId = TaskId("empty"))
        assertEquals(TaskData.EMPTY, context.rawInputData)
        assertEquals(1, context.runAttemptCount)
        assertNull(context.inputData<String>())
    }

    @Test
    fun `DesktopTask contract returns the doWork result`() {
        val task =
            object : DesktopTask {
                override suspend fun doWork(context: TaskContext): TaskResult =
                    TaskResult.Failure("from-${context.taskId}")
            }
        val result = kotlinx.coroutines.runBlocking { task.doWork(TaskContext(TaskId("job"))) }
        assertTrue(result is TaskResult.Failure)
        assertEquals("from-job", result.message)
    }
}
