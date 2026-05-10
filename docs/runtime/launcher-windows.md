# Launcher (Windows)

Windows Launcher API via JNI — badge notifications, jump lists, overlay icons, and thumbnail toolbar buttons for the app's taskbar button.

## Dependency

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.launcher-windows:<version>")
}
```

## Overview

This module provides four Windows launcher APIs:

- **Badge Notifications** — numeric counts (1–99+) or status glyph icons on the taskbar button and Start tile (APPX/MSIX only)
- **Jump Lists** — custom categories and pinned tasks in the taskbar right-click menu (all packaging types)
- **Overlay Icons** — small 16×16 status icons on the taskbar button (all packaging types)
- **Thumbnail Toolbar** — up to 7 clickable buttons in the taskbar thumbnail preview (all packaging types)

### Packaging Compatibility

| Feature | APPX/MSIX | NSIS / MSI / Distributable |
|---------|-----------|----------------------------|
| Badge Notifications | Yes | No (WinRT limitation) |
| Jump Lists | Yes | Yes |
| Overlay Icons | Yes | Yes |
| Thumbnail Toolbar | Yes | Yes |

---

## Badge Notifications

### Quick Start

```kotlin
import dev.nucleusframework.nucleus.launcher.windows.BadgeGlyph
import dev.nucleusframework.nucleus.launcher.windows.WindowsBadgeManager

// Initialize once at app startup
if (WindowsBadgeManager.isAvailable) {
    WindowsBadgeManager.initialize()
}

// Set a numeric badge (e.g., unread message count)
WindowsBadgeManager.setCount(5)

// Set a glyph badge
WindowsBadgeManager.setGlyph(BadgeGlyph.NEW_MESSAGE)

// Clear the badge
WindowsBadgeManager.clear()

// Clean up on shutdown
WindowsBadgeManager.uninitialize()
```

### `WindowsBadgeManager`

Thread-safe singleton providing the full badge API. Requires APPX/MSIX packaging.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded on this platform |
| `initialize(aumid: String? = null): Boolean` | Initialize the badge subsystem |
| `uninitialize()` | Release native resources |
| `setCount(count: Int): Boolean` | Set a numeric badge (0 clears, 1–99 shown as number, 100+ as "99+") |
| `setGlyph(glyph: BadgeGlyph): Boolean` | Set a glyph badge icon |
| `clear(): Boolean` | Remove the badge entirely |

### `BadgeGlyph`

Predefined status glyph icons:

| Glyph | Value | Description |
|-------|-------|-------------|
| `NONE` | `none` | Clears the badge |
| `ACTIVITY` | `activity` | Activity indicator |
| `ALARM` | `alarm` | Alarm status |
| `ALERT` | `alert` | Alert / requires attention |
| `ATTENTION` | `attention` | Attention required |
| `AVAILABLE` | `available` | Presence: available |
| `AWAY` | `away` | Presence: away |
| `BUSY` | `busy` | Presence: busy |
| `ERROR` | `error` | Error / failure |
| `NEW_MESSAGE` | `newMessage` | New message received |
| `PAUSED` | `paused` | Paused state |
| `PLAYING` | `playing` | Playing / active |
| `UNAVAILABLE` | `unavailable` | Presence: unavailable |

---

## Overlay Icons

Small 16×16 status icons displayed over the app's taskbar button. Works with all packaging types.

### Quick Start

```kotlin
import dev.nucleusframework.nucleus.launcher.windows.StockIcon
import dev.nucleusframework.nucleus.launcher.windows.TaskbarIconSource
import dev.nucleusframework.nucleus.launcher.windows.WindowsOverlayIcon

// Set a stock icon overlay
WindowsOverlayIcon.setIcon(
    window,
    TaskbarIconSource.FromStock(StockIcon.WARNING),
    description = "Warning",
)

// Set an overlay from an .ico file
WindowsOverlayIcon.setIcon(
    window,
    TaskbarIconSource.FromFile("C:\\path\\to\\icon.ico"),
    description = "Custom status",
)

