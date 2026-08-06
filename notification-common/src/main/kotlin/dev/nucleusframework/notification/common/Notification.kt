package dev.nucleusframework.notification.common

private const val MAX_BUTTONS = 5

/**
 * A cross-platform notification built via the [notification] DSL function.
 *
 * Call [send] to display the notification on the current platform.
 * The same instance can be sent multiple times (each call creates a new notification).
 */
public class Notification internal constructor(
    public val title: String,
    public val message: String,
    public val largeImage: String?,
    public val smallIcon: String?,
    public val buttons: List<NotificationButton>,
    public val onActivated: (() -> Unit)?,
    public val onDismissed: ((DismissReason) -> Unit)?,
    public val onFailed: (() -> Unit)?,
    internal val linux: LinuxNotificationScope?,
    internal val macos: MacNotificationScope?,
    internal val windows: WindowsNotificationScope?,
) {
    /** Sends this notification to the OS notification system. */
    public fun send(): NotificationResult = NotificationManager.send(this)
}

/** An action button on a notification. */
@ConsistentCopyVisibility
public data class NotificationButton internal constructor(
    val title: String,
    val onClick: () -> Unit,
)

@DslMarker
public annotation class NotificationDsl

/**
 * Builder for a cross-platform notification.
 *
 * Add up to [MAX_BUTTONS] action [button]s, and optionally attach platform-specific behavior
 * via the [linux], [macos], and [windows] blocks. Each platform block is applied only when the
 * notification is delivered on that OS — the semantics that differ between platforms (urgency,
 * expiry, history, etc.) live there rather than in a lossy shared abstraction.
 */
@NotificationDsl
public class NotificationBuilder internal constructor() {
    internal val buttons = mutableListOf<NotificationButton>()
    internal var linuxScope: LinuxNotificationScope? = null
    internal var macScope: MacNotificationScope? = null
    internal var windowsScope: WindowsNotificationScope? = null

    /** Adds a button with the given [title] and [onClick] handler. Max $MAX_BUTTONS buttons. */
    public fun button(
        title: String,
        onClick: () -> Unit,
    ) {
        require(buttons.size < MAX_BUTTONS) { "Maximum $MAX_BUTTONS buttons allowed" }
        buttons.add(NotificationButton(title, onClick))
    }

    /** Configures Linux-specific options (see [LinuxNotificationScope]); a no-op on other platforms. */
    public fun linux(block: LinuxNotificationScope.() -> Unit) {
        linuxScope = (linuxScope ?: LinuxNotificationScope()).apply(block)
    }

    /** Configures macOS-specific options (see [MacNotificationScope]); a no-op on other platforms. */
    public fun macos(block: MacNotificationScope.() -> Unit) {
        macScope = (macScope ?: MacNotificationScope()).apply(block)
    }

    /** Configures Windows-specific options (see [WindowsNotificationScope]); a no-op on other platforms. */
    public fun windows(block: WindowsNotificationScope.() -> Unit) {
        windowsScope = (windowsScope ?: WindowsNotificationScope()).apply(block)
    }
}

/** Former name of [NotificationBuilder], kept for source compatibility. */
@Deprecated("Renamed to NotificationBuilder", ReplaceWith("NotificationBuilder"))
public typealias NotificationButtonBuilder = NotificationBuilder

/**
 * Creates a cross-platform notification.
 * Lifecycle callbacks are not guaranteed to run on a UI thread.
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
 *
 *     // Platform-specific behavior, honored only on the matching OS:
 *     linux { urgency = Urgency.CRITICAL; transient = true; category = "device.error" }
 *     macos { interruptionLevel = InterruptionLevel.TIME_SENSITIVE }
 *     windows { scenario = ToastScenario.URGENT }
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
 * @param block Optional DSL block to add action buttons and per-platform options.
 */
public fun notification(
    title: String,
    message: String = "",
    largeImage: String? = null,
    smallIcon: String? = null,
    onActivated: (() -> Unit)? = null,
    onDismissed: ((DismissReason) -> Unit)? = null,
    onFailed: (() -> Unit)? = null,
    block: (NotificationBuilder.() -> Unit)? = null,
): Notification {
    val builder = block?.let { NotificationBuilder().apply(it) }
    return Notification(
        title = title,
        message = message,
        largeImage = largeImage,
        smallIcon = smallIcon,
        buttons = builder?.buttons?.toList() ?: emptyList(),
        onActivated = onActivated,
        onDismissed = onDismissed,
        onFailed = onFailed,
        linux = builder?.linuxScope,
        macos = builder?.macScope,
        windows = builder?.windowsScope,
    )
}
