package dev.nucleusframework.nucleus.notification

/**
 * Maps UNUserNotificationCenterDelegate.
 *
 * Implement this interface to handle foreground notification presentation,
 * user interactions with notifications, and notification settings navigation.
 */
interface NotificationCenterDelegate {
    /**
     * Called when a notification is about to be presented while the app is in the foreground.
     * Return the set of presentation options to use. Return an empty set to silently handle it.
     *
     * Maps: userNotificationCenter(_:willPresent:withCompletionHandler:)
     */
    fun willPresent(notification: DeliveredNotification): Set<PresentationOption>

    /**
     * Called when the user interacts with a notification (taps it or an action button).
     *
     * Maps: userNotificationCenter(_:didReceive:withCompletionHandler:)
     */
    fun didReceive(response: NotificationResponse)

    /**
     * Called when the user taps the notification settings button.
     * The notification parameter is non-null if triggered from a specific notification.
     *
     * Maps: userNotificationCenter(_:openSettingsFor:)
     */
    fun openSettings(notification: DeliveredNotification?) {}
}
