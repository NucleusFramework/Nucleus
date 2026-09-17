package dev.nucleusframework.notification

/** Date components for calendar-based triggers. Null fields are ignored (wildcard). */
public data class DateComponents(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val second: Int? = null,
    val weekday: Int? = null,
)

/** Maps UNNotificationSound */
public sealed class NotificationSound {
    public data object Default : NotificationSound()

    public data class Named(
        val name: String,
    ) : NotificationSound()

    public data object DefaultCritical : NotificationSound()

    public data class CriticalNamed(
        val name: String,
        val volume: Float,
    ) : NotificationSound()

    public data class DefaultCriticalWithVolume(
        val volume: Float,
    ) : NotificationSound()

    internal val typeId: Int get() =
        when (this) {
            is Default -> SOUND_TYPE_DEFAULT
            is Named -> SOUND_TYPE_NAMED
            is DefaultCritical -> SOUND_TYPE_DEFAULT_CRITICAL
            is CriticalNamed -> SOUND_TYPE_CRITICAL_NAMED
            is DefaultCriticalWithVolume -> SOUND_TYPE_DEFAULT_CRITICAL_VOLUME
        }

    internal val soundName: String get() =
        when (this) {
            is Named -> name
            is CriticalNamed -> name
            else -> ""
        }

    internal val soundVolume: Float get() =
        when (this) {
            is CriticalNamed -> volume
            is DefaultCriticalWithVolume -> volume
            else -> 1.0f
        }

    internal companion object {
        const val SOUND_TYPE_NONE = 0
        const val SOUND_TYPE_DEFAULT = 1
        const val SOUND_TYPE_NAMED = 2
        const val SOUND_TYPE_DEFAULT_CRITICAL = 3
        const val SOUND_TYPE_CRITICAL_NAMED = 4
        const val SOUND_TYPE_DEFAULT_CRITICAL_VOLUME = 5
    }
}

/** Maps UNNotificationAttachment */
public data class NotificationAttachment(
    val identifier: String,
    val url: String,
)

/** Maps UNMutableNotificationContent */
public data class NotificationContent(
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
    val badge: Int? = null,
    val sound: NotificationSound? = null,
    val userInfo: Map<String, String> = emptyMap(),
    val attachments: List<NotificationAttachment> = emptyList(),
    val threadIdentifier: String = "",
    val categoryIdentifier: String = "",
    val targetContentIdentifier: String = "",
    val interruptionLevel: InterruptionLevel = InterruptionLevel.ACTIVE,
    val relevanceScore: Double = 0.0,
)

/** Maps UNNotificationTrigger and subclasses */
public sealed class NotificationTrigger {
    public abstract val repeats: Boolean

    internal abstract val typeId: Int

    /** Maps UNTimeIntervalNotificationTrigger */
    public data class TimeInterval(
        val interval: Double,
        override val repeats: Boolean = false,
    ) : NotificationTrigger() {
        init {
            require(interval > 0) { "Time interval must be positive" }
            @Suppress("MagicNumber")
            require(!repeats || interval >= 60) { "Repeating time interval must be >= 60 seconds" }
        }

        override val typeId: Int get() = TRIGGER_TYPE_TIME_INTERVAL
    }

    /** Maps UNCalendarNotificationTrigger */
    public data class Calendar(
        val dateComponents: DateComponents,
        override val repeats: Boolean = false,
    ) : NotificationTrigger() {
        override val typeId: Int get() = TRIGGER_TYPE_CALENDAR
    }

    internal companion object {
        const val TRIGGER_TYPE_NONE = 0
        const val TRIGGER_TYPE_TIME_INTERVAL = 1
        const val TRIGGER_TYPE_CALENDAR = 2
    }
}

/** Maps UNNotificationAction */
public open class NotificationAction(
    public val identifier: String,
    public val title: String,
    public val options: Set<ActionOption> = emptySet(),
) {
    /** Default action identifier constant (user tapped the notification itself) */
    public companion object {
        public const val DEFAULT_ACTION_IDENTIFIER: String = "com.apple.UNNotificationDefaultActionIdentifier"
        public const val DISMISS_ACTION_IDENTIFIER: String = "com.apple.UNNotificationDismissActionIdentifier"
    }
}

/** Maps UNTextInputNotificationAction */
public class TextInputNotificationAction(
    identifier: String,
    title: String,
    options: Set<ActionOption> = emptySet(),
    public val textInputButtonTitle: String,
    public val textInputPlaceholder: String,
) : NotificationAction(identifier, title, options)

/** Maps UNNotificationCategory */
public data class NotificationCategory(
    val identifier: String,
    val actions: List<NotificationAction> = emptyList(),
    val intentIdentifiers: List<String> = emptyList(),
    val options: Set<CategoryOption> = emptySet(),
)

/** Maps UNNotificationRequest */
public data class NotificationRequest(
    val identifier: String,
    val content: NotificationContent,
    val trigger: NotificationTrigger? = null,
)

/** Maps UNNotificationSettings (read-only) */
public data class NotificationSettings(
    val authorizationStatus: AuthorizationStatus,
    val soundSetting: NotificationSetting,
    val badgeSetting: NotificationSetting,
    val alertSetting: NotificationSetting,
    val notificationCenterSetting: NotificationSetting,
    val lockScreenSetting: NotificationSetting,
    val alertStyle: AlertStyle,
    val showPreviewsSetting: ShowPreviewsSetting,
    val criticalAlertSetting: NotificationSetting,
    val providesAppNotificationSettings: Boolean,
    val timeSensitiveSetting: NotificationSetting,
    val directMessagesSetting: NotificationSetting,
    val scheduledDeliverySetting: NotificationSetting,
)

/** Maps UNNotification */
public data class DeliveredNotification(
    val identifier: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val date: Long,
    val categoryIdentifier: String,
    val threadIdentifier: String,
)

/** Maps UNNotificationResponse */
public data class NotificationResponse(
    val actionIdentifier: String,
    val notification: DeliveredNotification,
    val userText: String?,
)

/** Summary of a pending notification request returned by getPendingNotifications */
public data class PendingNotificationInfo(
    val identifier: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val categoryIdentifier: String,
    val threadIdentifier: String,
    val triggerType: Int,
    val triggerRepeats: Boolean,
    val triggerInterval: Double,
)

/** Summary of a registered notification category returned by getNotificationCategories */
public data class RegisteredCategoryInfo(
    val identifier: String,
    val optionsMask: Int,
    val actions: List<RegisteredActionInfo>,
)

/** Summary of a registered notification action within a category */
public data class RegisteredActionInfo(
    val identifier: String,
    val title: String,
    val optionsMask: Int,
    val isTextInput: Boolean,
    val textInputButtonTitle: String,
    val textInputPlaceholder: String,
)
