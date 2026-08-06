package dev.nucleusframework.notification.linux

/**
 * Notification urgency level as defined by the freedesktop specification.
 *
 * Servers may display low urgency notifications differently, and critical
 * notifications should not auto-expire.
 */
public enum class Urgency(
    public val value: Int,
) {
    LOW(0),
    NORMAL(1),
    CRITICAL(2),
    ;

    public companion object {
        public fun fromValue(value: Int): Urgency = entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

/**
 * Reason a notification was closed, as reported by the [NotificationClosed] signal.
 */
@Suppress("MagicNumber")
public enum class CloseReason(
    public val value: Int,
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

    public companion object {
        public fun fromValue(value: Int): CloseReason = entries.firstOrNull { it.value == value } ?: UNDEFINED
    }
}
