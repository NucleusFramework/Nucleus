# Runtime APIs

Nucleus provides runtime libraries for use in your application code. All are published on Maven Central.

## Libraries

| Library | Artifact | Description |
|---------|----------|-------------|
| Core Runtime | `dev.nucleusframework:nucleus.core-runtime` | Executable type detection, single instance, deep links, app metadata (`NucleusApp`) |
| AOT Runtime | `dev.nucleusframework:nucleus.aot-runtime` | AOT cache detection (includes core-runtime via `api`) |
| Updater Runtime | `dev.nucleusframework:nucleus.updater-runtime` | Auto-update library with update level detection and post-update events (includes core-runtime) |
| Taskbar Progress | `dev.nucleusframework:nucleus.taskbar-progress` | Native taskbar/dock progress bar and attention requests (Windows, macOS, Linux) |
| Notification (macOS) | `dev.nucleusframework:nucleus.notification-macos` | macOS UserNotifications API — local notifications, actions, badges via JNI |
| Notification (Windows) | `dev.nucleusframework:nucleus.notification-windows` | Windows Toast Notifications API — rich toasts, buttons, progress bars via JNI (WinRT) |
| Launcher (Windows) | `dev.nucleusframework:nucleus.launcher-windows` | Windows Launcher API — badge notifications, jump lists, overlay icons, and thumbnail toolbar (ITaskbarList3) via JNI |
| Notification (Linux) | `dev.nucleusframework:nucleus.notification-linux` | Freedesktop Desktop Notifications API via JNI (D-Bus) |
| Launcher (Linux) | `dev.nucleusframework:nucleus.launcher-linux` | Unity Launcher API — badge, progress, urgency, quicklist via JNI (D-Bus) |
| Launcher (macOS) | `dev.nucleusframework:nucleus.launcher-macos` | macOS dock context menu — custom items, submenus, click callbacks via JNI |
| Media Control | `dev.nucleusframework:nucleus.media-control` | OS media controls (play/pause, metadata, seek) — MPRIS D-Bus on Linux, MPNowPlayingInfoCenter on macOS, SystemMediaTransportControls on Windows via JNI |
| Menu (macOS) | `dev.nucleusframework:nucleus.menu-macos` | Complete NSMenu mapping — application menu bar, items, badges, delegates, SF Symbols via JNI |
| SF Symbols | `dev.nucleusframework:nucleus.sf-symbols` | Type-safe Apple SF Symbols constants (6 195 symbols, 21 categories) |
| Freedesktop Icons | `dev.nucleusframework:nucleus.freedesktop-icons` | Type-safe freedesktop Icon Naming Specification constants |
| Decorated Window | `dev.nucleusframework:nucleus.decorated-window` | Custom window decorations with native title bar |
| Decorated Window — Jewel | `dev.nucleusframework:nucleus.decorated-window-jewel` | Jewel (IntelliJ theme) color mapping for decorated windows |
| Decorated Window — Material 2 | `dev.nucleusframework:nucleus.decorated-window-material2` | Material 2 color mapping for decorated windows |
| Decorated Window — Material 3 | `dev.nucleusframework:nucleus.decorated-window-material3` | Material 3 color mapping for decorated windows |
| Dark Mode Detector | `dev.nucleusframework:nucleus.darkmode-detector` | Reactive OS dark mode detection via JNI |
| System Color | `dev.nucleusframework:nucleus.system-color` | Reactive system accent color and high contrast detection via JNI |
| Energy Manager | `dev.nucleusframework:nucleus.energy-manager` | Process-level and thread-level energy efficiency mode and screen-awake (caffeine) API for Windows, macOS, and Linux |
| Native SSL | `dev.nucleusframework:nucleus.native-ssl` | OS trust store integration — merges native certs with JVM defaults |
| Native HTTP | `dev.nucleusframework:nucleus.native-http` | `java.net.http.HttpClient` pre-configured with native OS trust |
| Native HTTP — OkHttp | `dev.nucleusframework:nucleus.native-http-okhttp` | OkHttp client pre-configured with native OS trust |
| Native HTTP — Ktor | `dev.nucleusframework:nucleus.native-http-ktor` | Ktor `HttpClient` extension for native OS trust (all engines) |
| Linux HiDPI | `dev.nucleusframework:nucleus.linux-hidpi` | Native HiDPI scale factor detection on Linux |
| Scheduler | `dev.nucleusframework:nucleus.scheduler` | Background task scheduling — periodic, calendar, and on-boot tasks via OS-native schedulers (launchd, systemd, Task Scheduler) |
| Service Management (macOS) | `dev.nucleusframework:nucleus.service-management-macos` | macOS SMAppService binding — login items, launch agents, launch daemons (macOS 13+) |
| Auto-Launch | `dev.nucleusframework:nucleus.autolaunch` | Cross-platform start-at-login — MSIX `StartupTask`, Win32 `HKCU\...\Run`, `SMAppService`, systemd user units, Flatpak portal |
| GraalVM Runtime | `dev.nucleusframework:nucleus.graalvm-runtime` | GraalVM native-image bootstrap + font substitutions (includes linux-hidpi) |

