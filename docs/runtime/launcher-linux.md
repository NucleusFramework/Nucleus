# Launcher (Linux)

Complete Kotlin mapping of the [Unity Launcher API](https://wiki.ubuntu.com/Unity/LauncherAPI) and [com.canonical.dbusmenu](https://github.com/AyatanaIndicators/libdbusmenu) interface via JNI. Control your launcher icon's badge count, progress bar, urgency, update status, and dynamic right-click quicklist menus on Linux.

!!! info "Pure D-Bus via GIO"
    Uses GLib/GIO (`libgio-2.0`) for D-Bus communication — no JNA, no reflection, no Java D-Bus libraries.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.launcher-linux:<version>")
}
```

Depends on `core-runtime` for `NativeLibraryLoader` and `freedesktop-icons` for typesafe icon names (both pulled in transitively).

## Quick Start

### Launcher Entry (Badge, Progress, Urgency)

```kotlin
import dev.nucleusframework.nucleus.launcher.linux.*

val appUri = LinuxLauncherEntry.appUri("myapp.desktop")

// Badge count
LinuxLauncherEntry.setCount(appUri, 42)
LinuxLauncherEntry.clearCount(appUri)

// Progress bar
LinuxLauncherEntry.setProgress(appUri, 0.65)
LinuxLauncherEntry.clearProgress(appUri)

// Urgency (flash the icon)
LinuxLauncherEntry.setUrgent(appUri, true)

// Bulk update
LinuxLauncherEntry.update(appUri, LauncherProperties(
    count = 5,
    countVisible = true,
    progress = 0.8,
    progressVisible = true,
    urgent = false,
))
```

### Dynamic Quicklist (Right-Click Menu)

```kotlin
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.nucleus.launcher.linux.*

val quicklist = LinuxQuicklist("/com/example/MyApp/Menu")

// Handle click events
quicklist.listener = LinuxQuicklist.Listener { id ->
    when (id) {
        1 -> openNewWindow()
        2 -> openFile()
        8 -> exitApp()
    }
}

// Set the menu
quicklist.setMenu(listOf(
    DbusmenuItem(id = 1, label = "New Window", icon = FreedesktopIcon.Action.WINDOW_NEW),
    DbusmenuItem(id = 2, label = "Open File", icon = FreedesktopIcon.Action.DOCUMENT_OPEN),
    DbusmenuItem.separator(id = 3),
    DbusmenuItem(id = 4, label = "Recent", icon = FreedesktopIcon.Action.DOCUMENT_OPEN_RECENT,
        children = listOf(
            DbusmenuItem(id = 41, label = "project.kt"),
            DbusmenuItem(id = 42, label = "build.gradle.kts"),
        ),
    ),
    DbusmenuItem.separator(id = 5),
    DbusmenuItem(id = 8, label = "Quit", icon = FreedesktopIcon.Action.APPLICATION_EXIT,
        disposition = DbusmenuItem.Disposition.ALERT),
))

// Attach quicklist to the launcher entry
LinuxLauncherEntry.update(appUri, LauncherProperties(quicklist = quicklist.objectPath))

// On shutdown
quicklist.dispose()
```

---

## API Reference

### `LinuxLauncherEntry`

Singleton entry point for the `com.canonical.Unity.LauncherEntry` D-Bus interface. All methods are thread-safe.

| Property / Method | Returns | Description |
|---|---|---|
| `isAvailable` | `Boolean` | `true` if the native library is loaded (Linux only). |
| `appUri(desktopFileId)` | `String` | Builds an `application://` URI from a `.desktop` file ID. |
| `update(appUri, properties)` | `Boolean` | Emits an `Update` signal with the given properties. |
| `registerQueryHandler(appUri)` | `Boolean` | Registers a D-Bus object to handle `Query` method calls (for debugging). |
| `unregister()` | `Unit` | Unregisters the `Query` handler and releases D-Bus resources. |

#### Convenience Methods

| Method | Description |
|---|---|
| `setCount(appUri, count, visible = true)` | Set the badge count. |
| `clearCount(appUri)` | Clear the badge count. |
| `setProgress(appUri, progress, visible = true)` | Set the progress bar (0.0–1.0). |
| `clearProgress(appUri)` | Clear the progress bar. |
| `setUrgent(appUri, urgent)` | Set or clear the urgency flag. |
| `setUpdating(appUri, updating)` | Set or clear the updating flag. |

---

### `LauncherProperties`

Data class representing the properties sent in an `Update` signal. All properties are nullable — only non-null values are included in the D-Bus signal.

| Property | Type | Description |
|---|---|---|
| `count` | `Long?` | Badge count displayed on the launcher icon. |
| `countVisible` | `Boolean?` | Whether the count badge is visible. |
| `progress` | `Double?` | Progress bar value (0.0–1.0). |
| `progressVisible` | `Boolean?` | Whether the progress bar is visible. |
| `urgent` | `Boolean?` | Whether the entry requests user attention. |
| `quicklist` | `String?` | D-Bus object path to a `com.canonical.dbusmenu` server, or empty string to unset. |
| `updating` | `Boolean?` | Whether the application is being updated. |

