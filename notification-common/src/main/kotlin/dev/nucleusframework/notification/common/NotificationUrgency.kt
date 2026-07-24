package dev.nucleusframework.notification.common

/**
 * Cross-platform notification urgency.
 *
 * This is the only prominence hint exposed by the common API, because it is the single
 * concept that maps to a real, first-class native equivalent on every supported OS:
 *
 * | Common      | Linux (freedesktop) | macOS (UserNotifications)          | Windows (WinRT)                        |
 * |-------------|---------------------|------------------------------------|----------------------------------------|
 * | [LOW]       | `urgency = low`     | `interruptionLevel = passive`      | default priority                       |
 * | [NORMAL]    | `urgency = normal`  | `interruptionLevel = active`       | default priority                       |
 * | [CRITICAL]  | `urgency = critical`| `interruptionLevel = timeSensitive`| high priority + `scenario = urgent`    |
 *
 * Notes:
 * - macOS: [CRITICAL] maps to `timeSensitive` (breaks through Focus / Do Not Disturb when the user
 *   allows time-sensitive notifications for the app), not the OS `critical` level. The `critical`
 *   level requires the restricted `com.apple.developer.usernotifications.critical-alerts`
 *   entitlement, which Apple grants manually per App ID and which must be embedded in a
 *   provisioning profile even for Developer ID (non-App-Store) apps — so it is unusable by a
 *   general-purpose library and is not exposed here. `timeSensitive` does not change the
 *   notification's expiration/lifetime. `interruptionLevel` requires macOS 12+ and is ignored on
 *   older systems.
 * - Windows: `put_Priority(High)` only affects connected-standby screen wake and Action Center
 *   ordering, so it is not visibly different on a normal desktop. [CRITICAL] therefore also uses
 *   the `urgent` scenario, which gives the toast distinct visual treatment and breaks through
 *   Focus Assist / Do Not Disturb (Windows 10 build 19041+; ignored on older builds). [LOW] and
 *   [NORMAL] behave identically on Windows.
 *
 * Platform-specific hints that are **not** common across all OSes (expire timeout, transient,
 * resident, freedesktop category, macOS relevance score, etc.) are intentionally left out of the
 * common API and must be set through the per-OS modules directly.
 */
enum class NotificationUrgency {
    /** Low priority; may be shown less prominently or omitted from Do-Not-Disturb breakthrough. */
    LOW,

    /** Default priority. */
    NORMAL,

    /**
     * High priority and time-sensitive: breaks through Focus / Do Not Disturb where the platform
     * and user settings allow. On Linux it also stays resident until dismissed; macOS and Windows
     * do not change the notification's lifetime.
     */
    CRITICAL,
}
