# Nucleus Application

The `nucleus-application` module provides a **unified entry point** that abstracts over the three decorated-window backends (`decorated-window-jbr`, `decorated-window-jni`, `decorated-window-tao`) and exposes a backend-agnostic window handle. With a single call site, the same code compiles and runs unchanged on the AWT-based JBR/JNI backends and on the no-AWT Tao backend.

Switching backends becomes a runtime decision driven by which `decorated-window-*` artifact is on the classpath — no source-level rewrite required.

!!! info "Why a separate module"
    Each backend has its own constraints (JBR runtime, AWT widget tree, Tao's own `ComposeScene`). Picking one is normally a compile-time commitment that leaks into every `application { … }` / `taoApplication { … }` call. `nucleus-application` removes that commitment from your code: write to `nucleusApplication { … }`, ship one of the three backends as a runtime dependency, and let the application module wire them together.

## Installation

Add `nucleus-application` plus **exactly one** decorated-window backend:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.nucleus-application:<version>")

    // Pick exactly one backend:
    implementation("io.github.kdroidfilter:nucleus.decorated-window-jni:<version>")    // any JVM (recommended)
    // implementation("io.github.kdroidfilter:nucleus.decorated-window-jbr:<version>") // JBR-only
    // implementation("io.github.kdroidfilter:nucleus.decorated-window-tao:<version>") // no-AWT, GraalVM-friendly
}
```

The three backends define overlapping symbols in the same package — by construction only one can be on the runtime classpath. `nucleus-application` detects which one is present and dispatches accordingly.

## Quick Start

```kotlin
fun main() = nucleusApplication {
    val state = rememberWindowState(size = DpSize(1024.dp, 720.dp))
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "My App",
    ) {
        TitleBar { state ->
            Text(title, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        // Your app content
        MyContent()
    }
}
```

That is all. Inside the `content` lambda the receiver is `NucleusDecoratedWindowScope`. `TitleBar { … }`, `DecoratedWindowState`, and the rest of the [decorated-window](decorated-window.md) API behave exactly as on the underlying backend.

## Public API

### `nucleusApplication`

```kotlin
fun nucleusApplication(
    backend: NucleusBackend = NucleusBackend.Auto,
    content: @Composable NucleusApplicationScope.() -> Unit,
)
```

| `backend`            | Behaviour                                                                 |
|----------------------|---------------------------------------------------------------------------|
| `NucleusBackend.Auto` | Pick whichever backend is on the classpath. Tao wins if both are present. |
| `NucleusBackend.Awt`  | Force the AWT-bound path (jbr or jni — whichever is at runtime).          |
| `NucleusBackend.Tao`  | Force the no-AWT path.                                                    |

`Auto` is the right default for libraries; explicit values are useful when an app ships multiple backends (rare) or wants to enforce one in tests.

### `NucleusApplicationScope`

Sealed interface returned to the `content` lambda. Two concrete subtypes drive the dispatch:

- `AwtNucleusApplicationScope` — wraps Compose Desktop's `application { … }` scope
- `TaoNucleusApplicationScope` — wraps Tao's `taoApplication { … }` scope

You normally don't pattern-match on it; it just provides `exitApplication()` and the active `backend`.

### `DecoratedWindow` & `DecoratedDialog`

```kotlin
@Composable
fun NucleusApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
)

@Composable
fun NucleusApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    /* … same shape, dialog defaults */
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
)
```

Both functions match the parameter set of the underlying backend's `DecoratedWindow` / `DecoratedDialog`, with the `macOSStyle` enum dropped — use `Modifier.macOSLargeCornerRadius()` on the `TitleBar` instead (works on every backend, see [Modifiers](#modifiers) below).

### `NucleusWindow`

The portable window handle exposed inside the content lambda as `nucleusWindow`:

```kotlin
@Stable
interface NucleusWindow {
    val isFocused: Boolean
    val isMinimized: Boolean
    val isMaximized: Boolean
    val isFullscreen: Boolean

    fun show()
    fun hide()
    fun toFront()
    fun requestFocus()
    fun setMinimized(minimized: Boolean)
    fun setMaximized(maximized: Boolean)
    fun setFullscreen(fullscreen: Boolean)
    fun setAlwaysOnTop(alwaysOnTop: Boolean)
    fun setMinimumSize(size: DpSize?)
    fun setIcon(painter: Painter?)
    fun close()

