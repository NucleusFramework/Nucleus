<p align="center">
  <img src="art/header.png" alt="Nucleus" />
</p>

# Nucleus

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.kdroidfilter.nucleus?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/io.github.kdroidfilter.nucleus)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kdroidfilter/nucleus.core-runtime?label=Maven%20Central)](https://central.sonatype.com/search?q=io.github.kdroidfilter.nucleus)
[![Pre Merge Checks](https://github.com/kdroidFilter/Nucleus/actions/workflows/pre-merge.yaml/badge.svg)](https://github.com/kdroidFilter/Nucleus/actions/workflows/pre-merge.yaml)
[![License: MIT](https://img.shields.io/github/license/kdroidFilter/Nucleus)](https://github.com/kdroidFilter/Nucleus/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

**Nucleus is the Kotlin framework for native desktop apps.** Combined with Compose Multiplatform, it forms the most complete, most performant, and most deeply integrated desktop application stack ever built.

Java evolved into Kotlin. JavaScript evolved into TypeScript. Desktop development is going through the same shift: Electron was the pioneer. **Kotlin + Compose + Nucleus** is what comes next.

---

## Why Nucleus

**Native on every OS** — Your app doesn't emulate native — it *is* native. Window decorations, notifications, taskbar badges, dock menus, system tray, dark mode, accent colors, global hotkeys — everything behaves exactly as users expect on their OS. And Nucleus makes it **simpler than the native APIs themselves** — Windows Toast, macOS UserNotifications, Linux D-Bus SNI, Win32 ITaskbarList3, Unity LauncherEntry — all behind clean, intuitive Kotlin APIs. Cross-platform that's **easier to use than native development itself** — without compromise.

**Performance that rivals C++** — The HotSpot JVM is the most advanced JIT compiler ever built, delivering performance approaching C++ and Rust levels — with the simplicity of Kotlin. True parallelism with coroutines and virtual threads, not a single-threaded event loop.

**Maximum lightness** — GraalVM native image compiles your entire app into a standalone binary. ~0.5s cold start, 100–150 MB RAM, tiny bundle. Compare that to 500 MB–1.5 GB for a typical Electron app.

**The most advanced desktop UI** — Compose Multiplatform with Skia GPU rendering, reactive state, and shared code across platforms. No frontend/backend split — your UI calls your logic directly. On top sits [Jewel](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel#readme), the desktop UI framework behind JetBrains IDEs.

---

## What Nucleus provides

### Ship everywhere

- **16 packaging formats** — DMG, PKG, NSIS, MSI, AppX, Portable, DEB, RPM, AppImage, Snap, Flatpak, ZIP, TAR, 7Z
- **Store-ready** — Mac App Store, Microsoft Store, Snapcraft, Flathub
- **Code signing & notarization** — Windows and macOS, built into the pipeline
- **Auto-update** — Check, download, verify, install — all built-in
- **Deep links & file associations** — Protocol handlers and file type registration

### Feel native

- **Decorated windows** — Custom title bar with native window controls on every OS
- **Notifications** — Native APIs on macOS, Windows, and Linux
- **Launchers** — Badges, progress bars, jump lists, dock menus, quicklists
- **Media controls** — OS media overlay integration (MPRIS, Now Playing, SMTC) with play/pause/seek events
- **System integration** — Dark mode, accent colors, high contrast, energy management, global hotkeys, taskbar progress, system info

### Perform

- **AOT Cache** — Near-instant cold startup with a single Gradle flag
- **GraalVM Native Image** — Standalone binary with automatic metadata resolution — zero manual config for most apps
- **ProGuard** — Built-in release builds with optimization and obfuscation

### Go deeper

- **[Native Access](https://nucleusframework.dev/native-access/)** — Write Kotlin/Native, call it from the JVM. No C, no boilerplate.
- **30+ runtime modules** — Intuitive Kotlin APIs for every OS integration
- **CI/CD ready** — Reusable GitHub Actions, 6-runner matrix builds, universal macOS binaries, MSIX bundles

---

## Quick Start

```kotlin
plugins {
    id("io.github.kdroidfilter.nucleus") version "<version>"
}

nucleus.application {
    mainClass = "com.example.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "MyApp"
        packageVersion = "1.0.0"
    }
}
```

```bash
./gradlew run                              # Run locally
./gradlew packageDistributionForCurrentOS  # Build installer for your OS
```

---

## Runtime Modules

Each module is published independently to Maven Central — use them together or standalone.

| Module | Description |
|--------|-------------|
| `nucleus.core-runtime` | Platform detection, single instance, deep links, executable type |
| `nucleus.aot-runtime` | AOT cache mode detection |
| `nucleus.updater-runtime` | Auto-update engine with GitHub/S3, progress tracking, SHA-512 |
| `nucleus.darkmode-detector` | Reactive OS dark mode detection |
| `nucleus.system-color` | Reactive accent color & high contrast detection |
| `nucleus.system-info` | CPU, memory, GPU (NVIDIA/AMD/Intel), temperature, network, processes |
| `nucleus.decorated-window` | Custom title bar with native controls |
| `nucleus.decorated-window-jewel` | Jewel (IntelliJ theme) integration |
| `nucleus.decorated-window-material2` | Material 2 integration |
| `nucleus.decorated-window-material3` | Material 3 integration |
| `nucleus.notification-macos` | macOS User Notifications |
| `nucleus.notification-windows` | Windows Toast Notifications |
| `nucleus.notification-linux` | Freedesktop Desktop Notifications |
| `nucleus.launcher-macos` | macOS Dock API — badge, menus |
| `nucleus.launcher-windows` | Windows taskbar — badges, jump lists, overlay icons, thumbnail toolbar |
| `nucleus.launcher-linux` | Unity Launcher — badge, progress, urgency, quicklist |
| `nucleus.clipboard-common` | Cross-platform clipboard — text, HTML, RTF, images, files + change watcher |
| `nucleus.clipboard-macos` | macOS `NSPasteboard` backend for the clipboard API |
| `nucleus.media-control` | OS media controls — MPRIS (Linux), Now Playing (macOS), SMTC (Windows) |
| `nucleus.menu-macos` | Native macOS menu bar |
| `nucleus.freedesktop-icons` | Type-safe freedesktop icon naming constants |
| `nucleus.taskbar-progress` | Cross-platform taskbar progress bar & attention requests |
| `nucleus.global-hotkey` | System-wide keyboard shortcuts |
| `nucleus.energy-manager` | Energy efficiency & screen-awake APIs |
| `nucleus.autolaunch` | Start the app at user login — MSIX `StartupTask`, Win32 `HKCU\...\Run`, `SMAppService`, systemd user units, Flatpak portal |
| `nucleus.native-ssl` | OS trust store integration |
| `nucleus.native-http` | HTTP client with native SSL |
| `nucleus.linux-hidpi` | Native HiDPI scale detection on Linux |
| `nucleus.graalvm-runtime` | Native-image bootstrap, font fixes, automatic resource inclusion |

---

## Roadmap

Modules planned for upcoming releases. Contributions welcome.

| Module | Description | macOS | Windows | Linux |
|--------|-------------|-------|---------|-------|
| `secure-storage` | Hardware-backed secret storage for tokens, passwords, keys | Keychain | Credential Manager / DPAPI | Secret Service (`libsecret`) |
| `biometric-auth` | Prompt for fingerprint / face authentication | `LocalAuthentication` (Touch ID / Face ID) | Windows Hello | `fprintd` via D-Bus / polkit |
| `share-sheet` | OS share sheet (URL, file, text) | `NSSharingService` | Windows `DataTransferManager` | xdg-desktop-portal `Share` |
| `power-events` | Sleep / wake / lock / unlock / screen-off / battery state events | `NSWorkspace` notifications | `WM_POWERBROADCAST` / `WTSRegisterSessionNotification` | `org.freedesktop.login1` D-Bus signals |
| `fs-watcher` | Native filesystem watcher (replaces slow `WatchService`) | `FSEvents` | `ReadDirectoryChangesW` | `inotify` |
| `clipboard` | Rich clipboard — image, files, HTML, RTF — plus change watcher | `NSPasteboard` | `OleGetClipboard` / Clipboard History API | `wl-clipboard` / X11 selections |
| `screen-capture` | Native screenshot / screen recording | `CGDisplayCreateImage` / ScreenCaptureKit | Windows Graphics Capture / DXGI | xdg-desktop-portal `Screenshot` |

---

## Requirements

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | JBR 25 recommended |
| Gradle | 8.0+ | |
| Kotlin | 2.0+ | |

## Documentation

Full documentation at **[nucleusframework.dev](https://nucleusframework.dev/)**.

## License

MIT — See [LICENSE](LICENSE).
