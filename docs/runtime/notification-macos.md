# Notification (macOS)

Complete Kotlin mapping of Apple's [UserNotifications](https://developer.apple.com/documentation/usernotifications) framework via JNI. Schedule local notifications with action buttons, text input, sounds, badges, and interruption levels.

!!! warning "Requires a packaged app"
    macOS notifications require a bundle identifier. Use `./gradlew runDistributable` or `./gradlew runGraalvmNative` — notifications are disabled when running via `./gradlew run`.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.notification-macos:<version>")
}
```

Depends on `core-runtime` for `NativeLibraryLoader` and `ExecutableRuntime` (pulled in transitively).

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.notification.*

// 1. Request authorization
NotificationCenter.requestAuthorization(
    setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE)
) { granted, error ->
    if (granted) {
        // 2. Send a notification
        NotificationCenter.add(
            NotificationRequest(
                identifier = "greeting",
                content = NotificationContent(
                    title = "Hello",
                    body = "Welcome to Nucleus!",
                    sound = NotificationSound.Default,
                ),
                trigger = NotificationTrigger.TimeInterval(interval = 5.0),
            )
        )
    }
}
```

!!! info "Async callbacks run on background threads"
    Completion callbacks (`requestAuthorization`, `add`, `getNotificationSettings`, etc.) are invoked on macOS's internal dispatch thread. Use `SwingUtilities.invokeLater` if you need to update Compose state from these callbacks. Delegate methods (`willPresent`, `didReceive`, `openSettings`) are automatically dispatched to the EDT by the library.

## API Reference

### `NotificationCenter`

| Property / Method | Description |
|---|---|
| `isAvailable: Boolean` | `true` if native lib loaded, macOS, and not in dev mode |

#### Authorization

| Method | Description |
|---|---|
| `requestAuthorization(options, callback)` | Request permission to post notifications. Callback: `(granted: Boolean, error: String?) -> Unit` |
| `getNotificationSettings(callback)` | Retrieve current notification settings. Callback: `(NotificationSettings) -> Unit` |

#### Notification Requests

| Method | Description |
|---|---|
| `add(request, callback?)` | Schedule or immediately deliver a notification. Callback: `(error: String?) -> Unit` |
| `removePendingNotifications(identifiers)` | Remove pending (not yet delivered) notifications by ID |
| `removeAllPendingNotifications()` | Remove all pending notifications |
| `getPendingNotifications(callback)` | Retrieve pending requests. Callback: `(List<PendingNotificationInfo>) -> Unit` |
| `removeDeliveredNotifications(identifiers)` | Remove delivered notifications from Notification Center by ID |
| `removeAllDeliveredNotifications()` | Remove all delivered notifications |
| `getDeliveredNotifications(callback)` | Retrieve delivered notifications. Callback: `(List<DeliveredNotification>) -> Unit` |

#### Categories & Actions

| Method | Description |
|---|---|
| `setNotificationCategories(categories)` | Register notification categories with action buttons |
| `getNotificationCategories(callback)` | Retrieve registered categories. Callback: `(List<RegisteredCategoryInfo>) -> Unit` |

#### Badge

| Method | Description |
|---|---|
| `setBadgeCount(count, callback?)` | Set the dock badge count. Uses `UNUserNotificationCenter.setBadgeCount` on macOS 13+, `NSDockTile` fallback on older. Callback: `(error: String?) -> Unit` |
| `getBadgeCount(callback)` | Get current badge count. Callback: `(Int) -> Unit` |

#### Delegate

| Method | Description |
|---|---|
| `setDelegate(delegate?)` | Set a `NotificationCenterDelegate` for foreground presentation and action callbacks. Pass `null` to remove. Without a delegate, notifications show banner + sound + list by default. |

---

### `NotificationCenterDelegate`

Implement this interface to control foreground notification display and handle user interactions.

