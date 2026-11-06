package dev.nucleusframework.notification.windows

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
 * Toast callbacks must reach the host's UI thread through [NucleusUiThread],
 * not the AWT EDT: under the Tao backend the EDT is not Compose's UI thread,
 * so a `SwingUtilities.invokeLater` here lands on a thread that paints nothing
 * (issue #310).
 */
class WindowsToastUiMarshalTest {
    @AfterTest
    fun tearDown() {
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `toast callbacks are marshalled through the registered ui executor`() {
        val ranOn = AtomicReference<String?>(null)
        val latch = CountDownLatch(3)
        NucleusUiThread.setExecutor { runnable ->
            thread(name = UI_THREAD_NAME) { runnable.run() }
        }
        val listener =
            object : ToastNotificationListener {
                override fun onActivated(
                    tag: String,
                    group: String,
                    arguments: String,
                    inputs: Map<String, String>,
                ) = record()

                override fun onDismissed(
                    tag: String,
                    group: String,
                    reason: DismissalReason,
                ) = record()

                override fun onFailed(
                    tag: String,
                    group: String,
                    errorCode: Int,
                ) = record()

                private fun record() {
                    ranOn.set(Thread.currentThread().name)
                    latch.countDown()
                }
            }
        NativeWindowsNotificationBridge.addListener(listener)
        try {
            // Native delivers these from a WinRT completion thread, never the UI one.
            thread(name = "winrt-completion-stub") {
                NativeWindowsNotificationBridge.onToastActivated("t", "g", "", emptyArray(), emptyArray())
                NativeWindowsNotificationBridge.onToastDismissed("t", "g", 0)
                NativeWindowsNotificationBridge.onToastFailed("t", "g", 1)
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "callbacks were not delivered")
            assertEquals(UI_THREAD_NAME, ranOn.get())
        } finally {
            NativeWindowsNotificationBridge.removeListener(listener)
        }
    }

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
    }
}
