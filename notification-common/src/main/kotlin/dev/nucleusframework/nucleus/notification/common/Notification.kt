package dev.nucleusframework.nucleus.notification.common

private const val MAX_BUTTONS = 5

/**
 * A cross-platform notification built via the [notification] DSL function.
 *
 * Call [send] to display the notification on the current platform.
 * The same instance can be sent multiple times (each call creates a new notification).
 */
class Notification internal constructor(
    val title: String,
    val message: String,
    val largeImage: String?,
    val smallIcon: String?,
    val buttons: List<NotificationButton>,
    val onActivated: (() -> Unit)?,
    val onDismissed: ((DismissReason) -> Unit)?,
    val onFailed: (() -> Unit)?,
) {
    /** Sends this notification to the OS notification system. */
    fun send(): NotificationResult = NotificationManager.send(this)
}

/** An action button on a notification. */
@ConsistentCopyVisibility
data class NotificationButton internal constructor(
    val title: String,
    val onClick: () -> Unit,
)

@DslMarker
annotation class NotificationDsl

/** Builder for notification action buttons. */
@NotificationDsl
class NotificationButtonBuilder internal constructor() {
    internal val buttons = mutableListOf<NotificationButton>()

    /** Adds a button with the given [title] and [onClick] handler. Max $MAX_BUTTONS buttons. */
    fun button(
        title: String,
        onClick: () -> Unit,
    ) {
        require(buttons.size < MAX_BUTTONS) { "Maximum $MAX_BUTTONS buttons allowed" }
        buttons.add(NotificationButton(title, onClick))
    }
}

/**
 * Creates a cross-platform notification.
 *
 * ```kotlin
 * val n = notification(
 *     title = "Download Complete",
 *     message = "report.pdf has been saved",
 *     onActivated = { openFile() },
 *     onDismissed = { reason -> log("dismissed: $reason") },
 * ) {
 *     button("Open") { openFile() }
 *     button("Show in Folder") { showInFolder() }
 * }
 * n.send()
 * ```
 *
 * @param title The notification title (required).
 * @param message The notification body text.
 * @param largeImage URI to a large image (hero image on Windows, image hint on Linux, attachment on macOS).
 * @param smallIcon URI to a small icon (app logo override on Windows, app icon on Linux, ignored on macOS).
 * @param onActivated Called when the user clicks the notification body.
 * @param onDismissed Called when the notification is dismissed, with the [DismissReason].
 * @param onFailed Called if the notification fails to display.
 * @param buttons Optional DSL block to add up to 5 action buttons.
 */
fun notification(
    title: String,
    message: String = "",
    largeImage: String? = null,
    smallIcon: String? = null,
    onActivated: (() -> Unit)? = null,
    onDismissed: ((DismissReason) -> Unit)? = null,
    onFailed: (() -> Unit)? = null,
    buttons: (NotificationButtonBuilder.() -> Unit)? = null,
): Notification {
    val buttonList =
        if (buttons != null) {
            NotificationButtonBuilder().apply(buttons).buttons.toList()
        } else {
            emptyList()
        }
    return Notification(title, message, largeImage, smallIcon, buttonList, onActivated, onDismissed, onFailed)
}
