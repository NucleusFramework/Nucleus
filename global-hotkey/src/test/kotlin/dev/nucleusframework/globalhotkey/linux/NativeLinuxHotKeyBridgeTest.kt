package dev.nucleusframework.globalhotkey.linux

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyModifier
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLinuxHotKeyBridgeTest {
    @AfterTest
    fun tearDown() {
        GlobalHotKeyManager.shutdown()
    }

    @Test
    fun `linux initialize register and native callback reach the listener`() {
        if (Platform.Current != Platform.Linux || !NativeLinuxHotKeyBridge.isLoaded) return
        if (!GlobalHotKeyManager.initialize()) {
            assertTrue(GlobalHotKeyManager.lastError != null)
            return
        }
        val fired = AtomicInteger(0)
        val handle =
            GlobalHotKeyManager.register(
                keyCode = KeyEvent.VK_F21,
                modifiers = HotKeyModifier.CONTROL.nativeFlag,
                description = "kover-linux",
            ) { _, _ -> fired.incrementAndGet() }
        if (handle == -1L) {
            assertTrue(GlobalHotKeyManager.lastError != null)
            return
        }
        try {
            val shortcut = GlobalHotKeyManager.portalShortcutId(handle)
            if (shortcut != null) {
                assertTrue(shortcut.startsWith("nucleus_"))
            }
            NativeLinuxHotKeyBridge.onHotKey(handle, KeyEvent.VK_F21, HotKeyModifier.CONTROL.nativeFlag)
            assertEquals(1, fired.get())
            NativeLinuxHotKeyBridge.onHotKey(9_999_999L, KeyEvent.VK_F21, 0)
            assertEquals(1, fired.get())
            assertTrue(GlobalHotKeyManager.commitRegistrations() || GlobalHotKeyManager.lastError != null)
        } finally {
            assertTrue(GlobalHotKeyManager.unregister(handle))
        }
    }
}
