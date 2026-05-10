# Notification (Common)

Cross-platform notification abstraction that unifies Linux, Windows, and macOS notification APIs behind a single Kotlin DSL. Send notifications with title, message, images, action buttons, and lifecycle callbacks — the module routes to the right platform backend at runtime.

!!! info "Simple subset by design"
    This module exposes the **intersection** of what all three platforms support: title, message, large image, small icon, up to 5 action buttons, and lifecycle callbacks. For platform-specific features (progress bars, input fields, scheduling, categories), use the dedicated [Linux](notification-linux.md), [Windows](notification-windows.md), or [macOS](notification-macos.md) modules directly.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.notification-common:<version>")
}
```

This single dependency pulls in all three platform modules (Linux, Windows, macOS) and `core-runtime` transitively. The module detects the current OS at runtime and delegates to the appropriate backend — non-native libraries are simply unused on other platforms.

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.notification.common.*

// Build a notification
val n = notification(
    title = "Download Complete",
    message = "report.pdf has been saved",
) {
    button("Open") { openFile() }
    button("Show in Folder") { showInFolder() }
}

// Send it
n.send()
```

## Full Example

```kotlin
import dev.nucleusframework.nucleus.notification.common.*

val myNotification = notification(
    title = "New Message from Alice",
    message = "Hey! Have you seen the latest build?",
    largeImage = Res.getUri("drawable/alice_avatar.png"),
    smallIcon = Res.getUri("drawable/app_icon.png"),
    onActivated = { openConversation("alice") },
    onDismissed = { reason -> println("Dismissed: $reason") },
    onFailed = { println("Failed to show notification") },
) {
    button("Reply") { showReplyDialog("alice") }
    button("Archive") { archiveConversation("alice") }
}

// Check availability before sending
if (NotificationManager.isAvailable()) {
    when (val result = myNotification.send()) {
        is NotificationResult.Success -> {
            // Store the handle to dismiss later
            val handle = result.handle
            // ...
            handle.dismiss()
        }
        is NotificationResult.Failure -> {
            println("Could not send: ${result.reason}")
        }
    }
}
```

## API Reference

### `notification()` — DSL Entry Point

Top-level function that builds a `Notification` instance.

```kotlin
fun notification(
    title: String,
    message: String = "",
    largeImage: String? = null,
    smallIcon: String? = null,
    onActivated: (() -> Unit)? = null,
    onDismissed: ((DismissReason) -> Unit)? = null,
    onFailed: (() -> Unit)? = null,
    buttons: (NotificationButtonBuilder.() -> Unit)? = null,
): Notification
```

| Parameter | Type | Default | Description |
|---|---|---|---|
| `title` | `String` | *(required)* | Notification title. |
| `message` | `String` | `""` | Body text. |
| `largeImage` | `String?` | `null` | URI to a large image (hero image on Windows, image hint on Linux, attachment on macOS). |
| `smallIcon` | `String?` | `null` | URI to a small icon (app logo on Windows, app icon on Linux, ignored on macOS). |
| `onActivated` | `(() -> Unit)?` | `null` | Called when the user clicks the notification body. |
| `onDismissed` | `((DismissReason) -> Unit)?` | `null` | Called when the notification is dismissed. |
| `onFailed` | `(() -> Unit)?` | `null` | Called if the notification fails to display. |
| `buttons` | DSL block | `null` | Builder block to add up to 5 action buttons. |

### `NotificationButtonBuilder`

Available inside the `notification { }` trailing lambda.

| Method | Description |
|---|---|
| `button(title: String, onClick: () -> Unit)` | Add an action button. Maximum 5 buttons (Windows limit). |

---

### `Notification`

Immutable notification object returned by the `notification()` function. The same instance can be sent multiple times — each call creates a new system notification.

| Method | Returns | Description |
|---|---|---|
| `send()` | `NotificationResult` | Sends the notification to the OS. |

---

### `NotificationResult`

Sealed class returned by `send()`.

| Subclass | Properties | Description |
|---|---|---|
| `Success` | `handle: NotificationHandle` | Notification sent successfully. |
| `Failure` | `reason: String` | Notification could not be sent. |

---

### `NotificationHandle`

Opaque handle to a sent notification.

| Method | Description |
|---|---|
| `dismiss()` | Programmatically close the notification if still visible. |

---

### `NotificationManager`

Singleton facade for platform detection and notification dispatch.

| Method | Returns | Description |
|---|---|---|
| `isAvailable()` | `Boolean` | `true` if the current platform's notification module is on the classpath and functional. |
| `initialize()` | `Unit` | Eagerly initialize the notification subsystem (Windows only — called lazily on first `send()` otherwise). |
| `send(notification)` | `NotificationResult` | Send a notification. Prefer using `notification.send()` directly. |

---

### `DismissReason`

Unified enum for why a notification was dismissed.

| Value | Description | Linux | Windows | macOS |
|---|---|---|---|---|
| `USER_DISMISSED` | User explicitly dismissed | `DISMISSED` | `USER_CANCELED` | Custom dismiss action |
| `TIMED_OUT` | Auto-expired after timeout | `EXPIRED` | `TIMED_OUT` | — |
| `APPLICATION` | Closed programmatically | `CLOSED` | `APPLICATION_HIDDEN` | — |
| `UNKNOWN` | Could not be determined | `UNDEFINED` | — | — |

