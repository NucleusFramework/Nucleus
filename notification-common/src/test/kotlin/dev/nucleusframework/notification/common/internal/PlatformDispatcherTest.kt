package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.common.DismissReason
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.notification.linux.CloseReason
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.DismissalReason
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformDispatcherTest {
    @Test
    fun `factory returns a macos dispatcher on this host`() {
        val dispatcher = DispatcherFactory.create()
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("mac") || os.contains("darwin")) {
            assertNotNull(dispatcher)
            assertIs<MacOsDispatcher>(dispatcher)
        }
    }

    @Test
    fun `callback registry stores gets and removes entries`() {
        val callbacks =
            NotificationCallbacks(
                onActivated = {},
                onDismissed = {},
                onFailed = {},
                buttonCallbacks = mapOf("btn_0" to {}),
            )
        CallbackRegistry.register("id-1", callbacks)
        assertEquals(callbacks, CallbackRegistry.get("id-1"))
        assertEquals(callbacks, CallbackRegistry.remove("id-1"))
        assertNull(CallbackRegistry.get("id-1"))
        assertNull(CallbackRegistry.remove("missing"))
    }

    @Test
    fun `macos dispatcher send exercises buttons dismiss category and images`() {
        val dispatcher = MacOsDispatcher.createIfAvailable() ?: return
        dispatcher.initialize()

        var failed = 0
        var activated = 0
        val withButtons =
            notification(
                title = "Title",
                message = "Body",
                largeImage = "file:///tmp/img.png",
                onActivated = { activated++ },
                onDismissed = {},
                onFailed = { failed++ },
            ) {
                button("Yes") { }
                button("No") { }
                macos {
                    interruptionLevel = InterruptionLevel.TIME_SENSITIVE
                    relevanceScore = 0.4
                    subtitle = "Sub"
                }
            }

        val first = dispatcher.send(withButtons)
        assertIs<NotificationResult.Success>(first)
        first.handle.dismiss()

        val cached =
            dispatcher.send(
                notification(title = "Again") {
                    button("Yes") { }
                    button("No") { }
                },
            )
        assertIs<NotificationResult.Success>(cached)

        val dismissOnly =
            dispatcher.send(
                notification(title = "Bye", onDismissed = {}),
            )
        assertIs<NotificationResult.Success>(dismissOnly)

        val plain = dispatcher.send(notification(title = "Plain"))
        assertIs<NotificationResult.Success>(plain)

        if (!dispatcher.isAvailable()) {
            assertTrue(failed >= 1)
        }
        assertEquals(0, activated)
    }

    @Test
    fun `linux dispatcher send returns failure when the native server is missing`() {
        val dispatcher = LinuxDispatcher.createIfAvailable() ?: return
        dispatcher.initialize()

        var failed = 0
        val result =
            dispatcher.send(
                notification(
                    title = "Linux",
                    message = "Body",
                    largeImage = "/tmp/img.png",
                    smallIcon = "/tmp/icon.png",
                    onActivated = {},
                    onFailed = { failed++ },
                ) {
                    button("Open") { }
                    linux {
                        urgency = Urgency.CRITICAL
                        category = "im.received"
                        transient = true
                        resident = false
                        expireTimeout = 2500
                    }
                },
            )

        if (dispatcher.isAvailable()) {
            assertIs<NotificationResult.Success>(result)
            result.handle.dismiss()
        } else {
            val failure = assertIs<NotificationResult.Failure>(result)
            assertEquals("Linux notification server returned 0", failure.reason)
            assertEquals(1, failed)
        }
        dispatcher.dismiss("not-a-number")
        dispatcher.dismiss("42")
    }

    @Test
    fun `windows dispatcher send returns failure when toast support is missing`() {
        val dispatcher = WindowsDispatcher.createIfAvailable() ?: return
        dispatcher.initialize()

        var failed = 0
        val result =
            dispatcher.send(
                notification(
                    title = "Win",
                    message = "Body",
                    largeImage = "https://example.com/hero.png",
                    smallIcon = "https://example.com/logo.png",
                    onActivated = {},
                    onFailed = { failed++ },
                ) {
                    button("Reply") { }
                    windows {
                        scenario = ToastScenario.URGENT
                        duration = ToastDuration.LONG
                    }
                },
            )

        if (dispatcher.isAvailable()) {
            assertIs<NotificationResult.Success>(result)
            result.handle.dismiss()
        } else {
            assertIs<NotificationResult.Failure>(result)
            assertEquals(1, failed)
        }
        dispatcher.dismiss("tag-only")
        dispatcher.dismiss("tag:group")
    }

    @Test
    fun `linux listener maps actions and close reasons`() {
        val dispatcher = LinuxDispatcher.createIfAvailable() ?: return
        val listener = listenerOf<dev.nucleusframework.notification.linux.LinuxNotificationListener>(dispatcher)

        var activated = 0
        var button = 0
        var dismissed: DismissReason? = null
        CallbackRegistry.register(
            "7",
            NotificationCallbacks(
                onActivated = { activated++ },
                onDismissed = { dismissed = it },
                onFailed = {},
                buttonCallbacks = mapOf("btn_0" to { button++ }),
            ),
        )

        listener.onActionInvoked(7, "default")
        listener.onActionInvoked(7, "btn_0")
        listener.onActionInvoked(7, "missing")
        listener.onActionInvoked(99, "default")
        listener.onClosed(7, CloseReason.EXPIRED)
        assertEquals(1, activated)
        assertEquals(1, button)
        assertEquals(DismissReason.TIMED_OUT, dismissed)

        CallbackRegistry.register(
            "8",
            NotificationCallbacks(null, { dismissed = it }, null, emptyMap()),
        )
        listener.onClosed(8, CloseReason.DISMISSED)
        assertEquals(DismissReason.USER_DISMISSED, dismissed)

        CallbackRegistry.register(
            "9",
            NotificationCallbacks(null, { dismissed = it }, null, emptyMap()),
        )
        listener.onClosed(9, CloseReason.CLOSED)
        assertEquals(DismissReason.APPLICATION, dismissed)

        CallbackRegistry.register(
            "10",
            NotificationCallbacks(null, { dismissed = it }, null, emptyMap()),
        )
        listener.onClosed(10, CloseReason.UNDEFINED)
        assertEquals(DismissReason.UNKNOWN, dismissed)

        CallbackRegistry.register(
            "11",
            NotificationCallbacks(
                onActivated = { error("activated") },
                onDismissed = { error("dismissed") },
                onFailed = null,
                buttonCallbacks = emptyMap(),
            ),
        )
        listener.onActionInvoked(11, "default")
        listener.onClosed(11, CloseReason.EXPIRED)
    }

    @Test
    fun `windows listener maps activation dismissal and failure`() {
        val dispatcher = WindowsDispatcher.createIfAvailable() ?: return
        val listener = listenerOf<dev.nucleusframework.notification.windows.ToastNotificationListener>(dispatcher)

        var activated = 0
        var button = 0
        var failed = 0
        var dismissed: DismissReason? = null
        CallbackRegistry.register(
            "t:ncm",
            NotificationCallbacks(
                onActivated = { activated++ },
                onDismissed = { dismissed = it },
                onFailed = { failed++ },
                buttonCallbacks = mapOf("btn_0" to { button++ }),
            ),
        )

        listener.onActivated("t", "ncm", "body", emptyMap())
        listener.onActivated("t", "ncm", "btn_0", emptyMap())
        listener.onActivated("missing", "ncm", "body", emptyMap())
        listener.onDismissed("t", "ncm", DismissalReason.USER_CANCELED)
        assertEquals(1, activated)
        assertEquals(1, button)
        assertEquals(DismissReason.USER_DISMISSED, dismissed)

        CallbackRegistry.register(
            "t2:ncm",
            NotificationCallbacks(null, { dismissed = it }, { failed++ }, emptyMap()),
        )
        listener.onDismissed("t2", "ncm", DismissalReason.TIMED_OUT)
        assertEquals(DismissReason.TIMED_OUT, dismissed)

        CallbackRegistry.register(
            "t3:ncm",
            NotificationCallbacks(null, { dismissed = it }, { failed++ }, emptyMap()),
        )
        listener.onDismissed("t3", "ncm", DismissalReason.APPLICATION_HIDDEN)
        assertEquals(DismissReason.APPLICATION, dismissed)

        CallbackRegistry.register(
            "t4:ncm",
            NotificationCallbacks(null, null, { failed++ }, emptyMap()),
        )
        listener.onFailed("t4", "ncm", 0x80004005.toInt())
        assertEquals(1, failed)
        listener.onFailed("missing", "ncm", 1)

        CallbackRegistry.register(
            "t5:ncm",
            NotificationCallbacks(
                onActivated = { error("activated") },
                onDismissed = { error("dismissed") },
                onFailed = { error("failed") },
                buttonCallbacks = emptyMap(),
            ),
        )
        listener.onActivated("t5", "ncm", "x", emptyMap())
        listener.onDismissed("t5", "ncm", DismissalReason.TIMED_OUT)
        CallbackRegistry.register(
            "t6:ncm",
            NotificationCallbacks(null, null, { error("failed") }, emptyMap()),
        )
        listener.onFailed("t6", "ncm", 1)
    }

    @Test
    fun `macos delegate routes default dismiss and button actions`() {
        val dispatcher = MacOsDispatcher.createIfAvailable() ?: return
        val delegate = fieldOf<dev.nucleusframework.notification.NotificationCenterDelegate>(dispatcher, "delegate")
        val presented =
            delegate.willPresent(
                dev.nucleusframework.notification.DeliveredNotification("id", "t", "s", "b", 1L, "c", "th"),
            )
        assertTrue(presented.contains(dev.nucleusframework.notification.PresentationOption.BANNER))
        assertTrue(presented.contains(dev.nucleusframework.notification.PresentationOption.SOUND))

        var activated = 0
        var button = 0
        var dismissed: DismissReason? = null
        CallbackRegistry.register(
            "mac-1",
            NotificationCallbacks(
                onActivated = { activated++ },
                onDismissed = { dismissed = it },
                onFailed = {},
                buttonCallbacks = mapOf("btn_0" to { button++ }),
            ),
        )
        val delivered =
            dev.nucleusframework.notification.DeliveredNotification("mac-1", "t", "s", "b", 1L, "c", "th")
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse(
                dev.nucleusframework.notification.NotificationAction.DEFAULT_ACTION_IDENTIFIER,
                delivered,
                null,
            ),
        )
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse("btn_0", delivered, null),
        )
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse("other", delivered, null),
        )
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse(
                dev.nucleusframework.notification.NotificationAction.DISMISS_ACTION_IDENTIFIER,
                delivered,
                null,
            ),
        )
        assertEquals(1, activated)
        assertEquals(1, button)
        assertEquals(DismissReason.USER_DISMISSED, dismissed)

        CallbackRegistry.register(
            "mac-2",
            NotificationCallbacks(
                onActivated = { error("activated") },
                onDismissed = { error("dismissed") },
                onFailed = null,
                buttonCallbacks = emptyMap(),
            ),
        )
        val boom =
            dev.nucleusframework.notification.DeliveredNotification("mac-2", "t", "s", "b", 1L, "c", "th")
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse(
                dev.nucleusframework.notification.NotificationAction.DEFAULT_ACTION_IDENTIFIER,
                boom,
                null,
            ),
        )
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse(
                dev.nucleusframework.notification.NotificationAction.DISMISS_ACTION_IDENTIFIER,
                boom,
                null,
            ),
        )
        delegate.didReceive(
            dev.nucleusframework.notification.NotificationResponse(
                "missing",
                dev.nucleusframework.notification.DeliveredNotification("nope", "", "", "", 0L, "", ""),
                null,
            ),
        )
    }

    @Test
    fun `macos and linux and windows dispatchers can be constructed`() {
        assertNotNull(MacOsDispatcher.createIfAvailable())
        assertNotNull(LinuxDispatcher.createIfAvailable())
        assertNotNull(WindowsDispatcher.createIfAvailable())
        val linux = LinuxDispatcher.createIfAvailable()!!
        val windows = WindowsDispatcher.createIfAvailable()!!
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("mac") || os.contains("darwin")) {
            assertFalse(linux.isAvailable())
            assertFalse(windows.isAvailable())
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> listenerOf(dispatcher: Any): T = fieldOf(dispatcher, "listener")

@Suppress("UNCHECKED_CAST")
private fun <T : Any> fieldOf(
    target: Any,
    name: String,
): T {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(target) as T
}
