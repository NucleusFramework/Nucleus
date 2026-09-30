package dev.nucleusframework.notification.linux

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
 * Signal callbacks must reach the host's UI thread through [NucleusUiThread],
 * not the AWT EDT: under the Tao backend the EDT is not Compose's UI thread,
 * so a `SwingUtilities.invokeLater` here lands on a thread that paints nothing
 * (issue #310).
 */
class LinuxNotificationUiMarshalTest {
    @AfterTest
    fun tearDown() {
        NucleusUiThread.setExecutor(null)
    }

    @Test
    fun `signal callbacks are marshalled through the registered ui executor`() {
        val ranOn = AtomicReference<String?>(null)
        val latch = CountDownLatch(2)
        NucleusUiThread.setExecutor { runnable ->
            thread(name = UI_THREAD_NAME) { runnable.run() }
        }
        val listener =
            object : LinuxNotificationListener {
                override fun onClosed(
                    notificationId: Int,
                    reason: CloseReason,
                ) {
                    ranOn.set(Thread.currentThread().name)
                    latch.countDown()
                }

                override fun onActionInvoked(
                    notificationId: Int,
                    actionKey: String,
                ) {
                    ranOn.set(Thread.currentThread().name)
                    latch.countDown()
                }
            }
        NativeLinuxNotificationBridge.addListener(listener)
        try {
            // Native delivers these from a GDBus signal thread, never the UI one.
            thread(name = "gdbus-signal-stub") {
                NativeLinuxNotificationBridge.onActionInvoked(1, NotificationAction.DEFAULT_KEY)
                NativeLinuxNotificationBridge.onNotificationClosed(1, CloseReason.EXPIRED.value)
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "callbacks were not delivered")
            assertEquals(UI_THREAD_NAME, ranOn.get())
        } finally {
            NativeLinuxNotificationBridge.removeListener(listener)
        }
    }

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
    }
}
