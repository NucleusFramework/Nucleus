package dev.nucleusframework.notification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationCenterApiTest {
    @Test
    fun `isAvailable is false outside a packaged app bundle`() {
        // This test JVM is not inside Foo.app/Contents/ — notifications must
        // refuse to talk to UNUserNotificationCenter.
        assertFalse(NotificationCenter.isAvailable)
    }

    @Test
    fun `authorization request reports the missing-bundle error`() {
        val error = AtomicReference<String?>()
        val granted = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT)) { ok, err ->
            granted.set(ok)
            error.set(err)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(false, granted.get())
        assertNotNull(error.get())
        assertTrue(
            error.get()!!.contains(".app") ||
                error.get()!!.contains("bundle") ||
                error.get()!!.contains("platform"),
        )
    }

    @Test
    fun `add remove badge and category calls are no-ops or error callbacks`() {
        val addError = AtomicReference<String?>()
        val addLatch = CountDownLatch(1)
        NotificationCenter.add(
            NotificationRequest(
                identifier = "kover-1",
                content =
                    NotificationContent(
                        title = "t",
                        body = "b",
                        subtitle = "s",
                        badge = 2,
                        sound = NotificationSound.Default,
                    ),
            ),
        ) { err ->
            addError.set(err)
            addLatch.countDown()
        }
        assertTrue(addLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(addError.get())

        NotificationCenter.removePendingNotifications(listOf("kover-1"))
        NotificationCenter.removeAllPendingNotifications()
        NotificationCenter.removeDeliveredNotifications(listOf("kover-1"))
        NotificationCenter.removeAllDeliveredNotifications()
        NotificationCenter.getNotificationSettings { }
        NotificationCenter.setDelegate(null)

        val pending = AtomicReference<List<PendingNotificationInfo>?>(null)
        val pendingLatch = CountDownLatch(1)
        NotificationCenter.getPendingNotifications {
            pending.set(it)
            pendingLatch.countDown()
        }
        assertTrue(pendingLatch.await(2, TimeUnit.SECONDS))
        assertEquals(emptyList(), pending.get())

        val delivered = AtomicReference<List<DeliveredNotification>?>(null)
        val deliveredLatch = CountDownLatch(1)
        NotificationCenter.getDeliveredNotifications {
            delivered.set(it)
            deliveredLatch.countDown()
        }
        assertTrue(deliveredLatch.await(2, TimeUnit.SECONDS))
        assertEquals(emptyList(), delivered.get())

        val categories = AtomicReference<List<RegisteredCategoryInfo>?>(null)
        val catLatch = CountDownLatch(1)
        NotificationCenter.getNotificationCategories {
            categories.set(it)
            catLatch.countDown()
        }
        assertTrue(catLatch.await(2, TimeUnit.SECONDS))
        assertEquals(emptyList(), categories.get())

        NotificationCenter.setNotificationCategories(
            setOf(
                NotificationCategory(
                    identifier = "build",
                    actions =
                        listOf(
                            NotificationAction("open", "Open"),
                            TextInputNotificationAction("reply", "Reply", emptySet(), "Send", "Type"),
                        ),
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                ),
            ),
        )

        val badgeError = AtomicReference<String?>()
        val badgeLatch = CountDownLatch(1)
        NotificationCenter.setBadgeCount(3) { err ->
            badgeError.set(err)
            badgeLatch.countDown()
        }
        assertTrue(badgeLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(badgeError.get())

        val badge = AtomicReference<Int?>(null)
        val getBadgeLatch = CountDownLatch(1)
        NotificationCenter.getBadgeCount {
            badge.set(it)
            getBadgeLatch.countDown()
        }
        assertTrue(getBadgeLatch.await(2, TimeUnit.SECONDS))
        assertEquals(0, badge.get())
    }

    @Test
    fun `calendar trigger request still reports unavailable`() {
        val error = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        NotificationCenter.add(
            NotificationRequest(
                identifier = "kover-cal",
                content =
                    NotificationContent(
                        title = "cal",
                        body = "body",
                        sound = NotificationSound.Named("Glass"),
                        attachments = listOf(NotificationAttachment("a1", "file:///tmp/x.png")),
                        userInfo = mapOf("k" to "v"),
                        interruptionLevel = InterruptionLevel.TIME_SENSITIVE,
                        relevanceScore = 0.5,
                    ),
                trigger =
                    NotificationTrigger.Calendar(
                        dateComponents =
                            DateComponents(year = 2026, month = 8, day = 18, hour = 12, minute = 0),
                        repeats = false,
                    ),
            ),
        ) { err ->
            error.set(err)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(error.get())
    }

    @Test
    fun `time-interval trigger and critical sound still report unavailable`() {
        val error = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        NotificationCenter.add(
            NotificationRequest(
                identifier = "kover-interval",
                content =
                    NotificationContent(
                        title = "iv",
                        body = "b",
                        sound = NotificationSound.DefaultCriticalWithVolume(0.2f),
                    ),
                trigger = NotificationTrigger.TimeInterval(interval = 90.0, repeats = true),
            ),
        ) { err ->
            error.set(err)
            latch.countDown()
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(error.get())
    }
}
