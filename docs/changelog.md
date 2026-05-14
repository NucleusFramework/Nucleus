# Changelog

## v1.11.0

**Released: 2026-04-13**

### New Modules

- **Notification Common** (`nucleus.notification-common`) — Unified cross-platform notification API for Linux, Windows, and macOS behind a single DSL. Supports per-notification callbacks, up to 5 action buttons, image attachments, and dismiss handling.

### New Features

- **Reactive GNOME titlebar button layout** — Decorated windows on Linux now read `org.gnome.desktop.wm.preferences` → `button-layout` via GSettings (`libgio` dlopen) to determine which buttons to show and on which side. The layout updates reactively when the user changes it in GNOME Tweaks or via `gsettings set`. Falls back to the default layout on KDE and other desktop environments. New `rememberLinuxButtonLayout()` composable for direct access.
- **Title bar controls-side plumbing (core/JBR/JNI)** — Add non-breaking `WindowControlsSide` + `LocalWindowControlsSide` infrastructure to share platform-resolved window controls side with core layout. This stage is infrastructure only and does not change current title bar layout behavior.
- **GraalVM reachability metadata for FileKit and dbus-java** — Apps using FileKit on Linux no longer need manual reachability entries for xdg-desktop-portal file dialogs. Includes new dbus-java conditional library metadata and Linux JDK internals (`UnixSystem`, `NativePRNG$NonBlocking`, `CollationData`).
- **Windows notification shortcut policies** — New `ShortcutPolicy` enum on `WindowsNotificationCenter` for finer control over Start Menu shortcut creation behavior.
- **`NucleusApp.appName` and `NucleusApp.aumid` properties** — Expose application name and AUMID for better configuration handling.

### Bug Fixes

- **Fix macOS multicolor accent color** — `system-color` now returns `null` when macOS is set to multicolor mode instead of incorrectly returning the default blue.
- **Fix incremental build issues in GraalVM tasks** — Disable state tracking on shared output directory modifications to prevent stale builds.
- **Fix nullable safety in JVM application tasks** — Simplify runtime classpath and `JavaExec` argument handling by removing unnecessary optional chaining.

### Improvements

- **Faster native library loading** — `NativeLibraryLoader` now uses CRC-32-based fingerprints for cache validation, eliminating unnecessary I/O during checks.

### Documentation

- **GraalVM status updated to alpha** — GraalVM Native Image support is now labeled "alpha" instead of "experimental", reflecting the improved out-of-the-box experience with centralized reachability metadata.
- **New System Tray documentation** — Full documentation section for ComposeNativeTray with screenshots and demo GIFs.
- **Landing page rewrite** — Repositioned Nucleus as a native Electron successor with bold performance comparisons.

---

## v1.10.0

**Released: 2026-04-12**

### New Modules

- **System Info** (`nucleus.system-info`) — Cross-platform system information module with JNI native implementations for Linux, Windows, and macOS. Exposes CPU, memory, and GPU metrics in real-time.
    - **GPU detection and live metrics** — Temperature, usage, VRAM, clock speeds, power draw, and fan speed on all platforms
    - **macOS**: IOKit & SMC for GPU metrics, supports Apple Silicon and discrete GPUs
    - **Windows**: DXGI for GPU enumeration, NVIDIA NVML + AMD ADL2 + Intel IGCL for live metrics, WMI thermal zone sensors, performance data for real-time CPU frequency
    - **Linux**: NVIDIA NVML, AMD, and Intel GPU support
    - Includes a demo application (`system-info-demo`) with lets-plot charting for CPU temperature history

### Bug Fixes

- **Fix `latest.yml` not generated for MSI and Portable formats** — Update YML generation was limited to NSIS; it now covers MSI and Portable installers as well
- **Fix `releaseDate` precision in `latest.yml`** — Use millisecond precision to match the electron-builder format expected by the auto-updater

### CI

- **Add `system-info` native builds to CI** — Build, verify, and upload native artifacts for all platforms in `build-natives`, `pre-merge`, `publish-maven`, `publish-plugin`, `test-packaging`, and `test-graalvm` workflows
- **Add MSVC setup step for Windows native builds** — Ensure Visual Studio compiler is available in CI for Windows JNI compilation

---

## v1.9.1

**Released: 2026-04-10**

### Bug Fixes

- **Fix DMG background image corruption** — Preserve TIFF background byte-for-byte instead of re-encoding; adjust DMG window size to match the image rather than padding the image

---

## v1.9.0

**Released: 2026-04-09**

### New Features

- **`controlButtonIconColor` / `controlButtonIconHoverColor`** — New styling properties on `DecoratedWindow` to customize the color of window control button icons (close, minimize, maximize) and their hover state
- **`titleBarClickable`** — New property to fix click handling in macOS fullscreen mode, ensuring title bar buttons remain interactive

### Bug Fixes

- **Fix macOS resize lag in decorated windows** — Remove `presentsWithTransaction` which caused visible lag during window resize on macOS
- **Fix fullscreen title bar clicks on non-notch macOS screens** — Title bar buttons were unresponsive in fullscreen on Macs without a notch
- **Fix crashes on macOS < 26 during resize and drag** — Guard against Liquid Glass APIs unavailable on older macOS versions
- **Fix DPI scaling for min/max window size on Windows** — Apply per-axis DPI scaling to `minSize`/`maxSize` constraints, and clean up resources on window dispose
- **Fix stale lock files in SingleInstanceManager** — Detect and clean up orphaned lock files from previous crashed instances
- **Fix DMG background image self-destruction** — Prevent the background image from being overwritten during the `dmg-assets` copy phase
- **Fix AppImage + Maximum compression warning** — Remove invalid `Fast` compression level and warn when `Maximum` is used with AppImage (unsupported by `mksquashfs`)
- **Remove unused `TitleBarIcons` API** — Clean up deprecated API from `decorated-window`

---

## v1.8.8

**Released: 2026-03-31**

### Bug Fixes

- **Fix macOS RTL detection** — Use `NSLocale` instead of `NSApplication` for layout direction detection, which works correctly in headless and early-startup contexts

### CI

- **Add missing Linux native verification for `decorated-window-core`** — Ensure Linux `.so` files are verified in CI workflows

---

## v1.8.7

**Released: 2026-03-30**

### New Features

- **Localized Window/Help menu titles** — macOS native menus now display localized titles based on the system locale, with a fix for GraalVM system locale initialization
- **Linux native layout direction detection** — Detect RTL/LTR layout direction on Linux via Pango JNI, replacing the AWT `ComponentOrientation` approach that failed in native image builds

### Bug Fixes

- **Gracefully handle missing platform-specific native libraries** — Runtime modules no longer crash when a native library is unavailable for the current platform; they fall back silently

