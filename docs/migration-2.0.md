# Migration from Nucleus 1.x to 2.0

Nucleus 2.0 is a major release that consolidates the framework around a single entry point — `nucleusApplication` — and renames the project namespace to `dev.nucleusframework`. The migration is mostly mechanical: search/replace for the namespace, then move your `application { }` block into `nucleusApplication(args) { }`. The DSL that emerges removes ~30 lines of bootstrap boilerplate from a typical `main()`.

This guide walks through the changes in order. Apply them top-to-bottom.

---

## Prerequisites

Before touching any code, bump these — 2.0 will not resolve otherwise.

### JDK toolchain

2.0 artifacts target JDK 17 (`nucleus-application`) and JDK 25 (`decorated-window-jewel`, the Jewel/IntelliJ stack). Bump every Kotlin module that depends on Nucleus:

```kotlin
// Single-target modules
kotlin { jvmToolchain(25) }

// KMP modules — set it on the top-level kotlin block (applies to the jvm() target)
kotlin {
    jvmToolchain(25)
    jvm()
    androidLibrary { … } // Android compilations still produce JVM 11 bytecode via their own jvmTarget
}
```

Symptom if you skip this: `Dependency resolution is looking for a library compatible with JVM runtime version 11, but 'dev.nucleusframework:nucleus.decorated-window-jewel' is only compatible with JVM runtime version 25 or newer.`

### IntelliJ snapshots repository

2.0 pulls Jewel `0.37.0-262.4852.74`, which only lives in the IntelliJ **snapshots** repository — not releases. Add it to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots") // ← new
    }
}
```

If your project also declares Jewel directly (e.g. `jewel-int-ui-standalone`), bump it to the same coordinate Nucleus brings in transitively — otherwise Gradle resolves two incompatible Jewel versions side-by-side:

```toml
intellijIcons = "262.4852.74"
jewel        = "0.37.0-262.4852.74"
```

---

## At a Glance

| Area | 1.x | 2.0 |
|---|---|---|
| Plugin ID | `io.github.kdroidfilter.nucleus` | `dev.nucleusframework` |
| Maven group | `io.github.kdroidfilter` | `dev.nucleusframework` |
| Kotlin package root | `io.github.kdroidfilter.nucleus.*` | `dev.nucleusframework.*` |
| Entry point | `application { … }` | `nucleusApplication(args) { … }` |
| Window | `Window(…)` | `DecoratedWindow(…)` (or `MaterialDecoratedWindow`, `JewelDecoratedWindow`) |
| GraalVM bootstrap | Manual `GraalVmInitializer.initialize()` | Automatic |
| Single instance | Manual `SingleInstanceManager.isSingleInstance(…)` | Automatic |
| Window restore on 2nd-instance | Manual `LaunchedEffect` + `toFront()` | Automatic |
| AOT training timer | Manual `Thread` + `exitProcess` | `aotTraining(duration = …)` |
| AutoLaunch cache prime | Manual `AutoLaunch.wasStartedAtLogin(args)` | Automatic |
| Windows AUMID | Manual `WindowsJumpListManager.setProcessAppId()` | Automatic |

---

## Step 1 — Plugin ID & Maven Coordinates

```diff
 plugins {
-    id("io.github.kdroidfilter.nucleus") version "1.3.0"
+    id("dev.nucleusframework") version "2.0.0"
 }
```

Module dependencies follow the same rename:

```diff
 dependencies {
-    implementation("io.github.kdroidfilter:nucleus.core-runtime:1.3.0")
-    implementation("io.github.kdroidfilter:nucleus.aot-runtime:1.3.0")
-    implementation("io.github.kdroidfilter:nucleus.nucleus-application:1.3.0")
+    implementation("dev.nucleusframework:nucleus.core-runtime:2.0.0")
+    implementation("dev.nucleusframework:nucleus.aot-runtime:2.0.0")
+    implementation("dev.nucleusframework:nucleus.nucleus-application:2.0.0")
 }
