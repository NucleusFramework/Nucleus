# Nucleus

<p align="center">
  <img src="assets/header.png" alt="Nucleus" />
</p>

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/dev.nucleusframework.nucleus?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/dev.nucleusframework.nucleus)
[![Maven Central](https://img.shields.io/maven-central/v/dev.nucleusframework/nucleus.core-runtime?label=Maven%20Central)](https://central.sonatype.com/search?q=dev.nucleusframework.nucleus)
[![Pre Merge Checks](https://github.com/nucleusframework/Nucleus/actions/workflows/pre-merge.yaml/badge.svg)](https://github.com/nucleusframework/Nucleus/actions/workflows/pre-merge.yaml)
[![License: MIT](https://img.shields.io/github/license/nucleusframework/Nucleus)](https://github.com/nucleusframework/Nucleus/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

**Nucleus is the Kotlin framework for native desktop apps.** Combined with Compose Multiplatform, it forms the most complete, most performant, and most deeply integrated desktop application stack ever built — on any language, any runtime, any platform.

Every technology eventually finds its mature form. Java evolved into **Kotlin**. JavaScript evolved into **TypeScript**. Desktop development is going through the same shift: Electron was the pioneer — it proved that cross-platform desktop apps could work. **Kotlin + Compose + Nucleus** is what comes next.

Not an alternative. An evolution.

Electron gave developers reach but asked them to accept a browser as a runtime, a DOM as a UI layer, and hundreds of megabytes as a baseline. Nucleus builds on **Kotlin** and **Compose Multiplatform** — running on the battle-tested JVM — to deliver desktop applications that are natively integrated, natively fast, and natively lightweight.

## Why Nucleus

### Native on every OS

Your app doesn't emulate native — it *is* native. Window decorations, notifications, taskbar badges, dock menus, dark mode, accent colors, global hotkeys, [system tray](runtime/system-tray/index.md) — everything behaves exactly as users expect on their OS. Not a web view wearing a disguise. A real desktop citizen, on every platform, on every screen.

And Nucleus doesn't just expose native APIs — it **makes them simpler than the originals**. Windows Toast Notifications, macOS UserNotifications, Linux D-Bus StatusNotifierItem, Win32 ITaskbarList3, Unity LauncherEntry — each of these is a complex, platform-specific API with its own conventions, threading model, and pitfalls. Nucleus wraps every single one behind a clean, intuitive Kotlin API that feels the same everywhere. The result is paradoxical: a **cross-platform framework that makes native APIs easier to use than native development itself**. Writing a notification, managing a system tray, or showing taskbar progress takes a few lines of Kotlin — not pages of platform documentation. No compromise on capability. No lowest-common-denominator abstraction. Every platform feature, exposed in full, but through an API that any Kotlin developer can pick up in minutes.

### Performance that rivals C++ — with the simplicity of Kotlin

The HotSpot JVM is the most advanced JIT compiler ever built. It optimizes your hot paths at runtime with decades of engineering behind it — delivering performance approaching C++ and Rust levels, but with the expressiveness of Kotlin. And unlike Electron's single-threaded event loop, the JVM gives you **true parallelism**: real threads, real cores, coroutines for structured concurrency, virtual threads for massive I/O.

For maximum lightness, [GraalVM native image](graalvm/index.md) compiles your entire app into a standalone binary — **~0.5s cold start**, **100–150 MB RAM**, tiny bundle, no JRE needed. Compare that to **500 MB–1.5 GB** for a typical Electron app.

### The most advanced desktop UI stack

Compose Multiplatform is not "React Native for desktop". It is a **compiled, type-safe, GPU-accelerated UI toolkit** with hardware-accelerated Skia rendering, a reactive state model, and shared code across Android, iOS, desktop, and web.

No frontend/backend split. No REST API between your UI and your logic. No serialization layer. Your UI calls your business logic directly, in the same language, in the same process. Separate your concerns with **modules**, not with network boundaries.

And on top of Compose sits **[Jewel](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel#readme)** — the most advanced desktop UI framework in the world. Not a web framework adapted for desktop. A desktop framework, period. Jewel carries behind it the entire experience of JetBrains and its IDEs — IntelliJ IDEA, Android Studio, WebStorm — applications used daily by millions of developers, built with desktop in mind from day one. Nucleus integrates deeply with both Jewel and Material 2/3, plus native window controls and OS-level hooks.

## What Nucleus provides

### Ship everywhere

- **16 packaging formats** — DMG, PKG, NSIS, MSI, AppX, Portable, DEB, RPM, AppImage, Snap, Flatpak, ZIP, TAR, 7Z
- **Store-ready** — Mac App Store, Microsoft Store, Snapcraft, Flathub — one build, all stores
- **Code signing & notarization** — Windows and macOS, built into the build pipeline
- **Auto-update** — Your app checks for updates, downloads them, verifies integrity, and installs — all built-in
- **Deep links & file associations** — Protocol handlers and file type registration on all platforms

### Go deeper when you need to

Nucleus meets you where you are:

- **Just ship an app?** — One Gradle DSL, one command. Done.
- **Need OS integration?** — 30+ runtime modules with intuitive Kotlin APIs: notifications, launchers, dark mode, system colors, taskbar progress, energy management, and more.
- **Need a platform API no library covers?** — [Native Access](native-access/index.md) lets you write Kotlin/Native and call it from the JVM. No C, no boilerplate, no build scripts.
- **Need maximum lightness?** — [GraalVM native image](graalvm/index.md) compiles your app into a standalone binary. Nucleus resolves all reflection metadata transparently.
- **CI/CD ready** — Reusable GitHub Actions, multi-platform matrix builds, universal macOS binaries, MSIX bundles.

## Quick start

```kotlin
plugins {
    id("dev.nucleusframework.nucleus") version "<version>"
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
./gradlew run                           # Run locally
./gradlew packageDistributionForCurrentOS  # Build installer for current OS
```

## Try the demo

A pre-built demo is available on the [GitHub Releases page](https://github.com/nucleusframework/Nucleus/releases).

=== "macOS"
    ```bash
    curl -fsSL https://nucleusframework.dev/install.sh | bash
    ```
    Detects your architecture (Apple Silicon or Intel), downloads, installs to `/Applications`, and launches.

=== "Linux"
    ```bash
    curl -fsSL https://nucleusframework.dev/install-linux.sh | bash
    ```
    Detects your architecture and package manager, downloads and installs the appropriate `.deb` or `.rpm`.

=== "Windows"
    Download the installer from the [releases page](https://github.com/nucleusframework/Nucleus/releases).

What you'll see:

- **Instant startup** — Near-instant cold boot powered by JDK 25+ AOT cache
- **Decorated Window** — Custom title bar with native window controls, Material 3 themed
- **Dark Mode Detection** — Toggle your OS theme and watch the app switch in real time
- **Auto-Update** — Checks for updates on launch, downloads with progress tracking, installs & restarts in one click

!!! tip "Test auto-update"
    Download an **older release**, install it, and launch. It will detect the newer version and offer to update — automatically.

The demo source code is in the [`example/`](https://github.com/nucleusframework/Nucleus/tree/main/example) directory.

## Requirements

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | JBR 25 recommended |
| Gradle | 8.0+ | |
| Kotlin | 2.0+ | |

## License

MIT — See [LICENSE](https://github.com/nucleusframework/Nucleus/blob/main/LICENSE).