!!! info "Thread safety"
    All delegate methods are automatically dispatched to the AWT Event Dispatch Thread by the library. You can safely update Compose state (`mutableStateOf`, `SnapshotStateList`, etc.) directly in your delegate implementation without manual thread marshalling.

    `willPresent` runs via `invokeAndWait` (synchronous, must return quickly). `didReceive` and `openSettings` run via `invokeLater` (asynchronous).

```kotlin
NotificationCenter.setDelegate(object : NotificationCenterDelegate {
    override fun willPresent(notification: DeliveredNotification): Set<PresentationOption> {
        // Return which presentation to use when app is in foreground
        return setOf(PresentationOption.BANNER, PresentationOption.SOUND)
    }

    override fun didReceive(response: NotificationResponse) {
        when (response.actionIdentifier) {
            "reply" -> println("User replied: ${response.userText}")
            "archive" -> println("Archived")
            NotificationAction.DEFAULT_ACTION_IDENTIFIER -> println("Tapped notification")
            NotificationAction.DISMISS_ACTION_IDENTIFIER -> println("Dismissed")
        }
    }

    override fun openSettings(notification: DeliveredNotification?) {
        // Optional: user tapped notification settings button
    }
})
```

| Method | Returns | Description |
|---|---|---|
| `willPresent(notification)` | `Set<PresentationOption>` | Called when notification arrives while app is foreground. Return empty set to suppress. |
| `didReceive(response)` | `Unit` | Called when user taps the notification or an action button. |
| `openSettings(notification?)` | `Unit` | Called when user taps the settings button. Optional, default no-op. |

---

### Data Types

#### `NotificationContent`

