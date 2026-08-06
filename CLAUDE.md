# Nucleus

A multi-module Gradle plugin and runtime library toolkit for shipping production-ready JVM desktop applications on macOS, Windows, and Linux.

Published releases are `2.5.x` (latest tag `v2.5.18`). Development happens on `nucleus-3.0` (the default branch, next release `3.0.0`); `main` only receives shipped release lines. Do not treat `IDEAL_API.md` as current — that file is gone; the real entry point is `nucleusApplication(args) { }` in `nucleus-application`. Plugin-injected strings are `NucleusApp`, not a generated `NucleusGenerated` object.

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a PR or reviewing one: PRs target `nucleus-3.0` (never `main`), every fix needs a minimal failing example, and every feature needs a prior GitHub discussion.

Deep dives (why a mechanism is built the way it is, measurements, E2E) live in [`docs/internals/`](docs/internals/); this file keeps the rules and a link. Extend the doc there, not this file.

## Project Structure

- `nucleus-application` - `nucleusApplication`, `DecoratedWindow` / `HostedWindow`, `onDeepLink`, `aotTraining`
- `core-runtime` - Executable type detection, single instance, deep links, platform detection, app metadata (`NucleusApp`)
- `aot-runtime` - AOT cache mode detection for JDK 25+ (Project Leyden)
- `updater-runtime` / `updater-testing` - Auto-update engine (GitHub/S3/local directory), SHA-512, delta/blockmap, progress, update level, post-update events, Windows NSIS hot update (see Development Notes), and update testing without publishing (see Development Notes); `updater-testing` ships `UpdateFeedServer`, the fault-injecting loopback release host
- `freedesktop-icons` - Type-safe freedesktop Icon Naming Specification constants (shared by notification-linux and launcher-linux)
- `sf-symbols` - Type-safe SF Symbols catalog
- `notification-common` - Cross-platform notification DSL with per-platform option blocks
- `notification-macos` - macOS User Notifications
- `notification-linux` - Freedesktop Desktop Notifications API via JNI (D-Bus org.freedesktop.Notifications)
- `notification-windows` - Windows Toast Notifications API via JNI (WinRT)
- `launcher-macos` - macOS Dock API — badge, menus
- `launcher-windows` - Windows Launcher API via JNI (WinRT/COM) — badge notifications, jump lists (ICustomDestinationList), overlay icons, and thumbnail toolbar buttons (ITaskbarList3) on taskbar
- `launcher-linux` - Unity Launcher API via JNI (badge, progress, urgency, quicklist via com.canonical.Unity.LauncherEntry + com.canonical.dbusmenu)
- `menu-macos` - Native macOS menu bar
- `media-control` - OS media controls — MPRIS (Linux), Now Playing (macOS), SMTC (Windows)
- `global-hotkey` - System-wide keyboard shortcuts. `HotKeyEventListener` gets PRESSED / RELEASED, `isRepeat` and the portal's Wayland `activationToken`; `TaoWindow.focus()` takes that token on its own through `core-runtime`'s `ActivationToken` mailbox. Mechanism and E2E (#739): [docs/internals/global-hotkey.md](docs/internals/global-hotkey.md)
- `taskbar-progress` - Native taskbar/dock progress bar and attention requests (Windows ITaskbarList3, macOS NSDockTile, Linux delegates to launcher-linux)
- `taskbar-progress-tao` - Taskbar progress on the Tao backend
- `darkmode-detector` - Reactive OS dark mode detection via JNI
- `system-color` - Reactive system accent color and high contrast detection via JNI
- `system-info` - CPU, memory, GPU, temperature, network, processes
- `energy-manager` - Energy efficiency & screen-awake APIs
- `autolaunch` - Start at login (Win32/MSIX/SMAppService/systemd/Flatpak portal)
- `scheduler` / `scheduler-testing` - OS-scheduled background tasks (Task Scheduler / launchd / systemd) + test doubles
- `fs-watcher` - Native filesystem watcher over the Rust `notify` crate, one native watcher per `FsWatcher`. Per-OS root spelling, FSEvents normalization (#570) and rename semantics: [docs/internals/fs-watcher.md](docs/internals/fs-watcher.md)
- `share` - Native share sheet, the repo's first **Kotlin Multiplatform library** (Android / iOS / web in pure Kotlin, only `jvm` goes through a Rust JNI crate). Module layout, desktop parents, the Wayland `keepAlive` and detekt in a KMP module: [docs/internals/share.md](docs/internals/share.md)
- `service-management-macos` - macOS `SMAppService` — login items, launch agents, daemons
- `spellcheck` - Native spell checking for `nucleusApplication` text fields (Linux hunspell via JNI, macOS `NSSpellChecker`, Windows `ISpellChecker`); no-op when the library or dictionary is missing
- `native-ssl` / `native-http` / `native-http-okhttp` / `native-http-ktor` - OS trust store integration
- `native-proxy` - OS proxy configuration via JNI (Windows: WinHTTP/WPAD/PAC; macOS: SCDynamicStore/CFNetwork PAC; Linux: GSettings/KDE/env)
- `linux-hidpi` - Native HiDPI scale detection on Linux
- `graalvm-runtime` - GraalVM native-image bootstrap
- `decorated-window-core` - Shared types, layout, styling (design-system agnostic)
- `decorated-window-tao` - **The only window backend** — no-AWT window shell over the Rust `tao` crate via JNI (Metal on macOS, EGL on Linux, ANGLE/GLES on Windows), single native event-loop thread as `Dispatchers.Main`. Linux/Wayland resize frame timing (#444): the in-frame `draw` hook, its watchdog, and why the opaque region always leaves out its bottom row: [docs/internals/tao-linux-frame-timing.md](docs/internals/tao-linux-frame-timing.md)
- `decorated-window-tao` internals: `window/tao/workspace/` is the shared core behind satellites / `DockLayout` and Chrome-like tabs (cross-window drag, Wayland transfer drag, drop previews, ranks, `dockSides` / `floatable` / `reorderable`). Put new cross-window gestures there rather than duplicating geometry or drag bookkeeping, and branch on `TaoWindow.canPlaceOnScreen`, never `isNativeWaylandSurface`: [docs/internals/tao-workspace.md](docs/internals/tao-workspace.md)
- `decorated-window-jewel` - Jewel (IntelliJ theme) integration
- `decorated-window-material2` - Material 2 color mapping
- `decorated-window-material3` - Material 3 color mapping
- `plugin-build/plugin` - Gradle plugin for packaging & distribution
- `buildSrc` - Build-only convention plugins (`nucleus.native-module`: the shared `buildNative*` wiring for every JNI module)
- `examples/` - **`lab/` is the test bench** (`./gradlew :examples:lab:app:run`, JVM 25) and the only app a release publishes, as a GraalVM native image. Probe anatomy (Contract + Gateway + ViewModel + Probe, Metro DI), fixtures, design system, release specifics and the other example apps: [docs/internals/lab.md](docs/internals/lab.md)

## Build & Run

```bash
./gradlew :examples:lab:app:run                  # Run the Lab (test bench app, JVM 25)
./gradlew packageDistributionForCurrentOS        # Package for current OS
./gradlew packageReleaseDistributionForCurrentOS # Release build with ProGuard
./gradlew preMerge                               # Full CI verification
./gradlew reformatAll                            # Format all code
```

## Key Technologies

- Kotlin 2.4 with Compose Multiplatform 1.12
- JNI for all native interop (no JNA in runtime modules)
- Gradle 9.8 with version catalog (`gradle/libs.versions.toml`)
- Detekt + KtLint for code quality

## Development Notes

- Target: JDK 17+ runtime, JDK 25+ recommended for AOT
- JNI code: be careful with macOS ARC/retain and weak references
- Native modules use platform-specific JNI implementations — test on each OS
- Plugin is published via included build in `plugin-build/`
- Version catalog is the source of truth for all dependency versions
- **Compose window API v2**: Compose 1.12's `androidx.compose.ui.window.v2` types are hard-wired to AWT (`Screen` wraps a `GraphicsDevice`, `WindowGeometryProviderScope` takes a displayable `java.awt.Window`), so they are **not accepted** by any Nucleus window API — a half-working surface (scoped providers and `requestScreen` inert) is worse than none. The supported v2 surface is `dev.nucleusframework.window.tao.v2`, a member-for-member AWT-free clone backed by `TaoMonitors` + `TaoWindow`: migrating from the Compose package is a single import change, and deleting the clone restores the upstream import if JetBrains decouples its own types. Multi-monitor geometry comes from `TaoMonitors` (`EnumDisplayMonitors` / `NSScreen.screens` / GDK), never `GraphicsEnvironment`
- **No reflection**: runtime modules must stay GraalVM native-image compatible, so reflection is not an acceptable implementation tool — not even with a graceful fallback. Reach for a static bridge instead (e.g. a friend-package Java accessor like `androidx.compose.ui.draganddrop.TaoTransferableAccess`, which reads Kotlin `internal` members through their `$ui`-mangled JVM names), a public API of our own, or a plugin bytecode transform. A feature that can only be built reflectively is a feature we do not ship: document the gap and offer a working alternative
- **Public API freeze**: root `build.gradle.kts` applies kotlinx binary-compatibility-validator + `explicitApi()` to every non-example module. Baselines live in `<module>/api/<module>.api`. After intentional public API changes run `./gradlew apiDump` and commit the dump; `apiCheck` (wired into `check` / `preMerge`) fails on accidental ABI drift. Exception: `decorated-window-jewel` (JVM 25) is ignored by BCV until ASM supports class-file 69 — still uses `explicitApi()`. Helper: `scripts/fix-explicit-api.py` for mechanical visibility/return-type fixes from kotlinc diagnostics. **Experimental surface**: the satellite / dock family (`Satellite`, `SatelliteWindow`, `SatelliteWorkspace`, `SatellitePlacement`, `DockLayout`, `DockSplitterScope`, …) and the Chrome-like tab family (`Tab`, `TabWindows`, `TabWorkspace`, `TabStrip`, `TabStripScope`, …), in both `decorated-window-tao` and `nucleus-application`, are marked `@ExperimentalNucleusApi` (`dev.nucleusframework.window`, lives in `decorated-window-core` so every consumer sees it; opt-in level ERROR). Library modules and the demos opt in module-wide with `compilerOptions { optIn.add("dev.nucleusframework.window.ExperimentalNucleusApi") }`; new public satellite/dock/tab declarations must carry the marker.
- **KDoc on public API**: `UndocumentedPublicClass` / `UndocumentedPublicFunction` are enforced by detekt (`detekt` is wired into `check` / `preMerge`). Pre-existing gaps are grandfathered in per-module `<module>/detekt-baseline.xml` files — any *new* undocumented public class or function fails the build. Do not regenerate a baseline to silence a new finding; write the KDoc. `UndocumentedPublicProperty` stays off because the generated icon/symbol catalogs (`sf-symbols`, `freedesktop-icons`) would swamp it
- **Logging**: `java.util.logging` is the single facade for every runtime module — no SLF4J dependency forced on consumers, no raw `println` / `System.err` in `src/main`. Logger names must be the fully-qualified class name (or an explicit `dev.nucleusframework.*` string) so the whole framework sits under one JUL namespace. `allowNucleusRuntimeLogging = true` is an opt-in convenience that raises the `dev.nucleusframework` logger to `nucleusLoggingLevel` and attaches a colored console handler; apps that configure JUL themselves (`logging.properties`, `jul-to-slf4j`) leave it `false` and Nucleus never touches the JUL configuration
- `decorated-window-tao` is the only window backend (no AWT, native event-loop-driven, true Windows fullscreen, GraalVM native-image first-class). The AWT-based backends (`decorated-window-awt` / `-jbr` / `-jni`), `NucleusBackend`, `LocalNucleusBackend`, the `backend =` parameter of `nucleusApplication`, and `NucleusWindowUnsafe.awtWindow` / `awtDialog` were all removed in 3.0. Compose Desktop's AWT `Window` / `Dialog` / `Tray` are unsupported — use `DecoratedWindow`, `HostedWindow` / `HostedDialog`, and an AWT-free tray
- **Event-loop watchdog** (#643, Windows): `TaoEventLoopWatchdog` polls `IsHungAppWindow` and raises `onUnresponsive` / `onResponsive`; the framework ships no UI of its own. Never probe the loop with a sent message (the #640 deadlock). Switches and E2E: [docs/internals/tao-event-loop-watchdog.md](docs/internals/tao-event-loop-watchdog.md)
- **Trackpad on Tao** (#652–#654, #660, #706): two-finger swipe arrives as `PanStart` / `PanMove` / `PanEnd`, pinch as `Scale*`, rotation as two synthetic Touch contacts; a handler listening only for `Scroll` must also handle Pan. Windows precision touchpads go through a DirectManipulation viewport per window (`-Dnucleus.tao.directManipulation=false` restores the Ctrl+wheel emulation). Ownership rules between gestures, GDK differences, headful coverage: [docs/internals/tao-trackpad.md](docs/internals/tao-trackpad.md)
- **Windows presentation = blt + DirectComposition mirror**: do **not** replace the blt present with a flip chain (tried, measured, reverted: trembling resize, white bands). Why, how the off-screen mirror works, E2E: [docs/internals/tao-windows-presentation.md](docs/internals/tao-windows-presentation.md)
- **Partial redraw** (#755, **opt-in** `nucleusOptimization { partialRedraw = true }`): repaints and presents only the damaged render layers, through a plugin patch of Compose internals; anything uncertain repaints in full. Per-OS mechanics, switches, verify oracle: [docs/internals/partial-redraw.md](docs/internals/partial-redraw.md)
- **glibc malloc arena cap** (#757, Linux; **opt-in** with `nucleusOptimization { mallocArenas = true }`, also on under the master): `capLinuxMallocArenas()` (`linux-hidpi`, same JNI library as the HiDPI detection — the one `GraalVmInitializer` already loads first on Linux) calls `mallopt(M_ARENA_MAX, 2)`, resolved through `dlsym` and gated on `gnu_get_libc_version` (musl untouched). glibc's default of 8 × cores arenas cost ~900 MB RSS in SeforimApp. The plugin adds `-Dnucleus.optimization.mallocArenaMax=2` (an explicit value in `jvmArgs` wins, `0` disables) and bakes `optimization.mallocArenaMax` into `nucleus-app.properties` for native images. A `MALLOC_ARENA_MAX` / `glibc.malloc.arena_max` tunable in the environment always wins. Must run before threads multiply: glibc latches its own limit once more than 8 arenas exist
- macOS Liquid Glass enabled by default via `macOsSdkVersion = "26.0"` (vtool SDK patching)
- **Windows NSIS hot update** (every NSIS installer of a JVM app, per-user installs): the app never leaves the screen while it updates. PowerShell scripts are written with a UTF-8 BOM (`writePowerShellScript`). Layout, handoff, multi-instance model, E2E: [docs/internals/windows-nsis-hot-update.md](docs/internals/windows-nsis-hot-update.md)
- **Testing updates without publishing**: `nucleus.updater.feedUrl`, `nucleus.updater.simulate`, the `serveUpdateFeed` task and `updater-testing`'s `UpdateFeedServer`. Installed apps honour launch overrides only with `UpdaterConfig.allowLaunchOverrides`; an unpackaged run never installs: [docs/internals/updater-testing.md](docs/internals/updater-testing.md)
- **PKG has two channels** (`macOS { pkg { appStore } }`): App Store (sandboxed, never notarized) vs Developer ID (#249). Gate sandbox-sensitive features on `ExecutableRuntime.isSandboxed()`, never on `isPkg()`. Signing identity, install-script shims: [docs/internals/macos-pkg.md](docs/internals/macos-pkg.md)
- **Node.js is provisioned, not required**: the plugin downloads Node for electron-builder into `<gradle-user-home>/nucleus/nodejs`, so CI runs no `actions/setup-node`. Precedence, and the `universal-macos` mirror script: [docs/internals/nodejs-provisioning.md](docs/internals/nodejs-provisioning.md)
- **Windows signing covers the app image, not only the installer** (#748): `WindowsAppImageSigner` signs launchers and DLLs (loose and inside JARs) before electron-builder runs: [docs/internals/windows-signing.md](docs/internals/windows-signing.md)
- **AOT cache vs installers** (#748 follow-up): the JDK refuses the whole AOT cache when any JAR's size or mtime differs from training, so JAR mtimes are pinned and nothing may rewrite a JAR after training. NSIS pinning, JVMCI on GraalVM runtimes: [docs/internals/aot-cache-installers.md](docs/internals/aot-cache-installers.md)
- **ProGuard consumer rules** (**opt-in** `proguard { consumerRules = true }`): library `META-INF/proguard/*` rules and `META-INF/services` providers are fed to standalone ProGuard, through an option allowlist. Guard rules, exclusions, measurements: [docs/internals/proguard-consumer-rules.md](docs/internals/proguard-consumer-rules.md)
- The HotSpot GC is selected type-safely with `application { garbageCollector = GarbageCollector.Z }` (unset = JVM ergonomics). The flags are prepended to the launcher `.cfg` java-options and to the `run` task — before `jvmArgs`, so an explicit `-XX:+Use…GC` there still wins — and the AOT training run inherits them from the `.cfg`

## Adding a Native JNI Module

When creating a new module with platform-specific JNI libraries, all steps below are required:

1. **Native source** — `<module>/src/main/native/{linux,macos,windows}/` with `build.sh`/`build.bat` + C/ObjC source. Library name: `nucleus_<feature>`. Linux: prefer `dlopen` over hard compile-time deps. Rust modules keep the crate at `src/main/native/` (`Cargo.toml` + `src/`) and only the launcher script under the per-OS directory.
2. **Build output** — scripts must place binaries in `<module>/src/main/resources/nucleus/native/{linux-x64,linux-aarch64,darwin-x64,darwin-aarch64,win32-x64,win32-aarch64}/`.
3. **Gradle wiring** — apply `id("nucleus.native-module")` and declare one line per platform. That is the whole build-script surface: the convention plugin (`buildSrc/src/main/kotlin/dev/nucleusframework/gradle/NativeModulePlugin.kt`) registers `buildNative{Windows,MacOs,Linux}` with the source inputs, the resources output, the host-OS and prebuilt-on-CI guards, the absolute build-script path, the `NativeLibraryLoader` cache eviction, and the `processResources` / `sourcesJar` dependencies.
   ```kotlin
   nucleusNative {
       macos("nucleus_feature")    // → libnucleus_feature.dylib
       linux("nucleus_feature")    // → libnucleus_feature.so
       windows("nucleus_feature")  // → nucleus_feature.dll
   }
   ```
4. **Kotlin JNI bridge** — `internal object` using `NativeLibraryLoader.load()` with `@JvmStatic external` methods. Always provide a Kotlin fallback when native lib is unavailable. Native code that must drop a pending JNI exception (a Kotlin callback that threw) calls `nucleus_jni_clear_exception(env)` from `native-common/nucleus_jni.h` — never a bare `ExceptionClear`.
5. **GraalVM reachability metadata** — create `<module>/src/main/resources/META-INF/native-image/dev.nucleusframework/nucleus.<module>/reachability-metadata.json` declaring all JNI-accessible classes/methods. Without this, native-image silently eliminates the bridge.
6. **CI build** (`build-natives.yaml`) — add one build step per platform job, gated with `if: steps.natives-cache.outputs.cache-hit != 'true'`, plus the library entries in that platform's `Verify ... natives` FILES list. Native outputs are cached keyed on `hashFiles('**/src/main/native/**', ...)`, so the new sources invalidate the cache automatically. Each platform job publishes a single merged artifact (`natives-windows`, `natives-macos`, `natives-linux-{x64,aarch64}`); consumer workflows fetch them all with one `pattern: 'natives-*'` download step and need **no changes** for a new module.
7. **CI verify list** — add the 6 arch paths to the EXPECTED array of the "Verify all natives present" step in `publish-maven.yaml` (PRs rely on the per-platform `Verify ... natives` lists of `build-natives.yaml`).

Common pitfalls: forgetting Linux `.so` in verify lists, missing `reachability-metadata.json`, forgetting the `cache-hit` guard on new build steps in `build-natives.yaml`, swallowing JNI exceptions with a silent `ExceptionClear`.

Existing `build.sh`/`build.bat` scripts also clear the `NativeLibraryLoader` cache themselves so a bare `./build.sh` (outside Gradle) is safe; new scripts don't have to, since `nucleus.native-module` does it after every run.

## Publishing to Maven Local

```bash
./gradlew publishDevToMavenLocal --no-configuration-cache
```

Publishes every runtime module and the plugin with version `dev`. The version is otherwise resolved from `GITHUB_REF` in every `build.gradle.kts` (`refs/tags/v3.0.0` → `3.0.0`, defaults to `1.0.0`); set it yourself and run `publishAllToMavenLocal` for another version.

- Run Gradle with JDK 25 (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`): the Metro Gradle plugin (`dev.zacsweers.metro` 1.4.5) requires a JVM 21+, so JDK 17 fails at configuration, and `java-21-openjdk-amd64` on this machine is a JRE
- Use `--no-configuration-cache`: the configuration cache can serve a stale version
- No signing needed locally (signing is conditional on the `signingInMemoryKey` property)

## Dev releases (unverified)

A tag `v<major>.<minor>.<patch>-dev-<id>` (convention: `v3.0.0-dev-YYYYMMDDHHMM`, UTC, the
`tag-dev` skill cuts it) publishes the runtime modules to Maven Central and the plugin to the
Gradle Plugin Portal **without running `preMerge`** — no tests, no `apiCheck`, no detekt; only
the compile/javadoc/sign graph the publish tasks themselves pull in. Natives are still built and
verified, since the JARs would be unusable otherwise. Their GitHub release is a
pre-release (never marked latest, generic notes). It is created only by
`publish-maven.yaml`'s `github-prerelease` job, once the Central publish succeeded.
`release-desktop` also runs on dev tags, as a separate workflow beside `publish-maven` (the Maven
deploy never waits for the packaging matrix): its publish job waits up to 60 min for that
pre-release and attaches the Lab (native image, JVM on Windows ARM, `alpha` update channel); if
the Maven deploy failed, no pre-release appears and nothing is published.

`.github/actions/release-tag-info` is the single place that classifies a tag: it rejects anything
that is not `v<semver>` (every publish task derives its version with
`GITHUB_REF.removePrefix("refs/tags/v")`, so a `dev-2026…` tag would have published a version
literally named `refs/tags/dev-2026…`) and exposes `is-dev`, which gates the `preMerge` step in
both publish workflows. Dev tags can be cut from any branch — `validate-release-ref` only
constrains `alpha`/`beta`/`rc`, and it derives the branch they must live on from the tag itself
(`v3.0.0-rc.1` → `nucleus-3.0`) rather than pinning one that goes stale each release line.

`3.0.0-dev-<ts>` orders below `3.0.0` for Gradle and Maven, so a dev build never shadows the real
release. The versions are immutable on Central: never retag, bump the timestamp.

## GraalVM Native Image

- Reflection metadata is centralized in 3 levels — users no longer copy hundreds of entries:
    - **L1**: Generic cross-platform metadata shipped in `graalvm-runtime` JAR (`reachability-metadata.json` with ~300+ types)
    - **L2**: Oracle GraalVM Reachability Metadata Repository — auto-resolved for classpath deps (enabled by default, `metadataRepository {}` DSL)
    - **L3**: Platform-specific metadata (macOS/Windows/Linux) shipped inside the plugin JAR under `nucleus/graalvm/platform-metadata/`
- GraalVM Deb/Rpm/Pacman packages honor `linux { afterInstall / afterRemove / beforeInstall / beforeRemove }` the same as JVM jpackage/electron-builder. User scripts are concatenated after Nucleus templates. electron-builder substitutes `${sanitizedProductName}` and `${executable}` only when those tokens are single-quoted (`'${sanitizedProductName}-daemon.service'`); double-quoted `"${sanitizedProductName}"` is left unsubstituted and systemd hooks silently no-op. Pacman `.INSTALL` `pre_remove` does not get deb-style `$1=upgrade`, so stop/disable the unit unconditionally and let after-install re-enable on upgrade.
- `graalvm { headless = true }` is for daemons/CLIs: skips L3 AWT/Java2D platform metadata, skips always-on L1 packs (`jdk-awt`, `jdk-fonts`, `jdk-graphics2d`, Skiko/Compose/tray), skips copying companion GUI native libs (`libawt`, `libfontmanager`, Skiko, …), and bakes `-Djava.awt.headless=true`. Default `false` (GUI). Without this, JNI registration of AWT types makes `native-image` pull `libawt`/`libawt_xawt` even when app code never references `java.awt`.
- `graalvm-runtime` auto-includes `.svg`, `.ttf`, `.otf`, `composeResources/*`, `nucleus/native/*`, and `META-INF/services/*` via `reachability-metadata.json` resource globs (the deprecated `-H:IncludeResources` option was dropped). The blanket `**/*.{svg,ttf,otf}` globs are a required catch-all for fonts/icons bundled inside **library** JARs (e.g. Jewel SVG icons) — those are not the app's own resources so `autoIncludeResources` doesn't cover them. They knowingly trigger native-image's advisory "pattern too generic" warning; do not remove them (it breaks Jewel icons in native image)
- **Nucleus JNI libraries ship loose, never extracted at run time** (jpackage and GraalVM): only the libraries listed in `META-INF/nucleus/native-libraries/` manifests move; Skiko is not embedded twice in a native image (#821): [docs/internals/native-libraries-layout.md](docs/internals/native-libraries-layout.md)
- The tracing agent (`runWithNativeAgent`) is only needed for app-specific reflection, uncommon libraries, and resource bundles
- PGO (Oracle GraalVM): `runWithPgoInstrument` builds + runs an instrumented image and records `graalvm/pgo/default.iprof` on exit; later native-image builds apply the profile automatically. Opt out with `-Pnucleus.graalvm.pgo=off`; customize via `graalvm { pgo { enabled / profile } }`
- Agent output is automatically deduplicated against library metadata on the classpath
- Sample apps have near-empty `reachability-metadata.json` — only app-specific entries remain
- `nucleusApplication` calls `GraalVmInitializer.initialize()` first. If you write a `main` that does not go through `nucleusApplication`, call `GraalVmInitializer.initialize()` yourself before anything else (required for native-image). Do not tell users to sequence GraalVM / single-instance / autolaunch / AUMID by hand — that bootstrap is already inside `nucleusApplication`. `Dispatchers.Main` on Tao is installed via `TaoMainDispatcherFactory` (ServiceLoader), not a manual `setMain`.
- Font substitutions (`@TargetClass`) in `graalvm-runtime` fix `InternalError: platform encoding not initialized` on Windows/Linux
- SLF4J is **not** initialized at build time — the API and the app-selected backend both initialize at run time, so the app keeps control of its provider, levels and environment-dependent config. Forcing `--initialize-at-build-time=org.slf4j` from a shared module breaks any run-time-initialized backend (SLF4J 2.x provider discovery parks Logback's `LogbackMDCAdapter`/`LoggerContext` in the image heap → build failure; adding backend classes one by one only exposes the next object). Apps with a fixed backend can opt in via `graalvm { buildArgs.add("--initialize-at-build-time=org.slf4j") }` — it trades a frozen provider and build-machine-captured config for a cheaper first log call. `examples/tao-native-test` bundles Logback + an `MDC` round-trip as the regression fixture
- GraalVM task surface mirrors the JVM one: `runGraalvmNative` is the fast dev loop (forces quick-build `-Ob`, ignoring the configured `optimization`), while `createGraalvmNativeDistributable` / `runGraalvmNativeDistributable` / `packageGraalvmNativeDistributionForCurrentOS` build & run the full app folder with the configured optimization (mirror `createDistributable` / `runDistributable` / `packageDistributionForCurrentOS`). Quick vs distributable is detected from the invoked task name and tracked as a compile input, so switching re-compiles
- Native images bake a default max heap of 25% of RAM (`-R:MaximumHeapSizePercent=25`, JVM/HotSpot parity) instead of native-image's Serial-GC default of 80%; configurable via `graalvm { maxHeapSizePercent = N }` or an absolute `graalvm { maxHeapSize = "2g" }`, and always overridable at runtime with `-Xmx`
- The image's GC is baked at build time via `graalvm { garbageCollector = NativeImageGarbageCollector.G1 }` (`--gc=`, unset = native-image's Serial GC). `G1` requires Oracle GraalVM (CE/NIK/Mandrel ship no G1 at all) and, outside Linux, GraalVM **25.4+** — 25.3 advertises `--gc=G1` and ships `g1GCStructs.h` but not `g1gc-cr.lib`, so the build dies at link time; anything unsupported degrades to a warning plus the Serial GC; the baked heap percentage follows the collector (`-R:MaximumHeapSizePercent` for Serial/Epsilon, `-R:MaxRAMPercentage` for G1, which does not know the former)
- The GraalVM toolchain is auto-downloaded by default (`graalvm { toolchain { } }` DSL), but only when `graalvm { isEnabled = true }` and only when a native-image task actually runs — every provider is resolved in `doFirst`, so an IDE sync or `gradlew tasks` never pulls a JDK. Cached under `~/.gradle/nucleus/graalvm/`.
- **Distribution defaults to GraalVM Community Edition** (`toolchain { distribution }`, GPLv2+CE, resolved from the `graalvm/graalvm-ce-builds` GitHub releases). `GraalvmDistribution.ORACLE` opts into Oracle GraalVM and logs a GFTC licensing warning — the GFTC forbids charging any fee associated with redistributing the Program, and the plugin ships GraalVM runtime libs (`libjvm`, `libawt`, …) next to the executable. In community mode the Oracle-only `runWithPgoInstrument` task is **not registered at all**; `-O3`, `--pgo` and `-H:AdvancedObfuscation` degrade to a warning. `examples/benchmark-demo` opts into ORACLE because `-O3`/PGO are its whole point.
- Install dirs embed the distribution (`graalvm-community-jdk-*` vs `graalvm-jdk-*`), so a pre-existing Oracle download is never silently reused after the default flipped; a `GRAALVM_HOME` whose distribution disagrees with the DSL is ignored with a warning. CI does not cache the toolchain (see [docs/internals/nodejs-provisioning.md](docs/internals/nodejs-provisioning.md)).
- Channel/version: innovation by default (`25i4` / GraalVM 25.4.4.1.1), `channel = GraalvmChannel.LTS` or an explicit `version` ("25", "25.0.1"). On Intel macs (dropped by both distributions after 25.0.1) it falls back to Liberica NIK via the BellSoft API — only the JDK feature version carries over there (BellSoft ships the LTS line only, so Intel macs get NIK 25.0.x even on the innovation channel). `toolchain { autoDownload = false }` restores Gradle toolchain resolution via `javaLanguageVersion`/`jvmVendor`.
