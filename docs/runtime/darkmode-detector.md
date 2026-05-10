# Dark Mode Detector

Compose for Desktop ships `isSystemInDarkTheme()` in its Foundation library, but that function only reads the theme **once** — it is not reactive. If the user toggles dark mode in the OS settings, the value will not update and the UI will stay stale until the next restart.

The `darkmode-detector` module solves this by providing a **reactive** `isSystemInDarkMode()` composable that uses native JNI bridges (no JNA) on each platform. It registers an OS-level listener that triggers recomposition the instant the system theme changes, giving your app real-time light/dark switching with no polling and no restart required.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.darkmode-detector:<version>")
}
```

## Usage

```kotlin
@Composable
fun App() {
    val isDark = isSystemInDarkMode()
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        // UI automatically recomposes when the OS theme changes
    }
}
```

`isSystemInDarkMode()` is a `@Composable` function that:

1. Reads the current system dark mode preference
2. Registers a native listener that fires when the user changes their OS theme
3. Triggers recomposition when the theme changes
4. Cleans up the listener when the composable leaves the composition

## Platform Detection Methods

| Platform | Method | Reactive |
|----------|--------|----------|
| **macOS** | `NSDistributedNotificationCenter` observer on `AppleInterfaceThemeChangedNotification`, reads `AppleInterfaceStyle` from `NSUserDefaults` | Yes — native callback via JNI |
| **Windows** | Reads `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme` registry key. Value `0` = dark, `1` = light | Yes — `RegNotifyChangeKeyValue` on background thread |
| **Linux** | XDG Desktop Portal `org.freedesktop.portal.Settings` D-Bus interface. `color-scheme = 1` means prefer-dark | Yes — listens for `SettingChanged` D-Bus signals |

All three platforms use **JNI native libraries** (Objective-C on macOS, C on Windows/Linux) bundled inside the JAR. The library is extracted and loaded at runtime automatically.

## Native Libraries

The module ships pre-built native binaries for:

- macOS: `libnucleus_darkmode.dylib` (arm64 + x64)
- Windows: `nucleus_windows_theme.dll` (x64 + ARM64)
- Linux: `libnucleus_linux_theme.so` (x64 + aarch64)

No external dependencies are needed at runtime.

## ProGuard

The `darkmode-detector` module uses JNI native libraries on all platforms. When ProGuard is enabled, the native bridge classes must be preserved so that JNI callbacks from native code work correctly. The Nucleus Gradle plugin includes these rules automatically, but if you need to add them manually:

```proguard
# macOS — NativeDarkModeBridge is looked up by name from native code
-keep class dev.nucleusframework.nucleus.darkmodedetector.mac.NativeDarkModeBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Linux — NativeLinuxBridge is looked up by name from native code
-keep class dev.nucleusframework.nucleus.darkmodedetector.linux.NativeLinuxBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Windows
-keep class dev.nucleusframework.nucleus.darkmodedetector.windows.NativeWindowsBridge {
    native <methods>;
}

-keep class dev.nucleusframework.nucleus.darkmodedetector.** { *; }
```

## Compose Preview

In Compose preview mode (`LocalInspectionMode`), the function falls back to the standard `isSystemInDarkTheme()` from Compose Foundation, which reads the JVM look-and-feel setting.

## Logging

Debug and error messages are logged under the tags `MacOSThemeDetector`, `WindowsThemeDetector`, and `LinuxPortalThemeDetector`. Logging is off by default. To enable it, set the global flag from `core-runtime`:

```kotlin
import dev.nucleusframework.nucleus.core.runtime.tools.allowNucleusRuntimeLogging

allowNucleusRuntimeLogging = true
```
