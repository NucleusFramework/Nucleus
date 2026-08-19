package dev.nucleusframework.globalhotkey

import dev.nucleusframework.core.runtime.Platform
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalHotKeyWindowsRegisterTest {
    private val handles = mutableListOf<Long>()

    @AfterTest
    fun tearDown() {
        handles.forEach { GlobalHotKeyManager.unregister(it) }
        GlobalHotKeyManager.shutdown()
    }

    @Test
    fun `windows initialize registers and unregisters keys including media`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(GlobalHotKeyManager.isAvailable, "nucleus_global_hotkey must load on Windows")
        assertTrue(GlobalHotKeyManager.initialize(), GlobalHotKeyManager.lastError)

        val first =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F13,
                modifiers = HotKeyModifier.CONTROL.nativeFlag,
            ) { _, _ -> }
        assertTrue(first != -1L, "register F13: ${GlobalHotKeyManager.lastError}")
        handles += first
        assertTrue(GlobalHotKeyManager.unregister(first), GlobalHotKeyManager.lastError)
        handles.remove(first)
        assertFalse(GlobalHotKeyManager.unregister(first))

        val media = GlobalHotKeyManager.register(MediaKey.PLAY_PAUSE) { _, _ -> }
        if (media != -1L) {
            handles += media
            assertTrue(GlobalHotKeyManager.unregister(media), GlobalHotKeyManager.lastError)
            handles.remove(media)
        } else {
            assertTrue(GlobalHotKeyManager.lastError != null)
        }

        assertTrue(
            GlobalHotKeyManager.commitRegistrations() || GlobalHotKeyManager.lastError != null,
        )
        GlobalHotKeyManager.shutdown()
        GlobalHotKeyManager.shutdown()
    }
}
