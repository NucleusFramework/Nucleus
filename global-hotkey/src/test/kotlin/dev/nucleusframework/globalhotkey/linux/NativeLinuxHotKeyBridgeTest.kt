package dev.nucleusframework.globalhotkey.linux

import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.globalhotkey.HotKeyEvent
import dev.nucleusframework.globalhotkey.HotKeyEventListener
import dev.nucleusframework.globalhotkey.HotKeyModifier
import dev.nucleusframework.globalhotkey.HotKeyState
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeLinuxHotKeyBridgeTest {
    @AfterTest
    fun tearDown() {
        GlobalHotKeyManager.shutdown()
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `linux initialize register and native callback reach the listener`() {
        if (Platform.Current != Platform.Linux || !NativeLinuxHotKeyBridge.isLoaded) return
        if (!GlobalHotKeyManager.initialize()) {
            assertTrue(GlobalHotKeyManager.lastError != null)
            return
        }
        // Run posted callbacks inline so the native-callback assertions stay synchronous.
        NucleusUiThread.setExecutor { it.run() }
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
            NativeLinuxHotKeyBridge.onHotKeyEvent(
                handle,
                KeyEvent.VK_F21,
                HotKeyModifier.CONTROL.nativeFlag,
                pressed = true,
                timestamp = 1L,
                activationToken = null,
            )
            assertEquals(1, fired.get())
            // A release never reaches a press-only listener.
            NativeLinuxHotKeyBridge.onHotKeyEvent(handle, KeyEvent.VK_F21, 0, false, 2L, null)
            assertEquals(1, fired.get())
            NativeLinuxHotKeyBridge.onHotKeyEvent(9_999_999L, KeyEvent.VK_F21, 0, true, 3L, null)
            assertEquals(1, fired.get())
            assertTrue(GlobalHotKeyManager.commitRegistrations() || GlobalHotKeyManager.lastError != null)
        } finally {
            assertTrue(GlobalHotKeyManager.unregister(handle))
        }
    }

    @Test
    fun `events carry state, repeat flag, timestamp and activation token`() {
        // Run posted callbacks inline so the native-callback assertions stay synchronous.
        NucleusUiThread.setExecutor { it.run() }
        val events = mutableListOf<HotKeyEvent>()
        val id = NativeLinuxHotKeyBridge.registerListener(HotKeyEventListener { events += it })
        try {
            fun native(
                pressed: Boolean,
                timestamp: Long,
                token: String? = null,
            ) = NativeLinuxHotKeyBridge.onHotKeyEvent(id, KeyEvent.VK_SPACE, 3, pressed, timestamp, token)

            native(pressed = true, timestamp = 10, token = "tok-1")
            native(pressed = false, timestamp = 20)
            native(pressed = true, timestamp = 30, token = "tok-2")
            // GNOME's auto-repeat: another Activated while held.
            native(pressed = true, timestamp = 60, token = "tok-3")
            native(pressed = false, timestamp = 70)
            native(pressed = true, timestamp = 80)

            assertEquals(
                listOf(
                    HotKeyState.PRESSED,
                    HotKeyState.RELEASED,
                    HotKeyState.PRESSED,
                    HotKeyState.PRESSED,
                    HotKeyState.RELEASED,
                    HotKeyState.PRESSED,
                ),
                events.map { it.state },
            )
            assertEquals(listOf(false, false, false, true, false, false), events.map { it.isRepeat })
            assertEquals(listOf(10L, 20L, 30L, 60L, 70L, 80L), events.map { it.timestamp })
            assertEquals(listOf("tok-1", null, "tok-2", "tok-3", null, null), events.map { it.activationToken })
            assertEquals(KeyEvent.VK_SPACE, events.first().keyCode)
            assertEquals(3, events.first().modifiers)
            assertFalse(events.first().toString().contains("tok-1"), "the token must not be logged")
        } finally {
            NativeLinuxHotKeyBridge.removeListener(id)
        }
    }

    @Test
    fun `a press a second after the last one is new even without a release`() {
        NucleusUiThread.setExecutor { it.run() }
        val events = mutableListOf<HotKeyEvent>()
        val id = NativeLinuxHotKeyBridge.registerListener(HotKeyEventListener { events += it })
        try {
            // A portal that never emits Deactivated: presses only.
            NativeLinuxHotKeyBridge.onHotKeyEvent(id, KeyEvent.VK_SPACE, 0, true, 1, null)
            NativeLinuxHotKeyBridge.onHotKeyEvent(id, KeyEvent.VK_SPACE, 0, true, 2, null)
            Thread.sleep(1_100)
            NativeLinuxHotKeyBridge.onHotKeyEvent(id, KeyEvent.VK_SPACE, 0, true, 3, null)
            assertEquals(listOf(false, true, false), events.map { it.isRepeat })
        } finally {
            NativeLinuxHotKeyBridge.removeListener(id)
        }
    }
}
