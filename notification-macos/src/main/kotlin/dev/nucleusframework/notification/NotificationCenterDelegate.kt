package dev.nucleusframework.notification

/**
 * Maps UNUserNotificationCenterDelegate.
 *
 * Implement this interface to handle foreground notification presentation,
 * user interactions with notifications, and notification settings navigation.
 *
 * Callbacks are not dispatched to AWT/Swing. Marshal to the active UI
 * dispatcher before touching UI state.
 */
public interface NotificationCenterDelegate {
    /**
     * Called when a notification is about to be presented while the app is in the foreground.
     * Return the set of presentation options to use. Return an empty set to silently handle it.
     * This callback is synchronous and must return quickly.
     *
     * Maps: userNotificationCenter(_:willPresent:withCompletionHandler:)
     */
    public fun willPresent(notification: DeliveredNotification): Set<PresentationOption>

    /**
     * Called when the user interacts with a notification (taps it or an action button).
     *
     * Maps: userNotificationCenter(_:didReceive:withCompletionHandler:)
     */
    public fun didReceive(response: NotificationResponse)

    /**
     * Called when the user taps the notification settings button.
     * The notification parameter is non-null if triggered from a specific notification.
     *
     * Maps: userNotificationCenter(_:openSettingsFor:)
     */
    public fun openSettings(notification: DeliveredNotification?) {}
}
