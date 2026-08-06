package dev.nucleusframework.notification.linux

/**
 * Listener for asynchronous notification signals from the freedesktop notification server.
 *
 * All callbacks are dispatched on the Swing EDT.
 * Register via [LinuxNotificationCenter.addListener]; signal monitoring starts automatically
 * when the first listener is added and stops when the last is removed.
 */
public interface LinuxNotificationListener {
    /**
     * Called when a notification is closed.
     *
     * @param notificationId The ID of the closed notification.
     * @param reason         Why the notification was closed.
     */
    public fun onClosed(
        notificationId: Int,
        reason: CloseReason,
    ) {}

    /**
     * Called when the user invokes an action on a notification.
     *
     * @param notificationId The ID of the notification whose action was invoked.
     * @param actionKey      The key of the invoked action (e.g. [NotificationAction.DEFAULT_KEY]).
     */
    public fun onActionInvoked(
        notificationId: Int,
        actionKey: String,
    ) {}

    /**
     * Called when the server provides an activation token (Wayland/X11).
     *
     * The token can be used to request window focus via the platform's activation protocol.
     *
     * @param notificationId The ID of the notification.
     * @param token          The activation token string.
     */
    public fun onActivationToken(
        notificationId: Int,
        token: String,
    ) {}
}
