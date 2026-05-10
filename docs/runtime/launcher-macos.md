# Launcher (macOS)

macOS dock context menu integration via JNI. Add custom items, submenus, separators, and click callbacks to your application's dock icon right-click menu.

!!! info "Method swizzling"
    Intercepts `applicationDockMenu:` on the existing `NSApplicationDelegate` via method swizzling. Works with any JVM — no JetBrains Runtime required.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.launcher-macos:<version>")
}
```

Depends on `core-runtime` for `NativeLibraryLoader` (pulled in transitively).

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.launcher.macos.*

// Listen for clicks
MacOsDockMenu.listener = DockMenuListener { itemId ->
    println("Clicked item: $itemId")
}

// Set the dock menu
MacOsDockMenu.setDockMenu(listOf(
    DockMenuItem(id = 1, title = "New Window"),
    DockMenuItem(id = 2, title = "Open File"),
    DockMenuItem.separator(id = 3),
    DockMenuItem(
        id = 4, title = "Recent Files",
        children = listOf(
            DockMenuItem(id = 41, title = "project.kt"),
            DockMenuItem(id = 42, title = "build.gradle.kts"),
        ),
    ),
    DockMenuItem.separator(id = 5),
    DockMenuItem(id = 6, title = "Preferences"),
    DockMenuItem(id = 7, title = "Disabled Item", enabled = false),
))

// Remove the dock menu
MacOsDockMenu.clearDockMenu()
```

!!! info "Thread safety"
    Click callbacks are dispatched to the Swing EDT via `SwingUtilities.invokeLater`. You can safely update Compose state directly in your listener.

---

## API Reference

### `MacOsDockMenu`

Singleton entry point for dock menu management. All methods are no-op when `isAvailable` is `false`.

| Property / Method | Description |
|---|---|
| `isAvailable: Boolean` | `true` if the native library is loaded (macOS only). |
| `listener: DockMenuListener?` | Callback invoked when a menu item is clicked. |
| `setDockMenu(items)` | Set the dock context menu. Installs the method swizzle on first call. |
| `clearDockMenu()` | Remove the dock context menu. |

---

### `DockMenuListener`

Functional interface for menu item click events:

```kotlin
MacOsDockMenu.listener = DockMenuListener { itemId ->
    when (itemId) {
        1 -> openNewWindow()
        2 -> openFile()
    }
}
```

| Method | Description |
|---|---|
| `onItemClicked(itemId: Int)` | Called when the user clicks a dock menu item. Invoked on the Swing EDT. |

---

### `DockMenuItem`

Data class representing a single item in the dock context menu.

| Property | Type | Default | Description |
|---|---|---|---|
| `id` | `Int` | *(required)* | Unique numeric identifier (must be > 0). |
| `title` | `String` | `""` | Display text. |
| `enabled` | `Boolean` | `true` | Whether the item is clickable. |
| `children` | `List<DockMenuItem>` | `emptyList()` | Sub-menu items. Non-empty creates a submenu. |

!!! note "macOS Dock menu limitations"
    macOS Dock menus do not support images on menu items — the Dock process strips them. Only text, separators, submenus, and enabled/disabled state are rendered.

#### Factory Methods

```kotlin
// Separator
DockMenuItem.separator(id = 3)
```

---

## Full Example

```kotlin
import dev.nucleusframework.nucleus.launcher.macos.*

// Set up a dock menu with submenus and callbacks
MacOsDockMenu.listener = DockMenuListener { itemId ->
    when (itemId) {
        1 -> createNewWindow()
        2 -> openFilePicker()
        41 -> openRecentFile("project.kt")
        42 -> openRecentFile("build.gradle.kts")
        43 -> openRecentFile("README.md")
        6 -> showPreferences()
    }
}

MacOsDockMenu.setDockMenu(listOf(
    DockMenuItem(id = 1, title = "New Window"),
    DockMenuItem(id = 2, title = "Open File"),
    DockMenuItem.separator(id = 3),
    DockMenuItem(
        id = 4, title = "Recent Files",
        children = listOf(
            DockMenuItem(id = 41, title = "project.kt"),
            DockMenuItem(id = 42, title = "build.gradle.kts"),
            DockMenuItem(id = 43, title = "README.md"),
        ),
    ),
    DockMenuItem.separator(id = 5),
    DockMenuItem(id = 6, title = "Preferences"),
))

// Clean up on shutdown
MacOsDockMenu.clearDockMenu()
```

---

## Relation to `taskbar-progress`

`taskbar-progress` provides cross-platform progress bar and attention request APIs. On macOS, it uses `NSDockTile` internally.

Use `launcher-macos` when you need macOS-specific dock context menu functionality. The two modules are complementary — `taskbar-progress` handles the dock icon badge/progress, while `launcher-macos` handles the right-click menu.

---

## Native Library

Ships pre-built macOS dylibs (arm64 + x86_64). `isAvailable` returns `false` on other platforms and all methods are no-op.

- `libnucleus_launcher_macos.dylib` — linked against `Cocoa.framework`
- Minimum deployment target: macOS 10.14
- Uses Objective-C method swizzling to intercept `applicationDockMenu:` on the existing `NSApplicationDelegate`
- Menu construction and swizzle installation run on the main thread via `dispatch_sync`

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.launcher.macos.NativeMacOsDockMenuBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata must include the bridge class:

```json
[
  {
    "type": "dev.nucleusframework.nucleus.launcher.macos.NativeMacOsDockMenuBridge",
    "methods": [
      { "name": "onMenuItemClicked", "parameterTypes": ["int"] }
    ]
  }
]
```
