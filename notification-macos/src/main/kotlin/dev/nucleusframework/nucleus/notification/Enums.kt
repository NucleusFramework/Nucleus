@file:Suppress("MagicNumber", "MaxLineLength")

package dev.nucleusframework.nucleus.notification

/** Maps UNAuthorizationStatus */
enum class AuthorizationStatus(
    val rawValue: Int,
) {
    NOT_DETERMINED(0),
    DENIED(1),
    AUTHORIZED(2),
    PROVISIONAL(3),
    EPHEMERAL(4),
    ;

    companion object {
        fun fromRawValue(value: Int): AuthorizationStatus =
            entries.firstOrNull { it.rawValue == value } ?: NOT_DETERMINED
    }
}

/** Maps UNNotificationSetting */
enum class NotificationSetting(
    val rawValue: Int,
) {
    NOT_SUPPORTED(0),
    DISABLED(1),
    ENABLED(2),
    ;

    companion object {
        fun fromRawValue(value: Int): NotificationSetting =
            entries.firstOrNull { it.rawValue == value } ?: NOT_SUPPORTED
    }
}

/** Maps UNAlertStyle */
enum class AlertStyle(
    val rawValue: Int,
) {
    NONE(0),
    BANNER(1),
    ALERT(2),
    ;

    companion object {
        fun fromRawValue(value: Int): AlertStyle = entries.firstOrNull { it.rawValue == value } ?: NONE
    }
}

/** Maps UNShowPreviewsSetting */
enum class ShowPreviewsSetting(
    val rawValue: Int,
) {
    ALWAYS(0),
    WHEN_AUTHENTICATED(1),
    NEVER(2),
    ;

    companion object {
        fun fromRawValue(value: Int): ShowPreviewsSetting = entries.firstOrNull { it.rawValue == value } ?: ALWAYS
    }
}

/** Maps UNNotificationInterruptionLevel (macOS 12.0+) */
enum class InterruptionLevel(
    val rawValue: Int,
) {
    PASSIVE(0),
    ACTIVE(1),
    TIME_SENSITIVE(2),
    CRITICAL(3),
    ;

    companion object {
        fun fromRawValue(value: Int): InterruptionLevel = entries.firstOrNull { it.rawValue == value } ?: ACTIVE
    }
}

/** Maps UNAuthorizationOptions (bitmask) */
enum class AuthorizationOption(
    val rawValue: Int,
) {
    BADGE(1 shl 0),
    SOUND(1 shl 1),
    ALERT(1 shl 2),
    CRITICAL_ALERT(1 shl 4),
    PROVIDES_APP_NOTIFICATION_SETTINGS(1 shl 5),
    PROVISIONAL(1 shl 6),
    TIME_SENSITIVE(1 shl 8),
}

/** Maps UNNotificationPresentationOptions (bitmask) */
enum class PresentationOption(
    val rawValue: Int,
) {
    BADGE(1 shl 0),
    SOUND(1 shl 1),

    @Deprecated("Use BANNER and/or LIST instead", replaceWith = ReplaceWith("BANNER"))
    ALERT(1 shl 2),
    LIST(1 shl 3),
    BANNER(1 shl 4),
}

/** Maps UNNotificationActionOptions (bitmask) */
enum class ActionOption(
    val rawValue: Int,
) {
    AUTHENTICATION_REQUIRED(1 shl 0),
    DESTRUCTIVE(1 shl 1),
    FOREGROUND(1 shl 2),
}

/** Maps UNNotificationCategoryOptions (bitmask) */
enum class CategoryOption(
    val rawValue: Int,
) {
    CUSTOM_DISMISS_ACTION(1 shl 0),
    ALLOW_IN_CAR_PLAY(1 shl 1),
    HIDDEN_PREVIEWS_SHOW_TITLE(1 shl 2),
    HIDDEN_PREVIEWS_SHOW_SUBTITLE(1 shl 3),
    ALLOW_ANNOUNCEMENT(1 shl 4),
}

/** Converts a Set of bitmask enums to a combined Int mask */
internal fun <T> Set<T>.toMask(rawValue: (T) -> Int): Int = fold(0) { acc, option -> acc or rawValue(option) }

/** Converts a combined Int mask back to a Set of bitmask enums */
internal inline fun <reified T : Enum<T>> Int.toOptionSet(rawValue: (T) -> Int): Set<T> =
    enumValues<T>().filter { this and rawValue(it) != 0 }.toSet()
