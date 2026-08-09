package dev.nucleusframework.notification.macos

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.notification.AlertStyle
import dev.nucleusframework.notification.AuthorizationStatus
import dev.nucleusframework.notification.DeliveredNotification
import dev.nucleusframework.notification.NotificationCenterDelegate
import dev.nucleusframework.notification.NotificationResponse
import dev.nucleusframework.notification.NotificationSetting
import dev.nucleusframework.notification.NotificationSettings
import dev.nucleusframework.notification.PendingNotificationInfo
import dev.nucleusframework.notification.PresentationOption
import dev.nucleusframework.notification.RegisteredActionInfo
import dev.nucleusframework.notification.RegisteredCategoryInfo
import dev.nucleusframework.notification.ShowPreviewsSetting
import dev.nucleusframework.notification.toMask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

private const val LIBRARY_NAME = "nucleus_notification"

@Suppress("TooManyFunctions", "LongParameterList")
internal object NativeMacNotificationBridge {
    private val logger = Logger.getLogger(NativeMacNotificationBridge::class.java.name)
    private val callbackCounter = AtomicLong(0)
    private val callbackThreadCounter = AtomicInteger(0)
    private val callbacks = ConcurrentHashMap<Long, Any>()
    private val callbackExecutor =
        Executors.newCachedThreadPool { runnable ->
            val threadNumber = callbackThreadCounter.incrementAndGet()
            Thread(runnable, "NucleusNotificationCallback-$threadNumber").apply { isDaemon = true }
        }

    @Volatile
    var delegate: NotificationCenterDelegate? = null

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMacNotificationBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // -- Callback management --

    fun <T : Any> registerCallback(callback: T): Long {
        val id = callbackCounter.incrementAndGet()
        callbacks[id] = callback
        return id
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> consumeCallback(id: Long): T? = callbacks.remove(id) as? T

    private fun dispatchCallback(callback: () -> Unit) {
        callbackExecutor.execute {
            try {
                callback()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                logger.log(Level.WARNING, "Error in notification callback", e)
            }
        }
    }

    // -- Native method declarations --

    @JvmStatic
    external fun nativeRequestAuthorization(
        optionsMask: Int,
        callbackId: Long,
    )

    @JvmStatic
    external fun nativeGetNotificationSettings(callbackId: Long)

    @JvmStatic
    @Suppress("LongParameterList")
    external fun nativeAddNotificationRequest(
        identifier: String,
        title: String,
        subtitle: String,
        body: String,
        badge: Int,
        soundType: Int,
        soundName: String,
        soundVolume: Float,
        threadIdentifier: String,
        categoryIdentifier: String,
        targetContentIdentifier: String,
        interruptionLevel: Int,
        relevanceScore: Double,
        userInfoKeys: Array<String>,
        userInfoValues: Array<String>,
        attachmentIds: Array<String>,
        attachmentUrls: Array<String>,
        triggerType: Int,
        triggerRepeats: Boolean,
        triggerTimeInterval: Double,
        calYear: Int,
        calMonth: Int,
        calDay: Int,
        calHour: Int,
        calMinute: Int,
        calSecond: Int,
        calWeekday: Int,
        callbackId: Long,
    )

    @JvmStatic
    external fun nativeRemovePendingNotifications(identifiers: Array<String>)

    @JvmStatic
    external fun nativeRemoveAllPendingNotifications()

    @JvmStatic
    external fun nativeGetPendingNotifications(callbackId: Long)

    @JvmStatic
    external fun nativeRemoveDeliveredNotifications(identifiers: Array<String>)

    @JvmStatic
    external fun nativeRemoveAllDeliveredNotifications()

    @JvmStatic
    external fun nativeGetDeliveredNotifications(callbackId: Long)

    @JvmStatic
    @Suppress("LongParameterList")
    external fun nativeSetNotificationCategories(
        categoryIdentifiers: Array<String>,
        categoryOptionMasks: IntArray,
        actionCategoryIndices: IntArray,
        actionIdentifiers: Array<String>,
        actionTitles: Array<String>,
        actionOptionMasks: IntArray,
        actionIsTextInput: BooleanArray,
        actionTextInputButtonTitles: Array<String>,
        actionTextInputPlaceholders: Array<String>,
    )

    @JvmStatic
    external fun nativeGetNotificationCategories(callbackId: Long)

    @JvmStatic
    external fun nativeSetBadgeCount(
        count: Int,
        callbackId: Long,
    )

    @JvmStatic
    external fun nativeGetBadgeCount(callbackId: Long)

    @JvmStatic
    external fun nativeSetDelegate(enabled: Boolean)

    // -- Callbacks from native code --

    @JvmStatic
    fun onAuthorizationResult(
        callbackId: Long,
        granted: Boolean,
        error: String?,
    ) {
        val callback = consumeCallback<(Boolean, String?) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(granted, error) }
    }

