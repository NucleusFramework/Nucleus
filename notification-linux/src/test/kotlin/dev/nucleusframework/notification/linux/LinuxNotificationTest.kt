package dev.nucleusframework.notification.linux

import dev.nucleusframework.freedesktop.icons.FreedesktopIcon
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxNotificationTest {
    @Test
    fun `urgency and close reason map known and unknown values`() {
        assertEquals(0, Urgency.LOW.value)
        assertEquals(1, Urgency.NORMAL.value)
        assertEquals(2, Urgency.CRITICAL.value)
        assertEquals(Urgency.LOW, Urgency.fromValue(0))
        assertEquals(Urgency.NORMAL, Urgency.fromValue(1))
        assertEquals(Urgency.CRITICAL, Urgency.fromValue(2))
        assertEquals(Urgency.NORMAL, Urgency.fromValue(99))

        assertEquals(CloseReason.EXPIRED, CloseReason.fromValue(1))
        assertEquals(CloseReason.DISMISSED, CloseReason.fromValue(2))
        assertEquals(CloseReason.CLOSED, CloseReason.fromValue(3))
        assertEquals(CloseReason.UNDEFINED, CloseReason.fromValue(4))
        assertEquals(CloseReason.UNDEFINED, CloseReason.fromValue(0))
    }

    @Test
    fun `notification model keeps hints actions and expiry`() {
        val hints =
            NotificationHints(
                urgency = Urgency.CRITICAL,
                category = "device.error",
                desktopEntry = "myapp",
                imagePath = FreedesktopIcon.Custom("/tmp/img.png"),
                actionIcons = true,
                soundFile = "/tmp/ping.wav",
                soundName = NotificationSound.Alert.DIALOG_ERROR,
                suppressSound = false,
                resident = true,
                transient = true,
                x = 10,
                y = 20,
            )
        val notification =
            Notification(
                appName = "Demo",
                replacesId = 3,
                appIcon = FreedesktopIcon.Custom("dialog-information"),
                summary = "Build failed",
                body = "<b>see logs</b>",
                actions =
                    listOf(
                        NotificationAction(NotificationAction.DEFAULT_KEY, ""),
                        NotificationAction("open", "Open"),
                    ),
                hints = hints,
                expireTimeout = 0,
            )
        assertEquals("Demo", notification.appName)
        assertEquals(3, notification.replacesId)
        assertEquals("Build failed", notification.summary)
        assertEquals(0, notification.expireTimeout)
        assertEquals("default", notification.actions.first().key)
        assertEquals(Urgency.CRITICAL, notification.hints.urgency)
        assertEquals("device.error", notification.hints.category)
        assertEquals(true, notification.hints.transient)
        assertEquals(true, notification.hints.resident)
        assertEquals("dialog-error", notification.hints.soundName?.value)
    }

    @Test
    fun `image data equals and hashcode consider pixels`() {
        val pixels = byteArrayOf(1, 2, 3)
        val first =
            ImageData(
                width = 1,
                height = 1,
                rowstride = 3,
                hasAlpha = false,
                data = pixels,
            )
        val same =
            ImageData(
                width = 1,
                height = 1,
                rowstride = 3,
                hasAlpha = false,
                data = byteArrayOf(1, 2, 3),
            )
        val different =
            ImageData(
                width = 1,
                height = 1,
                rowstride = 4,
                hasAlpha = true,
                data = byteArrayOf(1, 2, 3, 4),
            )
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertEquals(first, first)
        assertNotEquals(first, different)
        assertNotEquals(first, Any())
        assertEquals(3, first.channels)
        assertEquals(4, different.channels)
        assertEquals(8, first.bitsPerSample)
    }

    @Test
    fun `server information and default notification values`() {
        val info = ServerInformation("name", "vendor", "1.0", "1.2")
        assertEquals("name", info.name)
        assertEquals("vendor", info.vendor)
        assertEquals("1.0", info.version)
        assertEquals("1.2", info.specVersion)

        val defaults = Notification(summary = "Hi")
        assertEquals("", defaults.appName)
        assertEquals(0, defaults.replacesId)
        assertNull(defaults.appIcon)
        assertEquals("", defaults.body)
        assertTrue(defaults.actions.isEmpty())
        assertEquals(-1, defaults.expireTimeout)
    }

    @Test
    fun `notification sound catalog exposes spec names`() {
        assertEquals("x-custom", NotificationSound.Custom("x-custom").value)
        assertEquals("network-connectivity-lost", NotificationSound.Alert.NETWORK_CONNECTIVITY_LOST.value)
        assertEquals("message-new-email", NotificationSound.Notification.MESSAGE_NEW_EMAIL.value)
        assertEquals("camera-shutter", NotificationSound.Action.CAMERA_SHUTTER.value)
        assertEquals("window-close", NotificationSound.InputFeedback.WINDOW_CLOSE.value)
        assertEquals("game-over-winner", NotificationSound.Game.GAME_OVER_WINNER.value)
        assertTrue(NotificationSound.Alert.entries.isNotEmpty())
        assertTrue(NotificationSound.Notification.entries.isNotEmpty())
        assertTrue(NotificationSound.Action.entries.isNotEmpty())
        assertTrue(NotificationSound.InputFeedback.entries.isNotEmpty())
        assertTrue(NotificationSound.Game.entries.isNotEmpty())
    }

    @Test
    fun `center is a no-op when the native library is missing`() {
        assertFalse(LinuxNotificationCenter.isAvailable)
        val id =
            LinuxNotificationCenter.notify(
                Notification(
                    summary = "Hi",
                    body = "there",
                    actions = listOf(NotificationAction("open", "Open")),
                    hints =
                        NotificationHints(
                            urgency = Urgency.LOW,
                            category = "im",
                            imageData =
                                ImageData(
                                    width = 1,
                                    height = 1,
                                    rowstride = 3,
                                    hasAlpha = false,
                                    data = byteArrayOf(1, 2, 3),
                                ),
                            transient = false,
                            resident = false,
                            x = 1,
                            y = 2,
                        ),
                ),
            )
        assertEquals(0, id)
        LinuxNotificationCenter.closeNotification(1)
        assertTrue(LinuxNotificationCenter.getCapabilities().isEmpty())
        assertNull(LinuxNotificationCenter.getServerInformation())
    }

    @Test
    fun `listeners can be added and native callbacks dispatch on the edt`() {
        val latch = CountDownLatch(3)
        var closed: Pair<Int, CloseReason>? = null
        var action: Pair<Int, String>? = null
        var activation: Pair<Int, String>? = null
        val listener =
            object : LinuxNotificationListener {
                override fun onClosed(
                    notificationId: Int,
                    reason: CloseReason,
                ) {
                    closed = notificationId to reason
                    latch.countDown()
                }

                override fun onActionInvoked(
                    notificationId: Int,
                    actionKey: String,
                ) {
                    action = notificationId to actionKey
                    latch.countDown()
                }

                override fun onActivationToken(
                    notificationId: Int,
                    token: String,
                ) {
                    activation = notificationId to token
                    latch.countDown()
                }
            }
        LinuxNotificationCenter.addListener(listener)
        try {
            NativeLinuxNotificationBridge.onNotificationClosed(5, 2)
            NativeLinuxNotificationBridge.onActionInvoked(5, "default")
            NativeLinuxNotificationBridge.onActivationToken(5, "tok")
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(5 to CloseReason.DISMISSED, closed)
            assertEquals(5 to "default", action)
            assertEquals(5 to "tok", activation)
        } finally {
            LinuxNotificationCenter.removeListener(listener)
        }
    }

    @Test
    fun `listener default methods are no-ops`() {
        val listener = object : LinuxNotificationListener {}
        listener.onClosed(1, CloseReason.EXPIRED)
        listener.onActionInvoked(1, "default")
        listener.onActivationToken(1, "tok")
    }
}
