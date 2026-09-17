package dev.nucleusframework.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TaskIdTest {
    @Test
    fun `accepts letters digits underscore and hyphen`() {
        val id = TaskId("Sync_Job-42")
        assertEquals("Sync_Job-42", id.value)
        assertEquals("Sync_Job-42", id.toString())
    }

    @Test
    fun `value equality`() {
        assertEquals(TaskId("sync"), TaskId("sync"))
        assertNotEquals(TaskId("sync"), TaskId("backup"))
    }

    @Test
    fun `rejects empty string`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                TaskId("")
            }
        assertEquals("taskId must not be empty", error.message)
    }

    @Test
    fun `rejects characters outside the allowed set`() {
        val invalid = listOf("has space", "dot.id", "slash/id", "colon:id", "plus+id", "ünicode")
        for (value in invalid) {
            val error =
                assertFailsWith<IllegalArgumentException>(value) {
                    TaskId(value)
                }
            assertEquals("taskId must match [a-zA-Z0-9_-]+, got '$value'", error.message)
        }
    }
}
