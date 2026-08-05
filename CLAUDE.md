# ComposeDeskKit (Nucleus)

A multi-module Gradle plugin and runtime library toolkit for shipping production-ready JVM desktop applications on macOS, Windows, and Linux.

## Project Structure

- `core-runtime` - Executable type detection, single instance, deep links, platform detection, app metadata (`NucleusApp`)
- `aot-runtime` - AOT cache mode detection for JDK 25+ (Project Leyden)
- `updater-runtime` - Auto-update engine (GitHub/S3), SHA-512 verification, progress tracking, update level detection, post-update events
- `freedesktop-icons` - Type-safe freedesktop Icon Naming Specification constants (shared by notification-linux and launcher-linux)
- `notification-linux` - Freedesktop Desktop Notifications API via JNI (D-Bus org.freedesktop.Notifications)
- `notification-windows` - Windows Toast Notifications API via JNI (WinRT)
- `launcher-windows` - Windows Launcher API via JNI (WinRT/COM) — badge notifications, jump lists (ICustomDestinationList), overlay icons, and thumbnail toolbar buttons (ITaskbarList3) on taskbar
- `launcher-linux` - Unity Launcher API via JNI (badge, progress, urgency, quicklist via com.canonical.Unity.LauncherEntry + com.canonical.dbusmenu)
- `taskbar-progress` - Native taskbar/dock progress bar and attention requests (Windows ITaskbarList3, macOS NSDockTile, Linux delegates to launcher-linux)
- `darkmode-detector` - Reactive OS dark mode detection via JNI
- `system-color` - Reactive system accent color and high contrast detection via JNI
- `energy-manager` - Energy efficiency & screen-awake APIs
- `native-ssl` / `native-http` / `native-http-okhttp` / `native-http-ktor` - OS trust store integration
- `linux-hidpi` - Native HiDPI scale detection on Linux
- `graalvm-runtime` - GraalVM native-image bootstrap
- `decorated-window-core` - Shared types, layout, styling (design-system agnostic)
- `decorated-window-tao` - **Default/recommended backend** — no-AWT window shell over the Rust `tao` crate via JNI (Metal on macOS, EGL on Linux, ANGLE/GLES on Windows), single native event-loop thread as `Dispatchers.Main`
- `decorated-window-jbr` - JBR-based implementation (requires JetBrains Runtime) — **legacy/maintenance-only**
- `decorated-window-jni` - JNI-based implementation (any JVM, GraalVM compatible) — **legacy/maintenance-only**
- `decorated-window-jewel` - Jewel (IntelliJ theme) integration
- `decorated-window-material2` - Material 2 color mapping
- `decorated-window-material3` - Material 3 color mapping
- `plugin-build/plugin` - Gradle plugin for packaging & distribution
- `examples/` - Demo & sample applications (consolidated): `nucleus-demo` (flagship), `tao-demo`, `jni-demo`, `jewel-demo`, `cmp-demo` (KMP), `scheduler-demo`, `service-management-demo`, `system-info-demo`, `fs-watcher-smoke`, `extra-launcher-demo`, `benchmark-demo` (JIT-vs-GraalVM-O3 CPU benchmark suite, with SwiftUI + Tauri ports under `ports/`), `gstreamer-demo` (Linux: GStreamer video into a `TextureView`, needs its own `build.sh` and the GStreamer dev packages), `mediafoundation-demo` (Windows counterpart: Media Foundation/DXVA video into a `TextureView`, needs its own `build.bat`), `avfoundation-demo` (macOS counterpart: AVFoundation/VideoToolbox video into a `TextureView`, needs its own `build.sh`), plus `shared` (Compose helper used by tao/jni demos)

## Build & Run

```bash
./gradlew :examples:nucleus-demo:run            # Run the flagship demo app
./gradlew packageDistributionForCurrentOS        # Package for current OS
./gradlew packageReleaseDistributionForCurrentOS # Release build with ProGuard
./gradlew preMerge                               # Full CI verification
./gradlew reformatAll                            # Format all code
```

## Key Technologies

- Kotlin 2.3+ with Compose Desktop 1.10+
- JNI for all native interop (no JNA in runtime modules)
- JBR (JetBrains Runtime) API for decorated-window-jbr
- Gradle 9+ with version catalog (`gradle/libs.versions.toml`)
- Detekt + KtLint for code quality

## Development Notes

