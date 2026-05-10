package dev.nucleusframework.nucleus.notification.common

/** Result of sending a notification. */
sealed class NotificationResult {
    /** The notification was sent successfully. */
    data class Success(
        val handle: NotificationHandle,
    ) : NotificationResult()

    /** The notification could not be sent. */
    data class Failure(
        val reason: String,
    ) : NotificationResult()
}
