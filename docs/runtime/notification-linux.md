# Notification (Linux)

Complete Kotlin mapping of the [freedesktop Desktop Notifications Specification](https://specifications.freedesktop.org/notification/latest-single/) via JNI. Send notifications with action buttons, urgency levels, icons, sounds, and inline images on any Linux desktop.

!!! info "Pure D-Bus via GIO"
    Uses GLib/GIO (`libgio-2.0`) for D-Bus communication — no JNA, no reflection, no Java D-Bus libraries.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.notification-linux:<version>")
}
```

Depends on `core-runtime` for `NativeLibraryLoader` and `freedesktop-icons` for typesafe icon names (both pulled in transitively).

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.notification.linux.*

// Send a simple notification with typesafe icon and sound
val id = LinuxNotificationCenter.notify(
    Notification(
        appName = "My App",
        summary = "Hello!",
        body = "Welcome to Nucleus.",
        appIcon = FreedesktopIcon.Status.DIALOG_INFORMATION,
        hints = NotificationHints(
            imagePath = FreedesktopIcon.Status.DIALOG_INFORMATION,
            soundName = NotificationSound.Notification.DIALOG_INFORMATION,
        ),
    )
)

// Close it programmatically
LinuxNotificationCenter.closeNotification(id)
```

!!! tip "Use `imagePath` for reliable icon display"
    On GNOME Shell, the `appIcon` parameter may be ignored when the application has a visible window. Use the `imagePath` hint with a [freedesktop icon name](#icons) for consistent icon display across all desktops.

## API Reference

### `LinuxNotificationCenter`

Main entry point. All methods are thread-safe.

| Property / Method | Description |
|---|---|
| `isAvailable: Boolean` | `true` if native library loaded (Linux only) |

#### Sending Notifications

| Method | Returns | Description |
|---|---|---|
| `notify(notification)` | `Int` | Send a notification. Returns the server-assigned ID (> 0), or 0 on failure. |
| `closeNotification(id)` | `Unit` | Forcefully close a notification by ID. |

#### Server Queries

| Method | Returns | Description |
|---|---|---|
| `getCapabilities()` | `List<String>` | Query supported features (see [Capabilities](#capabilities)). |
| `getServerInformation()` | `ServerInformation?` | Query the notification daemon identity. |

#### Signal Listeners

| Method | Description |
|---|---|
| `addListener(listener)` | Register a `LinuxNotificationListener`. Signal monitoring starts automatically on first listener. |
| `removeListener(listener)` | Unregister a listener. Monitoring stops when the last listener is removed. |

---

### `LinuxNotificationListener`

Implement this interface to receive asynchronous signals from the notification server.

!!! info "Thread safety"
    All callbacks are dispatched to the Swing EDT via `SwingUtilities.invokeLater`. You can safely update Compose state directly.

```kotlin
LinuxNotificationCenter.addListener(object : LinuxNotificationListener {
    override fun onClosed(notificationId: Int, reason: CloseReason) {
        println("Notification #$notificationId closed: ${reason.name}")
    }

    override fun onActionInvoked(notificationId: Int, actionKey: String) {
        println("Action '$actionKey' on #$notificationId")
    }

    override fun onActivationToken(notificationId: Int, token: String) {
        // Wayland/X11 activation token for window focus
    }
})
```

| Method | Description |
|---|---|
| `onClosed(notificationId, reason)` | Notification was closed (expired, dismissed, or via API). |
| `onActionInvoked(notificationId, actionKey)` | User clicked an action button. |
| `onActivationToken(notificationId, token)` | Server provides an activation token (Wayland/X11). |

---

### Data Types

#### `Notification`

| Property | Type | Default | Description |
|---|---|---|---|
| `appName` | `String` | `""` | Application name (informational). |
| `replacesId` | `Int` | `0` | If non-zero, atomically replaces the notification with this ID. |
| `appIcon` | `FreedesktopIcon?` | `null` | Typesafe icon (see [Icons](#icons)). |
| `summary` | `String` | *(required)* | Single-line notification title. |
| `body` | `String` | `""` | Multi-line body text. Supports [limited markup](#body-markup). |
| `actions` | `List<NotificationAction>` | `emptyList()` | Interactive action buttons. |
| `hints` | `NotificationHints` | `NotificationHints()` | Display hints (urgency, image, sound, etc.). |
| `expireTimeout` | `Int` | `-1` | Auto-close timeout: `-1` = server default, `0` = never, positive = milliseconds. |

#### `NotificationAction`

| Property | Type | Description |
|---|---|---|
| `key` | `String` | Unique action identifier. Use `NotificationAction.DEFAULT_KEY` (`"default"`) for the body click action. |
| `label` | `String` | Human-readable button label. |

```kotlin
val actions = listOf(
    NotificationAction(NotificationAction.DEFAULT_KEY, "Open"),
    NotificationAction("reply", "Reply"),
    NotificationAction("archive", "Archive"),
)
```

#### `NotificationHints`

All properties are optional. `null` means the hint is not sent.

| Property | Type | Description |
|---|---|---|
| `urgency` | `Urgency?` | Urgency level (see [Urgency](#urgency)). |
| `category` | `String?` | Notification category (see [Categories](#categories)). |
| `desktopEntry` | `String?` | Desktop file name without `.desktop` suffix. Helps the daemon identify the app. |
| `imageData` | `ImageData?` | Raw pixel data (see [ImageData](#imagedata)). |
| `imagePath` | `FreedesktopIcon?` | Typesafe icon (see [Icons](#icons)). Takes priority over `appIcon`. |
| `actionIcons` | `Boolean?` | If `true`, action keys are interpreted as icon names. |
| `soundFile` | `String?` | Absolute path to a sound file (see [Sounds](#sounds)). |
| `soundName` | `NotificationSound?` | Typesafe sound (see [Sounds](#sounds)). |
| `suppressSound` | `Boolean?` | If `true`, suppress any notification sound. |
| `resident` | `Boolean?` | If `true`, notification stays after action is invoked. |
| `transient` | `Boolean?` | If `true`, bypass notification log/history. |
| `x` | `Int?` | Screen X position hint (requires `y`). |
| `y` | `Int?` | Screen Y position hint (requires `x`). |

#### `ImageData`

Embed raw pixel data directly in the notification. Corresponds to the `image-data` hint with D-Bus signature `(iiibiiay)`.

| Property | Type | Default | Description |
|---|---|---|---|
| `width` | `Int` | — | Image width in pixels. |
| `height` | `Int` | — | Image height in pixels. |
| `rowstride` | `Int` | — | Bytes per row (usually `width * channels`). |
| `hasAlpha` | `Boolean` | — | Whether the image has an alpha channel. |
| `bitsPerSample` | `Int` | `8` | Bits per color channel (must be 8). |
| `channels` | `Int` | `3` or `4` | Number of channels (3 = RGB, 4 = RGBA). |
| `data` | `ByteArray` | — | Raw pixel bytes in RGB(A) order. |

```kotlin
// Create a 2x2 red square
val imageData = ImageData(
    width = 2, height = 2, rowstride = 6,
    hasAlpha = false,
    data = byteArrayOf(
        0xFF.toByte(), 0, 0,  0xFF.toByte(), 0, 0,  // row 1
        0xFF.toByte(), 0, 0,  0xFF.toByte(), 0, 0,  // row 2
    ),
)
```

#### `ServerInformation`

| Property | Type | Description |
|---|---|---|
| `name` | `String` | Server name (e.g. `"gnome-shell"`, `"dunst"`, `"mako"`). |
| `vendor` | `String` | Vendor (e.g. `"GNOME"`, `"dunst"`). |
| `version` | `String` | Server version. |
| `specVersion` | `String` | Specification version implemented (e.g. `"1.2"`). |

---

### Enums

#### `Urgency`

| Value | Int | Description |
|---|---|---|
| `LOW` | `0` | Low priority — may be displayed less prominently. |
| `NORMAL` | `1` | Default urgency level. |
| `CRITICAL` | `2` | Critical — should not auto-expire. |

#### `CloseReason`

Received in `LinuxNotificationListener.onClosed`.

| Value | Int | Description |
|---|---|---|
| `EXPIRED` | `1` | The notification timed out. |
| `DISMISSED` | `2` | The user dismissed the notification. |
| `CLOSED` | `3` | Closed by `closeNotification()`. |
| `UNDEFINED` | `4` | Reserved / undefined reason. |

---

## Icons

Icons are typesafe via the `FreedesktopIcon` sealed interface from the shared [`freedesktop-icons`](freedesktop-icons.md) module. All 338 standard names from the [freedesktop Icon Naming Specification](https://specifications.freedesktop.org/icon-naming/latest/) are available as enum constants.

```kotlin
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon

FreedesktopIcon.Status.DIALOG_INFORMATION
FreedesktopIcon.Device.PRINTER
FreedesktopIcon.Custom("my-app-icon")
```

See [Freedesktop Icons](freedesktop-icons.md) for the full list of icon contexts and usage examples.

### Image Priority

When multiple image sources are set, the notification daemon picks one using this priority order (per spec):

1. `imageData` hint (raw pixels — always works)
2. `imagePath` hint (icon name or file path)
3. `appIcon` parameter

!!! warning "GNOME Shell and `appIcon`"
    GNOME Shell identifies running applications by their window/PID and may ignore `appIcon` in favor of the application's window icon. Use `imagePath` hint for reliable icon display on GNOME. Other daemons (dunst, mako, swaync) fully respect `appIcon`.

---

## Sounds

Sounds are typesafe via the `NotificationSound` sealed interface. All 146 standard names from the [freedesktop Sound Naming Specification](https://specifications.freedesktop.org/sound-naming-spec/latest/) are available as enum constants, grouped by category.

### `NotificationSound`

```kotlin
// Standard sound from the spec (typesafe)
hints = NotificationHints(soundName = NotificationSound.Notification.MESSAGE_NEW_INSTANT)
hints = NotificationHints(soundName = NotificationSound.Alert.DIALOG_ERROR)

// Custom sound name
hints = NotificationHints(soundName = NotificationSound.Custom("x-myapp-ding"))
```

### Sound Categories

| Enum | Count | Examples |
|---|---|---|
| `NotificationSound.Notification` | 40 | `MESSAGE_NEW_INSTANT`, `MESSAGE_NEW_EMAIL`, `COMPLETE_DOWNLOAD`, `DIALOG_INFORMATION`, `DIALOG_WARNING`, `DEVICE_ADDED`, `ALARM_CLOCK_ELAPSED` |
| `NotificationSound.Alert` | 7 | `DIALOG_ERROR`, `BATTERY_LOW`, `NETWORK_CONNECTIVITY_ERROR`, `SOFTWARE_UPDATE_URGENT` |
| `NotificationSound.Action` | 29 | `BELL_TERMINAL`, `TRASH_EMPTY`, `CAMERA_SHUTTER`, `SCREEN_CAPTURE`, `MESSAGE_SENT_INSTANT` |
| `NotificationSound.InputFeedback` | 44 | `WINDOW_CLOSE`, `BUTTON_PRESSED`, `DIALOG_OK`, `DRAG_START` |
| `NotificationSound.Game` | 5 | `GAME_OVER_WINNER`, `GAME_OVER_LOSER`, `GAME_CARD_SHUFFLE` |

Full specification: [freedesktop Sound Naming Specification](https://specifications.freedesktop.org/sound-naming-spec/latest/)

### `soundFile` — Custom Sound File

For sounds outside the theme, use an absolute file path:

```kotlin
hints = NotificationHints(soundFile = "/usr/share/sounds/freedesktop/stereo/bell.oga")
```

### `suppressSound`

Suppress all sounds for a notification:

```kotlin
hints = NotificationHints(suppressSound = true)
```

---

## Body Markup

The `body` field supports a subset of HTML when the server has the `body-markup` capability:

| Tag | Example | Description |
|---|---|---|
| `<b>` | `<b>bold</b>` | Bold text |
| `<i>` | `<i>italic</i>` | Italic text |
| `<u>` | `<u>underline</u>` | Underlined text |
| `<a>` | `<a href="https://...">link</a>` | Hyperlink |
| `<img>` | `<img src="file:///..." alt="alt"/>` | Inline image |

```kotlin
Notification(
    summary = "Build Complete",
    body = "<b>nucleus-1.3.0</b> built in <i>42s</i>. " +
        "<a href=\"https://github.com\">View on GitHub</a>",
)
```

---

## Categories

The `category` hint uses a dot-separated format. Standard categories defined by the spec:

| Category | Description |
|---|---|
| `device` | Generic device-related |
| `device.added` | Device added |
| `device.removed` | Device removed |
| `device.error` | Device error |
| `email` | Generic email |
| `email.arrived` | New email |
| `email.bounced` | Bounced email |
| `im` | Generic instant message |
| `im.received` | Message received |
| `im.error` | IM error |
| `network` | Generic network |
| `network.connected` | Connected |
| `network.disconnected` | Disconnected |
| `network.error` | Network error |
| `presence` | Generic presence |
| `presence.online` | User came online |
| `presence.offline` | User went offline |
| `transfer` | Generic file transfer |
| `transfer.complete` | Transfer complete |
| `transfer.error` | Transfer error |

Vendor extensions use the `x-vendor.*` prefix (e.g. `x-myapp.build-complete`).

---

## Capabilities

Query the server's supported features with `getCapabilities()`. Common capabilities:

| Capability | Description |
|---|---|
| `actions` | Server supports action buttons |
| `body` | Server supports body text |
| `body-markup` | Body supports HTML markup |
| `body-hyperlinks` | Body supports `<a>` links |
| `body-images` | Body supports `<img>` |
| `icon-static` | Server supports static icons |
| `persistence` | Server supports persistent notifications |
| `sound` | Server supports sounds |

```kotlin
val caps = LinuxNotificationCenter.getCapabilities()
if ("actions" in caps) {
    // Safe to use action buttons
}
```

---

## Full Example: Messaging with Actions

```kotlin
// Listen for user interactions
LinuxNotificationCenter.addListener(object : LinuxNotificationListener {
    override fun onActionInvoked(notificationId: Int, actionKey: String) {
        when (actionKey) {
            NotificationAction.DEFAULT_KEY -> openConversation()
            "reply" -> showReplyDialog()
            "archive" -> archiveMessage()
        }
    }

    override fun onClosed(notificationId: Int, reason: CloseReason) {
        println("Notification #$notificationId: ${reason.name}")
    }
})

// Send notification with actions
val id = LinuxNotificationCenter.notify(
    Notification(
        appName = "My Messenger",
        summary = "Alice",
        body = "<b>Project Nucleus</b>\nHey! Have you seen the latest build?",
        appIcon = FreedesktopIcon.Status.MAIL_UNREAD,
        actions = listOf(
            NotificationAction(NotificationAction.DEFAULT_KEY, "Open"),
            NotificationAction("reply", "Reply"),
            NotificationAction("archive", "Archive"),
        ),
        hints = NotificationHints(
            urgency = Urgency.NORMAL,
            category = "im.received",
            imagePath = FreedesktopIcon.Status.MAIL_UNREAD,
            soundName = NotificationSound.Notification.MESSAGE_NEW_INSTANT,
            desktopEntry = "my-messenger",
        ),
    )
)

// Replace with an updated notification
LinuxNotificationCenter.notify(
    Notification(
        replacesId = id,
        appName = "My Messenger",
        summary = "Alice (2 messages)",
        body = "Hey! Have you seen the latest build?\n<i>Also, lunch?</i>",
        appIcon = FreedesktopIcon.Status.MAIL_UNREAD,
        hints = NotificationHints(
            urgency = Urgency.NORMAL,
            category = "im.received",
            imagePath = FreedesktopIcon.Status.MAIL_UNREAD,
        ),
    )
)
```

## Notification Replacement

Use `replacesId` to atomically update an existing notification. The server replaces the old notification in-place without a new popup (useful for progress updates, message count changes, etc.):

```kotlin
// Initial notification
val id = LinuxNotificationCenter.notify(
    Notification(summary = "Downloading...", body = "0%")
)

// Update in-place
LinuxNotificationCenter.notify(
    Notification(replacesId = id, summary = "Downloading...", body = "50%")
)

// Final update
LinuxNotificationCenter.notify(
    Notification(replacesId = id, summary = "Download complete!", body = "100%")
)
```

---

## Native Library

Ships pre-built Linux shared libraries (x86_64 + aarch64). No macOS or Windows native — `isAvailable` returns `false` on other platforms.

- `libnucleus_notification_linux.so` — linked against `libgio-2.0` (GLib/GIO)
- Build requirement: `libgio-2.0-dev` (Debian/Ubuntu) or `glib2-devel` (Fedora)
- Signal listening runs in a dedicated thread with its own `GMainLoop`

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.notification.linux.NativeLinuxNotificationBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata must include the bridge class:

```json
[
  {
    "type": "dev.nucleusframework.nucleus.notification.linux.NativeLinuxNotificationBridge",
    "methods": [
      { "name": "onNotificationClosed", "parameterTypes": ["int", "int"] },
      { "name": "onActionInvoked", "parameterTypes": ["int", "java.lang.String"] },
      { "name": "onActivationToken", "parameterTypes": ["int", "java.lang.String"] }
    ]
  }
]
```
