# Decorated Window — Tao backend

The `decorated-window-tao` module is the **no-AWT** implementation of the [Decorated Window](decorated-window.md) API. Instead of building on top of Compose Desktop's AWT-bound `Window` (which itself wraps Swing's `JFrame`), the Tao backend opens a native window directly via [Tao](https://github.com/tauri-apps/tao) (the same Rust window library that powers Tauri) and runs Compose inside its own `ComposeScene`.

The result is a Compose Desktop application with **zero AWT dependency at runtime** — small startup, GraalVM native-image friendly, no Swing event-dispatch thread.

!!! warning "Experimental"
    This backend is newer than [`decorated-window-jbr`](decorated-window.md#decorated-window-jbr-jetbrains-runtime-implementation) and [`decorated-window-jni`](decorated-window.md#decorated-window-jni-nucleus-native-implementation). The public API is stable and matches the AWT backends, but the rendering pipeline (Metal on macOS, WGL on Windows, EGL on Linux) is implemented from scratch in this module — expect rougher edges than the AWT path. Report issues as you find them.

!!! tip "Use it through `nucleus-application`"
    For new code, prefer the unified entry point in [Nucleus Application](nucleus-application.md). It writes once against a backend-agnostic API and lets you swap between Tao and the AWT backends by changing only the runtime dependency. The Tao-specific symbols documented below remain available for advanced cases — primarily through `nucleusWindow.unsafe.taoWindow` / `nucleusWindow.unsafe.taoHandle`.

## When to use Tao

- **GraalVM native-image** — AWT is partially supported by GraalVM (via Liberica NIK with full AWT) but pulls in a large surface area. Tao avoids AWT entirely; the resulting native binary is significantly smaller and starts faster.
- **No-Swing apps** — if your codebase has no Swing widgets, AWT is dead weight. Tao removes it.
- **Single-thread native apps** — Tao runs on the macOS main thread without an AWT EDT in front. This is the natural threading model for native macOS apps and avoids the EDT ↔ Compose dispatcher hand-offs.

If none of these apply, stick with `decorated-window-jni` — it is the more battle-tested path.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.decorated-window-tao:<version>")

    // Optional but recommended — unified entry point that works with all three backends:
    implementation("io.github.kdroidfilter:nucleus.nucleus-application:<version>")

    // GraalVM bootstrap (required for native-image builds):
    implementation("io.github.kdroidfilter:nucleus.graalvm-runtime:<version>")
}
```

JVM target: **17+**. Tao requires Java 17 features.

### Supported platforms

| Platform | Architectures | Renderer |
|----------|---------------|----------|
| macOS    | arm64, x86_64 | Metal (`CAMetalLayer`) |
| Windows  | x64, ARM64    | WGL (Win32 OpenGL) + custom WndProc decoration |
| Linux    | x64, aarch64  | EGL on top of GTK-owned X11 / Wayland surface |

The native libraries are bundled in the JAR under `nucleus/native/<arch>/` and loaded on first use via `NativeLibraryLoader`.

## Quick Start

```kotlin
fun main() {
    GraalVmInitializer.initialize()
    taoApplication {
        val state = rememberWindowState(size = DpSize(1024.dp, 720.dp))
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Tao Demo",
        ) {
            TitleBar { state ->
                Text(title, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            // Your app content
            MyContent()
        }
    }
}
```

The same code through the unified entry point:

```kotlin
fun main() {
    GraalVmInitializer.initialize()
    nucleusApplication(backend = NucleusBackend.Tao) {
        DecoratedWindow(/* … same params … */) {
            TitleBar { state -> /* … */ }
            MyContent()
        }
    }
}
```

!!! note "macOS main thread"
    Tao runs the OS event loop on the **process main thread** (thread 0). On a regular JVM you must launch with `-XstartOnFirstThread`; in a GraalVM native-image build this is automatic. The Nucleus Gradle plugin injects the flag for `./gradlew run` and packaged distributions.

## Architecture

The Tao backend is a thin Kotlin layer on top of a Rust JNI bridge (`nucleus_tao` crate) that wraps the Tao window library plus per-platform native helpers.

```
┌──────────────────────────────────────────────────────────────────┐
│  Compose Desktop content (your @Composable code)                 │
├──────────────────────────────────────────────────────────────────┤
│  CanvasLayersComposeScene                                        │
│  • single-threaded recomposer, BroadcastFrameClock               │
│  • TaoMainDispatcher (posts onto Tao's event queue)              │
├──────────────────────────────────────────────────────────────────┤
│  TaoComposeSceneHost (per platform)                              │
│  • macOS: CAMetalLayer attached to NSView                        │
│  • Windows: WGL context bound to HWND, custom WndProc            │
│  • Linux: EGL surface on GTK-owned X11/wl_surface                │
├──────────────────────────────────────────────────────────────────┤
│  TaoWindow (Kotlin handle)                                       │
│  • thread-safe imperative API (show/hide/setMaximized/…)         │
│  • multi-cast event listeners (focus, resize, key, pointer, …)   │
├──────────────────────────────────────────────────────────────────┤
│  nucleus_tao Rust crate (JNI bridge)                             │
│  • Tao window + event loop                                       │
│  • per-platform helpers: Metal, WGL/WndProc, GLX/EGL, GTK        │
└──────────────────────────────────────────────────────────────────┘
```

Each window owns its own `ComposeScene` — no shared composition with the application scope. CompositionLocals declared *outside* `DecoratedWindow` do not propagate into the window content; the [theming wrappers](#theming) and [`nucleus-application`](nucleus-application.md) take care of forwarding the relevant ones.

## Public API

### `taoApplication`

```kotlin
fun taoApplication(
    content: @Composable ApplicationScope.() -> Unit,
)
```

Mirrors Compose Desktop's `application { … }` but drives the Compose recomposer through Tao's event loop. Blocks until `ApplicationScope.exitApplication()` is called. Must be called from the macOS main thread.

### `ApplicationScope`

Tao's counterpart of `androidx.compose.ui.window.ApplicationScope`:

```kotlin
interface ApplicationScope {
    fun exitApplication()
    val taoApplication: TaoApplication  // imperative escape hatch
}
```

### `DecoratedWindow`

```kotlin
@Composable
fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    title: String = "",
    icon: Painter? = null,
    minimumSize: DpSize? = null,
    visible: Boolean = true,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Classic,
    content: @Composable TaoDecoratedWindowScope.() -> Unit,
)
```

The parameter set is a strict superset of `decorated-window-jni`'s `DecoratedWindow` — call sites swap with minimal change.

`enabled = false` swallows pointer + keyboard events at the host level (the window appears unresponsive — matches the JNI backend's behaviour). `focusable = false` calls `tao::Window::set_focusable(false)`, preventing the window from ever becoming key (HUD / overlay windows).

The `state: WindowState` is **bidirectional**: native drag / resize / fullscreen transitions push back into `state.size`, `state.position`, `state.placement`. Writes to `state` are pushed to the native window via `LaunchedEffect`. The `applied` snapshot guards against feedback loops.

### `DecoratedDialog`

Same shape as `DecoratedWindow` but for modal / secondary windows: defaults to non-resizable, no minimize / maximize affordance. Tao does not yet expose native parent-child modality, so the dialog is a regular top-level window with dialog-friendly defaults — drive logical modality by disabling the parent's interactions while it is up.

### `MacOSStyle`

```kotlin
enum class MacOSStyle {
    /** Modern (Tahoe) on macOS 26+, classic chrome on older releases. */
    Auto,
    /** Force classic chrome, even on macOS 26+. */
    Classic,
    /** Force the modern treatment (NSToolbar attached) regardless of OS. */
    Modern,
}
```

Imperative counterpart of `Modifier.macOSLargeCornerRadius()`. **Default is `Classic`** so the swap-in API behaves identically to the AWT backends — opt in to the large corner radius via the modifier on `TitleBar`, not via this enum.

### `TitleBar`

```kotlin
@Composable
fun DecoratedWindowScope.TitleBar(
    modifier: Modifier = Modifier,
    gradientStartColor: Color = Color.Unspecified,
    style: TitleBarStyle = LocalTitleBarStyle.current,
    controlButtonsDirection: ControlButtonsDirection = ControlButtonsDirection.Auto,
    backgroundContent: @Composable () -> Unit = {},
    content: @Composable TitleBarScope.(DecoratedWindowState) -> Unit = {},
)
```

Matches the JNI backend's `TitleBar` signature. The `modifier` parameter consumes `Modifier.macOSLargeCornerRadius()` and `Modifier.newFullscreenControls()` for parity with the AWT backends — see [Modifiers](nucleus-application.md#modifiers).

### `TaoWindow`

The native window handle:

```kotlin
class TaoWindow {
    val handle: Long           // raw native handle, suitable for FFI
    val isResizable: Boolean
    val isMaximized: Boolean
    val isFullscreen: Boolean

    fun setTitle(title: String)
    fun setMinimized(b: Boolean)
    fun setMaximized(b: Boolean)
    fun setFullscreen(b: Boolean)
    fun setAlwaysOnTop(b: Boolean)
    fun setFocusable(b: Boolean)
    fun setMinimumSize(widthDp: Double?, heightDp: Double?)
    fun setIcon(width: Int, height: Int, pixels: ByteArray)  // premult RGBA
    fun setInnerSize(widthDp: Double, heightDp: Double)
    fun setOuterPosition(xDp: Double, yDp: Double)

    fun show()
    fun hide()
    fun requestRedraw()
    fun requestClose()
    fun requestUserClose()      // fires onCloseRequest as if the user clicked X
    fun dragWindow()             // initiate native drag — call during a press

    // Multi-cast event hooks: every call adds a listener
    fun onWindowReady(block: (width: Int, height: Int) -> Unit)
    fun onResized(block: (width: Int, height: Int) -> Unit)
    fun onMoved(block: (xPx: Int, yPx: Int) -> Unit)
    fun onFocusChanged(block: (focused: Boolean) -> Unit)
    fun onCloseRequested(block: () -> Unit)
    fun onDestroyed(block: () -> Unit)
    fun onScaleFactorChanged(block: (scale: Float) -> Unit)
    fun onKeyEvent(listener: KeyEventListener)
    /* … pointer hooks … */
}
```

You normally don't reach for `TaoWindow` directly — `TaoDecoratedWindowScope.window` exposes it inside the `DecoratedWindow` content lambda, and `nucleusWindow.unsafe.taoWindow` exposes it through the unified API.

### `TaoApplication`

The lower-level entry point underneath `taoApplication`. Exposes imperative `openWindow(...)` and `exit()`. Use it for advanced multi-window scenarios that don't map cleanly to the Composable shape; otherwise stay on `taoApplication`.

## Theming

Each window owns its own `ComposeScene`, so CompositionLocals declared *outside* `DecoratedWindow` do not propagate. You have two options:

1. **Apply the theme inside the content lambda**:

    ```kotlin
    DecoratedWindow(/* … */) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            NucleusDecoratedWindowTheme(isDark = true) {
                TitleBar { /* … */ }
                // content
            }
        }
    }
    ```

2. **Use `nucleus-application`** ([recommended](nucleus-application.md)). It auto-forwards `LocalIsDarkTheme`, `LocalDecoratedWindowStyle`, and `LocalTitleBarStyle` from the outer composition into the Tao scene, so a single outer wrapper works on every backend:

    ```kotlin
    nucleusApplication {
        NucleusDecoratedWindowTheme(isDark = true, titleBarStyle = myStyle) {
            DecoratedWindow(/* … */) {
                TitleBar { /* … */ }
                // content
            }
        }
    }
    ```

The Material 2 / Material 3 / Jewel wrappers also have a `NucleusApplicationScope` overload that captures the outer `MaterialTheme` (or Jewel theme) tokens and re-provides them inside the new scene — see [Nucleus Application § Theming wrappers](nucleus-application.md#theming-wrappers).

## GraalVM native-image

Tao is the recommended backend for GraalVM builds. Bootstrap requirements:

1. **`GraalVmInitializer.initialize()`** must be the first call in `main()` (registers Skiko, Tao native libs, font substitutions). Provided by [`nucleus.graalvm-runtime`](../graalvm/index.md).
2. **JNI reachability metadata** is shipped in the module under `META-INF/native-image/.../reachability-metadata.json` — no user action needed.
3. **No `-XstartOnFirstThread`** required: GraalVM native-image starts on the macOS main thread by default.

See the [GraalVM guide](../graalvm/index.md) for the full toolchain (Liberica NIK, Gradle plugin, agent runs).

## Platform notes

### macOS

- Native title bar is kept (transparent / full-size content), traffic lights stay native.
- `MacOSStyle.Modern` / `Modifier.macOSLargeCornerRadius()` attaches a hidden `NSToolbar` so AppKit applies the macOS 26 (Tahoe) large corner radius. No-op on Sequoia and below.
- Press-and-hold (long-press accent picker on `e`, `n`, …) is wired via an `NSTextView` overlay attached as the first responder during Compose `TextField` focus.
- VoiceOver is implemented via a per-window `NSAccessibility` projection that mirrors Compose's `SemanticsOwner` tree. Per-view registry + window focus forwarder.

### Windows

- Window is fully undecorated; close / min / max are drawn in Compose by the user content (the `TitleBar` composable lays them out at `Modifier.align(Alignment.End)`).
- Custom WndProc subclass returns `HTCLIENT` for the entire title-bar zone (not `HTMINBUTTON` / etc.) so DWM never paints native buttons over Compose UI.
- `_NET_WM_STATE_FULLSCREEN` equivalent uses Win32 fullscreen — covers the taskbar (a known JBR / standard-Compose limitation).

### Linux

- EGL renderer is attached to a GTK-owned surface (X11 XID or `wl_surface`, picked at runtime).
- Native GTK decorations are kept; the user's `TitleBar` composable is a sub-bar inside the content area. Window controls (min / max / close) are drawn in Compose with platform-accurate icons (GNOME Adwaita / KDE Breeze) and a reactive layout observer.
- Rounded corners are applied via XShape (GNOME 12 dp, KDE 5 dp top only); `applyRoundedShape()` re-runs after every resize to handle borderline maximize/restore frames.
- The default Skiko `URIManager` calls `Desktop.browse`, which initialises XAWT and deadlocks the GLX loop — Tao replaces it with a forked-process `xdg-open` (`TaoLinuxUriHandler`).

## Limitations

- **Press-and-hold accent picker** — implemented on macOS; not yet on Windows / Linux.
- **Snap layouts (Windows 11)** — not yet implemented.
- **Mica / DWM material backgrounds (Windows 11)** — not yet exposed.
- **Multi-window `ComposeScene` sharing** — each window has its own scene; CompositionLocals don't cross. Use [`nucleus-application`](nucleus-application.md) or re-provide them manually.
- **`DialogWindow` / `DialogState`** — `DecoratedDialog` accepts `DialogState` for shape parity but doesn't yet implement native parent-child modality. Disable the parent window's interactions while the dialog is up to emulate it.
- **`DeepLinkHandler`** — currently AWT-only (uses `java.awt.Desktop`). On Tao macOS, calling it early initialises NSApp and breaks the event loop — a Tao-aware variant is on the backlog.

## Module structure

| File / folder | Role |
|---------------|------|
| `tao/TaoApplicationCompose.kt` | `taoApplication { … }` entry point |
| `tao/ApplicationScope.kt` | Public `ApplicationScope` interface |
| `tao/DecoratedWindow.kt` | Imperative + Composable `DecoratedWindow` |
| `tao/DecoratedDialog.kt` | `DecoratedDialog` |
| `tao/MacOSStyle.kt` | `MacOSStyle` enum |
| `tao/TaoWindow.kt` | Native window handle |
| `tao/TaoApplication.kt` | Lower-level imperative entry point |
| `tao/render/TaoComposeSceneHost*.kt` | Per-platform Compose-scene host (Metal / WGL / EGL) |
| `tao/Native*Bridge.kt` | JNI bridges to the Rust crate |
| `src/main/native/` | Rust crate (`Cargo.toml`) + per-platform helpers (`macos/`, `windows/`, `linux/`) |
| `tao/TaoAccessibility.kt`, `TaoSemanticsObserver.kt` | NSAccessibility projection (macOS) |

## See also

- [Nucleus Application](nucleus-application.md) — backend-agnostic entry point.
- [Decorated Window](decorated-window.md) — overview, AWT backends comparison.
- [GraalVM](../graalvm/index.md) — native-image bootstrap.