---

### `LinuxQuicklist`

Dynamic quicklist server implementing `com.canonical.dbusmenu` over D-Bus. Desktop environments query this object to show a right-click context menu on the launcher icon.

```kotlin
val quicklist = LinuxQuicklist("/com/example/MyApp/Menu")
```

| Property / Method | Description |
|---|---|
| `objectPath: String` | The D-Bus object path for this menu server. |
| `listener: Listener?` | Callback invoked when a menu item is clicked. |
| `setMenu(items)` | Set the full menu layout. Replaces any previous layout. Returns `true` on success. |
| `dispose()` | Unregister the D-Bus object and release native resources. |

!!! info "Thread safety"
    Click callbacks are dispatched to the Swing EDT via `SwingUtilities.invokeLater`. You can safely update Compose state directly.

#### `LinuxQuicklist.Listener`

Functional interface for menu item click events:

```kotlin
quicklist.listener = LinuxQuicklist.Listener { itemId ->
    println("Clicked item: $itemId")
}
```

---

### `DbusmenuItem`

Data class representing a single menu item in a quicklist.

| Property | Type | Default | Description |
|---|---|---|---|
| `id` | `Int` | *(required)* | Unique numeric identifier (must be > 0, 0 is reserved for root). |
| `label` | `String` | `""` | Display text. Supports mnemonics with `_` prefix (e.g. `"_Open"`). |
| `icon` | `FreedesktopIcon?` | `null` | Typesafe icon from the [freedesktop Icon Naming Spec](freedesktop-icons.md). |
| `enabled` | `Boolean` | `true` | Whether the item is clickable. |
| `visible` | `Boolean` | `true` | Whether the item is shown. |
| `type` | `ItemType` | `STANDARD` | `STANDARD` or `SEPARATOR`. |
| `toggleType` | `ToggleType` | `NONE` | `NONE`, `CHECKBOX`, or `RADIO`. |
| `toggleState` | `Int` | `-1` | `-1` = indeterminate, `0` = unchecked, `1` = checked. |
| `shortcut` | `List<String>` | `emptyList()` | Keyboard shortcut descriptors (e.g. `listOf("Control", "S")`). |
| `disposition` | `Disposition` | `NORMAL` | Visual disposition: `NORMAL`, `INFORMATIONAL`, `WARNING`, or `ALERT`. |
| `children` | `List<DbusmenuItem>` | `emptyList()` | Sub-menu items. Non-empty creates a submenu. |

#### Factory Methods

```kotlin
// Separator
DbusmenuItem.separator(id = 3)
```

#### Checkbox / Radio Toggle

```kotlin
// Checkbox item
DbusmenuItem(
    id = 6,
    label = "Dark Mode",
    toggleType = DbusmenuItem.ToggleType.CHECKBOX,
    toggleState = if (darkMode) 1 else 0,
)
```

!!! tip "Updating toggle state"
    The dbusmenu protocol is stateless from the server side — the DE always queries `GetLayout` for the current state. To update a toggle, call `quicklist.setMenu(...)` again with the updated `toggleState`. The DE picks up the change on the next `GetLayout` query.

---

## Desktop Environment Support

The `com.canonical.Unity.LauncherEntry` D-Bus interface is supported by:

| Desktop / Dock | Badge | Progress | Urgent | Quicklist |
|---|---|---|---|---|
| GNOME (Ubuntu Dock / Dash to Dock) | Yes | Yes | Yes | Yes |
| KDE Plasma | Yes | Yes | Yes | Yes |
| Plank | Yes | Yes | Yes | Yes |
| budgie-panel | Yes | Yes | Yes | — |
| XFCE (with docklike-plugin) | — | — | — | — |

!!! note "Quicklist click events"
    GNOME Shell sends click events via `EventGroup` (batch), while KDE and Plank use `Event` (single). Both are handled transparently.

---

## Relation to `taskbar-progress`

`taskbar-progress` provides a cross-platform abstraction (Windows, macOS, Linux) for progress bars and attention requests. On Linux, it delegates to `launcher-linux` internally.

Use `launcher-linux` directly when you need Linux-specific features like badge counts, quicklist menus, or the updating flag. Use `taskbar-progress` for cross-platform progress bar needs.

---

## Native Library

Ships pre-built Linux shared libraries (x86_64 + aarch64). `isAvailable` returns `false` on other platforms.

- `libnucleus_launcher_linux.so` — linked against `libgio-2.0` (GLib/GIO)
- Build requirement: `libglib2.0-dev` (Debian/Ubuntu) or `glib2-devel` (Fedora)
- Each quicklist runs its own D-Bus server in a dedicated thread with its own `GMainLoop`

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.launcher.linux.NativeLinuxLauncherBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata must include the bridge class:

```json
[
  {
    "type": "dev.nucleusframework.nucleus.launcher.linux.NativeLinuxLauncherBridge",
    "methods": [
      { "name": "onMenuItemEvent", "parameterTypes": ["java.lang.String", "int"] }
    ]
  }
]
```