```kotlin
dependencies {
    // Pick what you need:
    implementation("dev.nucleusframework:nucleus.core-runtime:<version>")
    implementation("dev.nucleusframework:nucleus.aot-runtime:<version>")
    implementation("dev.nucleusframework:nucleus.updater-runtime:<version>")
    implementation("dev.nucleusframework:nucleus.taskbar-progress:<version>")
    implementation("dev.nucleusframework:nucleus.notification-macos:<version>")
    implementation("dev.nucleusframework:nucleus.notification-windows:<version>")
    implementation("dev.nucleusframework:nucleus.launcher-windows:<version>")
    implementation("dev.nucleusframework:nucleus.notification-linux:<version>")
    implementation("dev.nucleusframework:nucleus.launcher-linux:<version>")
    implementation("dev.nucleusframework:nucleus.launcher-macos:<version>")
    implementation("dev.nucleusframework:nucleus.media-control:<version>")
    implementation("dev.nucleusframework:nucleus.menu-macos:<version>")
    implementation("dev.nucleusframework:nucleus.sf-symbols:<version>")
    implementation("dev.nucleusframework:nucleus.freedesktop-icons:<version>")
    implementation("dev.nucleusframework:nucleus.decorated-window:<version>")
    implementation("dev.nucleusframework:nucleus.decorated-window-jewel:<version>")
    implementation("dev.nucleusframework:nucleus.decorated-window-material2:<version>")
    implementation("dev.nucleusframework:nucleus.decorated-window-material3:<version>")
    implementation("dev.nucleusframework:nucleus.darkmode-detector:<version>")
    implementation("dev.nucleusframework:nucleus.system-color:<version>")
    implementation("dev.nucleusframework:nucleus.energy-manager:<version>")
    implementation("dev.nucleusframework:nucleus.native-ssl:<version>")
    implementation("dev.nucleusframework:nucleus.native-http:<version>")
    implementation("dev.nucleusframework:nucleus.native-http-okhttp:<version>")
    implementation("dev.nucleusframework:nucleus.native-http-ktor:<version>")
    implementation("dev.nucleusframework:nucleus.linux-hidpi:<version>")
    implementation("dev.nucleusframework:nucleus.scheduler:<version>")
    implementation("dev.nucleusframework:nucleus.service-management-macos:<version>")
    implementation("dev.nucleusframework:nucleus.autolaunch:<version>")
    implementation("dev.nucleusframework:nucleus.graalvm-runtime:<version>")
}
```

## ProGuard

When ProGuard is enabled in a release build, the Nucleus Gradle plugin **automatically includes** the required rules for all Nucleus runtime libraries (`default-compose-desktop-rules.pro`). No manual configuration is needed.

Libraries that use JNI (`decorated-window`, `darkmode-detector`, `system-color`, `energy-manager`, `native-ssl`, `notification-macos`, `notification-windows`, `notification-linux`, `launcher-windows`, `launcher-linux`) require `-keep` rules for their native bridge classes — these are handled by the plugin automatically.

### Overriding the ProGuard configuration

> **Warning:** `configurationFiles.from(...)` **replaces** the plugin's auto-injected rules entirely — it does not append to them. If you supply your own configuration file, the Nucleus JNI keep rules will no longer be applied automatically and you must copy them into your file manually.

```kotlin
nucleus.application {
    buildTypes {
        release {
            proguard {
                isEnabled = true
                // ⚠ This replaces the auto-injected rules — see below for required manual rules.
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }
    }
}
```

When using a custom `configurationFiles`, add the following rules to your file to preserve all Nucleus JNI bridges:

```proguard
# Nucleus decorated-window JNI (macOS)
-keep class dev.nucleusframework.nucleus.window.utils.macos.NativeMacBridge {
    native <methods>;
}
-keep class dev.nucleusframework.nucleus.window.** { *; }

# Nucleus darkmode-detector JNI (macOS)
-keep class dev.nucleusframework.nucleus.darkmodedetector.mac.NativeDarkModeBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Nucleus darkmode-detector JNI (Linux)
-keep class dev.nucleusframework.nucleus.darkmodedetector.linux.NativeLinuxBridge {
    native <methods>;
    static void onThemeChanged(boolean);
}

# Nucleus darkmode-detector JNI (Windows)
-keep class dev.nucleusframework.nucleus.darkmodedetector.windows.NativeWindowsBridge {
    native <methods>;
}
-keep class dev.nucleusframework.nucleus.darkmodedetector.** { *; }

# Nucleus native-ssl JNI (macOS)
-keep class dev.nucleusframework.nucleus.nativessl.mac.NativeSslBridge {
    native <methods>;
}

# Nucleus native-ssl JNI (Windows)
-keep class dev.nucleusframework.nucleus.nativessl.windows.WindowsSslBridge {
    native <methods>;
}
```

Omitting these rules will cause `UnsatisfiedLinkError` or `ClassNotFoundException` at runtime in release builds.
