package dev.nucleusframework.scheduler

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskDataTest {
    @Serializable
    private data class Payload(
        val name: String,
        val count: Int,
        val extra: String = "default",
    )

    @Test
    fun `empty data reports empty and decodes to null`() {
        assertTrue(TaskData.EMPTY.isEmpty())
        assertFalse(TaskData.EMPTY.isNotEmpty())
        assertNull(TaskData.EMPTY.decode<Payload>())
        assertEquals("TaskData(<empty>)", TaskData.EMPTY.toString())
        assertEquals(0, TaskData.EMPTY.hashCode())
    }

    @Test
    fun `of encodes and decode round-trips`() {
        val data = TaskData.of(Payload(name = "sync", count = 3))
        assertTrue(data.isNotEmpty())
        assertFalse(data.isEmpty())
        assertEquals(Payload(name = "sync", count = 3), data.decode<Payload>())
        assertTrue(data.toString().contains("sync"))
    }

    @Test
    fun `of with explicit serializer matches reified of`() {
        val serializer = Payload.serializer()
        val viaExplicit = TaskData.of(Payload(name = "a", count = 1), serializer)
        val viaReified = TaskData.of(Payload(name = "a", count = 1))
        assertEquals(viaReified, viaExplicit)
        assertEquals(viaReified.hashCode(), viaExplicit.hashCode())
        assertEquals(Payload(name = "a", count = 1), viaExplicit.decode(serializer))
    }

    @Test
    fun `unknown JSON keys are ignored on decode`() {
        val json = """{"name":"x","count":2,"legacy":true}"""
        val data = TaskData(json)
        assertEquals(Payload(name = "x", count = 2), data.decode<Payload>())
    }

    @Test
    fun `equality is based on the raw JSON`() {
        val a = TaskData.of(Payload(name = "a", count = 1))
        val b = TaskData.of(Payload(name = "a", count = 1))
        val c = TaskData.of(Payload(name = "b", count = 1))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertFalse(a.equals("not-task-data"))
        assertEquals(false, a.equals(null))
    }
}