// Clear the overlay
WindowsOverlayIcon.clearIcon(window)
```

### `WindowsOverlayIcon`

Thread-safe singleton. Requires a `java.awt.Window` reference.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded |
| `setIcon(window, icon, description): Boolean` | Set the overlay icon |
| `clearIcon(window): Boolean` | Remove the overlay icon |
| `lastError: String?` | Last error message, or null |

---

## Thumbnail Toolbar

Up to 7 clickable buttons displayed in the taskbar thumbnail preview (visible when hovering the taskbar button).

### Quick Start

```kotlin
import dev.nucleusframework.nucleus.launcher.windows.StockIcon
import dev.nucleusframework.nucleus.launcher.windows.TaskbarIconSource
import dev.nucleusframework.nucleus.launcher.windows.ThumbnailToolbarButton
import dev.nucleusframework.nucleus.launcher.windows.WindowsThumbnailToolbar

// Register buttons (once per window)
WindowsThumbnailToolbar.setButtons(
    window,
    listOf(
        ThumbnailToolbarButton(
            id = 0,
            tooltip = "Previous",
            icon = TaskbarIconSource.FromStock(StockIcon.MEDIA_REWIND),
        ),
        ThumbnailToolbarButton(
            id = 1,
            tooltip = "Play/Pause",
            icon = TaskbarIconSource.FromStock(StockIcon.MEDIA_PLAY),
        ),
        ThumbnailToolbarButton(
            id = 2,
            tooltip = "Next",
            icon = TaskbarIconSource.FromStock(StockIcon.MEDIA_FORWARD),
        ),
    ),
) { buttonId ->
    println("Button clicked: $buttonId")
}

// Update button state (e.g., disable a button)
WindowsThumbnailToolbar.updateButtons(
    window,
    listOf(
        ThumbnailToolbarButton(id = 1, tooltip = "Paused", enabled = false),
    ),
)

// Unregister (hides buttons and removes callbacks)
WindowsThumbnailToolbar.unregister(window)
```

### `WindowsThumbnailToolbar`

Thread-safe singleton.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded |
| `setButtons(window, buttons, onClick): Boolean` | Register toolbar buttons (can be called again after `unregister`) |
| `updateButtons(window, buttons): Boolean` | Update the state of registered buttons |
| `unregister(window): Boolean` | Hide buttons and remove click callbacks |
| `lastError: String?` | Last error message, or null |

### `ThumbnailToolbarButton`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | `Int` | — | Unique button ID (0–6) |
| `tooltip` | `String` | `""` | Tooltip text |
| `icon` | `TaskbarIconSource?` | `null` | Button icon |
| `enabled` | `Boolean` | `true` | Whether the button is clickable |
| `hidden` | `Boolean` | `false` | Whether the button is hidden |
| `noBackground` | `Boolean` | `false` | Draw icon without button border |
| `dismissOnClick` | `Boolean` | `false` | Close thumbnail preview on click |
| `nonInteractive` | `Boolean` | `false` | Display-only (no click events) |

### Lifecycle Notes

- Windows only allows adding buttons **once** per window. Calling `setButtons` again after `unregister` internally uses `ThumbBarUpdateButtons` to re-show them.
- `unregister` **hides** buttons (Windows does not allow removing them). The buttons can be re-shown by calling `setButtons` again.
- Click events are delivered on the AWT Event Dispatch Thread.

---

## Jump Lists

Jump lists appear when the user right-clicks the app's taskbar button. They support custom categories, shell-managed categories (Recent/Frequent), and pinned user tasks.

### Quick Start

```kotlin
import dev.nucleusframework.nucleus.launcher.windows.JumpListCategory
import dev.nucleusframework.nucleus.launcher.windows.JumpListItem
import dev.nucleusframework.nucleus.launcher.windows.StockIcon
import dev.nucleusframework.nucleus.launcher.windows.TaskbarIconSource
import dev.nucleusframework.nucleus.launcher.windows.WindowsJumpListManager