### CI

- **Disable ktlint for `plugin-build`** — Exempt plugin-build from new ktlint rules to avoid false violations

---

## v1.8.6

**Released: 2026-03-30**

### Bug Fixes

- **Fix macOS input method crash in GraalVM native image** — Add missing reflection entries for macOS input method classes to platform metadata

---

## v1.8.5

**Released: 2026-03-30**

### Improvements

- **CI: menu-macos native build steps** — Add menu-macos native library build and verification to all CI workflows

---

## v1.8.4

**Released: 2026-03-30**

### New Features

- **Native Access documentation** — Comprehensive guide for the Nucleus Native Access API covering lifecycle details, supported types, and unsupported features

### Bug Fixes

- **Fix DMG background generation crash** — Check `ImageIO.write` return value to prevent `FileNotFoundException` when the image format is unsupported

---

## v1.8.3

**Released: 2026-03-30**

### Bug Fixes

- **Fix artifact naming** — Revert example `packageName` to simple form for clean installer artifact names

---

## v1.8.1

**Released: 2026-03-30**

### CI

- **Add `global-hotkey` native artifacts to publish workflows** — Download global-hotkey native libraries and install `libglib2.0-dev` in plugin publish and Maven publish CI pipelines

---

## v1.8.0

**Released: 2026-03-28**

### New Modules

- **Notification macOS** (`nucleus.notification-macos`) — Full UserNotifications API mapping via JNI. Supports rich notifications with title, subtitle, body, sound, badge, categories with actions, and delivery scheduling. Thread safety with EDT dispatch for delegate callbacks.

- **Notification Linux** (`nucleus.notification-linux`) — Full freedesktop Desktop Notifications API mapping via JNI (D-Bus `org.freedesktop.Notifications`). Supports notification actions, icons, urgency levels, and expiration.

- **Notification Windows** (`nucleus.notification-windows`) — Full Windows Toast Notifications API via JNI (WinRT). Rich toast templates with text, images, buttons, and audio.

- **Launcher Linux** (`nucleus.launcher-linux`) — Full Unity Launcher API mapping via JNI (`com.canonical.Unity.LauncherEntry` + `com.canonical.dbusmenu`). Badge count, progress bar, urgency flag, and quicklist menus with D-Bus menu support. Compatible with GNOME, KDE Plasma, and other DEs.

- **Launcher macOS** (`nucleus.launcher-macos`) — Dock menu API via JNI. Custom Dock context menu items with native callbacks. Requires `CRITICAL_ALERT` entitlement for certain notification features.

- **Launcher Windows** (`nucleus.launcher-windows`) — Windows Launcher API via JNI (WinRT/COM). Badge notifications, Jump Lists (`ICustomDestinationList`), overlay icons, and thumbnail toolbar buttons (`ITaskbarList3`) on the taskbar.

- **Freedesktop Icons** (`nucleus.freedesktop-icons`) — Type-safe constants for the freedesktop Icon Naming Specification. Shared dependency between `notification-linux` and `launcher-linux`.

- **Global Hotkey** (`nucleus.global-hotkey`) — Cross-platform global keyboard shortcut registration.
    - **Windows**: Low-level keyboard hook
    - **macOS**: Carbon API (`RegisterEventHotKey`)
    - **Linux**: X11 key grabbing + Wayland portal (`org.freedesktop.portal.GlobalShortcuts`)
    - Thread-safe registration/unregistration with synchronized init

- **Menu macOS** (`nucleus.menu-macos`) — Native macOS menu bar API via JNI. Includes GraalVM reachability metadata for JNI callbacks.

- **SF Symbols** (`nucleus.sf-symbols`) — Apple SF Symbols integration module.

### Bug Fixes

- **Fix global hotkey UI freeze on Linux** — Make portal binding non-blocking to avoid freezing the UI thread on Wayland, then dispatch to `Dispatchers.IO`
- **Fix `Dispatchers.Main` crash in example** — Add `coroutines-swing` dependency for desktop `Dispatchers.Main` support

### CI

- **Add `global-hotkey` native builds to CI** — Build, verify, and upload native artifacts for all platforms across all CI workflows

---

## v1.7.2

**Released: 2026-03-25**

### New Features

- **Webview, JNA, and SQLite JNI metadata** — Add missing GraalVM reachability metadata for webview, JNA, and SQLite JNI classes to L1
- **`Companion.serializer()` metadata for `@Serializable` classes** — The static analyzer now emits reflection entries for kotlinx.serialization `Companion.serializer()` methods automatically

### Bug Fixes

- **Fix proxy entries in `cleanupGraalvmMetadata` task** — Handle proxy configuration entries that could cause the cleanup task to fail

---

## v1.7.1

**Released: 2026-03-25**

### New Features

- **Expanded L1 library metadata** — Additional agent-captured entries for common libraries
- **FileKit GraalVM metadata** — Add reachability metadata for FileKit along with complete JDK/JNA entries

### Bug Fixes

- **Fix configuration cache serialization for `nativeImageCompile`** — Resolve serialization errors when Gradle configuration cache is enabled by building native-image arguments at execution time

---

## v1.7.0

**Released: 2026-03-24**

### New Features

- **Static bytecode analyzer for GraalVM reflection metadata** — New `AnalyzeStaticMetadataTask` that scans compiled bytecode to automatically detect classes requiring reflection, JNI, and resource configuration. Eliminates most manual metadata authoring:
    - JNI callback detection — finds native methods and their parameter/return types
    - Resource scanning — detects `getResource`/`getResourceAsStream` calls
    - Class loading wrappers — identifies `Class.forName`, `MethodHandles.Lookup.findClass` patterns
    - JNI superclass resolution — includes parent classes needed for field access
    - Enriched class entries — adds non-native methods and fields to JNI class metadata

- **`CleanupGraalvmMetadataTask`** — New Gradle task that removes entries from your manual `reachability-metadata.json` that are already covered by Nucleus library metadata (L1/L2/L3). Keeps manual config minimal.

- **File association support for GraalVM native image on macOS** — Register file type associations in the native image `.app` bundle so macOS opens files with your application.

- **macOS deployment target and SDK version patching** — Automatically patch the Mach-O deployment target and SDK version in GraalVM native image binaries for macOS Liquid Glass compatibility.

- **Per-library L1 metadata** — Split the monolithic L1 metadata file into per-library files with conditional filtering, so only relevant metadata is included based on actual classpath dependencies.

- **JNA GraalVM metadata** — Add core JNA JNI requirements to L1 metadata.

- **AWT drag-and-drop and file open handler metadata** — Add GraalVM reachability metadata for AWT drag-and-drop classes and macOS file open handlers.

