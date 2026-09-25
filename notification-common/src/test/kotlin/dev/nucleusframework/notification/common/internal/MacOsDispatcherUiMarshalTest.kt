package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.core.runtime.NucleusUiThread
import dev.nucleusframework.notification.DeliveredNotification
import dev.nucleusframework.notification.NotificationAction
import dev.nucleusframework.notification.NotificationResponse
import dev.nucleusframework.notification.common.DismissReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portable `notification { }` callbacks must reach the host's UI thread on
 * macOS too: `NotificationCenter` dispatches its delegate callbacks on a worker
 * pool of its own, so without marshalling an app's `onActivated` would run off
 * the UI thread here while the Linux and Windows bridges deliver it on it
 * (issue #310).
 */
class MacOsDispatcherUiMarshalTest {
    @AfterTest
    fun tearDown() {
        NucleusUiThread.setExecutor(null)
        CallbackRegistry.remove(NOTIFICATION_ID)
    }

    @Test
    fun `body clicks are marshalled through the registered ui executor`() {
        assertMarshalled(NotificationAction.DEFAULT_ACTION_IDENTIFIER) { record ->
            NotificationCallbacks(
                onActivated = record,
                onDismissed = null,
                onFailed = null,
                buttonCallbacks = emptyMap(),
            )
        }
    }

    @Test
    fun `button clicks are marshalled through the registered ui executor`() {
        assertMarshalled("btn_0") { record ->
            NotificationCallbacks(
                onActivated = null,
                onDismissed = null,
                onFailed = null,
                buttonCallbacks = mapOf("btn_0" to record),
            )
        }
    }

    @Test
    fun `dismissals are marshalled through the registered ui executor`() {
        assertMarshalled(NotificationAction.DISMISS_ACTION_IDENTIFIER) { record ->
            NotificationCallbacks(
                onActivated = null,
                onDismissed = { _: DismissReason -> record() },
                onFailed = null,
                buttonCallbacks = emptyMap(),
            )
        }
    }

    private fun assertMarshalled(
        actionIdentifier: String,
        callbacks: (record: () -> Unit) -> NotificationCallbacks,
    ) {
        val dispatcher = MacOsDispatcher.createIfAvailable() ?: return
        val ranOn = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        NucleusUiThread.setExecutor { runnable -> thread(name = UI_THREAD_NAME) { runnable.run() } }
        CallbackRegistry.register(
            NOTIFICATION_ID,
            callbacks {
                ranOn.set(Thread.currentThread().name)
                latch.countDown()
            },
        )

        // Native delivers this on a NucleusNotificationCallback pool thread.
        thread(name = "notification-callback-stub") {
            dispatcher.delegate.didReceive(
                NotificationResponse(
                    actionIdentifier = actionIdentifier,
                    notification = deliveredNotification(),
                    userText = null,
                ),
            )
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback was not delivered")
        assertEquals(UI_THREAD_NAME, ranOn.get())
    }

    private fun deliveredNotification() =
        DeliveredNotification(
            identifier = NOTIFICATION_ID,
            title = "Title",
            subtitle = "",
            body = "Body",
            date = 0,
            categoryIdentifier = "",
            threadIdentifier = "",
        )

    private companion object {
        const val UI_THREAD_NAME = "ui-thread-under-test"
        const val NOTIFICATION_ID = "ui-marshal-test"
    }
}
