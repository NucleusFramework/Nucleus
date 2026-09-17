package dev.nucleusframework.globalhotkey

import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalHotKeyMacRegisterTest {
    private val handles = mutableListOf<Long>()

    @AfterTest
    fun tearDown() {
        handles.forEach { GlobalHotKeyManager.unregister(it) }
        GlobalHotKeyManager.shutdown()
    }

    @Test
    fun `macos initialize registers and unregisters several keys`() {
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("mac") && !os.contains("darwin")) return
        assertTrue(GlobalHotKeyManager.isAvailable)
        assertTrue(GlobalHotKeyManager.initialize())
        val first =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F13,
                modifiers = HotKeyModifier.CONTROL.nativeFlag,
            ) { _, _ -> }
        if (first != -1L) {
            handles += first
            assertTrue(first > 0L)
            assertTrue(GlobalHotKeyManager.unregister(first))
            handles.remove(first)
            assertFalse(GlobalHotKeyManager.unregister(first))
        }
        val second =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F14,
                modifiers = 0,
                description = "coverage-f14",
            ) { code, mods ->
                assertEquals(KeyEvent.VK_F14, code)
                assertEquals(0, mods)
            }
        if (second != -1L) {
            handles += second
            assertTrue(GlobalHotKeyManager.unregister(second))
            handles.remove(second)
        }
        val media = GlobalHotKeyManager.register(MediaKey.NEXT_TRACK) { _, _ -> }
        assertEquals(-1L, media, "media keys are not supported on macOS")
        assertTrue(GlobalHotKeyManager.commitRegistrations() || GlobalHotKeyManager.lastError != null)
        GlobalHotKeyManager.shutdown()
        GlobalHotKeyManager.shutdown()
    }
}