---

## Platform Mapping

How each parameter maps to platform-specific APIs:

| Common | Linux | Windows | macOS |
|---|---|---|---|
| `title` | `summary` | First `AdaptiveText` (bold) | `content.title` |
| `message` | `body` | Second `AdaptiveText` | `content.body` |
| `largeImage` | `hints.imagePath` | Hero image (top banner) | `attachments[0]` |
| `smallIcon` | `appIcon` | App logo override (left of text) | Ignored (uses bundle icon) |
| `buttons` | `actions` list | `ToastButton` list | Auto-generated `NotificationCategory` |
| `onActivated` | `onActionInvoked` with `"default"` key | `onActivated` with empty arguments | `didReceive` with `DEFAULT_ACTION` |
| `onDismissed` | `onClosed` signal | `onDismissed` event | Requires `CUSTOM_DISMISS_ACTION` |
| `onFailed` | `notify()` returns 0 | `onFailed` event | `add()` callback error |

## Platform Details

### Windows

- **Installed app required**: Notifications require a Start Menu shortcut (`.lnk`) with the AUMID property. This is created by the installer (e.g. `./gradlew packageDistributionForCurrentOS`). When running via `./gradlew run`, notifications work only if the app has been installed before (the shortcut already exists). A warning is logged otherwise.
- **Initialization**: `WindowsNotificationCenter.initialize()` is called automatically on the first `send()`. Call `NotificationManager.initialize()` explicitly for early setup.
- **Tag/Group**: Each notification gets a unique tag (`n1`, `n2`, ...) under the `"ncm"` group.
- **Images**: `largeImage` maps to a hero image at the top of the toast. `smallIcon` maps to the app logo override (displayed left of the text). Both accept `file:///` URIs and HTTP URLs.
- **Buttons**: Up to 5, rendered as standard toast action buttons.

### macOS

- **App bundle required**: Notifications only work inside a packaged `.app` bundle (e.g. via `./gradlew runDistributable`). `isAvailable()` returns `false` when running via `./gradlew run`.
- **Authorization**: The user must have granted notification permissions. The common module does **not** auto-request authorization — use `NotificationCenter.requestAuthorization()` from the macOS module before sending.
- **Buttons**: Require pre-registered `NotificationCategory` objects. The common module handles this automatically — it generates and caches categories per unique button configuration.
- **Dismiss callback**: macOS does not natively fire dismiss events. The common module enables `CUSTOM_DISMISS_ACTION` on generated categories so `onDismissed` fires when the user explicitly dismisses.
- **Small icon**: Ignored — macOS always uses the app icon from the bundle.
- **Large image**: Mapped to a notification attachment (displayed as a thumbnail).

### Linux

- **No initialization needed**: The D-Bus connection is established automatically.
- **Images**: `largeImage` maps to the `imagePath` hint (icon name or `file://` URI). `smallIcon` maps to `appIcon`. See the [Linux notification docs](notification-linux.md#icons) for icon priority.
- **Default action**: A `"default"` action is automatically added when `onActivated` is set, so clicking the notification body triggers the callback.
- **All callbacks on Swing EDT**: Safe to update Compose state directly from callbacks.

## Compose Desktop Integration

```kotlin
@Composable
fun NotificationDemo() {
    var lastResult by remember { mutableStateOf<NotificationResult?>(null) }

    Button(onClick = {
        val n = notification(
            title = "Build Finished",
            message = "nucleus-1.3.0 compiled in 42s",
            onActivated = { println("Notification clicked") },
        ) {
            button("View Logs") { openLogs() }
        }
        lastResult = n.send()
    }) {
        Text("Send Notification")
    }

    lastResult?.let { result ->
        when (result) {
            is NotificationResult.Success -> Text("Sent!")
            is NotificationResult.Failure -> Text("Failed: ${result.reason}")
        }
    }
}
```

!!! tip "Getting the best experience across platforms"
    Always provide both `largeImage` and `smallIcon` for the richest display. On platforms that don't support one (e.g. `smallIcon` on macOS), it is silently ignored.

## Architecture

The module uses a dispatcher pattern inspired by `taskbar-progress`:

```
NotificationManager (singleton)
  └─ DispatcherFactory (selects by os.name)
       ├─ LinuxDispatcher    → LinuxNotificationCenter
       ├─ WindowsDispatcher  → WindowsNotificationCenter
       └─ MacOsDispatcher    → NotificationCenter (macOS)
```

Each dispatcher:

1. Checks for the platform module on the classpath via `Class.forName` (no `NoClassDefFoundError` if absent)
2. Registers **one global listener** on the platform's notification center
3. Routes callbacks to per-notification lambdas via a `ConcurrentHashMap<platformId, callbacks>` registry
4. Cleans up callback entries on dismiss/failure events

## ProGuard

No additional ProGuard rules are needed for `notification-common` itself. Ensure the platform module rules are applied — see [Linux](notification-linux.md#proguard), [Windows](notification-windows.md#proguard), [macOS](notification-macos.md#proguard).

## GraalVM

No additional GraalVM metadata is needed for `notification-common`. The platform modules ship their own `reachability-metadata.json`. See [Linux](notification-linux.md#graalvm), [Windows](notification-windows.md#graalvm), [macOS](notification-macos.md#graalvm).
