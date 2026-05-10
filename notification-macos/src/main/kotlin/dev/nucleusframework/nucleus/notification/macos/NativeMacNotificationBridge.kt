package dev.nucleusframework.nucleus.notification.macos

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader
import dev.nucleusframework.nucleus.notification.AlertStyle
import dev.nucleusframework.nucleus.notification.AuthorizationStatus
import dev.nucleusframework.nucleus.notification.DeliveredNotification
import dev.nucleusframework.nucleus.notification.NotificationCenterDelegate
import dev.nucleusframework.nucleus.notification.NotificationResponse
import dev.nucleusframework.nucleus.notification.NotificationSetting
import dev.nucleusframework.nucleus.notification.NotificationSettings
import dev.nucleusframework.nucleus.notification.PendingNotificationInfo
import dev.nucleusframework.nucleus.notification.PresentationOption
import dev.nucleusframework.nucleus.notification.RegisteredActionInfo
import dev.nucleusframework.nucleus.notification.RegisteredCategoryInfo
import dev.nucleusframework.nucleus.notification.ShowPreviewsSetting
import dev.nucleusframework.nucleus.notification.toMask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val LIBRARY_NAME = "nucleus_notification"

@Suppress("TooManyFunctions", "LongParameterList")
internal object NativeMacNotificationBridge {
    private val callbackCounter = AtomicLong(0)
    private val callbacks = ConcurrentHashMap<Long, Any>()

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
        consumeCallback<(Boolean, String?) -> Unit>(callbackId)?.invoke(granted, error)
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
        consumeCallback<(NotificationSettings) -> Unit>(callbackId)?.invoke(settings)
    }

    @JvmStatic
    fun onRequestAdded(
        callbackId: Long,
        error: String?,
    ) {
        consumeCallback<(String?) -> Unit>(callbackId)?.invoke(error)
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
                dev.nucleusframework.nucleus.notification.PendingNotificationInfo(
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
        consumeCallback<(List<PendingNotificationInfo>) -> Unit>(callbackId)
            ?.invoke(requests)
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
        consumeCallback<(List<DeliveredNotification>) -> Unit>(callbackId)?.invoke(notifications)
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
        consumeCallback<(List<RegisteredCategoryInfo>) -> Unit>(callbackId)?.invoke(categories)
    }

    @JvmStatic
    fun onBadgeResult(
        callbackId: Long,
        error: String?,
    ) {
        consumeCallback<(String?) -> Unit>(callbackId)?.invoke(error)
    }

    @JvmStatic
    fun onBadgeCount(
        callbackId: Long,
        count: Int,
    ) {
        consumeCallback<(Int) -> Unit>(callbackId)?.invoke(count)
    }

    // -- Delegate callbacks from native (dispatched to EDT for thread safety) --

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
        // invokeAndWait to run on EDT and return the result synchronously
        var options: Set<PresentationOption> = emptySet()
        javax.swing.SwingUtilities.invokeAndWait {
            options = d.willPresent(notification)
        }
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
        javax.swing.SwingUtilities.invokeLater { d.didReceive(response) }
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
        javax.swing.SwingUtilities.invokeLater { d.openSettings(notification) }
    }
}
