package dev.nucleusframework.launcher.linux

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
 * Quicklist clicks must reach the host's UI thread through [NucleusUiThread],
 * not the AWT EDT: under the Tao backend the EDT is not Compose's UI thread,
 * so a `SwingUtilities.invokeLater` here lands on a thread that paints nothing
 * (issue #310).
 */
class LinuxQuicklistUiMarshalTest {
    @AfterTest
    fun tearDown() {
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `item clicks are marshalled through the registered ui executor`() {
        val path = "/dev/nucleusframework/test/quicklist"
        val ranOn = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        NucleusUiThread.setExecutor { runnable ->
            thread(name = UI_THREAD_NAME) { runnable.run() }
        }
        val quicklist = LinuxQuicklist(path)
        quicklist.listener =
            LinuxQuicklist.Listener {
                ranOn.set(Thread.currentThread().name)
                latch.countDown()
            }
        LinuxQuicklist.register(path, quicklist)
        try {
            // Native delivers this from the dbusmenu GDBus thread, never the UI one.
            thread(name = "dbusmenu-stub") { LinuxQuicklist.onItemEvent(path, 7) }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "click was not delivered")
            assertEquals(UI_THREAD_NAME, ranOn.get())
        } finally {
            LinuxQuicklist.unregister(path)
        }
    }

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
    }
}
