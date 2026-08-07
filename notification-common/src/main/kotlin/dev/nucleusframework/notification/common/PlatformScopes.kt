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
public class LinuxNotificationScope internal constructor() {
    public var urgency: Urgency? = null
    public var category: String? = null
    public var transient: Boolean? = null
    public var resident: Boolean? = null
    public var expireTimeout: Int? = null
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
public class MacNotificationScope internal constructor() {
    public var interruptionLevel: InterruptionLevel? = null
    public var relevanceScore: Double? = null
    public var subtitle: String? = null
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
public class WindowsNotificationScope internal constructor() {
    public var scenario: ToastScenario? = null
    public var duration: ToastDuration? = null
}