    val focusFlow: StateFlow<Boolean>
    val minimizedFlow: StateFlow<Boolean>
    val maximizedFlow: StateFlow<Boolean>
    val fullscreenFlow: StateFlow<Boolean>

    val unsafe: NucleusWindowUnsafe
}
```

Use the methods and `*Flow` values for portable code. The same handle is also reachable from any composable via `LocalNucleusWindow.current`.

### `LocalNucleusBackend`

```kotlin
val LocalNucleusBackend: ProvidableCompositionLocal<NucleusBackend>
```

A child composable can branch on the active backend without reflective classpath checks:

```kotlin
@Composable
fun MaybeUseTaoTrick() {
    if (LocalNucleusBackend.current == NucleusBackend.Tao) {
        // Tao-only behaviour
    }
}
```

### Backend-specific escape hatches

```kotlin
interface NucleusWindowUnsafe {
    val awtWindow: ComposeWindow?    // populated on AWT (jbr/jni), null on Tao
    val awtDialog: ComposeDialog?    // populated on AWT inside DecoratedDialog
    val taoWindow: TaoWindow?        // populated on Tao, null on AWT
    val taoHandle: Long?             // raw native handle, suitable for FFI
}
```

These are namespaced behind `unsafe` deliberately: reaching for them is an explicit opt-out of the portable contract. Use them only when no portable equivalent exists (see [What can't be portable](#what-cant-be-portable)).

## Modifiers

The unified entry point keeps the existing `Modifier`-driven extensions:

- `Modifier.newFullscreenControls()` — opt-in macOS fullscreen overlay (jbr/jni)
- `Modifier.macOSLargeCornerRadius()` — opt-in macOS Sequoia / Tahoe large corner radius

Both are now honoured **identically on Tao**: applying the modifier to a `TitleBar` re-runs the same `NSToolbar` install path that the AWT backends use. The `MacOSStyle` enum on `tao.openDecoratedWindow` still exists as the imperative equivalent, but its default has been changed to `Classic` so that the modifier is the one source of truth across backends.

```kotlin
TitleBar(modifier = Modifier.macOSLargeCornerRadius()) { state -> /* … */ }
```

## Theming wrappers

The existing Material 2 / Material 3 / Jewel wrappers gained a `NucleusApplicationScope` overload:

- `nucleus.decorated-window-material3` — `MaterialDecoratedWindow`, `MaterialDecoratedDialog`
- `nucleus.decorated-window-material2` — `MaterialDecoratedWindow`
- `nucleus.decorated-window-jewel` — `JewelDecoratedWindow`

```kotlin
fun main() = nucleusApplication {
    MaterialTheme(colorScheme = darkColorScheme()) {
        MaterialDecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "My App",
        ) {
            MaterialTitleBar { state -> /* … */ }
            Surface(Modifier.fillMaxSize()) { /* content */ }
        }
    }
}
```

The wrappers capture the outer `MaterialTheme` / Jewel theme tokens and re-provide them inside the window content, which matters on Tao (each window owns its own `ComposeScene` and `CompositionLocal`s don't propagate across scenes). The legacy `ApplicationScope.MaterialDecoratedWindow` AWT-only overload is kept for backwards compatibility.

`nucleus-application` also auto-forwards `LocalIsDarkTheme`, `LocalDecoratedWindowStyle`, and `LocalTitleBarStyle` from the outer composition into the Tao scene — wrapping `NucleusDecoratedWindowTheme(…) { DecoratedWindow(…) { … } }` works the same on both backends.

## Migration

Migrating an existing AWT-only app (jbr or jni) is a two-line change for the entry point, plus one addition for any code that previously held a `ComposeWindow`.

### Step 1 — entry point

```diff
- import androidx.compose.ui.window.application
+ import io.github.kdroidfilter.nucleus.application.nucleusApplication
```

```diff
- fun main() = application {
+ fun main() = nucleusApplication {
      DecoratedWindow(/* … */) { /* … */ }
  }
```

Returning early stays the same:

```diff
-     return@application
+     return@nucleusApplication
```

### Step 2 — window handle

Inside the content lambda the receiver is now `NucleusDecoratedWindowScope`. The portable handle is `nucleusWindow: NucleusWindow`. The previously-implicit `window: ComposeWindow` is still reachable via `nucleusWindow.unsafe.awtWindow`.

For most call sites the portable `NucleusWindow` API is enough:

```diff
- LaunchedEffect(restoreRequestCount) {
-     if (restoreRequestCount > 0) {
-         window.toFront()
-         window.requestFocus()
-     }
- }
+ LaunchedEffect(restoreRequestCount) {
+     if (restoreRequestCount > 0) {
+         nucleusWindow.toFront()
+         nucleusWindow.requestFocus()
+     }
+ }
```

```diff
- var isWindowFocused by remember { mutableStateOf(window.isFocused) }
- DisposableEffect(window) {
-     val listener = object : WindowFocusListener {
-         override fun windowGainedFocus(e: WindowEvent?) { isWindowFocused = true }
-         override fun windowLostFocus(e: WindowEvent?) { isWindowFocused = false }
-     }
-     window.addWindowFocusListener(listener)
-     onDispose { window.removeWindowFocusListener(listener) }
- }
+ val isWindowFocused by nucleusWindow.focusFlow.collectAsState()
```

When you genuinely need the AWT widget (e.g. to call a Windows JNI bridge that takes a `java.awt.Window`), use the escape hatch:

```diff
- "Taskbar" -> TaskbarProgressScreen(window)
+ "Taskbar" -> TaskbarProgressScreen(nucleusWindow.unsafe.awtWindow!!)
```

### Step 3 — theming wrappers (optional)

If you used `application { MaterialDecoratedWindow(…) { … } }`, the call site doesn't need to change — `MaterialDecoratedWindow` resolves to its `NucleusApplicationScope` overload automatically.

### Cheat sheet

| Before (AWT-only) | After (portable) |
|---|---|
| `application { … }` | `nucleusApplication { … }` |
| `taoApplication { … }` | `nucleusApplication(NucleusBackend.Tao) { … }` |
| `window: ComposeWindow` (lambda receiver) | `nucleusWindow: NucleusWindow` |
| `window.toFront() / requestFocus() / isFocused` | `nucleusWindow.toFront() / requestFocus() / isFocused` |
| `window.addWindowFocusListener(…)` + `DisposableEffect` | `nucleusWindow.focusFlow.collectAsState()` |
| `window.extendedState = MAXIMIZED_BOTH` | `nucleusWindow.setMaximized(true)` |
| `window.extendedState or ICONIFIED` | `nucleusWindow.setMinimized(true)` |
| Native AWT bridge needing `java.awt.Window` | `nucleusWindow.unsafe.awtWindow!!` |
| Tao native bridge | `nucleusWindow.unsafe.taoWindow` / `taoHandle` |

## Auto resolution

When `backend = NucleusBackend.Auto`:

1. If `decorated-window-tao` is on the classpath → Tao.
2. Otherwise → AWT (whichever of `-jbr` / `-jni` is at runtime).

Detection uses `Class.forName` on a single marker class, run at most once per process. Tao-specific code is loaded lazily through a separate isolating object so an AWT-only classpath never tries to resolve Tao symbols at class-load time.

## What can't be portable

Most AWT-vs-Tao calls have a unified equivalent on `NucleusWindow`. The handful that don't, and that justify the `unsafe` accessors:

- **Swing / AWT widget tree** — `contentPane`, `glassPane`, `JMenuBar`, attaching native `JComponent`s. Tao has no Swing tree at all.
- **AWT-typed events** — `dispatchEvent(WindowEvent.WINDOW_CLOSING)` and other fully-typed AWT events.
- **`window.shape = RoundRectangle2D`** — AWT-only; Tao has its own XShape path on Linux.
- **`graphicsConfiguration` / `screenInsets`** — AWT screen-geometry queries.
- **AWT DnD via `java.awt.dnd.DropTarget`**.
- **`Desktop.setQuitHandler`** — process-level AWT.
- **JNI bridges resolving HWND / NSWindow from `WindowPeer`**.
- **Tao-only**: `dragWindow()` (manual native drag), `NSToolbar` tweaks, raw `CALayer` access, native handle for FFI.

For these cases reach for `nucleusWindow.unsafe.{awtWindow, awtDialog, taoWindow, taoHandle}`.

## Samples

- `sample-jni` — minimal AWT (JNI backend) demo using `nucleusApplication(NucleusBackend.Awt)`.
- `sample-tao` — minimal no-AWT (Tao backend) demo using `nucleusApplication(NucleusBackend.Tao)`.
- `jewel-sample` — Jewel theming on Tao via `JewelDecoratedWindow`.
- `example` — full-featured demo (deep links, single-instance, taskbar, launcher, notifications, …) on the AWT backend, illustrating the `nucleusWindow` portable API and the `unsafe.awtWindow` escape hatch where unavoidable.
