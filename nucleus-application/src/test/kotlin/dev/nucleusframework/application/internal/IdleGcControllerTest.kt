package dev.nucleusframework.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleGcControllerTest {
    @Test
    fun `registering an unfocused window does not schedule gc`() {
        val c = IdleGcController()
        c.register("w", focused = false, minimized = false)
        assertEquals(IdleGcCommand.NoChange, c.update("w", focused = false, minimized = false))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `unfocus schedules a deferred collection`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        assertEquals(IdleGcCommand.Debounce, c.update("w", focused = false, minimized = false))
        assertTrue(c.shouldRunDeferredGc())
    }

    @Test
    fun `refocus before the delay cancels deferred collection`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        assertEquals(IdleGcCommand.Debounce, c.update("w", focused = false, minimized = false))
        assertEquals(IdleGcCommand.Cancel, c.update("w", focused = true, minimized = false))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `minimize collects immediately`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        assertEquals(IdleGcCommand.CollectNow, c.update("w", focused = false, minimized = true))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `minimize of a still-focused window collects immediately`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        assertEquals(IdleGcCommand.CollectNow, c.update("w", focused = true, minimized = true))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `unfocus then minimize upgrades debounce to immediate`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        assertEquals(IdleGcCommand.Debounce, c.update("w", focused = false, minimized = false))
        assertEquals(IdleGcCommand.CollectNow, c.update("w", focused = false, minimized = true))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `second window still focused cancels idle gc`() {
        val c = IdleGcController()
        c.register("a", focused = true, minimized = false)
        c.register("b", focused = false, minimized = false)
        assertEquals(IdleGcCommand.Cancel, c.update("b", focused = true, minimized = false))
        assertEquals(IdleGcCommand.Cancel, c.update("a", focused = false, minimized = false))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `last focused window unfocusing schedules deferred collection`() {
        val c = IdleGcController()
        c.register("a", focused = true, minimized = false)
        c.register("b", focused = false, minimized = false)
        c.update("b", focused = true, minimized = false)
        c.update("a", focused = false, minimized = false)
        assertEquals(IdleGcCommand.Debounce, c.update("b", focused = false, minimized = false))
        assertTrue(c.shouldRunDeferredGc())
    }

    @Test
    fun `minimize is skipped while another window is interacting`() {
        val c = IdleGcController()
        c.register("a", focused = true, minimized = false)
        c.register("b", focused = false, minimized = false)
        assertEquals(IdleGcCommand.Cancel, c.update("b", focused = false, minimized = true))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `dialog focus keeps the app interacting`() {
        val c = IdleGcController()
        c.register("window", focused = true, minimized = false)
        c.register("dialog", focused = false, minimized = false)
        c.update("window", focused = false, minimized = false)
        assertEquals(IdleGcCommand.Cancel, c.update("dialog", focused = true, minimized = false))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `unregistering the last window cancels pending collection`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        c.update("w", focused = false, minimized = false)
        assertEquals(IdleGcCommand.Cancel, c.unregister("w"))
        assertFalse(c.shouldRunDeferredGc())
    }

    @Test
    fun `update after unregister is ignored`() {
        val c = IdleGcController()
        c.register("w", focused = true, minimized = false)
        c.unregister("w")
        assertEquals(IdleGcCommand.NoChange, c.update("w", focused = false, minimized = true))
    }
}
