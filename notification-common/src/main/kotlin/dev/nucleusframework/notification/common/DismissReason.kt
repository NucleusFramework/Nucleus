package dev.nucleusframework.notification.common

/** Unified reason why a notification was dismissed, mapped from platform-specific reasons. */
public enum class DismissReason {
    /** The user explicitly dismissed the notification. */
    USER_DISMISSED,

    /** The notification timed out and disappeared automatically. */
    TIMED_OUT,

    /** The notification was closed programmatically by the application. */
    APPLICATION,

    /** The reason could not be determined. */
    UNKNOWN,
}
