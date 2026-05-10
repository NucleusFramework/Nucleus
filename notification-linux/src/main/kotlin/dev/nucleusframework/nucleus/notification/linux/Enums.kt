package dev.nucleusframework.nucleus.notification.linux

/**
 * Notification urgency level as defined by the freedesktop specification.
 *
 * Servers may display low urgency notifications differently, and critical
 * notifications should not auto-expire.
 */
enum class Urgency(
    val value: Int,
) {
    LOW(0),
    NORMAL(1),
    CRITICAL(2),
    ;

    companion object {
        fun fromValue(value: Int): Urgency = entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

/**
 * Reason a notification was closed, as reported by the [NotificationClosed] signal.
 */
@Suppress("MagicNumber")
enum class CloseReason(
    val value: Int,
) {
    /** The notification expired (timeout). */
    EXPIRED(1),

    /** The notification was dismissed by the user. */
    DISMISSED(2),

    /** The notification was closed by a call to [LinuxNotificationCenter.closeNotification]. */
    CLOSED(3),

    /** Undefined/reserved reason. */
    UNDEFINED(4),
    ;

    companion object {
        fun fromValue(value: Int): CloseReason = entries.firstOrNull { it.value == value } ?: UNDEFINED
    }
}
