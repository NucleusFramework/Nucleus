# Taskbar Progress

Native taskbar/dock progress bar and attention requests on Windows, macOS, and Linux.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.taskbar-progress:<version>")
}
```

`taskbar-progress` depends on `core-runtime` for `Platform` detection and `NativeLibraryLoader` (pulled in transitively).

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.taskbarprogress.TaskbarProgress
import java.awt.Window

// Show a progress bar at 75%
TaskbarProgress.showProgress(window, 0.75)

// Show an indeterminate (pulsing) progress bar
TaskbarProgress.showIndeterminate(window)

// Show an error state
TaskbarProgress.showError(window)

// Hide the progress bar
TaskbarProgress.hideProgress(window)

// Request user attention (flash taskbar / bounce dock icon)
TaskbarProgress.requestAttention(window)
```

## API Reference

### `TaskbarProgress`

All methods accept a `java.awt.Window` parameter and return `Boolean` (`true` if the operation succeeded on the current platform).

| Method | Description |
|--------|-------------|
| `isAvailable(): Boolean` | Check if taskbar progress is supported on the current platform |
| `setProgress(window, value: Double): Boolean` | Set progress value (0.0–1.0, clamped) |
| `setState(window, state: State): Boolean` | Set progress state |
| `showProgress(window, value: Double): Boolean` | Set state to `NORMAL` + set progress value |
| `showError(window, value: Double = 1.0): Boolean` | Set state to `ERROR` + set progress value |
| `showIndeterminate(window): Boolean` | Set state to `INDETERMINATE` |
| `showPaused(window, value: Double = 1.0): Boolean` | Set state to `PAUSED` + set progress value |
| `hideProgress(window): Boolean` | Set state to `NO_PROGRESS` |
| `requestAttention(window, type: AttentionType = INFORMATIONAL): Boolean` | Request user attention |
| `stopAttention(window): Boolean` | Cancel attention request |

### `TaskbarProgress.State`

| Value | Windows | macOS | Linux |
|-------|---------|-------|-------|
| `NO_PROGRESS` | No overlay | Remove indicator | `progress-visible: false` |
| `INDETERMINATE` | Pulsing green | Pulsing bar | `progress-visible: true` (DE-dependent) |
| `NORMAL` | Green bar | Blue bar | Blue/accent bar |
| `ERROR` | Red bar | Red bar | `urgent: true` |
| `PAUSED` | Yellow bar | Yellow bar | (mapped to progress) |

### `TaskbarProgress.AttentionType`

| Value | Windows | macOS |
|-------|---------|-------|
| `INFORMATIONAL` | Flash taskbar button 4 times | Bounce dock icon once |
| `CRITICAL` | Flash until window receives focus | Bounce dock icon continuously |

### `linuxDesktopFilename`

Optional override for the Linux `.desktop` filename used by the D-Bus protocol:

```kotlin
TaskbarProgress.linuxDesktopFilename = "com.example.myapp.desktop"
```

By default, the module auto-detects the `.desktop` file using (in order):
`NucleusApp.appId`, `GIO_LAUNCHED_DESKTOP_FILE` env var, `BAMF_DESKTOP_FILE_HINT` env var, `/proc/self/exe` name, or XDG application directory scan.

## Compose Desktop Integration

```kotlin
@Composable
fun DownloadScreen(window: Window) {
    var progress by remember { mutableStateOf(0.0) }
    var isDownloading by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            TaskbarProgress.hideProgress(window)
        }
    }

    LaunchedEffect(isDownloading) {
        if (isDownloading) {
            TaskbarProgress.showIndeterminate(window)
            // Simulate download
            for (i in 0..100) {
                delay(50)
                progress = i / 100.0
                TaskbarProgress.showProgress(window, progress)
            }
            TaskbarProgress.hideProgress(window)
            isDownloading = false
        }
    }

    Button(onClick = { isDownloading = true }) {
        Text("Download")
    }
}
```

!!! tip "Getting the AWT Window in Compose"
    Use `LocalWindow.current` (from `androidx.compose.ui.awt`) or capture the window reference from your `Window` / `DecoratedWindow` scope.

## Platform Details

### Windows

Uses the COM `ITaskbarList3` interface for progress and state, and `FlashWindowEx` for attention requests. COM is lazily initialized on first use. Requires the HWND from `java.awt.Window`.

### macOS

Uses `NSDockTile` with a custom `NSProgressIndicator` subclass rendered at the bottom of the dock icon. All AppKit calls are dispatched to the main thread via `dispatch_sync`. Attention uses `NSApplication.requestUserAttention`.

!!! note "macOS dock progress is app-wide"
    The macOS dock shows a single progress indicator per application. The `window` parameter is accepted for API consistency but the progress applies to the app dock tile, not a specific window.

### Linux

Uses the D-Bus `com.canonical.Unity.LauncherEntry` protocol via GLib/GIO. Supported by GNOME (Ubuntu Dock, Dash to Dock), KDE Plasma, and other freedesktop-compliant desktop environments.

Requires a `.desktop` file to identify the application. The module auto-detects it, but you can override with `TaskbarProgress.linuxDesktopFilename` if detection fails.
