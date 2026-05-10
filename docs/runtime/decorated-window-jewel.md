# Decorated Window — Jewel

The `decorated-window-jewel` module provides Jewel (IntelliJ theme) wrappers around the [Decorated Window](decorated-window.md) components. It reads colors from `JewelTheme` and automatically builds the matching `DecoratedWindowStyle` and `TitleBarStyle` — no manual color mapping needed.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.decorated-window-jewel:<version>")
    // Transitive: nucleus.decorated-window is pulled in via `api`
}
```

## Quick Start

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()  // from nucleus.darkmode-detector
    val theme = if (isDark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()

    IntUiTheme(theme = theme, styling = ComponentStyling.default()) {
        JewelDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            JewelTitleBar { state ->
                Text(title)
            }
            // Your app content
        }
    }
}
```

That is all you need. The title bar, borders, and window control hover states automatically match your Jewel theme.

## Components

### `JewelDecoratedWindow`

Drop-in replacement for `DecoratedWindow`. Same parameters, but wraps the window in a `NucleusDecoratedWindowTheme` derived from the current `JewelTheme`.

```kotlin
JewelDecoratedWindow(
    onCloseRequest = ::exitApplication,
    state = rememberWindowState(),
    title = "My App",
    minimumSize = DpSize(1100.dp, 480.dp),
) {
    JewelTitleBar { state -> /* ... */ }
    // content
}
```

The `isDark` flag for `NucleusDecoratedWindowTheme` is read directly from `JewelTheme.isDark`. The `minimumSize` parameter sets a DPI-correct minimum window size and avoids the off-center first-frame issue described in [DecoratedWindow](decorated-window.md#decoratedwindow).

### `JewelDecoratedDialog`

Same concept for dialogs:

```kotlin
JewelDecoratedDialog(
    onCloseRequest = { showDialog = false },
    title = "Settings",
) {
    JewelDialogTitleBar { state ->
        Text(title)
    }
    // dialog content
}
```

### `JewelTitleBar` / `JewelDialogTitleBar`

Jewel-styled title bars. They read `JewelTheme.globalColors` and `JewelTheme.contentColor` and build a `TitleBarStyle` from them. They accept the same `gradientStartColor` and `backgroundContent` parameters as the base `TitleBar`.

```kotlin
JewelTitleBar(
    modifier = Modifier
        .newFullscreenControls()
        .macOSLargeCornerRadius(),
    gradientStartColor = Color.Unspecified,
    backgroundContent = {},
) { state ->
    // TitleBarScope content
}
```

See [Decorated Window — Fullscreen Title Bar](decorated-window.md#fullscreen-title-bar) for details on the sliding overlay behavior and the large corner radius modifier.

## Color Mapping

The module maps Jewel theme tokens to decorated window styling as follows:

| Decorated Window token | Jewel source |
|------------------------|--------------------|
| Title bar background | `globalColors.panelBackground` |
| Title bar inactive background | `globalColors.panelBackground` |
| Title bar content color | `contentColor` |
| Title bar border | `globalColors.borders.normal` |
| Window border | `globalColors.borders.normal` |
| Window border (inactive) | `globalColors.borders.normal` at 50% alpha |
| Fullscreen controls background | `globalColors.panelBackground` |

This means switching between light and dark Jewel themes will update the title bar, borders, and window controls automatically.

## Different Title Bar Theme

A common pattern with Jewel is having a dark title bar with a light content area. Wrap `JewelTitleBar` in a different `IntUiTheme` to achieve this:

```kotlin
IntUiTheme(theme = contentTheme, styling = ComponentStyling.default()) {
    JewelDecoratedWindow(
        onCloseRequest = ::exitApplication,
        title = "My App",
    ) {
        // Title bar uses a different theme
        IntUiTheme(theme = titleBarTheme, styling = ComponentStyling.default()) {
            JewelTitleBar { state -> /* ... */ }
        }
        // Content uses the outer theme
    }
}
```

`JewelTitleBar` reads colors from the nearest `JewelTheme` provider, so wrapping it in a different `IntUiTheme` changes its colors independently from the window.

## Using with Dark Mode Detector

Combine with [`nucleus.darkmode-detector`](darkmode-detector.md) for automatic theme switching:

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()
    val theme = if (isDark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition()

    IntUiTheme(theme = theme, styling = ComponentStyling.default()) {
        JewelDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            JewelTitleBar { /* ... */ }
            Text("Theme follows OS setting")
        }
    }
}
```

When the user toggles dark mode in their OS settings, `isSystemInDarkMode()` recomposes, the theme changes, and the decorated window updates to match — including the title bar, borders, and window control hover states.
