package dev.nucleusframework.scheduler

import dev.nucleusframework.scheduler.internal.NoopScheduler
import dev.nucleusframework.scheduler.internal.PlatformScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(InternalSchedulerApi::class)
class DesktopTaskSchedulerApiTest {
    @AfterTest
    fun restoreDelegate() {
        DesktopTaskScheduler.resetDelegate()
    }

    @Test
    fun `getInstance returns the object itself`() {
        assertSame(DesktopTaskScheduler, DesktopTaskScheduler.getInstance())
    }

    @Test
    fun `setTestDelegate routes all operations to the replacement`() {
        val fake = RecordingScheduler()
        DesktopTaskScheduler.setTestDelegate(fake)

        val request = TaskRequest.periodic(TaskId("sync"), 1.hours)
        assertTrue(DesktopTaskScheduler.isAvailable())
        assertTrue(DesktopTaskScheduler.enqueue(request))
        assertTrue(DesktopTaskScheduler.isScheduled(TaskId("sync")))
        assertEquals(TaskState.SCHEDULED, DesktopTaskScheduler.getTaskInfo(TaskId("sync"))?.state)
        assertEquals(1, DesktopTaskScheduler.getAllTasks().size)
        assertTrue(DesktopTaskScheduler.cancel(TaskId("sync")))
        DesktopTaskScheduler.cancelAll()

        assertEquals(listOf(request.taskId), fake.enqueued.map { it.taskId })
        assertEquals(listOf(TaskId("sync")), fake.cancelled)
        assertTrue(fake.cancelAllCalled)
    }

    @Test
    fun `NoopScheduler delegate makes isAvailable false`() {
        DesktopTaskScheduler.setTestDelegate(NoopScheduler)
        assertFalse(DesktopTaskScheduler.isAvailable())
        assertFalse(DesktopTaskScheduler.enqueue(TaskRequest.onBoot(TaskId("boot"))))
        assertFalse(DesktopTaskScheduler.isScheduled(TaskId("boot")))
        assertNull(DesktopTaskScheduler.getTaskInfo(TaskId("boot")))
        assertTrue(DesktopTaskScheduler.getAllTasks().isEmpty())
    }

    @Test
    fun `resetDelegate restores the platform backend`() {
        DesktopTaskScheduler.setTestDelegate(NoopScheduler)
        assertFalse(DesktopTaskScheduler.isAvailable())
        DesktopTaskScheduler.resetDelegate()
        // macOS/Linux backends are not NoopScheduler; Windows without the native lib is.
        val afterReset = DesktopTaskScheduler.isAvailable()
        DesktopTaskScheduler.setTestDelegate(RecordingScheduler())
        assertTrue(DesktopTaskScheduler.isAvailable())
        DesktopTaskScheduler.resetDelegate()
        assertEquals(afterReset, DesktopTaskScheduler.isAvailable())
    }

    private class RecordingScheduler : PlatformScheduler {
        val enqueued = mutableListOf<TaskRequest>()
        val cancelled = mutableListOf<TaskId>()
        var cancelAllCalled: Boolean = false
        private val tasks = mutableMapOf<TaskId, TaskRequest>()

        override fun enqueue(request: TaskRequest): Boolean {
            enqueued += request
            tasks[request.taskId] = request
            return true
        }

        override fun cancel(taskId: TaskId): Boolean {
            cancelled += taskId
            return tasks.remove(taskId) != null
        }

        override fun cancelAll() {
            cancelAllCalled = true
            tasks.clear()
        }

        override fun isScheduled(taskId: TaskId): Boolean = taskId in tasks

        override fun getTaskInfo(taskId: TaskId): TaskInfo? =
            if (taskId in tasks) TaskInfo(taskId, TaskState.SCHEDULED) else null

        override fun getAllTasks(): List<TaskInfo> = tasks.keys.map { TaskInfo(it, TaskState.SCHEDULED) }
    }
}
