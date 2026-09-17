package dev.nucleusframework.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TaskRegistryTest {
    private class ProbeTask : DesktopTask {
        override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Success
    }

    @Test
    fun `create returns a fresh instance from the registered factory`() {
        val id = TaskId("probe")
        val registry =
            TaskRegistry
                .Builder()
                .register(id) { ProbeTask() }
                .build()

        val first = registry.create(id)
        val second = registry.create(id)
        assertTrue(first is ProbeTask)
        assertTrue(second is ProbeTask)
        assertNotSame(first, second)
        assertEquals(setOf(id), registry.registeredIds())
    }

    @Test
    fun `create throws TaskNotFoundException for unknown ids`() {
        val registry = TaskRegistry.Builder().build()
        val error =
            assertFailsWith<TaskNotFoundException> {
                registry.create(TaskId("missing"))
            }
        assertEquals("No task registered for id 'missing'", error.message)
        assertTrue(registry.registeredIds().isEmpty())
    }

    @Test
    fun `later register for the same id replaces the factory`() {
        val id = TaskId("swap")
        val registry =
            TaskRegistry
                .Builder()
                .register(id) { ProbeTask() }
                .register(id) {
                    object : DesktopTask {
                        override suspend fun doWork(context: TaskContext): TaskResult = TaskResult.Retry("new")
                    }
                }.build()

        val result = kotlinx.coroutines.runBlocking { registry.create(id).doWork(TaskContext(id)) }
        assertEquals(TaskResult.Retry("new"), result)
    }
}
