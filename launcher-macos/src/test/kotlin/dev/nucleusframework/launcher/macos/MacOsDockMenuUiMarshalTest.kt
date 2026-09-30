package dev.nucleusframework.launcher.macos

import dev.nucleusframework.core.runtime.NucleusUiThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dock menu clicks must reach the host's UI thread through [NucleusUiThread],
 * not the AWT EDT: under the Tao backend the EDT is not Compose's UI thread,
 * so a `SwingUtilities.invokeLater` here lands on a thread that paints nothing
 * (issue #310).
 */
class MacOsDockMenuUiMarshalTest {
    @AfterTest
    fun tearDown() {
        MacOsDockMenu.listener = null
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `item clicks are marshalled through the registered ui executor`() {
        val ranOn = AtomicReference<String?>(null)
        val clicked = AtomicReference<Int?>(null)
        val latch = CountDownLatch(1)
        NucleusUiThread.setExecutor { runnable ->
            thread(name = UI_THREAD_NAME) { runnable.run() }
        }
        MacOsDockMenu.listener =
            DockMenuListener { itemId ->
                clicked.set(itemId)
                ranOn.set(Thread.currentThread().name)
                latch.countDown()
            }
        // Native delivers this from the AppKit main thread, never the AWT EDT.
        thread(name = "appkit-stub") { NativeMacOsDockMenuBridge.onMenuItemClicked(42) }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "click was not delivered")
        assertEquals(42, clicked.get())
        assertEquals(UI_THREAD_NAME, ranOn.get())
    }

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
    }
}
