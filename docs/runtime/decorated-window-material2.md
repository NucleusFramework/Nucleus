# Decorated Window — Material 2

The `decorated-window-material2` module provides Material 2 wrappers around the [Decorated Window](decorated-window.md) components. It reads colors from `MaterialTheme.colors` and automatically builds the matching `DecoratedWindowStyle` and `TitleBarStyle` — no manual color mapping needed.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.decorated-window-material2:<version>")
    // Transitive: nucleus.decorated-window is pulled in via `api`
}
```

## Quick Start

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()  // from nucleus.darkmode-detector
    val colors = if (isDark) darkColors() else lightColors()

    MaterialTheme(colors = colors) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            MaterialTitleBar { state ->
                Text(
                    title,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colors.onSurface,
                )
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                // Your app content
            }
        }
    }
}
```

That is all you need. The title bar, borders, and window control hover states automatically match your Material color scheme.

## Components

### `MaterialDecoratedWindow`

Drop-in replacement for `DecoratedWindow`. Same parameters, but wraps the window in a `NucleusDecoratedWindowTheme` derived from the current `MaterialTheme.colors`.

```kotlin
MaterialDecoratedWindow(
    onCloseRequest = ::exitApplication,
    state = rememberWindowState(),
    title = "My App",
    minimumSize = DpSize(1100.dp, 480.dp),
) {
    MaterialTitleBar { state -> /* ... */ }
    // content
}
```

The `isDark` flag for `NucleusDecoratedWindowTheme` is inferred from `Colors.isLight`. The `minimumSize` parameter sets a DPI-correct minimum window size and avoids the off-center first-frame issue described in [DecoratedWindow](decorated-window.md#decoratedwindow).

### `MaterialDecoratedDialog`

Same concept for dialogs:

```kotlin
MaterialDecoratedDialog(
    onCloseRequest = { showDialog = false },
    title = "Settings",
) {
    MaterialDialogTitleBar { state ->
        Text(title, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        // dialog content
    }
}
```

### `MaterialTitleBar` / `MaterialDialogTitleBar`

Material-styled title bars. They read `MaterialTheme.colors` and build a `TitleBarStyle` from it. They accept the same `gradientStartColor` and `backgroundContent` parameters as the base `TitleBar`.

```kotlin
MaterialTitleBar(
    modifier = Modifier
        .newFullscreenControls()   // sliding overlay title bar in fullscreen (all platforms)
        .macOSLargeCornerRadius(), // 26pt corner radius on macOS (Finder/Safari style)
    gradientStartColor = Color.Unspecified, // optional horizontal gradient
    backgroundContent = {},                // optional custom background layer
) { state ->
    // TitleBarScope content
}
```

See [Decorated Window — Fullscreen Title Bar](decorated-window.md#fullscreen-title-bar) for details on the sliding overlay behavior and the large corner radius modifier.

## Color Mapping

The module maps Material 2 tokens to decorated window styling as follows:

| Decorated Window token | Material 2 source |
|------------------------|--------------------|
| Title bar background | `surface` |
| Title bar inactive background | `surface` |
| Title bar content color | `onSurface` |
| Title bar border | `onSurface` at 12% alpha |
| Window border | `onSurface` at 12% alpha |
| Window border (inactive) | `onSurface` at 6% alpha |
| Fullscreen controls background | `surface` |

This means switching from a `lightColors()` to a `darkColors()` (or a custom color palette) will update the title bar, borders, and window controls automatically.

## Using with Dark Mode Detector

Combine with [`nucleus.darkmode-detector`](darkmode-detector.md) for automatic theme switching:

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()
    val colors = if (isDark) darkColors() else lightColors()

    MaterialTheme(colors = colors) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            MaterialTitleBar { /* ... */ }
            Surface(modifier = Modifier.fillMaxSize()) {
                Text("Theme follows OS setting")
            }
        }
    }
}
```

When the user toggles dark mode in their OS settings, `isSystemInDarkMode()` recomposes, the colors change, and the decorated window updates to match — including the title bar, borders, and window control hover states.

## Migrating to Material 3

If you later migrate to Material 3, switch to the [`decorated-window-material3`](decorated-window-material3.md) module instead. The API is identical — only the imports and color source change (`MaterialTheme.colorScheme` instead of `MaterialTheme.colors`).
