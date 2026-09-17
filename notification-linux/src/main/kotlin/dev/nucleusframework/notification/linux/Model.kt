package dev.nucleusframework.notification.linux

import dev.nucleusframework.freedesktop.icons.FreedesktopIcon

/**
 * Raw image data to embed directly in a notification.
 *
 * The pixel data must be in RGB or RGBA byte order (not pre-multiplied).
 * This corresponds to the `image-data` hint with D-Bus signature `(iiibiiay)`.
 *
 * @property width    Image width in pixels.
 * @property height   Image height in pixels.
 * @property rowstride Number of bytes per row (usually width * channels, may include padding).
 * @property hasAlpha Whether the image has an alpha channel.
 * @property bitsPerSample Bits per color channel (must be 8).
 * @property channels Number of channels (3 for RGB, 4 for RGBA).
 * @property data     Raw pixel bytes in RGB(A) order.
 */
public data class ImageData(
    val width: Int,
    val height: Int,
    val rowstride: Int,
    val hasAlpha: Boolean,
    @Suppress("MagicNumber") val bitsPerSample: Int = 8,
    @Suppress("MagicNumber") val channels: Int = if (hasAlpha) 4 else 3,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageData) return false
        return width == other.width &&
            height == other.height &&
            rowstride == other.rowstride &&
            hasAlpha == other.hasAlpha &&
            bitsPerSample == other.bitsPerSample &&
            channels == other.channels &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rowstride
        result = 31 * result + hasAlpha.hashCode()
        result = 31 * result + bitsPerSample
        result = 31 * result + channels
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Information about the notification server.
 *
 * Returned by [LinuxNotificationCenter.getServerInformation].
 */
public data class ServerInformation(
    val name: String,
    val vendor: String,
    val version: String,
    val specVersion: String,
)

/**
 * An interactive action button on a notification.
 *
 * @property key   Unique identifier for this action; use [DEFAULT_KEY] for the default click action.
 * @property label Human-readable label shown on the button.
 */
public data class NotificationAction(
    val key: String,
    val label: String,
) {
    public companion object {
        /** Special action key that maps to clicking the notification body itself. */
        public const val DEFAULT_KEY: String = "default"
    }
}

/**
 * Standard hints defined by the freedesktop Desktop Notifications specification.
 *
 * All properties are optional; `null` means the hint is not sent.
 *
 * @property urgency       Notification urgency level.
 * @property category      Notification type category (e.g. `"email.arrived"`, `"im.received"`).
 * @property desktopEntry  The desktop file name (without `.desktop` suffix) for the calling application.
 * @property imageData     Raw image data to display in the notification.
 * @property imagePath     Icon name or file URI for the notification image (see [FreedesktopIcon]).
 *                         Takes priority over [Notification.appIcon] per the spec.
 * @property actionIcons   If `true`, action keys are interpreted as icon names.
 * @property soundFile     Path to a sound file to play.
 * @property soundName     Sound from the freedesktop sound theme (see [NotificationSound]).
 * @property suppressSound If `true`, suppress any notification sound.
 * @property resident      If `true`, the notification is not removed when an action is invoked.
 * @property transient     If `true`, the notification should bypass the notification log/history.
 * @property x             X screen position hint (requires [y] to be set too).
 * @property y             Y screen position hint (requires [x] to be set too).
 */
public data class NotificationHints(
    val urgency: Urgency? = null,
    val category: String? = null,
    val desktopEntry: String? = null,
    val imageData: ImageData? = null,
    val imagePath: FreedesktopIcon? = null,
    val actionIcons: Boolean? = null,
    val soundFile: String? = null,
    val soundName: NotificationSound? = null,
    val suppressSound: Boolean? = null,
    val resident: Boolean? = null,
    val transient: Boolean? = null,
    val x: Int? = null,
    val y: Int? = null,
)

/**
 * A desktop notification to display via the freedesktop notification service.
 *
 * @property appName       Application name (informational, may be shown by the server).
 * @property replacesId    If non-zero, atomically replaces the notification with this ID.
 * @property appIcon       Application icon (see [FreedesktopIcon]). On GNOME Shell, prefer
 *                         [NotificationHints.imagePath] which is more reliably displayed.
 * @property summary       Single-line summary text (required).
 * @property body          Optional multi-line body; may contain limited markup:
 *                         `<b>`, `<i>`, `<u>`, `<a href="...">`, `<img src="..." alt="..."/>`.
 * @property actions       Interactive action buttons; use [NotificationAction.DEFAULT_KEY] for the
 *                         default click action.
 * @property hints         Notification hints (urgency, images, sounds, etc.).
 * @property expireTimeout Timeout in milliseconds: `-1` = server default, `0` = never expires,
 *                         positive = auto-close after that many milliseconds.
 */
public data class Notification(
    val appName: String = "",
    val replacesId: Int = 0,
    val appIcon: FreedesktopIcon? = null,
    val summary: String,
    val body: String = "",
    val actions: List<NotificationAction> = emptyList(),
    val hints: NotificationHints = NotificationHints(),
    val expireTimeout: Int = -1,
)