- **Ktor CIO, SLF4J, and common JDK resource metadata** — Additional L1 entries for Ktor CIO engine, SLF4J, and commonly used JDK internal classes.

### Bug Fixes

- **Fix agent metadata duplication** — Deduplicate tracing agent output against Oracle Reachability Metadata Repository and static analysis results
- **Fix configuration cache serialization in `runWithNativeAgent`** — Resolve Gradle serialization errors when configuration cache is enabled
- **Fix Aqua LAF resource pattern** — Add glob pattern for Aqua Look-and-Feel resources to prevent agent duplication on macOS
- **Fix unsupported class file version in `NativeMethodDetector`** — Handle newer class file versions gracefully instead of crashing
- **Fix `JarResourceDetector` false positives** — Narrow path matching to avoid detecting unrelated resources

### Breaking Changes

- **Default GraalVM config directory changed** — The default directory for manual GraalVM metadata is now `graalvm/` instead of `resources/`. Existing projects should move their `reachability-metadata.json` to the new location.

---

## v1.6.5

**Released: 2026-03-23**

### New Features

- **File association support for GraalVM native image on macOS** — Register file type associations (`CFBundleDocumentTypes`) in the GraalVM native image `.app` bundle

### Bug Fixes

- **Fix ktlint violations in `configureGraalvmApplication.kt`**

---

## v1.6.4

**Released: 2026-03-23**

### Bug Fixes

- **Fix infinite recursion in fullscreen mouse event forwarding** — Prevent stack overflow when forwarding mouse events in decorated window fullscreen mode

---

## v1.6.2

**Released: 2026-03-22**

### New Features

- **`appName` property** — New top-level property in `nativeDistributions {}` for the human-readable application display name (installer title, `.desktop` Name, Start Menu entry). Separates the display name from `packageName`, which remains the technical identifier used for executable and package file naming.

```kotlin
nativeDistributions {
    appName = "My App"            // Display name (installer, .desktop, Start Menu)
    linux { packageName = "myapp" }     // Technical: executable, .deb file name
    windows { packageName = "MyApp" }   // Technical: .exe name, MSI
}
```

### Bug Fixes

- **Fix MSI build failing with WiX `LGHT0094: File:mainExecutable not found`** — The JVM packaging path now sets `executableName` on the electron-builder task, matching what the GraalVM path already did. Without it, electron-builder fell back to a mismatched lowercase name from `package.json`, causing WiX to fail.
- **Fix Linux .deb file and launcher using Gradle project name instead of `linux.packageName`** — `executableName` now resolves to the platform-specific `packageName` (`linux.packageName`, `windows.packageName`, `macOS.packageName`) instead of the Gradle project name.
- **Fix `productName` missing in electron-builder YAML** — When no top-level `packageName` was set, `productName` was omitted from the generated config. It now falls back to `appName`, then `packageName`, then `executableName`.
- **Fix `package.json` `name` field ignoring platform-specific package name** — The generated `package.json` now uses `executableName` (platform-specific) for the `name` field, so the `${name}` variable in the artifact name template resolves correctly.

## v1.6.0

**Released: 2026-03-22**

### New Modules

- **Taskbar Progress** (`nucleus.taskbar-progress`) — Native taskbar/dock progress bar and attention requests on all platforms. Shows download progress, build status, or any long-running operation directly in the OS taskbar. See [Taskbar Progress](runtime/taskbar-progress.md).
    - **Windows**: `ITaskbarList3` (progress value/state) + `FlashWindowEx` (attention)
    - **macOS**: `NSDockTile` with custom `NSProgressIndicator` overlay + `NSApplication.requestUserAttention`
    - **Linux**: D-Bus `com.canonical.Unity.LauncherEntry` (GNOME, KDE Plasma, and compatible DEs)
    - Five states: `NO_PROGRESS`, `INDETERMINATE`, `NORMAL`, `ERROR`, `PAUSED`
    - Attention requests: `INFORMATIONAL` (brief flash) and `CRITICAL` (until focus)

- **App Metadata at Runtime** (`NucleusApp`) — New singleton in `core-runtime` that exposes plugin-injected metadata at runtime: `appId`, `version`, `vendor`, `description`. Populated via system properties and a generated `nucleus-app.properties` classpath resource. See [Runtime APIs](runtime/index.md).

### New Features

