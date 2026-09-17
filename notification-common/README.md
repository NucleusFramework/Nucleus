# notification-common

Cross-platform desktop notification API for JVM applications. Write one notification and it is
delivered through the native backend of whichever OS the app is running on — Freedesktop
Desktop Notifications on Linux, User Notifications on macOS, and Toast Notifications on Windows.

The shared surface is deliberately limited to what behaves the same everywhere (title, body,
image, action buttons, lifecycle callbacks). Behavior that genuinely differs between platforms —
urgency, expiry, history, interruption level — is opted into through per-platform `linux { }` /
`macos { }` / `windows { }` blocks, so the common abstraction never silently means different
things on different systems.

## Features

- One `notification { }` DSL that dispatches to the native backend at runtime
- Up to five action buttons, plus body-click / dismiss / failure callbacks
- `NotificationResult` with a handle you can use to dismiss the notification later
- Per-platform option blocks for platform-specific behavior, each a no-op off its OS
- No platform `when` branching required — the correct backend is selected automatically

## Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":notification-common"))
}
```

`notification-common` pulls in the `notification-linux`, `notification-windows`, and
`notification-macos` backends. Only the one matching the host OS activates its native library at
runtime; the others are inert.

## Quick start

```kotlin
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification

if (NotificationManager.isAvailable()) {
    val result =
        notification(
            title = "Download complete",
            message = "report.pdf has been saved",
            onActivated = { openFile() },
            onDismissed = { reason -> log("dismissed: $reason") },
            onFailed = { log("failed to display") },
        ) {
            button("Open") { openFile() }
            button("Show in Folder") { showInFolder() }
        }.send()

    when (result) {
        is NotificationResult.Success -> lastHandle = result.handle // handle.dismiss() to close it
        is NotificationResult.Failure -> log("not sent: ${result.reason}")
    }
}
```

Lifecycle callbacks are not guaranteed to run on a UI thread.

## Platform-specific options

Add a `linux { }`, `macos { }`, and/or `windows { }` block to configure behavior that only makes
sense on that platform. Each block is applied **only** when the notification is delivered on that
OS and ignored everywhere else, so a single call site can carry full-fidelity settings for all
three platforms:

```kotlin
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario

notification(title = "Build failed", message = "Disk usage is above 95%") {
    linux {
        urgency = Urgency.CRITICAL      // shown prominently; on most servers breaks through DnD
        category = "device.error"
        transient = true                // don't keep it in the notification history
        expireTimeout = 0               // 0 = never auto-expires (ms otherwise)
    }
    macos {
        interruptionLevel = InterruptionLevel.TIME_SENSITIVE
        relevanceScore = 0.9
    }
    windows {
        scenario = ToastScenario.URGENT // Windows 11+: breaks through Focus Assist
        duration = ToastDuration.LONG
    }
}.send()
```

### What each block supports

| `linux { }` (`LinuxNotificationScope`) | Description |
|---|---|
| `urgency` | Freedesktop urgency: `LOW`, `NORMAL`, `CRITICAL` |
| `category` | Type category, e.g. `"im.received"`, `"email.arrived"` |
| `transient` | Bypass the notification log/history |
| `resident` | Keep the notification after an action is invoked |
| `expireTimeout` | Auto-dismiss (ms): `-1`/unset = server default, `0` = never, `>0` = explicit |

| `macos { }` (`MacNotificationScope`) | Description |
|---|---|
| `interruptionLevel` | `PASSIVE` / `ACTIVE` / `TIME_SENSITIVE` / `CRITICAL` (the last two need an Apple entitlement; without it macOS falls back to `ACTIVE`) |
| `relevanceScore` | `0.0..1.0`, used to sort the app's notifications |
| `subtitle` | Line shown between the title and body |

| `windows { }` (`WindowsNotificationScope`) | Description |
|---|---|
| `scenario` | `DEFAULT` / `REMINDER` / `ALARM` / `INCOMING_CALL` / `URGENT` (`URGENT` requires Windows 11) |
| `duration` | `DEFAULT` / `SHORT` / `LONG` on-screen time |

> **Why not a single common `urgency`?** The levels don't map cleanly: `CRITICAL` breaks through
> Do Not Disturb on Linux and Windows, but on macOS that requires an entitlement most apps lack,
> so it can only reach `timeSensitive` (which merely re-prioritizes). Rather than expose a shared
> knob that behaves differently per OS, the divergent settings live in the platform blocks where
> the semantics are unambiguous.

## Backend modules

| Module | Backend |
|--------|---------|
| `nucleus.notification-linux` | Freedesktop Desktop Notifications (D-Bus `org.freedesktop.Notifications`) |
| `nucleus.notification-macos` | macOS User Notifications (`UNUserNotificationCenter`) |
| `nucleus.notification-windows` | Windows Toast Notifications (WinRT) |

Each backend can also be used directly for the full native API of a single platform.
