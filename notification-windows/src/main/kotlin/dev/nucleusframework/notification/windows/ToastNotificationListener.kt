package dev.nucleusframework.notification.windows

/**
 * Listener for toast notification lifecycle events.
 *
 * All callbacks are dispatched on the Swing EDT for thread safety.
 */
public interface ToastNotificationListener {
    /**
     * Called when the user clicks the toast body or an action button.
     *
     * @param tag The notification tag (if set).
     * @param group The notification group (if set).
     * @param arguments The launch arguments or button arguments.
     * @param userInputs Map of input ID to user-provided value (text boxes, selections).
     */
    public fun onActivated(
        tag: String,
        group: String,
        arguments: String,
        userInputs: Map<String, String>,
    )

    /**
     * Called when the toast is dismissed.
     *
     * @param tag The notification tag (if set).
     * @param group The notification group (if set).
     * @param reason Why the toast was dismissed.
     */
    public fun onDismissed(
        tag: String,
        group: String,
        reason: DismissalReason,
    )

    /**
     * Called when the toast fails to display.
     *
     * @param tag The notification tag (if set).
     * @param group The notification group (if set).
     * @param errorCode The HRESULT error code.
     */
    public fun onFailed(
        tag: String,
        group: String,
        errorCode: Int,
    )
}
