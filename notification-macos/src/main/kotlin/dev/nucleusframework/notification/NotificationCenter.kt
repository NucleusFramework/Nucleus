package dev.nucleusframework.notification

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.notification.macos.NativeMacNotificationBridge
import java.util.logging.Logger

/**
 * Kotlin API mapping Apple's UNUserNotificationCenter.
 *
 * Provides full access to the macOS UserNotifications framework via JNI.
 * All methods are no-op on non-macOS platforms (check [isAvailable]).
 * Requires a packaged `.app` bundle — notifications are disabled when
 * running via `./gradlew run` (no bundle identifier).
 * Async operations and delegate callbacks do not marshal to AWT/Swing.
 * Use your application dispatcher before touching UI state.
 */
@Suppress("TooManyFunctions")
public object NotificationCenter {
    private val logger = Logger.getLogger(NotificationCenter::class.java.name)

    private const val NO_BUNDLE_ERROR =
        "Notifications require a packaged .app bundle with a bundle identifier. " +
            "Use ./gradlew runDistributable or ./gradlew runGraalvmNative instead of ./gradlew run."

    /**
     * Whether the current process runs inside a macOS `.app` bundle
     * (e.g. via runDistributable, DMG, PKG) or as a GraalVM native image.
     */
    private val hasAppBundle: Boolean by lazy {
        if (ExecutableRuntime.isGraalVmNativeImage) return@lazy true
        val execPath =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
        execPath != null && execPath.contains(".app/Contents/")
    }

    /** Whether notifications are functional (native lib loaded AND inside an app bundle) */
    public val isAvailable: Boolean by lazy {
        when {
            !NativeMacNotificationBridge.isLoaded -> false
            !hasAppBundle -> {
                logger.warning(NO_BUNDLE_ERROR)
                false
            }
            else -> true
        }
    }

    private val unavailableReason: String?
        get() =
            when {
                !NativeMacNotificationBridge.isLoaded -> "Not available on this platform"
                !hasAppBundle -> NO_BUNDLE_ERROR
                else -> null
            }

    // -- Authorization --

    public fun requestAuthorization(
        options: Set<AuthorizationOption>,
        callback: (granted: Boolean, error: String?) -> Unit,
    ) {
        unavailableReason?.let {
            callback(false, it)
            return
        }
        val mask = options.toMask { it.rawValue }
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeRequestAuthorization(mask, id)
    }

    public fun getNotificationSettings(callback: (NotificationSettings) -> Unit) {
        if (!isAvailable) return
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeGetNotificationSettings(id)
    }

    // -- Notification Requests --

    public fun add(
        request: NotificationRequest,
        callback: ((error: String?) -> Unit)? = null,
    ) {
        unavailableReason?.let {
            callback?.invoke(it)
            return
        }
        val content = request.content
        val trigger = request.trigger

        val soundType = content.sound?.typeId ?: NotificationSound.SOUND_TYPE_NONE
        val soundName = content.sound?.soundName ?: ""
        val soundVolume = content.sound?.soundVolume ?: 1.0f

        val userInfoKeys = content.userInfo.keys.toTypedArray()
        val userInfoValues = content.userInfo.values.toTypedArray()

        val attachmentIds = content.attachments.map { it.identifier }.toTypedArray()
        val attachmentUrls = content.attachments.map { it.url }.toTypedArray()

        val triggerType = trigger?.typeId ?: NotificationTrigger.TRIGGER_TYPE_NONE
        val triggerRepeats = trigger?.repeats ?: false
        val triggerTimeInterval = (trigger as? NotificationTrigger.TimeInterval)?.interval ?: 0.0
        val calComponents = (trigger as? NotificationTrigger.Calendar)?.dateComponents

        val noOpCallback: (String?) -> Unit = {}
        val id = NativeMacNotificationBridge.registerCallback(callback ?: noOpCallback)

        NativeMacNotificationBridge.nativeAddNotificationRequest(
            identifier = request.identifier,
            title = content.title,
            subtitle = content.subtitle,
            body = content.body,
            badge = content.badge ?: -1,
            soundType = soundType,
            soundName = soundName,
            soundVolume = soundVolume,
            threadIdentifier = content.threadIdentifier,
            categoryIdentifier = content.categoryIdentifier,
            targetContentIdentifier = content.targetContentIdentifier,
            interruptionLevel = content.interruptionLevel.rawValue,
            relevanceScore = content.relevanceScore,
            userInfoKeys = userInfoKeys,
            userInfoValues = userInfoValues,
            attachmentIds = attachmentIds,
            attachmentUrls = attachmentUrls,
            triggerType = triggerType,
            triggerRepeats = triggerRepeats,
            triggerTimeInterval = triggerTimeInterval,
            calYear = calComponents?.year ?: -1,
            calMonth = calComponents?.month ?: -1,
            calDay = calComponents?.day ?: -1,
            calHour = calComponents?.hour ?: -1,
            calMinute = calComponents?.minute ?: -1,
            calSecond = calComponents?.second ?: -1,
            calWeekday = calComponents?.weekday ?: -1,
            callbackId = id,
        )
    }

