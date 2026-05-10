# Menu (macOS)

Declarative, Compose-reactive macOS application menu bar via JNI. Build a fully native menu bar with SF Symbols, keyboard shortcuts, badges, submenus, checkboxes, radio buttons, and more — all driven by Compose state.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.menu-macos:<version>")
}
```

Pulls in `core-runtime`, `compose.desktop.common`, and `sf-symbols` transitively. Consumers can use type-safe SF Symbol constants via `dev.nucleusframework.nucleus.sfsymbols.*` without declaring `sf-symbols` explicitly.

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.menu.macos.*
import dev.nucleusframework.nucleus.sfsymbols.*

@Composable
fun App() {
    NativeMenuBar {
        Menu("File") {
            Item("New", shortcut = NativeKeyShortcut("n"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.DOCUMENT_BADGE_PLUS)) { println("New") }
            Item("Open...", shortcut = NativeKeyShortcut("o"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.FOLDER)) { println("Open") }
            Separator()
            Item("Quit", shortcut = NativeKeyShortcut("q"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolPower.POWER)) { exitProcess(0) }
        }
    }
    // ... your window content
}
```

The menu bar is installed when `NativeMenuBar` enters the composition and the original is restored when it leaves.

---

## NativeMenuBar

```kotlin
@Composable
fun NativeMenuBar(content: @Composable NativeMenuBarScope.() -> Unit)
```

Top-level composable that replaces the application menu bar. **Reactive** — any Compose state read inside `content` triggers a rebuild of the native menu bar.

The **first** `Menu` call produces the application menu (the bold entry whose title is the app name).

!!! info "App name in the menu bar"
    The Nucleus Gradle plugin automatically sets the app name from `appName` (fallback: `packageName`) in the DSL — both for `./gradlew run` (`-Dapple.awt.application.name`) and for packaged `.app` distributions (`CFBundleName` in Info.plist).

---

## Menu

```kotlin
// Top-level menu (in NativeMenuBarScope)
@Composable
fun Menu(text: String, enabled: Boolean = true, mnemonic: Char? = null,
         content: @Composable NativeMenuScope.() -> Unit)

// Nested submenu (in NativeMenuScope)
@Composable
fun Menu(text: String, enabled: Boolean = true, mnemonic: Char? = null,
         icon: NsMenuItemImage? = null,
         content: @Composable NativeMenuScope.() -> Unit)
```

Can be nested to arbitrary depth:

```kotlin
Menu("File") {
    Menu("Open Recent", icon = NsMenuItemImage.SystemSymbol("clock.arrow.circlepath")) {
        Item("project.kt") { }
        Item("build.gradle.kts") { }
        Separator()
        Item("Clear Menu") { }
    }
}
```

---

## Item

```kotlin
fun Item(
    text: String,
    enabled: Boolean = true,
    shortcut: NativeKeyShortcut? = null,
    icon: NsMenuItemImage? = null,
    state: NsMenuItemState = NsMenuItemState.OFF,
    tag: Int = 0,
    badge: NsMenuItemBadge? = null,
    subtitle: String? = null,
    toolTip: String? = null,
    indentationLevel: Int = 0,
    isAlternate: Boolean = false,
    isHidden: Boolean = false,
    onStateImage: NsMenuItemImage? = null,
    offStateImage: NsMenuItemImage? = null,
    mixedStateImage: NsMenuItemImage? = null,
    onClick: () -> Unit = {},
)
```

Full-featured menu item. `onClick` is a trailing lambda:

```kotlin
Item("Save", shortcut = NativeKeyShortcut("s"), icon = NsMenuItemImage.SystemSymbol("square.and.arrow.down")) {
    println("Saved!")
}
```

!!! info "Thread safety"
    `onClick` callbacks are dispatched on the Swing EDT via `SwingUtilities.invokeLater`.

### Keyboard Shortcuts

