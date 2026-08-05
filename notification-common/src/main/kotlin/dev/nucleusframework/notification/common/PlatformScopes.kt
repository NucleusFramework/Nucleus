package dev.nucleusframework.notification.common

import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario

/**
 * Linux-specific notification options, configured via the `linux { }` block of the
 * [notification] DSL. These are applied only when the notification is delivered on Linux;
 * on any other platform the block is ignored.
 *
 * The cross-platform [notification] fields (title, message, image, buttons, callbacks) are
 * always used as the base — these options only add Linux-specific behavior on top.
 *
 * @property urgency freedesktop urgency hint. `CRITICAL` is shown prominently and, on most
 *   servers, breaks through Do Not Disturb and never auto-expires.
 * @property category Notification type category, e.g. `"email.arrived"` or `"im.received"`.
 * @property transient If `true`, the notification bypasses the notification log/history.
 * @property resident If `true`, the notification is not removed when one of its actions is invoked.
 * @property expireTimeout Auto-dismiss timeout in milliseconds: `null`/`-1` = server default,
 *   `0` = never expires, positive = auto-close after that many milliseconds.
 */
@NotificationDsl
class LinuxNotificationScope internal constructor() {
    var urgency: Urgency? = null
    var category: String? = null
    var transient: Boolean? = null
    var resident: Boolean? = null
    var expireTimeout: Int? = null
}

/**
 * macOS-specific notification options, configured via the `macos { }` block of the
 * [notification] DSL. Applied only when the notification is delivered on macOS.
 *
 * @property interruptionLevel `UNNotificationInterruptionLevel` (macOS 12+). `TIME_SENSITIVE`
 *   and `CRITICAL` require the corresponding Apple entitlement; without it the system falls
 *   back to `ACTIVE`.
 * @property relevanceScore Score in `0.0..1.0` used to sort the app's notifications in a group.
 * @property subtitle Subtitle shown between the title and the body.
 */
@NotificationDsl
class MacNotificationScope internal constructor() {
    var interruptionLevel: InterruptionLevel? = null
    var relevanceScore: Double? = null
    var subtitle: String? = null
}

/**
 * Windows-specific notification options, configured via the `windows { }` block of the
 * [notification] DSL. Applied only when the notification is delivered on Windows.
 *
 * @property scenario Toast scenario. `URGENT` (Windows 11+) breaks through Focus Assist;
 *   `REMINDER`/`ALARM` keep the toast on screen until dismissed.
 * @property duration How long the toast stays on screen before moving to the Action Center.
 */
@NotificationDsl
class WindowsNotificationScope internal constructor() {
    var scenario: ToastScenario? = null
    var duration: ToastDuration? = null
}