// Set a jump list with categories and tasks
WindowsJumpListManager.setJumpList(
    categories = listOf(
        JumpListCategory(
            name = "Recent Projects",
            items = listOf(
                JumpListItem(
                    title = "Project A",
                    arguments = "myapp://open?project=a",
                    icon = TaskbarIconSource.FromStock(StockIcon.FOLDER),
                ),
                JumpListItem(
                    title = "Project B",
                    arguments = "myapp://open?project=b",
                    icon = TaskbarIconSource.FromStock(StockIcon.FOLDER),
                ),
            ),
        ),
    ),
    tasks = listOf(
        JumpListItem(
            title = "New Window",
            arguments = "myapp://new-window",
            icon = TaskbarIconSource.FromStock(StockIcon.DESKTOP_PC),
        ),
        JumpListItem.SEPARATOR,
        JumpListItem(
            title = "Settings",
            arguments = "myapp://settings",
            icon = TaskbarIconSource.FromStock(StockIcon.SETTINGS),
        ),
    ),
)

// Clear the jump list
WindowsJumpListManager.clearJumpList()
```

### `WindowsJumpListManager`

Thread-safe singleton. Works for both APPX/MSIX and unpackaged apps.

| Method | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded |
| `setProcessAppId(aumid: String? = null): Boolean` | Set the AUMID (call before any window is created, unpackaged only) |
| `setJumpList(categories, tasks, knownCategories): Boolean` | Set the entire jump list atomically |
| `clearJumpList(): Boolean` | Remove all jump list entries |
| `lastError: String?` | Last error message, or null |

### `JumpListItem`

A single clickable item in a jump list.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `title` | `String` | `""` | Display text |
| `arguments` | `String` | `""` | Command-line args passed when clicked |
| `description` | `String` | `""` | Tooltip text |
| `icon` | `TaskbarIconSource?` | `null` | Item icon (null = app icon) |
| `isSeparator` | `Boolean` | `false` | Whether this is a separator (tasks only) |

Use `JumpListItem.SEPARATOR` for visual separators in the tasks section.

### `JumpListCategory`

A named group of items.

| Property | Type | Description |
|----------|------|-------------|
| `name` | `String` | Category display name (must be unique) |
| `items` | `List<JumpListItem>` | Items in this category |

### `KnownCategory`

Shell-managed categories populated automatically by Windows.

| Value | Description |
|-------|-------------|
| `FREQUENT` | Frequently used destinations |
| `RECENT` | Recently used destinations |

### How Click Handling Works

Unlike macOS (which uses in-process `NSMenu` delegates) or Linux (which uses D-Bus callbacks),
Windows jump lists have **no in-process callback mechanism**. When a user clicks a jump list item,
Windows **launches a new process** of your application with the item's `arguments` appended to the
command line.

To handle this in a running application, you need two pieces from `core-runtime`:

1. **`SingleInstanceManager`** — detects that a second instance was launched and forwards data to the primary instance via a file-based IPC mechanism.
2. **`DeepLinkHandler`** — parses URI-style arguments from the command line and provides a callback when a deep link is received.

#### Setup in `main()`

```kotlin
fun main(args: Array<String>) {
    // 1. Set the AUMID before any window is created (unpackaged apps only)
    WindowsJumpListManager.setProcessAppId()

    // 2. Register the deep link handler — parses any URI from CLI args
    DeepLinkHandler.register(args) { uri ->
        println("Received deep link: $uri")
        // Handle the URI (navigate, open file, etc.)
    }

    application {
        // 3. Enforce single instance with argument forwarding
        val isFirstInstance = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    // Secondary instance: write the received URI to the IPC file
                    DeepLinkHandler.writeUriTo(this)
                },
                onRestoreRequest = {
                    // Primary instance: read the URI from the IPC file
                    DeepLinkHandler.readUriFrom(this)
                    // Bring window to front
                    isWindowVisible = true
                },
            )
        }

        // 4. If this is a secondary instance, exit immediately
        if (!isFirstInstance) {
            exitApplication()
            return@application
        }

        // ... create your window
    }
}
```

#### Complete Flow

```
User clicks jump list item ("Open Dashboard", arguments = "myapp://dashboard")
    |
    v