```

The build-script DSL types move too:

```diff
-import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
-import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
+import dev.nucleusframework.desktop.application.dsl.TargetFormat
+import dev.nucleusframework.desktop.application.dsl.CompressionLevel
```

---

## Step 2 — Rename All Kotlin Imports

Every runtime import shifts root package. The cleanest way is a project-wide find & replace:

```
io.github.kdroidfilter.nucleus  →  dev.nucleusframework
```

Examples:

```diff
-import io.github.kdroidfilter.nucleus.core.runtime.DeepLinkHandler
-import io.github.kdroidfilter.nucleus.core.runtime.NucleusApp
-import io.github.kdroidfilter.nucleus.core.runtime.Platform
-import io.github.kdroidfilter.nucleus.darkmodedetector.isSystemInDarkMode
-import io.github.kdroidfilter.nucleus.updater.NucleusUpdater
-import io.github.kdroidfilter.nucleus.updater.provider.GitHubProvider
+import dev.nucleusframework.core.runtime.DeepLinkHandler
+import dev.nucleusframework.core.runtime.NucleusApp
+import dev.nucleusframework.core.runtime.Platform
+import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
+import dev.nucleusframework.updater.NucleusUpdater
+import dev.nucleusframework.updater.provider.GitHubProvider
```

There are no class renames at this step — only the package prefix changes.

### Don't forget your ProGuard / R8 keep rules

The find & replace must also reach your `proguard-rules.pro`. This is easy to miss because the build still compiles and a non-minified `run` works — the stale rules only bite a **release (ProGuard) build**, and they fail silently: a keep that points at a class that no longer exists is a no-op, not an error.

```diff
-# Nucleus decorated-window JNI
-keep class io.github.kdroidfilter.nucleus.window.utils.macos.NativeMacBridge {
+-keep class dev.nucleusframework.window.utils.macos.NativeMacBridge {
     native <methods>;
 }
--keep class io.github.kdroidfilter.nucleus.window.** { *; }
+-keep class dev.nucleusframework.window.** { *; }
```

This matters most if your app **overrides** the plugin's default ProGuard config via `proguard { configurationFiles.from(...) }` — then you own the Nucleus JNI keeps yourself, and the plugin won't inject its own as a fallback. The native bridges resolve their Java callbacks *by name* through JNI (`FindClass` + `GetMethodID`), so once a keep stops matching, ProGuard obfuscates the callback and the lookup blows up at runtime. See the [`NoSuchMethodError` entry under Troubleshooting](#troubleshooting) for the symptom.

---

## Step 3 — Switch to `nucleusApplication`

In 1.x you had Compose Desktop's `application { }` and called Nucleus init helpers around it. In 2.0 `nucleusApplication` runs all of that for you in the correct order and exposes a unified `NucleusApplicationScope`.

### Before — `main()` in 1.x

```kotlin
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()
    AutoLaunch.wasStartedAtLogin(args)
    if (Platform.Current == Platform.Windows) {
        WindowsJumpListManager.setProcessAppId()
    }

    if (AotRuntime.isTraining()) {
        Thread({
            Thread.sleep(45_000)
            kotlin.system.exitProcess(0)
        }, "aot-timer").apply { isDaemon = false }.start()
    }

    application {
        val isFirstInstance = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
                onRestoreRequest = {
                    DeepLinkHandler.readUriFrom(this)
                    // hand-rolled state to bring window back to front …
                },
            )
        }
        if (!isFirstInstance) {
            exitApplication()
            return@application
        }

        DeepLinkHandler.register(args) { uri -> handleDeepLink(uri) }

        Window(onCloseRequest = ::exitApplication, title = "My App") {
            App()
        }
    }
}
```

### After — `main()` in 2.0

```kotlin
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.application.aotTraining
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = nucleusApplication(args) {
    aotTraining(duration = 45.seconds)

    onDeepLink { uri -> handleDeepLink(uri) }

    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
        App()
    }
}
```

### Early-exits that must happen *before* `nucleusApplication`

`nucleusApplication` runs the full bootstrap (GraalVM init, single-instance lock, Compose loop). If your `main()` has invocation modes that should bypass all of that — e.g. a desktop scheduler/boot receiver re-launching the binary to run a single background task — keep them above the `nucleusApplication(args) { … }` call:

```kotlin
fun main(args: Array<String>) {
    if (DesktopBootReceiver.isSchedulerInvocation(args)) {
        DesktopBootReceiver.handle(args, registry = MyTaskRegistry.registry)
        exitProcess(0) // never reach Compose / single-instance
    }

    nucleusApplication(args) { … }
}
```

Putting these checks inside the scope would acquire the single-instance lock (and fight with the running primary instance) before short-circuiting — exactly what you don't want.

What `nucleusApplication` now handles for you, in order:

1. `GraalVmInitializer.initialize()` — fonts, charsets, HiDPI, `java.home`.
2. AOT training timer (when running with `-Dnucleus.aot.mode=training` and you call `aotTraining(…)`).
3. Single-instance lock acquisition. **If a second instance launches it relays its CLI deep link to the primary and exits with code 0 — Compose never starts on the secondary.**
4. Platform priming: `AutoLaunch.wasStartedAtLogin(args)` cache is warmed up and, on Windows, `WindowsJumpListManager.setProcessAppId()` is called before any window is created. Both run reflectively — they only fire if the `autolaunch` / `launcher-windows` modules are on the classpath.
5. Backend resolution (`NucleusBackend.Auto` picks AWT or Tao based on the classpath).
6. The Compose application loop.

You no longer need to call `AutoLaunch.wasStartedAtLogin(args)` or `WindowsJumpListManager.setProcessAppId()` from `main()` — they happen automatically. Other platform helpers (dock menus, Unity launcher quicklists, …) keep their native shape and live inside the scope.

---

## Step 4 — Replace `Window { }` with `DecoratedWindow { }`

Inside `nucleusApplication` you compose a decorated window. Three flavours are available — pick the one that matches your design system. Each lives in its own module, so add the matching dependency:

| Composable | Module |
|---|---|
| `DecoratedWindow(…)` — bare, bring your own title bar / theming | `nucleus.decorated-window-core` |
| `MaterialDecoratedWindow(…)` — Material 3 colors + decorated title bar | `nucleus.decorated-window-material` |
| `JewelDecoratedWindow(…)` — Jewel (IntelliJ) theme | `nucleus.decorated-window-jewel` |

All three expose `nucleusWindow` inside their content — a backend-agnostic handle for `show()`, `toFront()`, `setMinimized()`, etc.

### These are extension functions now — wrappers must propagate the scope

This is the most common breakage when porting an existing app: in 1.x, `JewelDecoratedWindow` (and friends) were plain `@Composable` functions, so you could wrap them in your own composable freely. In 2.0 they are **extensions on `NucleusApplicationScope`** (or `ApplicationScope` for the legacy variant):

```kotlin
fun NucleusApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit, …,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
)
```

Any wrapper composable you wrote in 1.x must become an extension on the same scope, otherwise you'll get `Unresolved reference 'JewelDecoratedWindow'` even though the import is correct.

```diff
 @Composable