- Target: JDK 17+ runtime, JDK 25+ recommended for AOT
- JNI code: be careful with macOS ARC/retain and weak references
- Native modules use platform-specific JNI implementations — test on each OS
- Plugin is published via included build in `plugin-build/`
- Version catalog is the source of truth for all dependency versions
- `decorated-window-tao` is the recommended backend for new projects (no AWT, native event-loop-driven, true Windows fullscreen, GraalVM native-image first-class). `decorated-window-jni` and `decorated-window-jbr` (the AWT-based backends) are legacy/maintenance-only
- macOS Liquid Glass enabled by default via `macOsSdkVersion = "26.0"` (vtool SDK patching)
- The HotSpot GC is selected type-safely with `application { garbageCollector = GarbageCollector.Z }` (unset = JVM ergonomics). The flags are prepended to the launcher `.cfg` java-options and to the `run` task — before `jvmArgs`, so an explicit `-XX:+Use…GC` there still wins — and the AOT training run inherits them from the `.cfg`

## Adding a Native JNI Module

When creating a new module with platform-specific JNI libraries, all steps below are required:

1. **Native source** — `<module>/src/main/native/{linux,macos,windows}/` with `build.sh`/`build.bat` + C/ObjC source. Library name: `nucleus_<feature>`. Linux: prefer `dlopen` over hard compile-time deps.
2. **Build output** — scripts must place binaries in `<module>/src/main/resources/nucleus/native/{linux-x64,linux-aarch64,darwin-x64,darwin-aarch64,win32-x64,win32-aarch64}/`. Build scripts must also clear the `NativeLibraryLoader` cache (`~/.cache/nucleus/native/<arch>/`) after compilation, otherwise the loader serves the stale cached copy instead of the freshly built library.
3. **Kotlin JNI bridge** — `internal object` using `NativeLibraryLoader.load()` with `@JvmStatic external` methods. Always provide a Kotlin fallback when native lib is unavailable.
4. **GraalVM reachability metadata** — create `<module>/src/main/resources/META-INF/native-image/dev.nucleusframework/nucleus.<module>/reachability-metadata.json` declaring all JNI-accessible classes/methods. Without this, native-image silently eliminates the bridge.
5. **CI build** (`build-natives.yaml`) — add one build step per platform job, gated with `if: steps.natives-cache.outputs.cache-hit != 'true'`, plus the library entries in that platform's `Verify ... natives` FILES list. Native outputs are cached keyed on `hashFiles('**/src/main/native/**', ...)`, so the new sources invalidate the cache automatically. Each platform job publishes a single merged artifact (`natives-windows`, `natives-macos`, `natives-linux-{x64,aarch64}`); consumer workflows fetch them all with one `pattern: 'natives-*'` download step and need **no changes** for a new module.
6. **CI verify lists** — add the 6 arch paths to the EXPECTED arrays of the "Verify all natives present" steps in `pre-merge.yaml` and `publish-maven.yaml`.

Common pitfalls: forgetting Linux `.so` in verify lists, missing `reachability-metadata.json`, forgetting the `cache-hit` guard on new build steps in `build-natives.yaml`.

## Publishing to Maven Local

Version is resolved from `GITHUB_REF` env var in every `build.gradle.kts` (`refs/tags/v1.3.0-beta-07` → `1.3.0-beta-07`). Without it, defaults to `1.0.0`.

**Prerequisites:**
- Use JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64`) — Kotlin DSL script compiler crashes on JDK 25
- Use `--no-configuration-cache` — configuration cache can serve a stale cached version
- Use absolute `-p` paths to avoid working-directory confusion
- No signing needed for local publish (signing is conditional on `signingInMemoryKey` property)

**Runtime libraries (main project):**
```bash
GITHUB_REF=refs/tags/v1.3.0-beta-07 JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 \
  ./gradlew -p /absolute/path/to/ComposeDeskKit publishToMavenLocal --no-configuration-cache
```

**Plugin (plugin-build):**
```bash
GITHUB_REF=refs/tags/v1.3.0-beta-07 JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 \
  ./gradlew -p /absolute/path/to/ComposeDeskKit/plugin-build :plugin:publishToMavenLocal --no-configuration-cache
```

Version format: `1.3.0-beta-XX` (hyphen before number, e.g. `1.3.0-beta-07`).

## GraalVM Native Image

- Reflection metadata is centralized in 3 levels — users no longer copy hundreds of entries:
    - **L1**: Generic cross-platform metadata shipped in `graalvm-runtime` JAR (`reachability-metadata.json` with ~300+ types)
    - **L2**: Oracle GraalVM Reachability Metadata Repository — auto-resolved for classpath deps (enabled by default, `metadataRepository {}` DSL)
    - **L3**: Platform-specific metadata (macOS/Windows/Linux) shipped inside the plugin JAR under `nucleus/graalvm/platform-metadata/`
- `graalvm-runtime` auto-includes `.svg`, `.ttf`, `.otf`, `composeResources/*`, `nucleus/native/*`, and `META-INF/services/*` via `reachability-metadata.json` resource globs (the deprecated `-H:IncludeResources` option was dropped). The blanket `**/*.{svg,ttf,otf}` globs are a required catch-all for fonts/icons bundled inside **library** JARs (e.g. Jewel SVG icons) — those are not the app's own resources so `autoIncludeResources` doesn't cover them. They knowingly trigger native-image's advisory "pattern too generic" warning; do not remove them (it breaks Jewel icons in native image)
- The tracing agent (`runWithNativeAgent`) is only needed for app-specific reflection, uncommon libraries, and resource bundles
- PGO (Oracle GraalVM): `runWithPgoInstrument` builds + runs an instrumented image and records `graalvm/pgo/default.iprof` on exit; later native-image builds apply the profile automatically. Opt out with `-Pnucleus.graalvm.pgo=off`; customize via `graalvm { pgo { enabled / profile } }`
- Agent output is automatically deduplicated against library metadata on the classpath
- Sample apps have near-empty `reachability-metadata.json` — only app-specific entries remain
- `GraalVmInitializer.initialize()` must be the first call in `main()` for native-image builds
- Font substitutions (`@TargetClass`) in `graalvm-runtime` fix `InternalError: platform encoding not initialized` on Windows/Linux
- SLF4J is **not** initialized at build time — the API and the app-selected backend both initialize at run time, so the app keeps control of its provider, levels and environment-dependent config. Forcing `--initialize-at-build-time=org.slf4j` from a shared module breaks any run-time-initialized backend (SLF4J 2.x provider discovery parks Logback's `LogbackMDCAdapter`/`LoggerContext` in the image heap → build failure; adding backend classes one by one only exposes the next object). Apps with a fixed backend can opt in via `graalvm { buildArgs.add("--initialize-at-build-time=org.slf4j") }` — it trades a frozen provider and build-machine-captured config for a cheaper first log call. `examples/tao-native-test` bundles Logback + an `MDC` round-trip as the regression fixture
- GraalVM task surface mirrors the JVM one: `runGraalvmNative` is the fast dev loop (forces quick-build `-Ob`, ignoring the configured `optimization`), while `createGraalvmNativeDistributable` / `runGraalvmNativeDistributable` / `packageGraalvmNativeDistributionForCurrentOS` build & run the full app folder with the configured optimization (mirror `createDistributable` / `runDistributable` / `packageDistributionForCurrentOS`). Quick vs distributable is detected from the invoked task name and tracked as a compile input, so switching re-compiles
- Native images bake a default max heap of 25% of RAM (`-R:MaximumHeapSizePercent=25`, JVM/HotSpot parity) instead of native-image's Serial-GC default of 80%; configurable via `graalvm { maxHeapSizePercent = N }` or an absolute `graalvm { maxHeapSize = "2g" }`, and always overridable at runtime with `-Xmx`
- The image's GC is baked at build time via `graalvm { garbageCollector = NativeImageGarbageCollector.G1 }` (`--gc=`, unset = native-image's Serial GC). `G1` is Oracle GraalVM + Linux only and degrades to a warning plus the Serial GC anywhere else; the baked heap percentage follows the collector (`-R:MaximumHeapSizePercent` for Serial/Epsilon, `-R:MaxRAMPercentage` for G1, which does not know the former)
- The GraalVM toolchain is auto-downloaded by default (`graalvm { toolchain { } }` DSL), but only when `graalvm { isEnabled = true }` and only when a native-image task actually runs — every provider is resolved in `doFirst`, so an IDE sync or `gradlew tasks` never pulls a JDK. Cached under `~/.gradle/nucleus/graalvm/`.
- **Distribution defaults to GraalVM Community Edition** (`toolchain { distribution }`, GPLv2+CE, resolved from the `graalvm/graalvm-ce-builds` GitHub releases). `GraalvmDistribution.ORACLE` opts into Oracle GraalVM and logs a GFTC licensing warning — the GFTC forbids charging any fee associated with redistributing the Program, and the plugin ships GraalVM runtime libs (`libjvm`, `libawt`, …) next to the executable. In community mode the Oracle-only `runWithPgoInstrument` task is **not registered at all**; `-O3`, `--pgo` and `-H:AdvancedObfuscation` degrade to a warning. `examples/benchmark-demo` opts into ORACLE because `-O3`/PGO are its whole point.
- Install dirs embed the distribution (`graalvm-community-jdk-*` vs `graalvm-jdk-*`), so a pre-existing Oracle download is never silently reused after the default flipped; a `GRAALVM_HOME` whose distribution disagrees with the DSL is ignored with a warning. The CI cache key includes the distribution too.
- Channel/version: innovation by default (`25i2`), `channel = GraalvmChannel.LTS` or an explicit `version` ("25", "25.0.1"). On Intel macs (dropped by both distributions after 25.0.1) it falls back to Liberica NIK via the BellSoft API — only the JDK feature version carries over there (BellSoft ships the LTS line only, so Intel macs get NIK 25.0.x even on the innovation channel). `toolchain { autoDownload = false }` restores Gradle toolchain resolution via `javaLanguageVersion`/`jvmVendor`.
