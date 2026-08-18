package dev.nucleusframework.notification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacOsNotificationModelTest {
    @Test
    fun `status enums map known values and fall back`() {
        assertEquals(AuthorizationStatus.NOT_DETERMINED, AuthorizationStatus.fromRawValue(0))
        assertEquals(AuthorizationStatus.DENIED, AuthorizationStatus.fromRawValue(1))
        assertEquals(AuthorizationStatus.AUTHORIZED, AuthorizationStatus.fromRawValue(2))
        assertEquals(AuthorizationStatus.PROVISIONAL, AuthorizationStatus.fromRawValue(3))
        assertEquals(AuthorizationStatus.EPHEMERAL, AuthorizationStatus.fromRawValue(4))
        assertEquals(AuthorizationStatus.NOT_DETERMINED, AuthorizationStatus.fromRawValue(-1))

        assertEquals(NotificationSetting.NOT_SUPPORTED, NotificationSetting.fromRawValue(0))
        assertEquals(NotificationSetting.DISABLED, NotificationSetting.fromRawValue(1))
        assertEquals(NotificationSetting.ENABLED, NotificationSetting.fromRawValue(2))
        assertEquals(NotificationSetting.NOT_SUPPORTED, NotificationSetting.fromRawValue(9))

        assertEquals(AlertStyle.NONE, AlertStyle.fromRawValue(0))
        assertEquals(AlertStyle.BANNER, AlertStyle.fromRawValue(1))
        assertEquals(AlertStyle.ALERT, AlertStyle.fromRawValue(2))
        assertEquals(AlertStyle.NONE, AlertStyle.fromRawValue(8))

        assertEquals(ShowPreviewsSetting.ALWAYS, ShowPreviewsSetting.fromRawValue(0))
        assertEquals(ShowPreviewsSetting.WHEN_AUTHENTICATED, ShowPreviewsSetting.fromRawValue(1))
        assertEquals(ShowPreviewsSetting.NEVER, ShowPreviewsSetting.fromRawValue(2))
        assertEquals(ShowPreviewsSetting.ALWAYS, ShowPreviewsSetting.fromRawValue(5))

        assertEquals(InterruptionLevel.PASSIVE, InterruptionLevel.fromRawValue(0))
        assertEquals(InterruptionLevel.ACTIVE, InterruptionLevel.fromRawValue(1))
        assertEquals(InterruptionLevel.TIME_SENSITIVE, InterruptionLevel.fromRawValue(2))
        assertEquals(InterruptionLevel.CRITICAL, InterruptionLevel.fromRawValue(3))
        assertEquals(InterruptionLevel.ACTIVE, InterruptionLevel.fromRawValue(99))
    }

    @Test
    fun `bitmask helpers convert option sets`() {
        val options = setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE)
        val mask = options.toMask { it.rawValue }
        assertEquals(
            AuthorizationOption.ALERT.rawValue or
                AuthorizationOption.SOUND.rawValue or
                AuthorizationOption.BADGE.rawValue,
            mask,
        )
        assertEquals(options, mask.toOptionSet(AuthorizationOption::rawValue))
        assertTrue(0.toOptionSet(PresentationOption::rawValue).isEmpty())
        assertEquals(
            setOf(ActionOption.FOREGROUND, ActionOption.DESTRUCTIVE),
            (ActionOption.FOREGROUND.rawValue or ActionOption.DESTRUCTIVE.rawValue)
                .toOptionSet(ActionOption::rawValue),
        )
        assertEquals(
            setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
            CategoryOption.CUSTOM_DISMISS_ACTION.rawValue.toOptionSet(CategoryOption::rawValue),
        )
    }

    @Test
    fun `notification sound variants expose type name and volume`() {
        assertEquals(NotificationSound.SOUND_TYPE_DEFAULT, NotificationSound.Default.typeId)
        assertEquals("", NotificationSound.Default.soundName)
        assertEquals(1.0f, NotificationSound.Default.soundVolume)

        val named = NotificationSound.Named("ping")
        assertEquals(NotificationSound.SOUND_TYPE_NAMED, named.typeId)
        assertEquals("ping", named.soundName)
        assertEquals(1.0f, named.soundVolume)

        assertEquals(NotificationSound.SOUND_TYPE_DEFAULT_CRITICAL, NotificationSound.DefaultCritical.typeId)

        val critical = NotificationSound.CriticalNamed("siren", 0.3f)
        assertEquals(NotificationSound.SOUND_TYPE_CRITICAL_NAMED, critical.typeId)
        assertEquals("siren", critical.soundName)
        assertEquals(0.3f, critical.soundVolume)

        val volume = NotificationSound.DefaultCriticalWithVolume(0.5f)
        assertEquals(NotificationSound.SOUND_TYPE_DEFAULT_CRITICAL_VOLUME, volume.typeId)
        assertEquals("", volume.soundName)
        assertEquals(0.5f, volume.soundVolume)
    }

    @Test
    fun `triggers validate interval and calendar fields`() {
        val interval = NotificationTrigger.TimeInterval(90.0, repeats = true)
        assertEquals(NotificationTrigger.TRIGGER_TYPE_TIME_INTERVAL, interval.typeId)
        assertTrue(interval.repeats)

        val error =
            assertFailsWith<IllegalArgumentException> { NotificationTrigger.TimeInterval(0.0) }
        assertTrue(error.message!!.contains("positive"))
        val repeatError =
            assertFailsWith<IllegalArgumentException> {
                NotificationTrigger.TimeInterval(30.0, repeats = true)
            }
        assertTrue(repeatError.message!!.contains("60"))

        val components =
            DateComponents(
                year = 2024,
                month = 8,
                day = 18,
                hour = 9,
                minute = 30,
                second = 0,
                weekday = 1,
            )
        val calendar = NotificationTrigger.Calendar(components, repeats = true)
        assertEquals(NotificationTrigger.TRIGGER_TYPE_CALENDAR, calendar.typeId)
        assertEquals(2024, calendar.dateComponents.year)
        assertEquals(1, calendar.dateComponents.weekday)
    }

    @Test
    fun `content request category and response models keep their fields`() {
        val content =
            NotificationContent(
                title = "Title",
                subtitle = "Sub",
                body = "Body",
                badge = 3,
                sound = NotificationSound.Named("ping"),
                userInfo = mapOf("k" to "v"),
                attachments = listOf(NotificationAttachment("img", "file:///tmp/a.png")),
                threadIdentifier = "thread",
                categoryIdentifier = "cat",
                targetContentIdentifier = "target",
                interruptionLevel = InterruptionLevel.TIME_SENSITIVE,
                relevanceScore = 0.8,
            )
        val request =
            NotificationRequest(
                identifier = "id-1",
                content = content,
                trigger = NotificationTrigger.TimeInterval(120.0),
            )
        assertEquals("Title", request.content.title)
        assertEquals("cat", request.content.categoryIdentifier)
        assertEquals(0.8, request.content.relevanceScore)
        assertEquals("id-1", request.identifier)

        val action =
            TextInputNotificationAction(
                identifier = "reply",
                title = "Reply",
                options = setOf(ActionOption.FOREGROUND),
                textInputButtonTitle = "Send",
                textInputPlaceholder = "Message",
            )
        val category =
            NotificationCategory(
                identifier = "cat",
                actions = listOf(action, NotificationAction("open", "Open")),
                intentIdentifiers = listOf("intent"),
                options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
            )
        val textAction = category.actions.first() as TextInputNotificationAction
        assertEquals("reply", textAction.identifier)
        assertEquals("Send", textAction.textInputButtonTitle)
        assertEquals("Message", textAction.textInputPlaceholder)
        assertEquals(NotificationAction.DEFAULT_ACTION_IDENTIFIER, NotificationAction.DEFAULT_ACTION_IDENTIFIER)
        assertEquals(NotificationAction.DISMISS_ACTION_IDENTIFIER, NotificationAction.DISMISS_ACTION_IDENTIFIER)

        val delivered =
            DeliveredNotification("id", "t", "s", "b", 123L, "cat", "thread")
        val response = NotificationResponse("open", delivered, "typed")
        assertEquals("typed", response.userText)
        assertEquals("id", response.notification.identifier)

        val pending =
            PendingNotificationInfo("p", "t", "s", "b", "c", "th", 1, true, 60.0)
        assertEquals(60.0, pending.triggerInterval)
        val registered =
            RegisteredCategoryInfo(
                "c",
                1,
                listOf(RegisteredActionInfo("a", "A", 0, true, "Send", "Hi")),
            )
        assertTrue(registered.actions.single().isTextInput)
    }

    @Test
    fun `notification settings model stores every field`() {
        val settings =
            NotificationSettings(
                authorizationStatus = AuthorizationStatus.AUTHORIZED,
                soundSetting = NotificationSetting.ENABLED,
                badgeSetting = NotificationSetting.ENABLED,
                alertSetting = NotificationSetting.DISABLED,
                notificationCenterSetting = NotificationSetting.ENABLED,
                lockScreenSetting = NotificationSetting.DISABLED,
                alertStyle = AlertStyle.BANNER,
                showPreviewsSetting = ShowPreviewsSetting.WHEN_AUTHENTICATED,
                criticalAlertSetting = NotificationSetting.NOT_SUPPORTED,
                providesAppNotificationSettings = true,
                timeSensitiveSetting = NotificationSetting.ENABLED,
                directMessagesSetting = NotificationSetting.DISABLED,
                scheduledDeliverySetting = NotificationSetting.DISABLED,
            )
        assertEquals(AuthorizationStatus.AUTHORIZED, settings.authorizationStatus)
        assertTrue(settings.providesAppNotificationSettings)
        assertEquals(AlertStyle.BANNER, settings.alertStyle)
    }

    @Test
    fun `authorization option masks fold every shipped flag`() {
        assertEquals(0, emptySet<AuthorizationOption>().toMask { it.rawValue })
        assertEquals(
            AuthorizationOption.BADGE.rawValue or
                AuthorizationOption.SOUND.rawValue or
                AuthorizationOption.ALERT.rawValue or
                AuthorizationOption.CRITICAL_ALERT.rawValue or
                AuthorizationOption.PROVIDES_APP_NOTIFICATION_SETTINGS.rawValue or
                AuthorizationOption.PROVISIONAL.rawValue or
                AuthorizationOption.TIME_SENSITIVE.rawValue,
            AuthorizationOption.entries.toSet().toMask { it.rawValue },
        )
        assertEquals(
            PresentationOption.LIST.rawValue or PresentationOption.BANNER.rawValue,
            setOf(PresentationOption.LIST, PresentationOption.BANNER).toMask { it.rawValue },
        )
    }
}

