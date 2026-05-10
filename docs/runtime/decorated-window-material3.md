# Decorated Window — Material 3

The `decorated-window-material3` module provides Material 3 wrappers around the [Decorated Window](decorated-window.md) components. It reads colors from `MaterialTheme.colorScheme` and automatically builds the matching `DecoratedWindowStyle` and `TitleBarStyle` — no manual color mapping needed.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.decorated-window-material3:<version>")
    // Transitive: nucleus.decorated-window is pulled in via `api`
}
```

## Quick Start

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()  // from nucleus.darkmode-detector
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            MaterialTitleBar { state ->
                Text(
                    title,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface,
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

Drop-in replacement for `DecoratedWindow`. Same parameters, but wraps the window in a `NucleusDecoratedWindowTheme` derived from the current `MaterialTheme.colorScheme`.

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

The `isDark` flag for `NucleusDecoratedWindowTheme` is inferred automatically from the luminance of `colorScheme.background`. The `minimumSize` parameter sets a DPI-correct minimum window size and avoids the off-center first-frame issue described in [DecoratedWindow](decorated-window.md#decoratedwindow).

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

Material-styled title bars. They read `MaterialTheme.colorScheme` and build a `TitleBarStyle` from it. They accept the same `gradientStartColor`, `backgroundContent`, and `controlButtonsDirection` parameters as the base `TitleBar`.

```kotlin
MaterialTitleBar(
    modifier = Modifier
        .newFullscreenControls()   // sliding overlay title bar in fullscreen (all platforms)
        .macOSLargeCornerRadius(), // 26pt corner radius on macOS (Finder/Safari style)
    gradientStartColor = Color.Unspecified,                  // optional horizontal gradient
    controlButtonsDirection = ControlButtonsDirection.Auto,   // button placement (Auto/System/Ltr/Rtl)
    backgroundContent = {},                                  // optional custom background layer
) { state ->
    // TitleBarScope content
}
```

See [controlButtonsDirection](decorated-window.md#controlbuttonsdirection--independent-button-placement) for details on decoupling button placement from content direction.

See [Decorated Window — Fullscreen Title Bar](decorated-window.md#fullscreen-title-bar) for details on the sliding overlay behavior and the large corner radius modifier.

#### Custom background with `backgroundContent`

`backgroundContent` is a composable drawn behind the title bar content, on top of the base `background` fill. Use it to render shapes, gradients, or images that require full layout control. The lambda receives a `Box` scope sized to the full title bar area.

A common use case is a diagonal color band on the leading edge — for example a branded accent that covers the native window controls area:

```kotlin
MaterialTitleBar(
    backgroundContent = {
        val brandColor = Color(0xFFD32F2F)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val slantWidth = size.height * 4f
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(slantWidth, 0f)
                    lineTo(slantWidth - size.height, size.height)
                    lineTo(0f, size.height)
                    close()
                },
                color = brandColor,
            )
        }
    },
) { _ ->
    // content
}
```

This draws a solid red trapezoid from the left edge with a 45° diagonal cut. Adjust `size.height * N` to control the width.

## Color Mapping

The module maps Material 3 tokens to decorated window styling as follows:

| Decorated Window token | Material 3 source |
|------------------------|--------------------|
| Title bar background | `surface` |
| Title bar inactive background | `surface` |
| Title bar content color | `onSurface` |
| Title bar border | `outlineVariant` |
| Window border | `outlineVariant` |
| Window border (inactive) | `outlineVariant` at 50% alpha |
| Button hover background | `onSurface` at 8% alpha |
| Button press background | `onSurface` at 12% alpha |
| Close button hover | `error` |
| Close button press | `error` at 70% alpha |
| Fullscreen controls background | `surface` |

This means switching from a `lightColorScheme()` to a `darkColorScheme()` (or a dynamic/custom scheme) will update the title bar, borders, and window controls automatically.

## Using with Dark Mode Detector

Combine with [`nucleus.darkmode-detector`](darkmode-detector.md) for automatic theme switching:

```kotlin
fun main() = application {
    val isDark = isSystemInDarkMode()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
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

When the user toggles dark mode in their OS settings, `isSystemInDarkMode()` recomposes, the `colorScheme` changes, and the decorated window updates to match — including the title bar, borders, and window control hover states.
