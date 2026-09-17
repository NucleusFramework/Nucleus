package dev.nucleusframework.taskbarprogress.tao

import dev.nucleusframework.taskbarprogress.TaskbarProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class TaoTaskbarProgressTest {
    @Test
    fun `availability matches the shared taskbar backend`() {
        assertEquals(TaskbarProgress.isAvailable(), TaoTaskbarProgress.isAvailable())
        assertEquals(TaskbarProgress.isAvailable(), NucleusTaskbarProgress.isAvailable())
    }
}
