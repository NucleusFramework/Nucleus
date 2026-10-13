package dev.nucleusframework.globalhotkey.windows

import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.globalhotkey.HotKeyListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hotkey presses must reach the host's UI thread through [NucleusUiThread],
 * not run on the native message-loop thread that received them (issue #310's rule,
 * which every other native-callback module already follows).
 */
class WindowsHotKeyUiMarshalTest {
    @AfterTest
    fun tearDown() {
        NativeWindowsHotKeyBridge.clearListeners()
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `presses are marshalled through the registered ui executor`() {
        val ranOn = AtomicReference<String?>(null)
        val received = AtomicReference<Pair<Int, Int>?>(null)
        val latch = CountDownLatch(1)
        NucleusUiThread.setExecutor { runnable -> thread(name = UI_THREAD_NAME) { runnable.run() } }
        val id =
            NativeWindowsHotKeyBridge.registerListener(
                HotKeyListener { keyCode, modifiers ->
                    received.set(keyCode to modifiers)
                    ranOn.set(Thread.currentThread().name)
                    latch.countDown()
                },
            )

        // Native delivers this from its own message-loop thread.
        thread(name = "win32-loop-stub") { NativeWindowsHotKeyBridge.onHotKey(id, 0x7B, 0x2) }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "press was not delivered")
        assertEquals(0x7B to 0x2, received.get())
        assertEquals(UI_THREAD_NAME, ranOn.get())
    }

    @Test
    fun `a press still queued when the hotkey is unregistered is dropped`() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        NucleusUiThread.setExecutor { queued += it }
        val fired = AtomicReference<Int?>(null)
        val id = NativeWindowsHotKeyBridge.registerListener(HotKeyListener { keyCode, _ -> fired.set(keyCode) })

        NativeWindowsHotKeyBridge.onHotKey(id, 0x7B, 0)
        NativeWindowsHotKeyBridge.removeListener(id)
        queued.forEach(Runnable::run)

        assertEquals(1, queued.size)
        assertNull(fired.get())
    }

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
    }
}
