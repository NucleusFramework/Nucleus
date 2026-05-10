# System Color

The `system-color` module provides **reactive** detection of the OS accent color and high contrast mode. It uses native JNI bridges on each platform to register OS-level listeners that trigger recomposition the instant a setting changes — no polling, no restart required.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.system-color:<version>")
}
```

## Usage

### Accent Color

```kotlin
@Composable
fun App() {
    val accentColor = systemAccentColor()

    MaterialTheme(
        colorScheme = if (accentColor != null) {
            // Build a dynamic color scheme from the system accent
            darkColorScheme(primary = accentColor)
        } else {
            // null = unsupported platform or macOS multicolor mode
            darkColorScheme()
        }
    ) {
        // UI automatically recomposes when the accent color changes
    }
}
```

!!! note "macOS Multicolor Mode"
    On macOS, when the user selects **Multicolor** in System Settings → Appearance → Accent color, each app is expected to use its own default color. In this case, `systemAccentColor()` returns `null` so your app can fall back to its own brand color or a default palette.

### Check Support

Use `isSystemAccentColorSupported()` to check platform support before entering a composable context — useful for feature gating or conditional UI:

```kotlin
fun main() = application {
    if (isSystemAccentColorSupported()) {
        // Platform supports accent color — safe to use systemAccentColor()
    }
}
```

### High Contrast

```kotlin
@Composable
fun App() {
    val highContrast = isSystemInHighContrast()

    if (highContrast) {
        // Use high contrast colors / larger borders
    }
}
```

## Material You on Desktop

Android's [Material You](https://m3.material.io/styles/color/dynamic/choosing-a-source) dynamic theming derives an entire color scheme from a single seed color — the system accent color. On desktop, this is not supported out of the box, but you can reproduce the same effect by combining `systemAccentColor()` with the [material-kolor](https://github.com/jordond/MaterialKolor) library.

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.system-color:<version>")
    implementation("com.materialkolor:material-kolor:4.1.1")
}
```

```kotlin
@Composable
fun App() {
    val accentColor = systemAccentColor()
    val seedColor = accentColor ?: Color(0xFF6750A4) // Material default purple

    DynamicMaterialTheme(
        seedColor = seedColor,
        isDark = isSystemInDarkTheme(),
        animate = true,
        style = PaletteStyle.TonalSpot,
    ) {
        // Your app content — the entire color scheme adapts
        // to the OS accent color in real time
    }
}
```

When the user changes their accent color in system settings, `systemAccentColor()` triggers a recomposition and `DynamicMaterialTheme` smoothly animates the entire palette to the new color.

The Nucleus example app uses this exact approach — you can test it by running:

```bash
./gradlew :example:run
```

## API Reference

| Function | Returns | Description |
|----------|---------|-------------|
| `systemAccentColor()` | `Color?` | Composable. Returns the current system accent color, or `null` if unsupported or if the OS is in multicolor mode (macOS). Recomposes on change. |
| `isSystemInHighContrast()` | `Boolean` | Composable. Returns `true` if the OS is in high contrast / increased contrast mode. Recomposes on change. |
| `isSystemAccentColorSupported()` | `Boolean` | Non-composable. Returns whether the current platform supports accent color detection. |

## Platform Detection Methods

| Platform | Accent Color | High Contrast | Reactive |
|----------|-------------|---------------|----------|
| **macOS** | `NSColor.controlAccentColor` (macOS 10.14+). Returns `null` in **multicolor mode** (detected via `AppleAccentColor` user default). | `accessibilityDisplayShouldIncreaseContrast` | Yes — `NSSystemColorsDidChangeNotification` / `NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification` |
| **Windows** | `HKCU\SOFTWARE\Microsoft\Windows\DWM\AccentColor` registry key (AABBGGRR) | `SystemParametersInfoW(SPI_GETHIGHCONTRAST)` | Yes — `RegNotifyChangeKeyValue` on background thread |
| **Linux** | XDG Desktop Portal `org.freedesktop.appearance` / `accent-color` — RGB tuple (0.0–1.0) | XDG Desktop Portal `org.freedesktop.appearance` / `contrast` — uint32 (1 = high) | Yes — D-Bus `SettingChanged` signal listener |

All three platforms use **JNI native libraries** bundled inside the JAR. The library is extracted and loaded at runtime automatically.

## Native Libraries

The module ships pre-built native binaries for:

- macOS: `libnucleus_systemcolor.dylib` (arm64 + x64)
- Windows: `nucleus_systemcolor.dll` (x64 + ARM64)
- Linux: `libnucleus_systemcolor.so` (x64 + aarch64)

On Linux, `libdbus-1` must be present at runtime (installed by default on all major desktop distributions).

## Linux Desktop Environment Support

The Linux implementation uses the [XDG Desktop Portal](https://flatpak.github.io/xdg-desktop-portal/) D-Bus interface, which provides a desktop-agnostic abstraction:

- **GNOME 47+** — Full accent color and contrast support
- **KDE Plasma 6+** — Accent color support
- **elementary OS** — Accent color support
- Other DEs — Works if the desktop portal implements `org.freedesktop.appearance`

If the portal is not available or the setting is not configured, `systemAccentColor()` returns `null` and `isSystemInHighContrast()` returns `false`.

## ProGuard

When ProGuard is enabled, the native bridge classes must be preserved. The Nucleus Gradle plugin includes these rules automatically, but if you need to add them manually:

```proguard
# macOS
-keep class dev.nucleusframework.nucleus.systemcolor.mac.NativeMacSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onAccentColorCleared();
    static void onContrastChanged(boolean);
}

# Windows
-keep class dev.nucleusframework.nucleus.systemcolor.windows.NativeWindowsSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(int, int, int);
    static void onHighContrastChanged(boolean);
}

# Linux
-keep class dev.nucleusframework.nucleus.systemcolor.linux.NativeLinuxSystemColorBridge {
    native <methods>;
    static void onAccentColorChanged(float, float, float);
    static void onHighContrastChanged(boolean);
}

-keep class dev.nucleusframework.nucleus.systemcolor.** { *; }
```

## Logging

Debug messages are logged under the tags `MacSystemColorDetector`, `WindowsSystemColor`, and `LinuxSystemColorDetector`. Logging is off by default. To enable it:

```kotlin
import dev.nucleusframework.nucleus.core.runtime.tools.allowNucleusRuntimeLogging

allowNucleusRuntimeLogging = true
```
