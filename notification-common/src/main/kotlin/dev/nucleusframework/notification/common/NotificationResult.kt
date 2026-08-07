package dev.nucleusframework.notification.common

/** Result of sending a notification. */
public sealed class NotificationResult {
    /** The notification was sent successfully. */
    public data class Success(
        val handle: NotificationHandle,
    ) : NotificationResult()

    /** The notification could not be sent. */
    public data class Failure(
        val reason: String,
    ) : NotificationResult()
}
