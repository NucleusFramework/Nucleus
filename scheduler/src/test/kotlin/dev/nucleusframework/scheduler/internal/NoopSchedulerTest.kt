package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(InternalSchedulerApi::class)
class NoopSchedulerTest {
    @Test
    fun `every operation is a silent no-op`() {
        val request = TaskRequest.periodic(TaskId("ghost"), 1.hours)

        assertFalse(NoopScheduler.enqueue(request))
        assertFalse(NoopScheduler.cancel(TaskId("ghost")))
        NoopScheduler.cancelAll()
        assertFalse(NoopScheduler.isScheduled(TaskId("ghost")))
        assertNull(NoopScheduler.getTaskInfo(TaskId("ghost")))
        assertTrue(NoopScheduler.getAllTasks().isEmpty())
        assertEquals(emptyList(), NoopScheduler.getAllTasks())
    }
}