```kotlin
Item("Save", shortcut = NativeKeyShortcut("s")) { }                    // ⌘S
Item("Save As...", shortcut = NativeKeyShortcut("s", shift = true)) { } // ⇧⌘S
Item("Find...", shortcut = NativeKeyShortcut("f", option = true)) { }   // ⌥⌘F
Item("Full Screen", shortcut = NativeKeyShortcut("f", control = true)) { } // ⌃⌘F
```

`NativeKeyShortcut` parameters:

| Parameter | Modifier | Default |
|---|---|---|
| `command` | ⌘ Command | `true` |
| `shift` | ⇧ Shift | `false` |
| `option` | ⌥ Option | `false` |
| `control` | ⌃ Control | `false` |
| `function` | Fn | `false` |

For special keys, use `NativeKey` constants:

```kotlin
Item("Exit", shortcut = NativeKeyShortcut(NativeKey.Escape, command = false)) { }
Item("Help", shortcut = NativeKeyShortcut("?")) { }
```

Available constants: `NativeKey.Escape`, `Return`, `Tab`, `Delete`, `Backspace`, `Up`, `Down`, `Left`, `Right`, `F1`–`F12`, `Home`, `End`, `PageUp`, `PageDown`.

### Images (SF Symbols)

With the `sf-symbols` module (type-safe):

```kotlin
import dev.nucleusframework.nucleus.sfsymbols.*

Item("Cut", icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.SCISSORS)) { }
Item("Inbox", icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.TRAY_FILL)) { }
Item("Undo", icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_UTURN_BACKWARD)) { }
```

With raw strings (no extra dependency):

```kotlin
Item("Cut", icon = NsMenuItemImage.SystemSymbol("scissors")) { }
```

Other image sources:

```kotlin
Item("Info", icon = NsMenuItemImage.Named("NSActionTemplate")) { }  // AppKit named image
Item("Icon", icon = NsMenuItemImage.File("/path/to/icon.png")) { }  // file path
```

State-specific images:

```kotlin
Item("Sync",
    state = NsMenuItemState.ON,
    onStateImage = NsMenuItemImage.SystemSymbol(SFSymbolStatus.CHECKMARK_CIRCLE_FILL),
    offStateImage = NsMenuItemImage.SystemSymbol(SFSymbolShapes.CIRCLE),
    mixedStateImage = NsMenuItemImage.SystemSymbol(SFSymbolStatus.MINUS_CIRCLE),
) { }
```

### Badges (macOS 14+)

```kotlin
Item("Inbox", badge = NsMenuItemBadge.Count(42)) { }
Item("Updates", badge = NsMenuItemBadge.updates(3)) { }
Item("Alerts", badge = NsMenuItemBadge.alerts(1)) { }
Item("New", badge = NsMenuItemBadge.newItems(7)) { }
Item("Build", badge = NsMenuItemBadge.Text("PASS")) { }
```

Predefined types (`alerts`, `updates`, `newItems`) are automatically localized by macOS. Custom string badges must be localized by the caller.

### Subtitle (macOS 14.4+)

```kotlin
Item("Main Title", subtitle = "Secondary description text") { }
```

### Other Properties

```kotlin
Item("Disabled", enabled = false) { }
Item("Hidden (shortcut still works)", isHidden = true, shortcut = NativeKeyShortcut("h", shift = true, option = true)) { }
Item("Indented", indentationLevel = 2) { }
Item("With Tooltip", toolTip = "Hover to see this") { }
Item("Tagged", tag = 42) { }
```

---

## Alternate Items

Alternate items appear when the user holds Option. They must immediately follow the base item and share the same key equivalent with a different modifier:

```kotlin
Item("Paste", shortcut = NativeKeyShortcut("v")) { }
Item("Paste and Match Style",
    shortcut = NativeKeyShortcut("v", option = true, shift = true),
    isAlternate = true,
) { }
```

---

## CheckboxItem

Toggles between checked (✓) and unchecked:

```kotlin
var showToolbar by remember { mutableStateOf(true) }

CheckboxItem("Show Toolbar", checked = showToolbar,
    icon = NsMenuItemImage.SystemSymbol("sidebar.left"),
    onCheckedChange = { showToolbar = it },
)
```

