<p align="center">
  <img src="art/header.png" alt="Nucleus" />
</p>

[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/dev.nucleusframework?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/dev.nucleusframework)
[![Maven Central](https://img.shields.io/maven-central/v/dev.nucleusframework/nucleus.core-runtime?label=Maven%20Central)](https://central.sonatype.com/search?q=dev.nucleusframework)
[![Pre Merge Checks](https://github.com/NucleusFramework/Nucleus/actions/workflows/pre-merge.yaml/badge.svg)](https://github.com/NucleusFramework/Nucleus/actions/workflows/pre-merge.yaml)
[![License: MIT](https://img.shields.io/github/license/NucleusFramework/Nucleus)](https://github.com/NucleusFramework/Nucleus/blob/main/LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4%2B-7F52FF?logo=kotlin&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Linux-blue)

The Nucleus framework lets you write cross-platform desktop applications using
Kotlin. It is based on [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)
and adds native window decorations, deep operating-system integration, code signing,
and native installers — all configured through a single Gradle DSL. Your app runs
either as a [GraalVM Native Image](https://www.graalvm.org/) or on the JVM.

It targets the gap between "a Compose Desktop window" and "an application the operating
system treats as its own" — the work that is normally spread across a dozen half-maintained
libraries and a hand-written packaging pipeline. Every OS integration is a real platform
API behind a Kotlin one: no AWT dependency on the Tao backend, and the accessibility layer
is verified against AT-SPI, UI Automation, and macOS AX in CI on all three platforms.

Read [Why Nucleus](https://nucleusframework.dev/en/docs/why-nucleus) for how it compares to
vanilla Compose Desktop, Electron, and Tauri.

## Project status

Published releases are `2.5.x` (latest tag `v2.5.0`). Nucleus is under active development
and moves fast. All published runtime modules run in Kotlin `explicitApi()` mode with
their public surface locked by a binary-compatibility dump (`api/*.api`, checked by
`apiCheck` via kotlinx binary-compatibility-validator). Breaking changes to a public FQN
or signature fail CI. The one exception is `decorated-window-jewel` (JVM 25 bytecode),
which still uses `explicitApi()` but is not dumped until BCV can read class-file major
version 69.

Windowing runs on a single backend: the no-AWT Tao one. The legacy AWT-based backends
(`decorated-window-jni`, `decorated-window-jbr`, and the shared `decorated-window-awt`
chrome) are removed in 2.6. To migrate: depend on `nucleus.decorated-window-tao`, drop the
`backend = NucleusBackend.…` argument (`NucleusBackend` and `LocalNucleusBackend` are gone),
and replace AWT-typed window access (`window.unsafe.awtWindow`, Compose Desktop's `Window` /
`Dialog` / `Tray`) with `nucleusWindow`, `HostedWindow` / `HostedDialog`, and an AWT-free
tray.

## Used by

- [AB Download Manager](https://github.com/amir1376/ab-download-manager) — packaging plugin
- [Hammer](https://github.com/Darkrock-Studios/hammer-editor)
- [OtakuWorld](https://github.com/jakepurple13/OtakuWorld)
- [Husi](https://github.com/xchacha20-poly1305/husi)
- [Zayit](https://github.com/kdroidFilter/Zayit)
- [GitVantage](https://github.com/rocketraman/gitvantage)

## Showcase

- [EdgeTranslator](https://github.com/NucleusFramework/EdgeTranslator) — offline AI translator

## Installation

Nucleus ships as a Gradle plugin. Apply it alongside the Kotlin and Compose plugins in
your module's `build.gradle.kts`:

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
    id("dev.nucleusframework") version "2.5.0"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    // Entry point — provides nucleusApplication and DecoratedWindow
    implementation("dev.nucleusframework:nucleus.nucleus-application:2.5.0")
    // Tao backend — Rust-native windowing
    implementation("dev.nucleusframework:nucleus.decorated-window-tao:2.5.0")
}
```

For more installation options, see the
[install guide](https://nucleusframework.dev/en/docs/start/install) and
[project setup](https://nucleusframework.dev/en/docs/start/project-setup).

## Requirements

Nucleus builds on Compose Multiplatform and requires:

| Requirement | Version | Note |
|-------------|---------|------|
| JDK | 17+ (25+ for AOT cache) | Any vendor — no JetBrains Runtime needed |
| Kotlin | 2.4.10+ | This repo builds with Kotlin 2.4.10 |
| Compose Multiplatform | 1.12.0 | Required by the 2.5 line; will not run on 1.11.x |
| Gradle | 9.0+ | Bundled wrapper is Gradle 9.4.0 |

## Platform support

Each build can compile, run, and package for macOS, Windows, and Linux from a single
codebase.

* **macOS** — Intel (`x64`) and Apple Silicon (`arm64`), shipped as a universal binary. Liquid Glass on macOS 26.
* **Windows** — `x64` and `arm64`.
* **Linux** — `x64` and `arm64`, with Wayland and X11 support.

## Getting started

Create `src/main/kotlin/com/example/Main.kt`:

```kotlin
package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication

fun main(args: Array<String>) = nucleusApplication(args) {
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        title = "MyApp",
    ) {
        Box(Modifier.fillMaxSize()) {
            Text("Hello from Nucleus")
        }
    }
}
```

`nucleusApplication` initializes GraalVM native-image support, takes the
single-instance lock, and primes autolaunch / Windows AUMID when those
modules are on the classpath. Pass the process `args` so deep links,
file associations, and "started at login" see the original command line.
Windows are Tao-backed: the native event loop owns the main thread and doubles
as `Dispatchers.Main`, with no AWT in the process. Inside the block you can call
`onDeepLink { }` and `aotTraining()`; plugin-injected metadata is `NucleusApp`,
not a generated constants object.

Then configure packaging in `build.gradle.kts`:

```kotlin
nucleus.application {
    mainClass = "com.example.MainKt"

    nativeDistributions {
        packageName = "MyApp"
        packageVersion = "1.0.0"
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
    }
}
```

```shell
./gradlew run                              # Run locally in a native Tao window
./gradlew packageDistributionForCurrentOS  # Build an installer for your OS
```

The full [quickstart](https://nucleusframework.dev/en/docs/start/quickstart) walks through
each step.

## Resources for learning Nucleus

* [nucleusframework.dev/en/docs](https://nucleusframework.dev/en/docs) — all of Nucleus's documentation
* [Quickstart](https://nucleusframework.dev/en/docs/start/quickstart) — build and package your first app
* [Architecture](https://nucleusframework.dev/en/docs/concepts/architecture) — the layered model behind the framework
* [examples/](examples/) — demo and sample applications, including the flagship `nucleus-demo`

## What Nucleus provides

**Ship everywhere** — 18 packaging formats (DMG, PKG, EXE, MSI, NSIS, NSIS-Web, Portable,
AppX, DEB, RPM, Pacman, AppImage, raw AppImage, Snap, Flatpak, ZIP, TAR, 7Z), store
publishing (Mac App Store, Microsoft Store, Snapcraft, Flathub), code signing and
notarization, built-in auto-update, deep links, and file associations.

**Feel native** — Decorated windows with native controls, notifications, taskbar/dock
badges and menus, media controls (MPRIS, Now Playing, SMTC), dark mode, accent colors,
global hotkeys, system tray, native context menus, and OS spell check — all behind
clean Kotlin APIs.

**Perform** — GraalVM Native Image compiles your app to a standalone binary with automatic
reachability metadata; a Hello-World Compose window cold-starts in about 0.2 s and around
30 MB of RAM (measured on Windows 11). Or stay on the JVM with an AOT cache (JDK 25+) and
ProGuard-optimized release builds.

## Runtime modules

Each module is published independently to Maven Central — use them together or standalone.

| Module | Description |
|--------|-------------|
| `nucleus.nucleus-application` | `nucleusApplication`, `DecoratedWindow` / `HostedWindow` |
| `nucleus.core-runtime` | Platform detection, single instance, deep links, `NucleusApp` metadata |
| `nucleus.aot-runtime` | AOT cache mode detection |
| `nucleus.updater-runtime` | Auto-update (GitHub/S3), SHA-512, delta/blockmap, progress |
| `nucleus.darkmode-detector` | Reactive OS dark mode detection |
| `nucleus.system-color` | Reactive accent color & high contrast detection |
| `nucleus.system-info` | CPU, memory, GPU (NVIDIA/AMD/Intel), temperature, network, processes |
| `nucleus.decorated-window-tao` | Windowing backend (Rust `tao`, no AWT) |
| `nucleus.decorated-window-core` | Shared window types, layout, chrome (design-system agnostic) |
| `nucleus.decorated-window-jewel` | Jewel (IntelliJ theme) integration |
| `nucleus.decorated-window-material2` | Material 2 integration |
| `nucleus.decorated-window-material3` | Material 3 integration |
| `nucleus.notification-common` | Cross-platform notification DSL with per-platform option blocks |
| `nucleus.notification-macos` | macOS User Notifications |
| `nucleus.notification-windows` | Windows Toast Notifications |
| `nucleus.notification-linux` | Freedesktop Desktop Notifications |
| `nucleus.launcher-macos` | macOS Dock API — badge, menus |
| `nucleus.launcher-windows` | Windows taskbar — badges, jump lists, overlay icons, thumbnail toolbar |
| `nucleus.launcher-linux` | Unity Launcher — badge, progress, urgency, quicklist |
| `nucleus.media-control` | OS media controls — MPRIS (Linux), Now Playing (macOS), SMTC (Windows) |
| `nucleus.menu-macos` | Native macOS menu bar |
| `nucleus.spellcheck` | OS spell check (Hunspell / NSSpellChecker / Windows Spell Checking) |
| `nucleus.freedesktop-icons` | Type-safe freedesktop icon naming constants |
| `nucleus.sf-symbols` | Type-safe SF Symbols catalog |
| `nucleus.taskbar-progress` | Cross-platform taskbar progress bar & attention requests |
| `nucleus.taskbar-progress-tao` | Taskbar progress on the Tao backend |
| `nucleus.global-hotkey` | System-wide keyboard shortcuts |
| `nucleus.energy-manager` | Energy efficiency & screen-awake APIs |
| `nucleus.autolaunch` | Start the app at user login across all platforms |
| `nucleus.scheduler` | OS-scheduled background tasks (Task Scheduler / launchd / systemd) |
| `nucleus.scheduler-testing` | Test doubles for `scheduler` |
| `nucleus.fs-watcher` | Native filesystem watcher |
| `nucleus.service-management-macos` | macOS `SMAppService` — login items, launch agents, daemons |
| `nucleus.native-ssl` | OS trust store integration |
| `nucleus.native-http` | HTTP client with native SSL |
| `nucleus.native-http-okhttp` | OkHttp engine on `native-http` |
| `nucleus.native-http-ktor` | Ktor engine on `native-http` |
| `nucleus.linux-hidpi` | Native HiDPI scale detection on Linux |
| `nucleus.graalvm-runtime` | Native-image bootstrap, font fixes, automatic resource inclusion |

## Documentation

Full documentation is available at
**[nucleusframework.dev](https://nucleusframework.dev/)** (English and French).

## Community

Ask questions, report bugs, and share what you're building on
[GitHub Discussions](https://github.com/NucleusFramework/Nucleus/discussions), the
[issue tracker](https://github.com/NucleusFramework/Nucleus/issues), and
[#nucleus](https://kotlinlang.slack.com/archives/C0BQYKHBGR0) on the Kotlin Slack.

## License

[MIT](LICENSE)