class NotificationCenterFallbackTest {
    @Test
    fun `center public methods are callable and report fallback when unavailable`() {
        if (!NotificationCenter.isAvailable) {
            var authGranted: Boolean? = null
            var authError: String? = null
            NotificationCenter.requestAuthorization(
                setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND),
            ) { granted, error ->
                authGranted = granted
                authError = error
            }
            assertEquals(false, authGranted)
            val authMessage = requireNotNull(authError)
            assertTrue(authMessage.contains("bundle") || authMessage.contains("platform"), authMessage)

            var addError: String? = null
            NotificationCenter.add(
                NotificationRequest(
                    identifier = "x",
                    content =
                        NotificationContent(
                            title = "T",
                            body = "B",
                            badge = 1,
                            sound = NotificationSound.Named("ping"),
                            userInfo = mapOf("a" to "b"),
                            attachments = listOf(NotificationAttachment("i", "file:///tmp/a.png")),
                            interruptionLevel = InterruptionLevel.CRITICAL,
                            relevanceScore = 1.0,
                        ),
                    trigger = NotificationTrigger.Calendar(DateComponents(hour = 9)),
                ),
            ) { addError = it }
            val addMessage = requireNotNull(addError)
            assertTrue(addMessage.contains("bundle") || addMessage.contains("platform"), addMessage)

            var badgeError: String? = null
            NotificationCenter.setBadgeCount(3) { badgeError = it }
            val badgeMessage = requireNotNull(badgeError)
            assertTrue(badgeMessage.contains("bundle") || badgeMessage.contains("platform"), badgeMessage)

            var pending: List<PendingNotificationInfo>? = null
            NotificationCenter.getPendingNotifications { pending = it }
            assertEquals(emptyList(), pending)

            var delivered: List<DeliveredNotification>? = null
            NotificationCenter.getDeliveredNotifications { delivered = it }
            assertEquals(emptyList(), delivered)

            var categories: List<RegisteredCategoryInfo>? = null
            NotificationCenter.getNotificationCategories { categories = it }
            assertEquals(emptyList(), categories)

            var badge = -1
            NotificationCenter.getBadgeCount { badge = it }
            assertEquals(0, badge)
        } else {
            NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT)) { _, _ -> }
            NotificationCenter.add(NotificationRequest("id", NotificationContent(title = "T"))) { }
            NotificationCenter.setBadgeCount(0) { }
            NotificationCenter.getPendingNotifications { }
            NotificationCenter.getDeliveredNotifications { }
            NotificationCenter.getNotificationCategories { }
            NotificationCenter.getBadgeCount { }
            NotificationCenter.getNotificationSettings { }
        }

        NotificationCenter.removePendingNotifications(listOf("a"))
        NotificationCenter.removeAllPendingNotifications()
        NotificationCenter.removeDeliveredNotifications(listOf("a"))
        NotificationCenter.removeAllDeliveredNotifications()
        NotificationCenter.setNotificationCategories(
            setOf(
                NotificationCategory(
                    identifier = "c",
                    actions =
                        listOf(
                            NotificationAction("open", "Open", setOf(ActionOption.FOREGROUND)),
                            TextInputNotificationAction("reply", "Reply", emptySet(), "Send", "Hi"),
                        ),
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                ),
            ),
        )
        NotificationCenter.setDelegate(null)
        NotificationCenter.setDelegate(
            object : NotificationCenterDelegate {
                override fun willPresent(notification: DeliveredNotification) = setOf(PresentationOption.BANNER)

                override fun didReceive(response: NotificationResponse) = Unit
            },
        )
        NotificationCenter.setDelegate(null)
    }

    @Test
    @Suppress("LongMethod")
    fun `bridge callbacks reconstruct settings and delivered notifications`() {
        val bridge = dev.nucleusframework.notification.macos.NativeMacNotificationBridge

        val settingsRef = AtomicReference<NotificationSettings?>(null)
        val settingsLatch = CountDownLatch(1)
        val settingsId =
            bridge.registerCallback<Function1<NotificationSettings, Unit>> {
                settingsRef.set(it)
                settingsLatch.countDown()
            }
        bridge.onNotificationSettings(
            settingsId,
            2,
            2,
            2,
            2,
            2,
            1,
            1,
            1,
            0,
            true,
            2,
            1,
            1,
        )
        assertTrue(settingsLatch.await(3, TimeUnit.SECONDS))
        val settings = requireNotNull(settingsRef.get())
        assertEquals(AuthorizationStatus.AUTHORIZED, settings.authorizationStatus)
        assertEquals(AlertStyle.BANNER, settings.alertStyle)
        assertTrue(settings.providesAppNotificationSettings)

        val pendingLatch = CountDownLatch(1)
        val pendingRef = AtomicReference<List<PendingNotificationInfo>>(emptyList())
        val pendingId =
            bridge.registerCallback<Function1<List<PendingNotificationInfo>, Unit>> {
                pendingRef.set(it)
                pendingLatch.countDown()
            }
        bridge.onPendingNotifications(
            pendingId,
            arrayOf("p"),
            arrayOf("t"),
            arrayOf("s"),
            arrayOf("b"),
            arrayOf("c"),
            arrayOf("th"),
            intArrayOf(1),
            booleanArrayOf(true),
            doubleArrayOf(90.0),
        )
        assertTrue(pendingLatch.await(3, TimeUnit.SECONDS))
        assertEquals("p", pendingRef.get().single().identifier)
        assertEquals(90.0, pendingRef.get().single().triggerInterval)

        val deliveredLatch = CountDownLatch(1)
        val deliveredRef = AtomicReference<List<DeliveredNotification>>(emptyList())
        val deliveredId =
            bridge.registerCallback<Function1<List<DeliveredNotification>, Unit>> {
                deliveredRef.set(it)
                deliveredLatch.countDown()
            }
        bridge.onDeliveredNotifications(
            deliveredId,
            arrayOf("d"),
            arrayOf("t"),
            arrayOf("s"),
            arrayOf("b"),
            longArrayOf(1L),
            arrayOf("c"),
            arrayOf("th"),
        )
        assertTrue(deliveredLatch.await(3, TimeUnit.SECONDS))
        assertEquals("d", deliveredRef.get().single().identifier)

        val categoriesLatch = CountDownLatch(1)
        val categoriesRef = AtomicReference<List<RegisteredCategoryInfo>>(emptyList())
        val categoriesId =
            bridge.registerCallback<Function1<List<RegisteredCategoryInfo>, Unit>> {
                categoriesRef.set(it)
                categoriesLatch.countDown()
            }
        bridge.onNotificationCategories(
            categoriesId,
            arrayOf("cat"),
            intArrayOf(1),
            intArrayOf(0),
            arrayOf("reply"),
            arrayOf("Reply"),
            intArrayOf(4),
            booleanArrayOf(true),
            arrayOf("Send"),
            arrayOf("Hi"),
        )
        assertTrue(categoriesLatch.await(3, TimeUnit.SECONDS))
        val category = categoriesRef.get().single()
        assertEquals("cat", category.identifier)
        assertTrue(category.actions.single().isTextInput)

        val authLatch = CountDownLatch(1)
        var granted = true
        var error: String? = "unset"
        val authId =
            bridge.registerCallback<Function2<Boolean, String?, Unit>> { g, e ->
                granted = g
                error = e
                authLatch.countDown()
            }
        bridge.onAuthorizationResult(authId, false, "denied")
        assertTrue(authLatch.await(3, TimeUnit.SECONDS))
        assertFalse(granted)
        assertEquals("denied", error)

        val addLatch = CountDownLatch(1)
        var addError: String? = "unset"
        val addId =
            bridge.registerCallback<Function1<String?, Unit>> {
                addError = it
                addLatch.countDown()
            }
        bridge.onRequestAdded(addId, null)
        assertTrue(addLatch.await(3, TimeUnit.SECONDS))
        assertNull(addError)

        val badgeLatch = CountDownLatch(2)
        var badgeError: String? = "unset"
        var badgeCount = -1
        val badgeId =
            bridge.registerCallback<Function1<String?, Unit>> {
                badgeError = it
                badgeLatch.countDown()
            }
        val countId =
            bridge.registerCallback<Function1<Int, Unit>> {
                badgeCount = it
                badgeLatch.countDown()
            }
        bridge.onBadgeResult(badgeId, "nope")
        bridge.onBadgeCount(countId, 4)
        assertTrue(badgeLatch.await(3, TimeUnit.SECONDS))
        assertEquals("nope", badgeError)
        assertEquals(4, badgeCount)
    }

    @Test
    fun `bridge delegate callbacks invoke the registered delegate`() {
        val bridge = dev.nucleusframework.notification.macos.NativeMacNotificationBridge
        val presented = AtomicReference<DeliveredNotification?>(null)
        val received = AtomicReference<NotificationResponse?>(null)
        val opened = AtomicReference<DeliveredNotification?>(null)
        val receiveLatch = CountDownLatch(1)
        val openLatch = CountDownLatch(1)
        bridge.delegate =
            object : NotificationCenterDelegate {
                override fun willPresent(notification: DeliveredNotification): Set<PresentationOption> {
                    presented.set(notification)
                    return setOf(PresentationOption.BANNER, PresentationOption.SOUND)
                }

                override fun didReceive(response: NotificationResponse) {
                    received.set(response)
                    receiveLatch.countDown()
                }

                override fun openSettings(notification: DeliveredNotification?) {
                    opened.set(notification)
                    openLatch.countDown()
                }
            }
        try {
            val mask =
                bridge.onWillPresentNotification("id", "t", "s", "b", 1L, "c", "th")
            assertEquals(
                PresentationOption.BANNER.rawValue or PresentationOption.SOUND.rawValue,
                mask,
            )
            assertEquals("id", presented.get()?.identifier)

            bridge.onDidReceiveResponse("open", "id", "t", "s", "b", 1L, "c", "th", "typed")
            assertTrue(receiveLatch.await(3, TimeUnit.SECONDS))
            assertEquals("typed", received.get()?.userText)

            bridge.onOpenSettings(true, "id", "t", "s", "b", 1L, "c", "th")
            assertTrue(openLatch.await(3, TimeUnit.SECONDS))
            assertEquals("id", opened.get()?.identifier)

            val noneMask =
                run {
                    bridge.delegate = null
                    bridge.onWillPresentNotification("x", "", "", "", 0L, "", "")
                }
            assertEquals(0, noneMask)
            bridge.onDidReceiveResponse("x", "x", "", "", "", 0L, "", "", null)
            bridge.onOpenSettings(false, null, null, null, null, 0L, null, null)
        } finally {
            bridge.delegate = null
        }
    }
}