| Property | Type | Default | Description |
|---|---|---|---|
| `title` | `String` | `""` | Notification title |
| `subtitle` | `String` | `""` | Notification subtitle |
| `body` | `String` | `""` | Notification body text |
| `badge` | `Int?` | `null` | Badge count (`null` = don't change) |
| `sound` | `NotificationSound?` | `null` | Sound to play |
| `userInfo` | `Map<String, String>` | `emptyMap()` | Custom key-value data |
| `attachments` | `List<NotificationAttachment>` | `emptyList()` | Media attachments (file paths) |
| `threadIdentifier` | `String` | `""` | Thread ID for grouping |
| `categoryIdentifier` | `String` | `""` | Category ID (links to registered actions) |
| `targetContentIdentifier` | `String` | `""` | Content identifier (macOS 11+) |
| `interruptionLevel` | `InterruptionLevel` | `ACTIVE` | Interruption level (macOS 12+) |
| `relevanceScore` | `Double` | `0.0` | Relevance score for sorting (macOS 12+) |

#### `NotificationRequest`

| Property | Type | Description |
|---|---|---|
| `identifier` | `String` | Unique request ID (reusing an ID replaces the previous notification) |
| `content` | `NotificationContent` | Notification content |
| `trigger` | `NotificationTrigger?` | Delivery trigger (`null` is not supported — use `TimeInterval(1.0)` for near-immediate) |

#### `NotificationTrigger`

```kotlin
// Fire after 30 seconds
NotificationTrigger.TimeInterval(interval = 30.0)

// Fire every day at 9:00
NotificationTrigger.Calendar(
    dateComponents = DateComponents(hour = 9, minute = 0),
    repeats = true,
)
```

| Subclass | Properties | Description |
|---|---|---|
| `TimeInterval` | `interval: Double`, `repeats: Boolean` | Fire after N seconds. Repeating requires `interval >= 60`. |
| `Calendar` | `dateComponents: DateComponents`, `repeats: Boolean` | Fire when date/time components match. |

#### `DateComponents`

All fields are optional (`null` = wildcard).

| Property | Type | Description |
|---|---|---|
| `year` | `Int?` | Year |
| `month` | `Int?` | Month (1–12) |
| `day` | `Int?` | Day of month (1–31) |
| `hour` | `Int?` | Hour (0–23) |
| `minute` | `Int?` | Minute (0–59) |
| `second` | `Int?` | Second (0–59) |
| `weekday` | `Int?` | Day of week (1=Sunday, 7=Saturday) |

#### `NotificationSound`

| Variant | Description |
|---|---|
| `NotificationSound.Default` | Default system sound |
| `NotificationSound.Named(name)` | Custom sound from app bundle |
| `NotificationSound.DefaultCritical` | Default critical alert sound (requires entitlement) |
| `NotificationSound.DefaultCriticalWithVolume(volume)` | Critical sound with custom volume (0.0–1.0) |
| `NotificationSound.CriticalNamed(name, volume)` | Named critical sound with volume |

#### `NotificationAttachment`

| Property | Type | Description |
|---|---|---|
| `identifier` | `String` | Unique attachment ID |
| `url` | `String` | Absolute file path to the media (image, audio, video) |

#### `NotificationAction`

```kotlin
// Simple button
NotificationAction(
    identifier = "archive",
    title = "Archive",
    options = setOf(ActionOption.DESTRUCTIVE),
)

// Text input button
TextInputNotificationAction(
    identifier = "reply",
    title = "Reply",
    options = setOf(ActionOption.FOREGROUND),
    textInputButtonTitle = "Send",
    textInputPlaceholder = "Type your reply...",
)
```

| Property | Type | Description |
|---|---|---|
| `identifier` | `String` | Unique action ID (received in `didReceive`) |
| `title` | `String` | Button label |
| `options` | `Set<ActionOption>` | Action behavior flags |

`TextInputNotificationAction` adds:

| Property | Type | Description |
|---|---|---|
| `textInputButtonTitle` | `String` | Submit button label |
| `textInputPlaceholder` | `String` | Input placeholder text |

Constants: `NotificationAction.DEFAULT_ACTION_IDENTIFIER`, `NotificationAction.DISMISS_ACTION_IDENTIFIER`.

#### `NotificationCategory`

```kotlin
NotificationCategory(
    identifier = "message",
    actions = listOf(replyAction, archiveAction),
    intentIdentifiers = emptyList(),
    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
)
```

| Property | Type | Description |
|---|---|---|
| `identifier` | `String` | Category ID (referenced by `NotificationContent.categoryIdentifier`) |
| `actions` | `List<NotificationAction>` | Action buttons (max 10, first 2 shown in compact view) |
| `intentIdentifiers` | `List<String>` | SiriKit intent identifiers |
| `options` | `Set<CategoryOption>` | Category behavior flags |

#### `NotificationSettings`

Read-only snapshot of the app's notification permissions.

| Property | Type | Description |
|---|---|---|
| `authorizationStatus` | `AuthorizationStatus` | Current authorization state |
| `soundSetting` | `NotificationSetting` | Sound permission |
| `badgeSetting` | `NotificationSetting` | Badge permission |
| `alertSetting` | `NotificationSetting` | Alert permission |
| `notificationCenterSetting` | `NotificationSetting` | Notification Center display |
| `lockScreenSetting` | `NotificationSetting` | Lock screen display |
| `alertStyle` | `AlertStyle` | Alert presentation style |
| `showPreviewsSetting` | `ShowPreviewsSetting` | Preview display setting |
| `criticalAlertSetting` | `NotificationSetting` | Critical alert permission |
| `providesAppNotificationSettings` | `Boolean` | Whether app provides custom settings UI |
| `timeSensitiveSetting` | `NotificationSetting` | Time-sensitive permission (macOS 12+) |
| `directMessagesSetting` | `NotificationSetting` | Direct messages permission (macOS 12+) |
| `scheduledDeliverySetting` | `NotificationSetting` | Scheduled delivery setting (macOS 15+) |

#### `NotificationResponse`

Received in `NotificationCenterDelegate.didReceive`.

| Property | Type | Description |
|---|---|---|
| `actionIdentifier` | `String` | Which action button was tapped |
| `notification` | `DeliveredNotification` | The original notification |
| `userText` | `String?` | Text entered by user (non-null for `TextInputNotificationAction`) |

#### `DeliveredNotification`

| Property | Type | Description |
|---|---|---|
| `identifier` | `String` | Notification request ID |
| `title` | `String` | Title |
| `subtitle` | `String` | Subtitle |
| `body` | `String` | Body |
| `date` | `Long` | Delivery timestamp (epoch millis) |
| `categoryIdentifier` | `String` | Category ID |
| `threadIdentifier` | `String` | Thread ID |

---

### Enums

#### `AuthorizationStatus`

| Value | Description |
|---|---|
| `NOT_DETERMINED` | User hasn't been asked yet |
| `DENIED` | User denied |
| `AUTHORIZED` | User granted |
| `PROVISIONAL` | Provisional (quiet) authorization |
| `EPHEMERAL` | Ephemeral authorization |

#### `AuthorizationOption` (bitmask)

| Value | Description |
|---|---|
| `BADGE` | Badge count |
| `SOUND` | Notification sounds |
| `ALERT` | Alerts (banners/notifications) |
| `CRITICAL_ALERT` | Critical alerts — **requires** `com.apple.developer.usernotifications.critical-alerts` entitlement. Including this option without the entitlement causes the entire authorization request to fail silently. |
| `PROVIDES_APP_NOTIFICATION_SETTINGS` | App provides custom settings |
| `PROVISIONAL` | Provisional authorization (no prompt) |
| `TIME_SENSITIVE` | Time-sensitive notifications (macOS 12+) — may require entitlement on some macOS versions |

#### `PresentationOption` (bitmask)

Returned by `willPresent` to control foreground display.

| Value | Description |
|---|---|
| `BADGE` | Update badge |
| `SOUND` | Play sound |
| `LIST` | Show in Notification Center list (macOS 11+) |
| `BANNER` | Show banner (macOS 11+) |

#### `InterruptionLevel`

| Value | Description |
|---|---|
| `PASSIVE` | No sound, no wake |
| `ACTIVE` | Default behavior |
| `TIME_SENSITIVE` | Breaks through Focus modes |
| `CRITICAL` | Always shows, even in DND (requires Apple entitlement) |

#### `ActionOption` (bitmask)

| Value | Description |
|---|---|
| `AUTHENTICATION_REQUIRED` | Device must be unlocked |
| `DESTRUCTIVE` | Red styling |
| `FOREGROUND` | Launches app to foreground |

#### `CategoryOption` (bitmask)

| Value | Description |
|---|---|
| `CUSTOM_DISMISS_ACTION` | Send dismiss action to delegate |
| `ALLOW_IN_CAR_PLAY` | Allow in CarPlay |
| `HIDDEN_PREVIEWS_SHOW_TITLE` | Show title when previews hidden |
| `HIDDEN_PREVIEWS_SHOW_SUBTITLE` | Show subtitle when previews hidden |
| `ALLOW_ANNOUNCEMENT` | Allow Siri announcement |

#### `NotificationSetting`

| Value | Description |
|---|---|
| `NOT_SUPPORTED` | Not available on this device |
| `DISABLED` | Disabled by user |
| `ENABLED` | Enabled |

#### `AlertStyle`

| Value | Description |
|---|---|
| `NONE` | No alerts |
| `BANNER` | Auto-dismissing banners |
| `ALERT` | Persistent alerts (require interaction) |

#### `ShowPreviewsSetting`

| Value | Description |
|---|---|
| `ALWAYS` | Always show previews |
| `WHEN_AUTHENTICATED` | Only when unlocked |
| `NEVER` | Never show previews |

---

## Full Example: Messaging Actions

```kotlin
// Register categories with action buttons
val replyAction = TextInputNotificationAction(
    identifier = "reply",
    title = "Reply",
    options = setOf(ActionOption.FOREGROUND),
    textInputButtonTitle = "Send",
    textInputPlaceholder = "Type your reply...",
)
val markReadAction = NotificationAction(identifier = "mark-read", title = "Mark as Read")
val deleteAction = NotificationAction(
    identifier = "delete",
    title = "Delete",
    options = setOf(ActionOption.DESTRUCTIVE),
)

NotificationCenter.setNotificationCategories(setOf(
    NotificationCategory(
        identifier = "message",
        actions = listOf(replyAction, markReadAction, deleteAction),
        options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
    )
))

// Set delegate to handle action callbacks
NotificationCenter.setDelegate(object : NotificationCenterDelegate {
    override fun willPresent(notification: DeliveredNotification) =
        setOf(PresentationOption.BANNER, PresentationOption.SOUND, PresentationOption.LIST)

    override fun didReceive(response: NotificationResponse) {
        when (response.actionIdentifier) {
            "reply" -> sendMessage(response.userText ?: "")
            "mark-read" -> markAsRead(response.notification.identifier)
            "delete" -> deleteMessage(response.notification.identifier)
            NotificationAction.DEFAULT_ACTION_IDENTIFIER -> openConversation()
        }
    }
})

// Send a notification with actions
NotificationCenter.add(
    NotificationRequest(
        identifier = "msg-42",
        content = NotificationContent(
            title = "Alice",
            subtitle = "Project Nucleus",
            body = "Hey! Have you seen the latest build?",
            sound = NotificationSound.Default,
            categoryIdentifier = "message",
            threadIdentifier = "conversation-alice",
        ),
        trigger = NotificationTrigger.TimeInterval(interval = 1.0),
    )
) { error ->
    if (error != null) println("Failed: $error")
}
```

## Critical Alerts

Critical alerts bypass Do Not Disturb and Focus modes. They require:

1. An Apple-issued entitlement (`com.apple.developer.usernotifications.critical-alerts`) — [request from Apple](https://developer.apple.com/contact/request/notifications-critical-alerts-entitlement/)
2. The entitlement declared in your `entitlements.plist`
3. `AuthorizationOption.CRITICAL_ALERT` in `requestAuthorization`

!!! danger "Do NOT include `CRITICAL_ALERT` without the entitlement"
    If you pass `AuthorizationOption.CRITICAL_ALERT` in `requestAuthorization` without the Apple-issued entitlement, **the entire authorization request fails** — macOS returns `granted = false` with error `"Notifications are not allowed for this application"` and the permission dialog is never shown. The same applies to `TIME_SENSITIVE` on some macOS versions.

    Only use `ALERT`, `SOUND`, and `BADGE` unless you have obtained the corresponding entitlement from Apple:

    ```kotlin
    // Safe default — works without special entitlements
    NotificationCenter.requestAuthorization(
        setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE)
    ) { granted, error -> ... }
    ```

```kotlin
// Only use this if you have the com.apple.developer.usernotifications.critical-alerts entitlement
NotificationCenter.requestAuthorization(
    setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.CRITICAL_ALERT)
) { granted, _ ->
    if (granted) {
        NotificationCenter.add(
            NotificationRequest(
                identifier = "critical-1",
                content = NotificationContent(
                    title = "System Alert",
                    body = "Immediate attention required",
                    sound = NotificationSound.DefaultCritical,
                    interruptionLevel = InterruptionLevel.CRITICAL,
                ),
                trigger = NotificationTrigger.TimeInterval(interval = 1.0),
            )
        )
    }
}
```

## Native Library

Ships pre-built macOS dylibs (arm64 + x86_64). No Windows or Linux native — `isAvailable` returns `false` on other platforms and all methods are no-op.

- `libnucleus_notification.dylib` — linked against `UserNotifications.framework` and `Cocoa.framework`
- Minimum deployment target: macOS 10.14
- `UNNotificationPresentationOptionBanner`/`List` require macOS 11+, falls back to `Alert` on 10.x
- `InterruptionLevel` requires macOS 12+
- `setBadgeCount` uses `UNUserNotificationCenter` API on macOS 13+, `NSDockTile` fallback on older

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.notification.macos.NativeMacNotificationBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

Reachability metadata is included in the JAR at `META-INF/native-image/dev.nucleusframework/nucleus.notification-macos/reachability-metadata.json`. No additional configuration needed.