-fun MyOnboardingWindow(vmFactory: ViewModelFactory) {
+fun NucleusApplicationScope.MyOnboardingWindow(vmFactory: ViewModelFactory) {
     JewelDecoratedWindow(onCloseRequest = {}, title = "…") { … }
 }
```

The call site (inside `nucleusApplication { … }`) doesn't change — the receiver is implicit.

```diff
-application {
-    Window(onCloseRequest = ::exitApplication, title = "My App") {
+nucleusApplication(args) {
+    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
         App()
     }
 }
```

The plain Compose Desktop `Window` still works inside `nucleusApplication`, but you lose the unified `nucleusWindow` handle and the automatic restore-on-second-instance behavior described below.

### Dialogs follow the same rule

`JewelDecoratedDialog` ships in two flavours, mirroring `JewelDecoratedWindow`:

| Receiver | Backend support |
|---|---|
| `JewelDecoratedDialog(…)` (no receiver) | AWT only (JBR / JNI). Crashes on Tao with `NoClassDefFoundError: dev/nucleusframework/window/DecoratedDialogKt`. |
| `NucleusApplicationScope.JewelDecoratedDialog(…)` | Backend-agnostic. Dispatches to AWT or Tao under the hood. |

Use the scoped variant for anything composed inside `nucleusApplication { … }` — your "About", "Settings", and confirmation dialogs all need it:

```diff
 @Composable
-fun MyAboutDialog(onClose: () -> Unit) {
+fun NucleusApplicationScope.MyAboutDialog(onClose: () -> Unit) {
     JewelDecoratedDialog(onCloseRequest = onClose, title = "About") { … }
 }
```

The same applies to `MaterialDecoratedDialog` / the generic `DecoratedDialog` extension on `NucleusApplicationScope`.

### CompositionLocals propagate across the Tao scene boundary

The Tao backend opens a fresh `ComposeScene` per window/dialog. As of 2.0.0-alpha-202605131305 the full parent `CompositionLocalContext` (theme, `LocalDensity`, `LocalLayoutDirection`, user locals, …) is bridged into the new scene automatically — same behavior as Compose's own `Dialog`/`Popup`.

This means you do **not** need to wrap content twice anymore:

```kotlin
// Before — needed on Tao to avoid "No TextStyle provided" / "No IsDarkTheme provided"
IntUiTheme(theme, styling) {
    JewelDecoratedWindow(…) {
        IntUiTheme(theme, styling) { …content… }   // duplicate
    }
}

// After — a single wrap in the parent scope is enough on every backend
IntUiTheme(theme, styling) {
    JewelDecoratedWindow(…) { …content… }
}
```

If you previously threaded `theme` / `styling` parameters through every custom window or dialog (`JewelOnboardingWindow`, `JewelAboutWindow`, …) to re-apply `IntUiTheme` inside the scene, you can drop the threading: read the theme from the outer scope once.

---

## Step 5 — Single Instance Is Automatic

`nucleusApplication` acquires the single-instance lock synchronously, **before** Compose starts.

- Primary instance: a watcher fires whenever another launch happens, and any `DecoratedWindow` currently composed is automatically restored: `show()` + `setMinimized(false)` + `toFront()` + `requestFocus()`.
- Secondary instance: its CLI deep-link argument (if any) is written to the IPC file via `DeepLinkHandler.writeUriTo`, then the process exits with code 0. The primary receives the URI through its `onDeepLink { }` handler.

Delete the manual block from 1.x:

```diff
-val isFirstInstance = remember {
-    SingleInstanceManager.isSingleInstance(
-        onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
-        onRestoreRequest = {
-            DeepLinkHandler.readUriFrom(this)
-            isWindowVisible = true
-            restoreRequestCount++
-        },
-    )
-}
-if (!isFirstInstance) {
-    exitApplication()
-    return@application
-}
-
-LaunchedEffect(restoreRequestCount) {
-    if (restoreRequestCount > 0) {
-        nucleusWindow.toFront()
-        nucleusWindow.requestFocus()
-    }
-}
```

To opt out — for editor-style apps that allow multiple concurrent instances — pass `enableSingleInstance = false`:

```kotlin
nucleusApplication(args, enableSingleInstance = false) { … }
```

---

## Step 6 — AOT Training Uses `aotTraining { }`

The manual thread-sleep-then-exit pattern is replaced by a one-liner.

```diff
-if (AotRuntime.isTraining()) {
-    Thread({
-        Thread.sleep(45_000)
-        kotlin.system.exitProcess(0)
-    }, "aot-timer").apply { isDaemon = false }.start()
-
-    preloadNavigationScreens()
-    preloadFontsAndImages()
-}

 nucleusApplication(args) {
+    aotTraining(duration = 45.seconds)
+
+    if (isAotTraining) {
+        preloadNavigationScreens()
+        preloadFontsAndImages()
+    }
     …
 }
```

`aotTraining` is a no-op outside training mode and idempotent if called multiple times. The scope also exposes `aotMode`, `isAotTraining`, and `isAotRuntime` properties for branching on the JVM's current AOT state. See [AOT Cache](runtime/aot-cache.md) for the full story.

---

## Step 7 — Deep Links Use `onDeepLink { }`

The scope's `onDeepLink { }` replaces direct calls to `DeepLinkHandler.register(args, …)`. It picks the right code path for the active backend (AWT or Tao) and parses the CLI [args] you handed to `nucleusApplication`.

```diff
-DeepLinkHandler.register(args) { uri -> handleDeepLink(uri) }
+onDeepLink { uri -> handleDeepLink(uri) }
```

URIs delivered before the handler is registered (cold-start macOS Apple Events on the Tao backend, second-instance relays before Compose mounts) are buffered and replayed.

`DeepLinkHandler` is still public and useful for low-level work — `onDeepLink` is the convenience front door.

---

## Step 8 — Drop Explicit `GraalVmInitializer.initialize()`

Anywhere you called it manually, remove it:

```diff
-GraalVmInitializer.initialize()
-application { … }
+nucleusApplication(args) { … }
```

`nucleusApplication` runs it for you as the very first bootstrap step. Calling it again is harmless but unnecessary.

---

## Final Result

A typical `main()` after the migration:

```kotlin
fun main(args: Array<String>) = nucleusApplication(args) {
    // Prime platform side-effects on the first composition (runs only on primary instance).
    remember {
        AutoLaunch.wasStartedAtLogin(args)
        if (Platform.Current == Platform.Windows) {
            WindowsJumpListManager.setProcessAppId()
        }
        true
    }

    aotTraining(duration = 45.seconds)

    onDeepLink { uri -> handleDeepLink(uri) }

    MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
        App()
    }
}
```

No ordered init list. No `SingleInstanceManager` plumbing. No restore counter and `LaunchedEffect` to bring the window back. Each platform integration (jump lists, dock menus, Unity launcher, notifications, …) keeps its native shape — what changed is the boilerplate around them.

---

## Field Notes — Multi-Window & Cross-Window Drag on the Tao Backend

Everything below was learned migrating a real app (Zayit) to Chrome-style multi-window with
cross-window tab drag & drop on the Tao backend. If your 1.x code leaned on AWT for anything
window- or pointer-related, read this section before porting.

### AWT is absent on Tao — and it fails *silently*

The Tao backend never creates AWT windows. Every AWT API still compiles, still runs, and does
**nothing**: no events, no windows, `null` handles. There is no exception to catch — features
just go dead. Grep your app for these and replace them:

| 1.x / AWT pattern | Symptom on Tao | 2.0 replacement |
|---|---|---|
| `Toolkit.addAWTEventListener(…)` (global mouse/key tracking) | Listener never fires | Observe the Compose pointer stream in `PointerEventPass.Initial` (see drag recipe below) |
| `KeyboardFocusManager.addKeyEventDispatcher(…)` | Dispatcher never fires | `onPreviewKeyEvent` / `onKeyEvent` on the window |
| `nucleusWindow.unsafe.awtWindow` | Always `null` | `nucleusWindow.boundsOnScreen()`, `toFront()`, `requestFocus()`, … |
| `window.locationOnScreen`, `MouseInfo.getPointerInfo()` | N/A (no AWT window) | `NucleusWindow.boundsOnScreen()` + pointer positions from Compose |
| `JWindow` / `JDialog` overlays (drag ghosts, HUDs) | Window never shows (or AWT headless) | `JewelDecoratedWindow(undecorated = true, focusable = false, alwaysOnTop = true)` |
| `GraphicsEnvironment.getScreenDevices()` (multi-monitor math) | May throw `HeadlessException` | Guard with `runCatching`; work-area natives exist per-platform if needed |

Rule of thumb: portable code goes through `NucleusWindow`; anything reached via `unsafe.*` is a
deliberate opt-out that must handle the other backend returning `null`.

### New portable APIs (added 2026-07)

```kotlin
// Backend-agnostic outer window bounds, logical (dp) screen coordinates, top-left origin.
// AWT reads user-space coords directly; Tao converts the physical rect through the window's
// scale factor. Null while the native window isn't realized yet.
val bounds: NucleusWindowBounds? = nucleusWindow.boundsOnScreen()

// Tao-level primitives behind it (unsafe tier):
val rectPx: LongArray? = taoWindow.outerBoundsPx() // [x, y, w, h] physical px, Win32 GetWindowRect convention on all 3 OSes
val scale: Float = taoWindow.scaleFactor

// Fully borderless windows — on macOS this is the only way to get rid of the traffic lights,
// which are NATIVE and present on every decorated Tao window:
JewelDecoratedWindow(
    onCloseRequest = {},
    undecorated = true,     // ← new; honoured by Tao, ignored by AWT
    focusable = false,      // never becomes key — no focus flicker while it follows the pointer
    alwaysOnTop = true,
    resizable = false,
    state = ghostWindowState,
) { /* chip content */ }
```

### Coordinate systems cheat sheet

Mixing these up produces hit-tests that are off by exactly one scale factor — on a Retina
display everything lands at half the expected distance.

| Value | Unit | Origin | Notes |
|---|---|---|---|
| Compose scene coords (`boundsInWindow`, pointer positions) | **physical px** | window top-left | divide by `LocalDensity.current.density` to get dp |
| `WindowState.position` / `.size` | logical dp | screen top-left | bidirectionally synced with the native window |
| `TaoWindow.outerBoundsPx()`, work-area natives | **physical px** | primary-screen top-left | macOS Y is already flipped from AppKit's bottom-left |
| `NucleusWindow.boundsOnScreen()` | logical dp | screen top-left | the one to use in app code |

Conversion used for cross-window hit-testing:
`screenDp = window.boundsOnScreen() + positionInWindowPx / density`.

### `WindowState` sync semantics — two traps

1. **`state.position` is only `Absolute` after a native move event.** A window created with
   `WindowPosition.Aligned(Center)` keeps `Aligned` in its state — the realized coordinates are
   never written back. Don't derive screen positions from `WindowState`; use
   `boundsOnScreen()`.
2. **Placement is applied at builder time.** A window created `Maximized` and mutated to
   `Floating` a moment later visibly flashes maximized first. If you restore window geometry
   from disk, read it *before* creating the window and construct the `WindowState` with the
   final placement/size/position — don't create-then-mutate.

### Multiple windows

`JewelDecoratedWindow` is a plain composable on the application scope — call it N times for N
windows, keyed by a stable id:

```kotlin
nucleusApplication(args) {
    val windows by windowManager.windows.collectAsState()
    windows.forEach { w ->
        key(w.id) {
            JewelDecoratedWindow(state = w.windowState, …) { … }
        }
    }
}
```

Focus tracking comes from `nucleusWindow.focusFlow`; bring-to-front is
`toFront()` + `requestFocus()` (works on Tao via `nativeFocus`).

### Cross-window drag & drop recipe (no OS DnD involved)

The platform captures the pointer during a button-held drag: the **source window keeps
receiving move events even when the cursor is outside its bounds** (macOS `mouseDragged`,
Win32 `SetCapture`, X11 implicit grab). That capture is the whole trick — it replaces the
global AWT listener an AWT implementation would use (IntelliJ's `DockManagerImpl` pattern):

1. Observe the drag in the source window with a non-consuming `pointerInput` in
   `PointerEventPass.Initial` — the gesture owner (e.g. a reorderable row) is unaffected.
2. Convert to logical screen coords with `boundsOnScreen()` + `position / density`.
3. Hit-test the other windows' registered drop areas in screen space. Make drop targets
   generous (a whole title-bar strip, not just the tabs row — with one tab the row is a
   ~180 dp target users will miss).
4. Show a ghost: a small `undecorated + focusable=false + alwaysOnTop` window following the
   pointer via `WindowState.position` mutations. Create it once per drag session and hide it
   with `visible=false` instead of disposing — recreating a native window mid-drag stutters.
5. On release: mutate your model (move the tab / spawn a window). A window spawned "under the
   cursor" should be `Floating` at a **reduced** size — deriving it from a maximized source
   window otherwise reproduces a maximized footprint.

Two things that do **not** work for the ghost:

- **Compose `Popup`**: on Tao, popups are real native panels (`NSPanel` / child HWND), but they
  are positioned and clamped **within the parent window** — a popup cannot float outside the
  window bounds.
- **A decorated window**: on macOS every decorated Tao window carries native traffic lights.
  A ghost following the cursor puts them right under the pointer, hover-glowing. That is what
  `undecorated = true` is for.

### Wayland caveat

Native Wayland (xdg-shell) forbids clients from positioning toplevels: `setOuterPosition` is a
no-op, so ghost placement, cascading, and "spawn under the cursor" degrade to
compositor-decided placement. Nucleus logs a one-shot warning; `NUCLEUS_TAO_LINUX_RENDERER=x11`
falls back to XWayland when precise positioning matters.

---

## Troubleshooting

**My imports won't resolve after the rename.**
Search the project for `io.github.kdroidfilter.nucleus` — anything left over is a stale import. The replacement is always `dev.nucleusframework`.

**A release build crashes with `NoSuchMethodError` (or `UnsatisfiedLinkError`) from a native bridge.**
Symptom — the debug `run` works, GraalVM/native-image builds work, but the ProGuard release path dies on startup:

```
Exception in thread "main" java.lang.NoSuchMethodError: onEvent
    at dev.nucleusframework.window.tao.NativeTaoBridge.nativeRunBlocking(Native Method)
    at dev.nucleusframework.window.tao.TaoApplication.run(TaoApplication.kt:50)
```

Your ProGuard keep rules still reference the old `io.github.kdroidfilter.nucleus.*` package, so they no longer match the renamed 2.0 classes. The Nucleus native libraries call back into Kotlin *by name* through JNI; once the keep stops matching, ProGuard renames the callback (`onEvent`, `onThemeChanged`, …) and the native lookup fails. ProGuard flags the dead rules as notes you can grep the build log for:

```
Note: the configuration refers to the unknown class 'io.github.kdroidfilter.nucleus.window.utils.macos.NativeMacBridge'
```

Fix: apply the namespace rename to `proguard-rules.pro` as well — see [Step 2 → keep rules](#dont-forget-your-proguard--r8-keep-rules).

**`nucleusApplication` is unresolved.**
Add `implementation("dev.nucleusframework:nucleus.nucleus-application:2.0.0")` to the module's dependencies. The runtime split moved `nucleusApplication` out of `core-runtime`.

**My window doesn't come back when I click the dock icon / taskbar of a second launch.**
That auto-behavior is wired inside `DecoratedWindow`. If you use plain Compose Desktop `Window`, you keep the 1.x manual pattern. Switch to `DecoratedWindow` (or one of the styled variants) to get it for free.

**I want multiple concurrent instances.**
Pass `enableSingleInstance = false` to `nucleusApplication`. The lock is skipped entirely.

**`Unresolved reference 'JewelDecoratedWindow'` even though the import is correct.**
The composable became an extension on `NucleusApplicationScope` in 2.0. Wrap-style helper composables must propagate the receiver — see [Step 4](#step-4--replace-window---with-decoratedwindow--).

**`NoClassDefFoundError: dev/nucleusframework/window/DecoratedDialogKt` on the Tao backend.**
You're calling the AWT-only `JewelDecoratedDialog` (no receiver) under `NucleusBackend.Tao`. Switch the host composable to an extension on `NucleusApplicationScope` so the call resolves to `NucleusApplicationScope.JewelDecoratedDialog`, which dispatches to the right backend — see [Step 4 → Dialogs](#dialogs-follow-the-same-rule).

**`IllegalStateException: No TextStyle provided` / `No IsDarkTheme provided` on Tao but not on AWT.**
Older Tao builds (pre-`v2.0.0-alpha-202605131225`) did not bridge `CompositionLocals` across the per-window `ComposeScene`. Bump to `2.0.0-alpha-202605131305` or newer and remove any duplicate `IntUiTheme { … }` you added inside the window/dialog content lambda — a single wrap in the outer scope is enough.

**`Could not find org.jetbrains.jewel:jewel-foundation:0.37.…`**
The IntelliJ snapshots repo is missing. Add `maven("https://www.jetbrains.com/intellij-repository/snapshots")` to `dependencyResolutionManagement.repositories` — see [Prerequisites](#prerequisites).

**`Dependency resolution is looking for a library compatible with JVM runtime version 11`.**
Bump the toolchain — Nucleus 2.0 requires JDK 25 for the Jewel stack and JDK 17 for `nucleus-application`. See [Prerequisites](#prerequisites).

**My global `AWTEventListener` / `KeyEventDispatcher` never fires on Tao.**
Expected — the Tao backend creates no AWT windows, so no AWT events exist. There is no error;
the listener is simply never called. Replace with Compose-level observation (a non-consuming
`pointerInput` in `PointerEventPass.Initial`, or the window's `onPreviewKeyEvent`). See
[Field Notes → AWT is absent on Tao](#awt-is-absent-on-tao--and-it-fails-silently).

**My floating overlay window shows macOS traffic lights (and they highlight under the cursor).**
Every decorated Tao window keeps the native buttons on macOS. Pass `undecorated = true` (plus
`focusable = false`, `alwaysOnTop = true` for overlays) — see
[Field Notes → New portable APIs](#new-portable-apis-added-2026-07).

**A Compose `Popup` won't render outside the window.**
By design: Tao popups are native panels, but positioned and clamped within the parent window's
bounds. Anything that must float beyond the window (drag ghost, tear-off preview) needs a real
window with `undecorated = true`.

**Cross-window hit-testing is off by ~2× on Retina / HiDPI displays.**
You mixed physical px and logical dp. Compose scene coordinates (`boundsInWindow`, pointer
positions) are physical px on Tao — divide by `LocalDensity`; `boundsOnScreen()` and
`WindowState` are logical. See [Field Notes → Coordinate systems](#coordinate-systems-cheat-sheet).

**A restored window flashes maximized before jumping to its saved floating frame.**
Placement is applied at window-builder time. Read the persisted geometry *before* composing the
window and build the initial `WindowState` from it, instead of creating the window with a
default state and mutating it once your restore logic runs.

**`WindowState.position` says `Aligned` even though the window is clearly somewhere on screen.**
Also by design: the realized coordinates of an `Aligned` position are not written back into the
state (only native move events produce `Absolute`). Use `NucleusWindow.boundsOnScreen()` for
real screen coordinates.

**Windows opened programmatically stack exactly on top of each other on Wayland.**
Native Wayland forbids client-side toplevel positioning; cascading/`setOuterPosition` are
no-ops. Set `NUCLEUS_TAO_LINUX_RENDERER=x11` to fall back to XWayland if placement matters.