Windows launches: myapp.exe myapp://dashboard
    |
    v
main(args = ["myapp://dashboard"]) starts
    |
    +-> DeepLinkHandler.register(args) parses "myapp://dashboard" as a URI
    |
    v
SingleInstanceManager.isSingleInstance() tries to acquire lock
    |
    +- Lock FAILS (primary instance holds it)
    |   +-> onRestoreFileCreated: writes URI to .restore_request file
    |   +-> Secondary instance exits
    |
    v
Primary instance's WatchService detects .restore_request file
    |
    +-> onRestoreRequest: DeepLinkHandler.readUriFrom(path)
    |   +-> onDeepLink callback fires with URI("myapp://dashboard")
    |
    +-> Window brought to front, URI handled
```

> **Note:** Jump list items require a real application executable. They do **not** work
> in Gradle `run` dev mode (where the process is `java.exe`). Test with `runDistributable`
> or a packaged build (APPX, NSIS, MSI).

---

## Icons

All APIs that accept icons use the unified `TaskbarIconSource` sealed class.

### `TaskbarIconSource`

| Variant | Description |
|---------|-------------|
| `FromStock(stockIcon: StockIcon)` | Windows Shell stock icon (available on all Vista+ systems, no files needed) |
| `FromFile(path: String)` | Load from an `.ico` file on disk |
| `FromResource(dllPath: String, index: Int)` | Extract from a resource DLL (e.g., `shell32.dll`) |

### `StockIcon`

Type-safe enum mapping all 94 Windows Shell Stock Icons (`SHSTOCKICONID`). Commonly used values:

| Icon | Description |
|------|-------------|
| `StockIcon.WARNING` | Yellow warning triangle |
| `StockIcon.ERROR` | Red error circle |
| `StockIcon.INFO` | Blue information circle |
| `StockIcon.SHIELD` | UAC shield |
| `StockIcon.HELP` | Blue question mark |
| `StockIcon.LOCK` | Padlock |
| `StockIcon.KEY` | Key |
| `StockIcon.FIND` | Magnifying glass |
| `StockIcon.SETTINGS` | Gear/settings |
| `StockIcon.WORLD` | Globe |
| `StockIcon.USERS` | Multiple users |
| `StockIcon.FOLDER` | Closed folder |
| `StockIcon.DESKTOP_PC` | Desktop computer |
| `StockIcon.MEDIA_PLAY` | Media play button |
| `StockIcon.MEDIA_REWIND` | Media rewind |
| `StockIcon.MEDIA_FORWARD` | Media fast-forward |

See the `StockIcon` enum for the full list of 94 icons covering documents, folders, drives, network, media, devices, and status categories.

---

## AUMID Resolution

The AUMID (Application User Model ID) identifies your app to Windows:

| Packaging | Badge | Jump List | Overlay / Toolbar |
|-----------|-------|-----------|-------------------|
| **APPX/MSIX** | Automatic (package identity) | Automatic (package identity) | No AUMID needed |
| **Unpackaged** | Not supported | Auto-derived from `NucleusApp.appId` | No AUMID needed |

## Platform Notes

- Badge notifications require APPX/MSIX packaging (WinRT limitation).
- Jump lists, overlay icons, and thumbnail toolbar work for all packaging types.
- All operations are synchronous.
- `isAvailable` returns `false` on non-Windows platforms.
- Thumbnail toolbar buttons persist for the lifetime of the window. `unregister` hides them; `setButtons` re-shows them.

## ProGuard

When ProGuard is enabled, the Nucleus Gradle plugin automatically includes the required keep rules. No manual configuration is needed.

## GraalVM Native Image

The module ships with `reachability-metadata.json` for GraalVM native-image compatibility. No additional configuration is required.