- **Centralized GraalVM native-image metadata** — Nucleus now ships all generic and platform-specific reflection metadata out of the box, organized in three levels:
    - **L1 (graalvm-runtime JAR)** — Generic cross-platform reflection entries for Compose Desktop, AWT/Swing, Skiko, security providers, font managers, and more (~300+ types). Automatically picked up from the classpath by native-image.
    - **L2 (Oracle Reachability Metadata Repository)** — Automatic resolution of metadata for all runtime classpath dependencies from the [Oracle GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata). Covers popular libraries like ktor, kotlinx.serialization, SLF4J, Logback, and many others. Enabled by default.
    - **L3 (plugin platform metadata)** — Platform-specific AWT/Java2D/font/security metadata for macOS, Windows, and Linux, shipped inside the Gradle plugin. Written to the build directory at compile time — no per-platform `when` block needed in your build script.

    Users no longer need to copy thousands of reflection entries from the example app. Most applications will work without any manual reflection configuration. See [Centralized Reflection Metadata](graalvm/index.md#centralized-reflection-metadata).

- **`metadataRepository {}` DSL** — New configuration block in `graalvm {}` to control the Oracle Reachability Metadata Repository integration. Supports `enabled`, `version`, `excludedModules`, and `moduleToConfigVersion`. Enabled by default with version `0.10.6`.

- **`resolveReachabilityMetadata` task** — New Gradle task that resolves Oracle Reachability Metadata Repository entries for all runtime classpath dependencies. Runs automatically before `packageGraalvmNative`.

- **Auto-include Compose Multiplatform resources** — `graalvm-runtime` now includes `composeResources/.*` in the `native-image.properties` resource patterns, so all Compose resources (images, strings, fonts loaded via `Res.*`) are included automatically.

- **Smart agent merge and deduplication** — The `runWithNativeAgent` task now deduplicates agent output against library metadata already shipped in classpath JARs. This prevents the tracing agent from re-adding entries that Nucleus (or other libraries) already provide, keeping your app-specific config clean and minimal.

- **All JDK locale bundles auto-included** — Locale-specific resource bundles are now part of the centralized metadata, reducing runtime `MissingResourceException` crashes.

- **`UpdateLevel` enum** — `UpdateResult.Available` now carries an `UpdateLevel` (`MAJOR`, `MINOR`, `PATCH`, `PRE_RELEASE`) computed by comparing semantic version numbers. Allows the UI to adapt messaging based on update significance. See [Auto Update](auto-update.md#update-level).

- **Post-update detection** — New `NucleusUpdater.consumeUpdateEvent()` and `wasJustUpdated()` methods detect that the app was just updated and return an `UpdateEvent` with `previousVersion`, `newVersion`, and `updateLevel`. Useful for "What's new" dialogs, migrations, or analytics. See [Auto Update](auto-update.md#post-update-detection).

- **Default jlink modules expanded** — `java.net.http` and `jdk.accessibility` are now included in the default jlink module list alongside `java.base`, `java.desktop`, `java.logging`, and `jdk.crypto.ec`. No need to add them manually.

- **Auto-generate main class reflection metadata** — The plugin now automatically adds the application's main class to the GraalVM native-image reflection configuration, eliminating one manual step.

### Bug Fixes

- **Eliminate resize flash on `DecoratedDialog` (Windows)** — Add native `WndProc` subclass for dialogs that handles `WM_ERASEBKGND` and `WM_WINDOWPOSCHANGING` to prevent white flash during dialog resize.
- **Fix WM_CLASS showing `LambdaForm` class name in GNOME under native image** — Add `@TargetClass` substitution for `XToolkit.getAWTAppClassName()` that returns the real app name from `NucleusApp.appId` instead of the internal LambdaForm class.
- **Fix Linux executable alias for GraalVM native image layout** — Support the native image binary directory structure when creating the executable symlink.
- **Align native image WM_CLASS with `.desktop` `StartupWMClass`** — Ensures the GNOME taskbar icon matches the `.desktop` file.
- **High-quality Linux icon generation** — Replace single-step `Graphics2D` resizing with Thumbnailator progressive bilinear downscaling, fixing blurry icons in DEB packages at small sizes (16x16, 32x32).
- **Prevent reflection fallback crash in native image** — When the JNI library is loaded, return its result directly without falling through to reflection-based `sun.awt.AWTAccessor` access, which triggers `IllegalAccessException` under JPMS in native image.
- **Rethrow `CancellationException` in updater** — Prevent coroutine scope leaks when update operations are cancelled.
- **npm 11 compatibility** — Multiple fixes for `ECOMPROMISED` errors in parallel builds: isolated npm prefix, separate npmrc files, ensure npm prefix lib directory exists.
- **Fix Jewel decorated window border on Linux light mode** — Use subtle semi-transparent border (`Color(0x12FFFFFF)`) on Linux instead of opaque Jewel `borders.normal` color. On GNOME/KDE, the window border now blends with the native window chrome in light mode instead of showing an obtrusive gray outline.

### Breaking Changes

- **Sample app `reachability-metadata.json` files drastically reduced** — If you copied metadata from the example or jewel-sample apps, the source files are now nearly empty (framework entries moved to L1/L3). This is not a code-breaking change, but you should clean up your own metadata files — see the [migration guide](graalvm/index.md#migration-from-v15x).

- **Removed old-format config files from samples** — `predefined-classes-config.json`, `proxy-config.json`, `resource-config.json`, and `serialization-config.json` have been removed. All configuration is consolidated in `reachability-metadata.json`.

- **`UpdateResult.Available` signature changed** — Now includes a `level: UpdateLevel` field. If you destructure `UpdateResult.Available`, add the new field.

---

## v1.5.9

**Released: 2026-03-20**

### New Features

- **`runGraalvmNative` task** — New Gradle task that builds and runs the GraalVM native image directly, without packaging into an installer. Useful for quick iteration during development.

### Breaking Changes

- **Remove release build type for GraalVM** — GraalVM native-image tasks no longer have `release` variants (`packageReleaseGraalvmNative`, etc.). ProGuard is redundant with native-image's closed-world dead code elimination and harmful because it can rename classes referenced in `reachability-metadata.json`. See [No Release Build Type](graalvm/index.md#no-release-build-type).

---

## v1.5.8

**Released: 2026-03-20**

### New Features

- **Automatic resource inclusion for GraalVM native-image** — `graalvm-runtime` now ships a `native-image.properties` that auto-includes all `.svg`, `.ttf`, `.otf` resources, `nucleus/native/*` JNI libraries, and `META-INF/services/*` descriptors via glob patterns. Projects no longer need to run the tracing agent just to discover icon and font resources — they are embedded in the native binary automatically. The tracing agent is still required for reflection, JNI, resource bundles, and non-standard resources. See [Automatic Resource Inclusion](graalvm/index.md#automatic-resource-inclusion).

---

## v1.5.7

**Released: 2026-03-20**

### Bug Fixes

- **Fix first-frame flash when starting maximized** — Override `state.size` with the screen work area (bounds minus taskbar insets, DPI-scaled) **before** the `Window` composable, so the AWT window is created at the correct maximized dimensions from the start. The previous approach (`PreSizeIfMaximized` via `DisposableEffect` inside `Window`) ran too late — the first Skia frame had already rendered at the default size. Affects the JNI `DecoratedWindow` variant.

---

## v1.5.6

**Released: 2026-03-20**

### Bug Fixes

- **Fix first-frame flash when starting maximized** _(superseded by v1.5.7)_ — Initial attempt using `DisposableEffect` inside `Window` to pre-size the AWT window.

---

## v1.5.5

**Released: 2026-03-19**

### New Features

- **Add `SystemNative` enum value to `ControlButtonsDirection`** — A new `SystemNative` variant that reads the native OS locale from JVM startup system properties (`user.language`, `user.country`, `user.variant`) instead of `Locale.getDefault()`. Unlike the `System` mode which reflects the current JVM default locale (mutable via `Locale.setDefault()`), `SystemNative` is immune to runtime locale overrides and always reflects the true operating system configuration. Useful for applications that temporarily change the UI locale but want control buttons to match the native system direction.

---

## v1.5.4

**Released: 2026-03-19**

### New Features

- **Add `controlButtonsDirection` to `DialogTitleBar`** — Propagate `controlButtonsDirection` through the entire `DialogTitleBar` chain: `DialogTitleBarImpl` (core), platform-specific implementations (JNI + JBR for Linux/Windows/macOS), and themed wrappers (`JewelDialogTitleBar`, Material 2 and Material 3 `MaterialDialogTitleBar`). Aligns dialog title bars with the existing `TitleBar` API.

---

## v1.5.3

**Released: 2026-03-19**

### Bug Fixes

- **Fix control buttons global position when content and controls share the same edge** — End-aligned items (control buttons) are now placed before Start-aligned items in the title bar layout. Previously, in RTL apps with LTR control buttons (or vice versa), the content would claim the edge first, pushing buttons inward. Affects all platforms.

---

## v1.5.2

**Released: 2026-03-19**

### Bug Fixes

- **Sync control buttons layout direction with `controlButtonsDirection`** — Control buttons (close, maximize, minimize) now inherit the resolved `controlButtonsDirection` as their `LocalLayoutDirection`. Previously, in RTL apps with `ControlButtonsDirection.System`, button internals were rendered in RTL even though the buttons were placed on the correct side. Introduces `LocalControlButtonsDirection` composition local, consumed by `WindowControlArea` and `WindowsWindowControlArea`.

---

## v1.5.1

**Released: 2026-03-19**

### Improvements

- **Add `controlButtonsDirection` to `JewelTitleBar` and Material 2 `MaterialTitleBar`** — Forward the `controlButtonsDirection` parameter to the underlying `TitleBar` so consumers can control window button placement independently of the content layout direction. Material 3 `MaterialTitleBar` already had this parameter; Jewel and Material 2 are now aligned.

---

## v1.5.0

**Released: 2026-03-19**

### New Features

- **Automatic Liquid Glass support via `macOsSdkVersion`** — Nucleus now automatically patches the app launcher's `LC_BUILD_VERSION` Mach-O header to macOS SDK 26.0 using `vtool`, enabling Liquid Glass window decorations (larger traffic lights, rounded corners). This works with **any JDK** — a JDK compiled with Xcode 26 is no longer required. The `run` task uses a cached patched copy of the JVM, while distributable builds patch the launcher before signing. Controlled via `macOS { macOsSdkVersion = "26.0" }` (enabled by default, set to `null` to disable). Requires Xcode Command Line Tools. See [macOS 26 Window Appearance](targets/macos.md#macos-26-window-appearance-liquid-glass).
- **`Modifier.clientRegion()` for JBR title bar hit testing** — New modifier function that registers composable elements as interactive client regions within a `DecoratedWindow`'s title bar. Client regions receive mouse events (clicks, presses) instead of triggering window dragging. Uses AWT-level mouse listeners with precise coordinate-based hit testing, replacing the old pointer-event-based `customTitleBarMouseEventHandler`. See [Decorated Window](runtime/decorated-window.md).
- **`decorated-window-jewel` module** — New module providing Jewel theme integration for `DecoratedWindow`. Used by the jewel-sample app.
- **`decorated-window-material2` module** — New module providing Material 2 theme color mapping for `DecoratedWindow`, complementing the existing Material 3 module.
- **`decorated-window-material3` module** — Renamed from `decorated-window-material` for clarity. Provides Material 3 color mapping for `DecoratedWindow`.
- **Decouple control buttons direction from title bar content direction** — Window control buttons (close, minimize, maximize) now follow their platform-native position regardless of the title bar's layout direction.
- **Intercept system quit events** — `onCloseRequest` in `DecoratedWindow` now intercepts macOS Cmd+Q and Dock quit events, giving the app a chance to confirm or cancel the quit.
- **`backgroundContent` slot in `TitleBar`** — New composable slot for rendering content behind the title bar (e.g. a gradient or blurred background).
- **Surface notarization details on failure** — Notarization errors now include the Apple submission ID and log URL for easier debugging.

### Bug Fixes

- **Fix title bar drag on Windows (`decorated-window-jbr`)** — Window dragging via the title bar no longer occasionally fails on the first attempt when another window has focus. The new `WindowMouseEventEffect` approach uses AWT mouse listeners for reliable hit-test forwarding to JBR's `CustomTitleBar`, fixing the intermittent missed drag events. ([#53](https://github.com/kdroidFilter/Nucleus/issues/53))
- Promote `core-runtime` to `api` scope in `updater-runtime` and `system-color` so consumers no longer need to declare it separately.
- Include generic provider in publish mode detection.
- Skip Flatpak packaging gracefully when `flatpak` CLI is missing.
- Use sandboxed flag for jpackage `macAppStore` instead of relying on `targetFormat`.
- Propagate DMG contents entries to universal CI build.
- Compensate macOS title bar height in DMG background image.
- Migrate from deprecated `painterResource` to `ImageVector` icons.
- Replace deprecated Compose accessors with version catalog entries.
- Add Gradle 9.4 task caching and normalization annotations across all task classes.

### Deprecations

- **`decorated-window-material` renamed** — Use `decorated-window-material3` instead.

---

## v1.4.8

**Released: 2026-03-18**

### New Features

- **Intercept system quit events via `onCloseRequest`** — `DecoratedWindow` now intercepts macOS Cmd+Q and Dock quit events.
- **Surface notarization submission ID and Apple log on failure** — Easier debugging of notarization issues.

### Bug Fixes

- **Reliable title bar drag via AWT-level `clientRegion` hit testing** — Fixes intermittent missed drag events on Windows when another window has focus.
- Promote `core-runtime` to `api` scope in `updater-runtime` and `system-color`.
- Include generic provider in publish mode detection.
- Skip Flatpak packaging gracefully when `flatpak` CLI is missing.
- Use sandboxed flag for jpackage `macAppStore` instead of `targetFormat`.

---

## v1.4.7

**Released: 2026-03-11**

### New Features

- **`backgroundContent` slot in `TitleBar` and `MaterialTitleBar`** — New composable slot for rendering content behind the title bar (e.g. a gradient or blurred background).

---

## v1.4.6

**Released: 2026-03-11**

### Bug Fixes

- **Fix `objc_retain` crash on freed `NSWindow` during disposal** — Prevent crash when the Objective-C runtime attempts to retain an already-deallocated `NSWindow` reference from a JNI callback.

---

## v1.4.5

**Released: 2026-03-10**

### Bug Fixes

- **Fix crash on `__weak` `NSWindow` reference from JNI thread during disposal** — Prevent crash when a weak reference to `NSWindow` is accessed from a JNI thread after the window has been deallocated.

---

## v1.4.4

**Released: 2026-03-10**

### Bug Fixes

- **Fix crash on `NSWindow` cleanup during fullscreen transition** — Prevent crash when the window is being cleaned up while a fullscreen animation is still in progress.

---

## v1.4.3

**Released: 2026-03-10**

### New Features

- **Safari-like fullscreen title bar behavior on macOS** — Fullscreen title bar renders as a translucent overlay that pushes content down, matching native Safari behavior.
- **Executable type and version detection for GraalVM native-image builds** — `ExecutableType` now correctly identifies GraalVM native binaries and their version.

### Bug Fixes

- Enable redirect following in `NativeHttpClient` and add SSL lib to native-image resources.

---

## v1.4.2

**Released: 2026-03-10**

### Bug Fixes

- **Harden fullscreen state management on Windows** — Fix multiple fullscreen-related issues:
    - Improve fullscreen exit state restoration and maximize handling.
    - Override delegate placement on fullscreen exit with saved state.
    - Restore correct placement on fullscreen exit and eliminate maximize glitch.
    - Restore pointer events to content area in fullscreen mode.

---

## v1.4.1

**Released: 2026-03-10**

### Bug Fixes

- Add missing ProGuard rules for `system-color`, `energy-manager`, and `linux-hidpi`.
- Apply `macOSLargeCornerRadius` to jewel-sample title bar.
- Update GraalVM reachability metadata for macOS.

---

## v1.4.0

**Released: 2026-03-09**

### New Features

- **Native fullscreen with sliding title bar** — Platform-native fullscreen experience: Safari-like on macOS, Edge-like on Windows, Firefox-like on Linux. When the window enters fullscreen, the title bar becomes a floating overlay that slides down on hover near the top edge and slides back up when the pointer moves away. Enable with `Modifier.newFullscreenControls()` on `TitleBar` / `MaterialTitleBar`. See [Decorated Window](runtime/decorated-window.md).
- **macOS large corner radius** — New `Modifier.macOSLargeCornerRadius()` modifier applies the 26pt window corner radius used by Finder and Safari. Installs an invisible `NSToolbar` and repositions traffic light buttons to match Apple's native inset. See [Decorated Window](runtime/decorated-window.md).
- **System Color module** (`nucleus.system-color`) — Reactive detection of OS accent color and high contrast mode via JNI. Supports macOS (`NSColor.controlAccentColor`), Windows (DWM registry), and Linux (XDG Desktop Portal D-Bus). Composable APIs: `systemAccentColor()`, `isSystemInHighContrast()`. See [System Color](runtime/system-color.md).
- **Energy Manager module** (`nucleus.energy-manager`) — Comprehensive energy management with three tiers: full efficiency mode (EcoQoS + IDLE_PRIORITY_CLASS on Windows, `PRIO_DARWIN_BG` + task_policy_set on macOS, nice +19/ioprio/timerslack on Linux), light efficiency mode (CPU deprioritization only, no I/O throttling), and thread-level efficiency mode. Includes screen-awake (caffeine) API to prevent display sleep (IOPMAssertion on macOS, SetThreadExecutionState on Windows, D-Bus/X11 on Linux). Coroutine helpers: `withEfficiencyMode()`, `withLightEfficiencyMode()`. See [Energy Manager](runtime/energy-manager.md).
- **Auto-center `DecoratedDialog` on parent window** — Dialogs are now automatically centered on their parent with reliable positioning via `windowOpened` event. See [Decorated Window](runtime/decorated-window.md).
- **macOS RTL traffic-light support** — Correct traffic light button positioning in right-to-left layouts. See [Decorated Window](runtime/decorated-window.md).
- **Centralized native library loading** — New `NativeLibraryLoader` with persistent cache replaces per-module loading logic.
- **Fullscreen-aware window controls** — Maximize button shows exit-fullscreen icon when in fullscreen mode on Linux and Windows, with new SVG icon variants (active/inactive/dark). See [Decorated Window](runtime/decorated-window.md).
- **AWT window background sync on macOS** — Idempotent property application prevents redundant `PropertyChangeEvent` firings, reducing visual jitter during layout passes.
- **Sample CMP module** (`sample-cmp`) — New Kotlin Multiplatform Compose sample with Android and Desktop targets.
- **Example app gallery** — Material 3 component showcase with actions, communication, containment, selection, text inputs, typography, elevation, and color screens.

### Bug Fixes

- **Fix Windows fullscreen** — Compose for Desktop does not handle fullscreen correctly on Windows (window does not cover the taskbar). Now uses native Win32 APIs for true fullscreen, matching Edge and other native Windows applications.
- **Eliminate white resize flash on Windows** — Adjust Skiko's clear color to transparent for dark themes and synchronize DWM caption/border colors for consistent Windows 11 window chrome styling.
- **Skip Android configurations in `CleanNativeLibsTransform`** — Fixes build issues when Android targets are present. ([#79](https://github.com/kdroidFilter/ComposeDeskKit/issues/79))
- **Skip ZIP stapling to preserve blockmap** — Prevents breaking auto-update blockmap integrity during notarization. ([#70](https://github.com/kdroidFilter/ComposeDeskKit/issues/70))
- **Detect target arch from JDK release file for cross-building** — Fixes architecture detection when cross-compiling. ([#71](https://github.com/kdroidFilter/ComposeDeskKit/issues/71))
- Move Windows dark mode monitoring to native thread for reliability.
- Correct D-Bus `ReadOne` variant parsing for Linux accent color.

### Deprecations

- **`appStore` property deprecated** — PKG distributions are now always treated as App Store builds. The `appStore` property is no longer needed. ([#65](https://github.com/kdroidFilter/ComposeDeskKit/issues/65))

---

## v1.3.8

**Released: 2026-03-02**

### Bug Fixes

- Match native macOS traffic light button spacing.

---

## v1.3.7

**Released: 2026-03-02**

### Bug Fixes

- Handle ZIP stapling by extracting `.app`, stapling, and re-zipping.
- Remove deprecated `internetEnabled` DMG setting.

---

## v1.3.6

**Released: 2026-03-02**

### Bug Fixes

- Fix fullscreen button transitions and alignment.
- Restore title bar appearance before fullscreen exit animation.
- Fallback to default icon for GraalVM native image on Windows.
- Update `latest-mac.yml` checksums and file sizes after notarization.
- Remove `xvfb-run` from test-graalvm workflow (Xvfb already started by `setup-nucleus`).

---

## v1.3.5

**Released: 2026-03-02**

### Bug Fixes

- Add `homepage` to jewel-sample `nativeDistributions` for electron-builder DEB packaging.

---

## v1.3.4

**Released: 2026-03-02**

### Bug Fixes

- Remove `xvfb-run` from graalvm workflow (Xvfb already started by `setup-nucleus`).

---

## v1.3.3

**Released: 2026-03-02**

### New Features

- Add `graalvm` option to `setup-nucleus` composite action.
- Configure Windows code signing for jewel-sample using shared certificate.

### Bug Fixes

- Add `libx11-dev` and `libdbus-1-dev` to graalvm release Linux dependencies.
- Configure jewel-sample `nativeDistributions` with icons, deb maintainer, and platform settings.
- Use `packageGraalvmDeb`/`Dmg`/`Nsis` tasks instead of raw native image output.

---

## v1.3.2

**Released: 2026-03-02**

No user-facing changes (tag only).

---

## v1.3.1

**Released: 2026-03-02**

### Bug Fixes

- Use `packageGraalvmDeb`/`Dmg`/`Nsis` tasks instead of raw native image output.
- Add missing native artifact downloads and `libx11-dev` to publish-plugin workflow.
- Pass repository to `gh release` commands in graalvm workflow.
- Remove custom icons from jewel-sample, use default icons instead.

---

## v1.3.0

**Released: 2026-03-02**

### New Features

- **GraalVM Native Image support (experimental)** — Compile Compose Desktop apps into standalone native binaries with instant cold boot (~0.5 s), lower memory usage (~100–150 MB vs ~300–400 MB on JVM), and smaller bundles. New `graalvm {}` DSL block, `runWithNativeAgent` task for reflection metadata collection, and `packageGraalvmNative` / `packageGraalvmDeb` / `packageGraalvmDmg` / `packageGraalvmNsis` packaging tasks. Requires [BellSoft Liberica NIK 25](https://bell-sw.com/liberica-native-image-kit/). See [GraalVM Native Image](graalvm/index.md).
- **New `graalvm-runtime` module** (`nucleus.graalvm-runtime`) — Centralizes native-image bootstrap logic into a single `GraalVmInitializer.initialize()` call: Metal L&F, `java.home`/`java.library.path` setup, charset/fontmanager early init, Linux HiDPI detection, and GraalVM `@TargetClass` font substitutions for Windows and Linux.
- **Decorated Window module split** — `decorated-window` split into `decorated-window-core`, `decorated-window-jbr` (JBR-based, same behavior as before), and `decorated-window-jni` (JBR-free, works with GraalVM). See [Migration Guide](#migration-guide-12x--13x) below.
- **`decorated-window-jni` module** — New JNI-based implementation of `DecoratedWindow` that works without JetBrains Runtime, including support for Linux via native JNI bridge. Compatible with GraalVM Native Image.
- **Linux HiDPI scaling support** — Native `GDK_SCALE` handling for correct rendering on HiDPI Linux displays.
- **Auto-notarize macOS distributions** — `packageDistributionForCurrentOS` now automatically notarizes on macOS when notarization credentials are configured.
- **Jewel Sample app** — Standalone Jewel UI showcase with GraalVM native image CI, platform-specific packaging, and Windows code signing.

### Bug Fixes

- Replace `OBJC_ASSOCIATION_ASSIGN` with `RETAIN_NONATOMIC` to prevent dangling pointer on macOS.
- Resolve fontmanager loading on Linux native image.
- Ensure Skiko library is extracted and loaded in GraalVM Native Image.
- Use `onlyIf` instead of `enabled` for native build tasks (configuration cache compatibility).
- Detect unconsumed double-clicks before triggering macOS zoom.

### Documentation

- Comprehensive GraalVM Native Image guide for Compose Desktop.
- macOS 26 window appearance guide for JVM and native image.
- Linux HiDPI runtime documentation.
- AOT cache documentation rewrite with motivation and Project Leyden reference.
- Decorated window docs update with changelog and migration guide.

### CI/CD

- GraalVM native-image build workflow for PR CI.
- CI workflow to release Jewel Sample as GraalVM native image on tags.
- Migrate detekt to 2.0.0-alpha.2 for JDK 25 support.

---

## Migration Guide: 1.2.x → 1.3.x

### Decorated Window: monolithic module split

The `decorated-window` module has been split into three modules:

| Before (1.2.x) | After (1.3.x) |
|---|---|
| `nucleus.decorated-window` | `nucleus.decorated-window-core` (shared) |
| | `nucleus.decorated-window-jbr` (JBR implementation) |
| | `nucleus.decorated-window-jni` (JNI implementation, new) |

**Dependency update** — replace:

```kotlin
implementation("io.github.kdroidfilter:nucleus.decorated-window:<version>")
```

With one of:

```kotlin
// JBR-based (same behavior as before)
implementation("io.github.kdroidfilter:nucleus.decorated-window-jbr:<version>")

// JNI-based (no JBR dependency, works with GraalVM)
implementation("io.github.kdroidfilter:nucleus.decorated-window-jni:<version>")
```

**Breaking changes in `TitleBarColors`** — the following fields have been **removed**:

- `titlePaneButtonHoveredBackground`
- `titlePaneButtonPressedBackground`
- `titlePaneCloseButtonHoveredBackground`
- `titlePaneCloseButtonPressedBackground`

These platform-specific button state colors are now handled internally by each module's native implementation. If you were constructing `TitleBarColors` explicitly with these fields, remove them.

**No other code changes required** — all composable APIs (`DecoratedWindow`, `DecoratedDialog`, `TitleBar`, `DialogTitleBar`), scopes, and state types are identical. No import changes needed — the package remains `io.github.kdroidfilter.nucleus.window`.

See [Decorated Window](runtime/decorated-window.md) for full details on choosing between JBR and JNI.

---

## v1.2.7

**Released: 2026-02-22**

### Bug Fixes

- Use per-platform winCodeSign archives to fix AppX build on Windows.

---

## v1.2.6

**Released: 2026-02-22**

### Bug Fixes

- Preserve sandbox entitlements when re-signing PKG for App Store without certificate.

---

## v1.2.5

**Released: 2026-02-22**

### Bug Fixes

- Map `PublishMode.Auto` to `"onTag"` for electron-builder.

---

## v1.2.4

**Released: 2026-02-22**

### Bug Fixes

- Add decorated-window native macOS build and publish steps to CI.

---

## v1.2.3

**Released: 2026-02-21**

### Bug Fixes

- Round bottom corners of decorated window on GNOME.
- Use Developer ID signing for DMG/ZIP formats to pass notarization.

---

## v1.2.2

**Released: 2026-02-20**

### New Features

- **Generic publish provider** — New `generic` provider for self-hosted update servers, alongside GitHub and S3. See [Auto-Update](auto-update.md).

---

## v1.2.1

**Released: 2026-02-20**

### Bug Fixes

- Prevent crash when no publish provider is configured in electron-builder.

---

## v1.2.0

**Released: 2026-02-20**

### New Features

- **Native SSL module** (`nucleus.native-ssl`) — Load OS-trusted certificates via JNI: macOS Keychain (`SecTrustCopyAnchorCertificates`), Windows Crypt32, Linux system cert paths. Aligns with JetBrains `jvm-native-trusted-roots`. Includes cryptographic `isSelfSigned` verification on macOS. See [Native SSL](runtime/native-ssl.md).
- **Native HTTP modules** (`nucleus.native-http`, `nucleus.native-http-okhttp`, `nucleus.native-http-ktor`) — HTTP clients that use the OS trust store out of the box, with OkHttp and Ktor adapters. See [Native HTTP](runtime/native-http.md).
- **CA certificate patching** — New build-time task patches the JVM's `cacerts` with OS-trusted certificates.
- **ProGuard JNI keep rules** — Default ProGuard template now includes keep rules for `native-ssl`.

### Documentation

- Add native-ssl and native-http module documentation.
- Update comparison with CA certificate patching and native SSL details.

---

## v1.1.6

**Released: 2026-02-20**

### New Features

- Default `artifactName` to `${name}-${version}-${os}-${arch}.${ext}` for consistent naming.

### Bug Fixes

- Preserve symlinks when copying app image on macOS.
- Isolate app image and electron-builder cache per task.
- Add PR packaging test workflow for all platforms.

---

## v1.1.4

**Released: 2026-02-19**

### Bug Fixes

- Isolate npm cache per task to prevent `EPERM` on parallel builds.
- Resolve configuration cache serialization error for sandboxed pipeline.

---

## v1.1.3

**Released: 2026-02-19**

### Bug Fixes

- Default `setup-node` to `true` and remove npm cache workaround.

---

## v1.1.2

**Released: 2026-02-19**

### Bug Fixes

- Clean npm cache on Windows runners to prevent `ECOMPROMISED` errors.
- Only enable sandboxed pipeline for OS-compatible store formats.

### Documentation

- Add `TargetFormat` import change note (from `compose` to `nucleus`) to migration guide.
- Add ProGuard rules documentation for JNI libraries.
- Add LLM documentation (`llms.txt`, `llms-full.txt`).
- Add comprehensive packaging tools comparison page.

---

## v1.1.1

**Released: 2026-02-19**

### Bug Fixes

- Unseal `jbr-api` JAR to prevent sealing violation on startup.

---

## v1.1.0

**Released: 2026-02-19**

### New Features

- **Decorated Window module** (`nucleus.decorated-window`) — Custom window decorations with native title bars, traffic light buttons on macOS, window controls on Windows (close/minimize/maximize), and GNOME/KDE styling on Linux. Replaces JNA/Unsafe with an Objective-C JNI bridge on macOS. See [Decorated Window](runtime/decorated-window.md).
- **Material theme module** (`nucleus.decorated-window-material`) — Automatic Material 3 color mapping for `DecoratedWindow`.
- **Darkmode Detector module** (`nucleus.darkmode-detector`) — Reactive OS dark mode detection via JNI on macOS, Windows, and Linux (XDG Desktop Portal). Replaces the JNA-based implementation with pure JNI for smaller binaries and no external dependencies. See [Darkmode Detector](runtime/darkmode-detector.md).
- **RTL layout support** — Title bar respects right-to-left layout direction with dedicated toggle icons.
- **KDE Breeze window styling** — Dedicated icon set, corner radius, and hover/pressed states matching KDE Plasma.
- **GNOME window styling** — Rounded corners, subtle border, and inactive title bar styling matching GNOME/Adwaita.

### Bug Fixes

- Dispatch AppKit calls to main thread in JNI bridge.
- Hide border when window is maximized in any direction or fills the screen.
- Use rounded border shape matching GNOME/KDE window corners.

---

## v1.0.9

**Released: 2026-02-18**

### Bug Fixes

- Flatten native lib extraction to resources root for sandboxed builds.

---

## v1.0.8

**Released: 2026-02-18**

### Bug Fixes

- Move native dylibs to `Contents/Frameworks/` for sandboxed macOS builds.
- Place universal-arch native libs in resources root.

---

## v1.0.7

**Released: 2026-02-18**

### Bug Fixes

- Prevent JAR filename collisions in sandboxed pipeline.
- Ad-hoc sign `jspawnhelper` instead of stripping signature.

---

## v1.0.6

**Released: 2026-02-18**

### Bug Fixes

- Inject `application-identifier` for App Store signing and fix configuration cache.

---

## v1.0.5

**Released: 2026-02-17**

### Bug Fixes

- Bypass electron-builder for App Store PKG signing.
- Use full installer identity for PKG signing.

---

## v1.0.4

**Released: 2026-02-17**

### Bug Fixes

- Re-sign macOS app after `.cfg` modification for PKG builds.
- Use separate sandboxed entitlements for App Store PKG signing.

---

## v1.0.3

**Released: 2026-02-17**

### Bug Fixes

- Strip certificate prefix from PKG identity for electron-builder.

---

## v1.0.2

**Released: 2026-02-17**

### New Features

- **`installAndQuit()`** — Silent background update API that installs the update and exits without restarting.

---

## v1.0.1

**Released: 2026-02-17**

### Bug Fixes

- Resolve Gradle configuration cache serialization failures.
- Derive plugin version from Git tag instead of `gradle.properties`.
- Move `installationPath` to macOS-only DSL.

---

## v1.0.0

**Released: 2026-02-17**

Initial release — fork of the JetBrains Compose Desktop Gradle plugin, repackaged as **Nucleus** with extended packaging, distribution, and runtime features.

### Packaging & Distribution

- **Electron-builder backend** — DMG, PKG, NSIS, NSIS Web, AppImage, DEB, RPM, Snap, and AppX formats. Replaces jpackage for installer generation.
- **macOS code signing and notarization** — Developer ID and App Store signing, automatic notarization via `notarytool`.
- **macOS App Sandbox** — Sandboxed PKG builds with JNI library extraction, entitlements management, and provisioning profiles.
- **Windows code signing** — SignTool integration with SHA-256 hashing.
- **AppX packaging** — Windows Store packaging with tile assets and identity configuration.
- **DMG customization** — Background images, icon positioning, window size, badge icons, and format selection.
- **Layered icons** — macOS 26+ `.icon` directory support for dynamic tilt/depth effects.
- **Splash screen** — Splash image support in JVM launcher.
- **Universal macOS binaries** — CI workflow for `lipo`-based fat binaries (Apple Silicon + Intel).
- **Type-safe DSL** — Enums for target formats, publish modes, and compression settings replace raw strings.
- **Linux packaging options** — `startupWMClass`, `debDepends`, `rpmRequires`, `debCompression`, `rpmCompression`, after-install scripts, and architecture suffixes.
- **File associations** — macOS `CFBundleURLTypes` injection and Linux/Windows file association support via electron-builder.

### Runtime Modules

- **`core-runtime`** — Executable type detection, platform identification, `SingleInstanceManager`, `DeepLinkHandler`.
- **`aot-runtime`** — AOT cache mode detection for JDK 25+ (Project Leyden). AOT cache generation task with safety timeout.
- **`updater-runtime`** — Auto-update engine with GitHub and S3 providers. SHA-512 verification, progress tracking, `installAndRestart()`. Platform-specific install strategies: detached shell script on macOS, background PowerShell on Windows, AppImage replacement on Linux.

### Build & CI

- **Composite actions** — `setup-nucleus`, `update-yml`, and release publishing actions.
- **Cross-platform release workflow** — macOS (arm64 + x64 + universal), Windows (x64 + arm64), Linux (x64 + arm64).
- **Configuration cache support** — Full Gradle configuration cache compatibility.
- **Plugin version from Git tag** — No manual version management in `gradle.properties`.