Supports all the same optional parameters as `Item` (shortcut, badge, subtitle, etc.) except `state`, `isAlternate`, `isHidden`, and state images.

---

## RadioButtonItem

Mutually exclusive selection — manage state externally:

```kotlin
var theme by remember { mutableStateOf("System") }

RadioButtonItem("System", selected = theme == "System", onClick = { theme = "System" })
RadioButtonItem("Light", selected = theme == "Light", onClick = { theme = "Light" })
RadioButtonItem("Dark", selected = theme == "Dark", onClick = { theme = "Dark" })
```

---

## Separator

```kotlin
Separator()
```

---

## SectionHeader (macOS 14+)

Non-clickable section label. Falls back to a disabled item on older macOS versions.

```kotlin
SectionHeader("View Options")
CheckboxItem("Show Toolbar", ...)
CheckboxItem("Show Status Bar", ...)
```

---

## Conditional Menus

Because the content is `@Composable`, you can use standard Compose control flow:

```kotlin
var advanced by remember { mutableStateOf(false) }

Menu("Advanced") {
    CheckboxItem("Enable Advanced", checked = advanced, onCheckedChange = { advanced = it })
    if (advanced) {
        Separator()
        Menu("Settings") {
            Item("Setting 1") { }
            Item("Setting 2") { }
        }
    }
}
```

The menu bar rebuilds automatically when `advanced` changes — the "Settings" submenu appears or disappears.

---

## Well-Known Menus

When the menu bar is installed, submenus with specific titles are automatically registered with macOS:

| Submenu title | Effect |
|---|---|
| `"Services"` | macOS populates with system services. |
| `"Window"` | macOS adds the window list and "Bring All to Front". |
| `"Help"` | macOS adds the search-in-menus field. |

Detection is by **exact title**. To opt out, use a different title:

```kotlin
Menu("Services") { /* macOS fills this */ }           // ✓ auto-registered
Menu("App Services") { Item("Custom") { } }           // ✗ fully yours
```

---

## Full Example

```kotlin
import dev.nucleusframework.nucleus.sfsymbols.*

@Composable
fun App() {
    var showToolbar by remember { mutableStateOf(true) }
    var theme by remember { mutableStateOf("System") }
    var inboxCount by remember { mutableStateOf(42) }

    NativeMenuBar {
        Menu("MyApp") {
            Item("About MyApp", icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.INFO_CIRCLE)) { }
            Separator()
            Item("Settings...", shortcut = NativeKeyShortcut(","),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.GEARSHAPE)) { }
            Separator()
            Menu("Services") { }
            Separator()
            Item("Quit", shortcut = NativeKeyShortcut("q"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolPower.POWER)) { exitProcess(0) }
        }
        Menu("File") {
            Item("New", shortcut = NativeKeyShortcut("n"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.DOCUMENT_BADGE_PLUS)) { }
            Item("Open...", shortcut = NativeKeyShortcut("o"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolObjectsAndTools.FOLDER)) { }
            Separator()
            Item("Save", shortcut = NativeKeyShortcut("s"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolShapes.SQUARE_AND_ARROW_DOWN)) { }
        }
        Menu("View") {
            CheckboxItem("Show Toolbar", checked = showToolbar,
                icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.SIDEBAR_LEFT),
                onCheckedChange = { showToolbar = it })
            Separator()
            SectionHeader("Theme")
            RadioButtonItem("System", selected = theme == "System",
                onClick = { theme = "System" })
            RadioButtonItem("Light", selected = theme == "Light",
                onClick = { theme = "Light" })
            RadioButtonItem("Dark", selected = theme == "Dark",
                onClick = { theme = "Dark" })
        }
        Menu("Badges") {
            Item("Inbox", badge = NsMenuItemBadge.Count(inboxCount),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolGeneral.TRAY_FILL)) { }
            Item("Updates", badge = NsMenuItemBadge.updates(3),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolArrows.ARROW_DOWN_CIRCLE)) { }
        }
        Menu("Window") { }
        Menu("Help") {
            Item("MyApp Help", shortcut = NativeKeyShortcut("?"),
                icon = NsMenuItemImage.SystemSymbol(SFSymbolStatus.QUESTIONMARK_CIRCLE)) { }
        }
    }

    // Window content...
}
```