    public fun removePendingNotifications(identifiers: List<String>) {
        if (!isAvailable) return
        NativeMacNotificationBridge.nativeRemovePendingNotifications(identifiers.toTypedArray())
    }

    public fun removeAllPendingNotifications() {
        if (!isAvailable) return
        NativeMacNotificationBridge.nativeRemoveAllPendingNotifications()
    }

    public fun getPendingNotifications(callback: (List<PendingNotificationInfo>) -> Unit) {
        if (!isAvailable) {
            callback(emptyList())
            return
        }
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeGetPendingNotifications(id)
    }

    public fun removeDeliveredNotifications(identifiers: List<String>) {
        if (!isAvailable) return
        NativeMacNotificationBridge.nativeRemoveDeliveredNotifications(identifiers.toTypedArray())
    }

    public fun removeAllDeliveredNotifications() {
        if (!isAvailable) return
        NativeMacNotificationBridge.nativeRemoveAllDeliveredNotifications()
    }

    public fun getDeliveredNotifications(callback: (List<DeliveredNotification>) -> Unit) {
        if (!isAvailable) {
            callback(emptyList())
            return
        }
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeGetDeliveredNotifications(id)
    }

    // -- Categories --

    public fun setNotificationCategories(categories: Set<NotificationCategory>) {
        if (!isAvailable) return
        val categoryIdentifiers = categories.map { it.identifier }.toTypedArray()
        val categoryOptionMasks = categories.map { cat -> cat.options.toMask { it.rawValue } }.toIntArray()

        val actionCategoryIndices = mutableListOf<Int>()
        val actionIdentifiers = mutableListOf<String>()
        val actionTitles = mutableListOf<String>()
        val actionOptionMasks = mutableListOf<Int>()
        val actionIsTextInput = mutableListOf<Boolean>()
        val actionTextInputButtonTitles = mutableListOf<String>()
        val actionTextInputPlaceholders = mutableListOf<String>()

        categories.forEachIndexed { catIdx, category ->
            category.actions.forEach { action ->
                actionCategoryIndices.add(catIdx)
                actionIdentifiers.add(action.identifier)
                actionTitles.add(action.title)
                actionOptionMasks.add(action.options.toMask { it.rawValue })
                val textInputAction = action as? TextInputNotificationAction
                actionIsTextInput.add(textInputAction != null)
                actionTextInputButtonTitles.add(textInputAction?.textInputButtonTitle ?: "")
                actionTextInputPlaceholders.add(textInputAction?.textInputPlaceholder ?: "")
            }
        }

        NativeMacNotificationBridge.nativeSetNotificationCategories(
            categoryIdentifiers = categoryIdentifiers,
            categoryOptionMasks = categoryOptionMasks,
            actionCategoryIndices = actionCategoryIndices.toIntArray(),
            actionIdentifiers = actionIdentifiers.toTypedArray(),
            actionTitles = actionTitles.toTypedArray(),
            actionOptionMasks = actionOptionMasks.toIntArray(),
            actionIsTextInput = actionIsTextInput.toBooleanArray(),
            actionTextInputButtonTitles = actionTextInputButtonTitles.toTypedArray(),
            actionTextInputPlaceholders = actionTextInputPlaceholders.toTypedArray(),
        )
    }

    public fun getNotificationCategories(callback: (List<RegisteredCategoryInfo>) -> Unit) {
        if (!isAvailable) {
            callback(emptyList())
            return
        }
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeGetNotificationCategories(id)
    }

    // -- Badge --

    public fun setBadgeCount(
        count: Int,
        callback: ((error: String?) -> Unit)? = null,
    ) {
        unavailableReason?.let {
            callback?.invoke(it)
            return
        }
        val noOpCallback: (String?) -> Unit = {}
        val id = NativeMacNotificationBridge.registerCallback(callback ?: noOpCallback)
        NativeMacNotificationBridge.nativeSetBadgeCount(count, id)
    }

    public fun getBadgeCount(callback: (Int) -> Unit) {
        if (!isAvailable) {
            callback(0)
            return
        }
        val id = NativeMacNotificationBridge.registerCallback(callback)
        NativeMacNotificationBridge.nativeGetBadgeCount(id)
    }

    // -- Delegate --

    public fun setDelegate(delegate: NotificationCenterDelegate?) {
        if (!isAvailable) return
        NativeMacNotificationBridge.delegate = delegate
        NativeMacNotificationBridge.nativeSetDelegate(delegate != null)
    }
}