    @JvmStatic
    @Suppress("LongParameterList")
    fun onNotificationSettings(
        callbackId: Long,
        authorizationStatus: Int,
        soundSetting: Int,
        badgeSetting: Int,
        alertSetting: Int,
        notificationCenterSetting: Int,
        lockScreenSetting: Int,
        alertStyle: Int,
        showPreviewsSetting: Int,
        criticalAlertSetting: Int,
        providesAppNotificationSettings: Boolean,
        timeSensitiveSetting: Int,
        directMessagesSetting: Int,
        scheduledDeliverySetting: Int,
    ) {
        val settings =
            NotificationSettings(
                authorizationStatus = AuthorizationStatus.fromRawValue(authorizationStatus),
                soundSetting = NotificationSetting.fromRawValue(soundSetting),
                badgeSetting = NotificationSetting.fromRawValue(badgeSetting),
                alertSetting = NotificationSetting.fromRawValue(alertSetting),
                notificationCenterSetting = NotificationSetting.fromRawValue(notificationCenterSetting),
                lockScreenSetting = NotificationSetting.fromRawValue(lockScreenSetting),
                alertStyle = AlertStyle.fromRawValue(alertStyle),
                showPreviewsSetting = ShowPreviewsSetting.fromRawValue(showPreviewsSetting),
                criticalAlertSetting = NotificationSetting.fromRawValue(criticalAlertSetting),
                providesAppNotificationSettings = providesAppNotificationSettings,
                timeSensitiveSetting = NotificationSetting.fromRawValue(timeSensitiveSetting),
                directMessagesSetting = NotificationSetting.fromRawValue(directMessagesSetting),
                scheduledDeliverySetting = NotificationSetting.fromRawValue(scheduledDeliverySetting),
            )
        val callback = consumeCallback<(NotificationSettings) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(settings) }
    }

    @JvmStatic
    fun onRequestAdded(
        callbackId: Long,
        error: String?,
    ) {
        val callback = consumeCallback<(String?) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(error) }
    }

    @JvmStatic
    @Suppress("LongParameterList")
    fun onPendingNotifications(
        callbackId: Long,
        identifiers: Array<String>,
        titles: Array<String>,
        subtitles: Array<String>,
        bodies: Array<String>,
        categoryIdentifiers: Array<String>,
        threadIdentifiers: Array<String>,
        triggerTypes: IntArray,
        triggerRepeats: BooleanArray,
        triggerIntervals: DoubleArray,
    ) {
        val requests =
            identifiers.indices.map { i ->
                dev.nucleusframework.notification.PendingNotificationInfo(
                    identifier = identifiers[i],
                    title = titles[i],
                    subtitle = subtitles[i],
                    body = bodies[i],
                    categoryIdentifier = categoryIdentifiers[i],
                    threadIdentifier = threadIdentifiers[i],
                    triggerType = triggerTypes[i],
                    triggerRepeats = triggerRepeats[i],
                    triggerInterval = triggerIntervals[i],
                )
            }
        val callback = consumeCallback<(List<PendingNotificationInfo>) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(requests) }
    }

    @JvmStatic
    @Suppress("LongParameterList")
    fun onDeliveredNotifications(
        callbackId: Long,
        identifiers: Array<String>,
        titles: Array<String>,
        subtitles: Array<String>,
        bodies: Array<String>,
        dates: LongArray,
        categoryIdentifiers: Array<String>,
        threadIdentifiers: Array<String>,
    ) {
        val notifications =
            identifiers.indices.map { i ->
                DeliveredNotification(
                    identifier = identifiers[i],
                    title = titles[i],
                    subtitle = subtitles[i],
                    body = bodies[i],
                    date = dates[i],
                    categoryIdentifier = categoryIdentifiers[i],
                    threadIdentifier = threadIdentifiers[i],
                )
            }
        val callback = consumeCallback<(List<DeliveredNotification>) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(notifications) }
    }

    @JvmStatic
    @Suppress("LongParameterList")
    fun onNotificationCategories(
        callbackId: Long,
        categoryIdentifiers: Array<String>,
        categoryOptionMasks: IntArray,
        actionCategoryIndices: IntArray,
        actionIdentifiers: Array<String>,
        actionTitles: Array<String>,
        actionOptionMasks: IntArray,
        actionIsTextInput: BooleanArray,
        actionTextInputButtonTitles: Array<String>,
        actionTextInputPlaceholders: Array<String>,
    ) {
        val categories =
            categoryIdentifiers.indices.map { catIdx ->
                val actions =
                    actionCategoryIndices.indices
                        .filter { actionCategoryIndices[it] == catIdx }
                        .map { actIdx ->
                            RegisteredActionInfo(
                                identifier = actionIdentifiers[actIdx],
                                title = actionTitles[actIdx],
                                optionsMask = actionOptionMasks[actIdx],
                                isTextInput = actionIsTextInput[actIdx],
                                textInputButtonTitle = actionTextInputButtonTitles[actIdx],
                                textInputPlaceholder = actionTextInputPlaceholders[actIdx],
                            )
                        }
                RegisteredCategoryInfo(
                    identifier = categoryIdentifiers[catIdx],
                    optionsMask = categoryOptionMasks[catIdx],
                    actions = actions,
                )
            }
        val callback = consumeCallback<(List<RegisteredCategoryInfo>) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(categories) }
    }

    @JvmStatic
    fun onBadgeResult(
        callbackId: Long,
        error: String?,
    ) {
        val callback = consumeCallback<(String?) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(error) }
    }

    @JvmStatic
    fun onBadgeCount(
        callbackId: Long,
        count: Int,
    ) {
        val callback = consumeCallback<(Int) -> Unit>(callbackId) ?: return
        dispatchCallback { callback(count) }
    }

    // -- Delegate callbacks from native --

    private fun buildNotification(
        identifier: String,
        title: String,
        subtitle: String,
        body: String,
        date: Long,
        categoryIdentifier: String,
        threadIdentifier: String,
    ) = DeliveredNotification(
        identifier = identifier,
        title = title,
        subtitle = subtitle,
        body = body,
        date = date,
        categoryIdentifier = categoryIdentifier,
        threadIdentifier = threadIdentifier,
    )

    @JvmStatic
    @Suppress("LongParameterList")
    fun onWillPresentNotification(
        identifier: String,
        title: String,
        subtitle: String,
        body: String,
        date: Long,
        categoryIdentifier: String,
        threadIdentifier: String,
    ): Int {
        val d = delegate ?: return emptySet<PresentationOption>().toMask { it.rawValue }
        val notification =
            buildNotification(
                identifier,
                title,
                subtitle,
                body,
                date,
                categoryIdentifier,
                threadIdentifier,
            )
        val options = d.willPresent(notification)
        return options.toMask { it.rawValue }
    }

    @JvmStatic
    @Suppress("LongParameterList")
    fun onDidReceiveResponse(
        actionIdentifier: String,
        notifIdentifier: String,
        title: String,
        subtitle: String,
        body: String,
        date: Long,
        categoryIdentifier: String,
        threadIdentifier: String,
        userText: String?,
    ) {
        val d = delegate ?: return
        val notification =
            buildNotification(
                notifIdentifier,
                title,
                subtitle,
                body,
                date,
                categoryIdentifier,
                threadIdentifier,
            )
        val response =
            NotificationResponse(
                actionIdentifier = actionIdentifier,
                notification = notification,
                userText = userText,
            )
        dispatchCallback { d.didReceive(response) }
    }

    @JvmStatic
    fun onOpenSettings(
        hasNotification: Boolean,
        identifier: String?,
        title: String?,
        subtitle: String?,
        body: String?,
        date: Long,
        categoryIdentifier: String?,
        threadIdentifier: String?,
    ) {
        val d = delegate ?: return
        val notification =
            if (hasNotification && identifier != null) {
                DeliveredNotification(
                    identifier = identifier,
                    title = title ?: "",
                    subtitle = subtitle ?: "",
                    body = body ?: "",
                    date = date,
                    categoryIdentifier = categoryIdentifier ?: "",
                    threadIdentifier = threadIdentifier ?: "",
                )
            } else {
                null
            }
        dispatchCallback { d.openSettings(notification) }
    }
}
