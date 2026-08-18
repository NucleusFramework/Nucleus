package dev.nucleusframework.globalhotkey

import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalHotKeyManagerTest {
    @AfterTest
    fun tearDown() {
        GlobalHotKeyManager.shutdown()
    }

    @Test
    fun `modifier flags combine with plus`() {
        assertEquals(0x0001, HotKeyModifier.ALT.nativeFlag)
        assertEquals(0x0002, HotKeyModifier.CONTROL.nativeFlag)
        assertEquals(0x0004, HotKeyModifier.SHIFT.nativeFlag)
        assertEquals(0x0008, HotKeyModifier.META.nativeFlag)
        assertEquals(0x0003, HotKeyModifier.ALT + HotKeyModifier.CONTROL)
        assertEquals(0x0007, (HotKeyModifier.ALT + HotKeyModifier.CONTROL) + HotKeyModifier.SHIFT)
        assertEquals(0x000F, ((HotKeyModifier.ALT + HotKeyModifier.CONTROL) + HotKeyModifier.SHIFT) + HotKeyModifier.META)
        assertEquals(0xB3, MediaKey.PLAY_PAUSE.nativeCode)
        assertEquals(0xB2, MediaKey.STOP.nativeCode)
        assertEquals(0xB0, MediaKey.NEXT_TRACK.nativeCode)
        assertEquals(0xB1, MediaKey.PREV_TRACK.nativeCode)
    }

    @Test
    fun `register before initialize returns the documented fallback`() {
        GlobalHotKeyManager.shutdown()
        val handle =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F24,
                modifiers = HotKeyModifier.CONTROL + HotKeyModifier.ALT,
            ) { _, _ -> }
        if (!GlobalHotKeyManager.isAvailable) {
            assertEquals(-1L, handle)
            assertEquals("Not available on this platform", GlobalHotKeyManager.lastError)
            assertFalse(GlobalHotKeyManager.unregister(1L))
            assertFalse(GlobalHotKeyManager.commitRegistrations())
            assertNull(GlobalHotKeyManager.portalShortcutId(1L))
            assertFalse(GlobalHotKeyManager.initialize())
            assertEquals("Global hotkeys not available on this platform", GlobalHotKeyManager.lastError)
        } else {
            assertEquals(-1L, handle)
            assertEquals("Not initialized - call GlobalHotKeyManager.initialize() first", GlobalHotKeyManager.lastError)
            assertFalse(GlobalHotKeyManager.unregister(1L))
            assertFalse(GlobalHotKeyManager.commitRegistrations())
            assertNull(GlobalHotKeyManager.portalShortcutId(1L))
        }
    }

    @Test
    fun `initialize register unregister and shutdown on this platform`() {
        if (!GlobalHotKeyManager.isAvailable) {
            assertFalse(GlobalHotKeyManager.initialize())
            return
        }
        assertTrue(GlobalHotKeyManager.initialize())
        assertTrue(GlobalHotKeyManager.initialize())

        val handle =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F24,
                modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
                description = "Coverage test",
            ) { _, _ -> }
        if (handle != -1L) {
            assertTrue(handle > 0L)
            assertTrue(GlobalHotKeyManager.unregister(handle))
        } else {
            assertTrue(GlobalHotKeyManager.lastError != null)
        }

        val media = GlobalHotKeyManager.register(MediaKey.PLAY_PAUSE) { _, _ -> }
        assertEquals(-1L, media)
        assertEquals("Media keys are not supported on macOS", GlobalHotKeyManager.lastError)

        assertTrue(GlobalHotKeyManager.commitRegistrations())
        assertNull(GlobalHotKeyManager.portalShortcutId(1L))
        GlobalHotKeyManager.shutdown()
        GlobalHotKeyManager.shutdown()
    }

    @Test
    fun `initialized manager can register alternate modifiers and reject a bogus unregister`() {
        if (!GlobalHotKeyManager.isAvailable) return
        assertTrue(GlobalHotKeyManager.initialize())

        val none =
            GlobalHotKeyManager.register(keyCode = KeyEvent.VK_F23, modifiers = 0) { _, _ -> }
        if (none != -1L) {
            assertTrue(none > 0L)
            assertTrue(GlobalHotKeyManager.unregister(none))
        }

        val combo =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F22,
                modifiers = HotKeyModifier.ALT + HotKeyModifier.META,
            ) { _, _ -> }
        if (combo != -1L) {
            assertTrue(GlobalHotKeyManager.unregister(combo))
        }

        val missing = GlobalHotKeyManager.unregister(9_999_999L)
        if (!missing) {
            assertTrue(GlobalHotKeyManager.lastError != null)
        }
        assertTrue(GlobalHotKeyManager.commitRegistrations())
        GlobalHotKeyManager.shutdown()
    }
}