---

## API Reference

### Composable

| Function | Scope | Description |
|---|---|---|
| `NativeMenuBar { }` | — | Installs a native menu bar. Restores original on disposal. |

### NativeMenuBarScope

| Function | Description |
|---|---|
| `Menu(text, enabled, mnemonic, content)` | Top-level menu. |

### NativeMenuScope

| Function | Description |
|---|---|
| `Item(text, ..., onClick)` | Regular item with all NSMenuItem properties. Trailing lambda. |
| `CheckboxItem(text, checked, onCheckedChange, ...)` | Toggle item. |
| `RadioButtonItem(text, selected, onClick, ...)` | Radio selection item. |
| `Separator()` | Separator line. |
| `SectionHeader(title)` | Section header (macOS 14+). |
| `Menu(text, enabled, icon, content)` | Nested submenu. |

### NativeKeyShortcut

```kotlin
data class NativeKeyShortcut(
    val key: String,
    val command: Boolean = true,
    val shift: Boolean = false,
    val option: Boolean = false,
    val control: Boolean = false,
    val function: Boolean = false,
)
```

### NativeKey

Special key constants: `Escape`, `Return`, `Tab`, `Delete`, `Backspace`, `Up`, `Down`, `Left`, `Right`, `F1`–`F12`, `Home`, `End`, `PageUp`, `PageDown`.

### NsMenuItemImage

| Variant | Description |
|---|---|
| `NsMenuItemImage.SystemSymbol(symbol)` | Type-safe SF Symbol from the `sf-symbols` module (macOS 11+). |
| `NsMenuItemImage.SystemSymbol(name)` | SF Symbol by raw string name (macOS 11+). |
| `NsMenuItemImage.Named(name)` | AppKit named image. |
| `NsMenuItemImage.File(path)` | Image from file path. |

The `sf-symbols` module provides **6 195** type-safe constants across 21 categories: `SFSymbolArrows`, `SFSymbolMedia`, `SFSymbolObjectsAndTools`, `SFSymbolStatus`, `SFSymbolGeneral`, `SFSymbolDevices`, `SFSymbolShapes`, etc.

### NsMenuItemBadge

| Factory | Description |
|---|---|
| `NsMenuItemBadge.Count(n)` | Numeric badge. |
| `NsMenuItemBadge.Text(s)` | Custom string. |
| `NsMenuItemBadge.alerts(n)` | System-localized alerts. |
| `NsMenuItemBadge.updates(n)` | System-localized updates. |
| `NsMenuItemBadge.newItems(n)` | System-localized new items. |

### NsMenuItemState

| Value | Description |
|---|---|
| `OFF` | No mark. |
| `ON` | Checkmark (✓). |
| `MIXED` | Dash (—). |

---

## Native Library

Ships pre-built macOS dylibs (arm64 + x86_64). `NativeMenuBar` is a no-op on non-macOS platforms.

- `libnucleus_menu_macos.dylib` — linked against `Cocoa.framework`
- Minimum deployment target: macOS 10.13
- Newer APIs (badges, section headers, subtitles) degrade gracefully on older systems
- All mutations dispatched on the main thread
- Action callbacks routed back to Kotlin via JNI, then to Swing EDT

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.menu.macos.NativeNsMenuBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

```json
[
  {
    "type": "dev.nucleusframework.nucleus.menu.macos.NativeNsMenuBridge",
    "methods": [
      { "name": "onMenuItemAction", "parameterTypes": ["long"] },
      { "name": "onMenuWillOpen", "parameterTypes": ["long"] },
      { "name": "onMenuDidClose", "parameterTypes": ["long"] },
      { "name": "onMenuNeedsUpdate", "parameterTypes": ["long"] },
      { "name": "onMenuWillHighlightItem", "parameterTypes": ["long", "long"] },
      { "name": "onNumberOfItemsInMenu", "parameterTypes": ["long"] }
    ]
  }
]
```
