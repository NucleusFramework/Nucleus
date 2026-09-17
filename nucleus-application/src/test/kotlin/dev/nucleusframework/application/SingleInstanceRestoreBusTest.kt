package dev.nucleusframework.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleInstanceRestoreBusTest {
    @Test
    fun `fire increments the restore signal`() {
        val before = SingleInstanceRestoreBus.signal.value
        SingleInstanceRestoreBus.fire()
        val after = SingleInstanceRestoreBus.signal.value
        assertEquals(before + 1, after)
        assertTrue(after > 0)
        SingleInstanceRestoreBus.fire()
        assertEquals(after + 1, SingleInstanceRestoreBus.signal.value)
    }
}
