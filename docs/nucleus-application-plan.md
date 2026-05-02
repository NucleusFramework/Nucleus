# Plan: unified `nucleusApplication` API

> Status: foundation landed in `:nucleus-application` (window + dialog +
> Material3 wrapper + sample-jni / sample-tao migrated). Pending: macOS-style
> Modifier wired in tao TitleBar, `taoApplication` made internal, Material2 /
> Jewel unified wrappers, example/jewel-sample migration.
>
> Note vs. spec: the unified scope exposes the portable handle as
> `nucleusWindow: NucleusWindow` (not `window`), because the adapter scopes
> also implement the backend-specific scope (`AwtDecoratedWindowScope` /
> `TaoDecoratedWindowScope`) — required for the existing
> `TitleBar { … }` cast at runtime. Backend-specific `window: ComposeWindow`
> / `window: TaoWindow` is still reachable through that inheritance, and the
> portable handle is also available via `LocalNucleusWindow.current`.
> Goal: a single entry point that picks the window backend (AWT-based JBR/JNI
> or no-AWT Tao) and exposes a backend-agnostic `NucleusWindow` handle so user
> code does not change when swapping backends.

## Target ergonomics

```kotlin
fun main() = nucleusApplication(backend = NucleusBackend.Auto) {
    val state = rememberWindowState(size = DpSize(1200.dp, 800.dp))
    DecoratedWindow(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "Demo",
    ) {
        // window.toFront(), state.isActive, etc. — works on both AWT and Tao
        TitleBar { state -> Text(title) }
        // user content
    }
}
```

Switching `backend` (or letting `Auto` resolve it) is the **only** change needed
to swap from AWT to Tao or vice-versa.

---

## 1. New Gradle module: `decorated-window-application`

```kotlin
dependencies {
    api(project(":decorated-window-core"))
    compileOnly(project(":decorated-window-jbr"))
    compileOnly(project(":decorated-window-jni"))
    compileOnly(project(":decorated-window-tao"))
    api(libs.compose.foundation)
}
```

Consumers add **one** backend at runtime (`runtimeOnly` JBR / JNI / Tao).

JVM target: 17 (Tao requirement).

Module name: **`nucleus-application`**.

---

## 2. Public API

### Backend selector

```kotlin
enum class NucleusBackend {
    Auto,    // detect at runtime (prefer Tao when available + macOS, else AWT)
    Awt,     // jbr or jni — whichever is on the classpath
    Tao,     // no-AWT
}
```

### Entry point

```kotlin
fun nucleusApplication(
    backend: NucleusBackend = NucleusBackend.Auto,
    content: @Composable NucleusApplicationScope.() -> Unit,
)
```

Body dispatches on the resolved backend → either Compose Desktop's
`application { … }` or Tao's `taoApplication { … }`, wrapping the inner scope
in a `NucleusApplicationScope`.

### Sealed application scope

```kotlin
sealed interface NucleusApplicationScope {
    fun exitApplication()
}

internal class AwtNucleusApplicationScope(
    val composeScope: androidx.compose.ui.window.ApplicationScope,
) : NucleusApplicationScope {
    override fun exitApplication() = composeScope.exitApplication()
}

internal class TaoNucleusApplicationScope(
    val taoScope: io.github.kdroidfilter.nucleus.window.tao.ApplicationScope,
) : NucleusApplicationScope {
    override fun exitApplication() = taoScope.exitApplication()
}
```

### Unified `DecoratedWindow` / `DecoratedDialog`

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
```

Body: `when (this)` on the sealed scope → calls the AWT or Tao
`DecoratedWindow`, wraps the inner scope into a `NucleusDecoratedWindowScope`.

Same shape for `DecoratedDialog`.

### Unified window scope + handle

```kotlin
interface NucleusDecoratedWindowScope : DecoratedWindowScope {
    val window: NucleusWindow
}

interface NucleusWindow {
    // Read
    val isFocused: Boolean
    val isMinimized: Boolean
    val isMaximized: Boolean
    val isFullscreen: Boolean

    // Commands
    fun show()
    fun hide()
    fun toFront()
    fun requestFocus()
    fun setMinimized(b: Boolean)
    fun setMaximized(b: Boolean)
    fun setFullscreen(b: Boolean)
    fun setAlwaysOnTop(b: Boolean)
    fun setMinimumSize(size: DpSize)
    fun setIcon(painter: Painter?)
    fun close()

    // Observables (Compose-friendly via collectAsState)
    val focusFlow: StateFlow<Boolean>

    // Typed escape hatches behind an `unsafe` accessor (null when not the
    // active backend). Using `window.unsafe.*` is an explicit opt-out of the
    // backend-agnostic contract.
    val unsafe: NucleusWindowUnsafe
}

interface NucleusWindowUnsafe {
    val awtWindow: ComposeWindow? get() = null
    val taoHandle: Long? get() = null
}
```

Internal implementations:
- `AwtNucleusWindow(window: ComposeWindow)` — wraps `WindowFocusListener`,
  `toFront()`, `requestFocus()` via AWT.
- `TaoNucleusWindow(window: TaoWindow)` — wraps `dragWindow`, `setMaximized`,
  state holder feeds `focusFlow`.

---

## 3. Theming wrappers consolidation

`MaterialDecoratedWindow` / `JewelDecoratedWindow` collapse to **one** function
per module, on `NucleusApplicationScope`:

```kotlin
@Composable
fun NucleusApplicationScope.MaterialDecoratedWindow(...) {
    // Capture outer theme once, re-provide INSIDE the DecoratedWindow content
    // (covers Tao's separate ComposeScene, no-op-ish on AWT)
    val outerColorScheme = MaterialTheme.colorScheme
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes

    DecoratedWindow(...) {
        MaterialTheme(outerColorScheme, outerTypography, outerShapes) {
            NucleusDecoratedWindowTheme(...) {
                content()
            }
        }
    }
}
```

Removes the current dual-overload (one for AWT `ApplicationScope`, one for
`tao.ApplicationScope`).

---

## 4. User migration

Before:
```kotlin
application { DecoratedWindow(...) { ... } }
// or
taoApplication { DecoratedWindow(...) { ... } }
```

After:
```kotlin
nucleusApplication(backend = NucleusBackend.Auto) {
    DecoratedWindow(...) { ... }
}
```

Code accessing `window`:
```kotlin
// Before (jbr/jni): window.toFront() — type ComposeWindow
// After (unified):  window.toFront() — type NucleusWindow, works everywhere
// AWT-only behavior: window.unsafe.awtWindow?.someAwtSpecificMethod()
// Tao-only behavior: window.unsafe.taoHandle?.let { … }
```

---

## 5. Implementation steps (ordered)

1. **Create module `decorated-window-application`** (build.gradle.kts, package,
   JVM 17, `compileOnly` on the three backends).
2. **Define abstract types**: `NucleusBackend`, sealed `NucleusApplicationScope`,
   `NucleusWindow`, `NucleusDecoratedWindowScope`, `NucleusDecoratedDialogScope`.
3. **Implement `nucleusApplication(backend, content)`** with dispatch + Auto
   detection (classpath probing via `Class.forName`).
4. **Implement `AwtNucleusWindow` + `TaoNucleusWindow`** (wrappers,
   `StateFlow<Boolean>` for focus/state).
5. **Implement `NucleusApplicationScope.DecoratedWindow`** (sealed-when dispatch
   + scope adapter).
6. **Same for `DecoratedDialog`.**
7. **Migrate `MaterialDecoratedWindow` / `JewelDecoratedWindow`** to a single
   `NucleusApplicationScope` overload; theme re-provided inside the content
   lambda.
8. **Migrate samples**:
   - `example`: `application { }` → `nucleusApplication(Auto) { }`,
     `window.toFront()` keeps working through `NucleusWindow`.
   - `jewel-sample`: same migration; backend = Tao explicitly if desired.
9. **Keep existing AWT entry points usable**: the current AWT `DecoratedWindow`
   overloads on `androidx.compose.ui.window.ApplicationScope` stay public and
   non-deprecated — `nucleusApplication` is additive, not a replacement.
   `taoApplication` was never published, so it can be made `internal` to the
   `nucleus-application` module without a deprecation cycle.

---

## 6. Subtle points to handle during implementation

- **`window` resolution inside content**: `NucleusDecoratedWindowScope` extends
  `DecoratedWindowScope` (no `window` field there), so `window` resolves to the
  `NucleusWindow` exposed by the nucleus scope. ✓
- **`StateFlow<Boolean> focusFlow`**: replaces the AWT `WindowFocusListener` and
  Tao's `state.isActive` with a unified observable. `AwtNucleusWindow` installs
  a listener that pushes into the flow; `TaoNucleusWindow` reads from the
  existing `stateHolder`.
- **`Auto` resolution**:
  1. If the `backend` parameter is explicitly set (≠ `Auto`) → respect it.
  2. Otherwise, detect the backend present on the classpath. By construction an
     app ships **one** of `decorated-window-{jbr,jni,tao}` (their imports
     overlap, so coexistence is not supported), so detection is unambiguous.
  3. Fallback order if multiple are somehow present: Tao → JBR → JNI.
- **`DeepLinkHandler` on Tao**: must defer `Desktop.getDesktop()` early init
  (currently blocks NSApp on Tao macOS — see prior session). The application
  module should expose a Tao-aware deep-link entry that bypasses AWT.
- **`taoApplication` not exposed publicly**: kept `internal` to the application
  module. Migrating users go through `nucleusApplication`.
- **`LocalNucleusBackend` CompositionLocal**: `nucleusApplication` provides a
  `CompositionLocal<NucleusBackend>` so any internal component / downstream lib
  can branch on the active backend (e.g. `if (LocalNucleusBackend.current ==
  Tao) …`). This is the primary mechanism for backend-aware adaptation —
  preferred over reflective classpath checks at runtime.

---

## 7. Backend-specific escape hatches

What stays backend-specific and how to access it from the unified API:

- `MacOSStyle` (Liquid Glass): exposed via the existing AWT `Modifier` API. The
  Tao backend must implement the **same `Modifier`** so user code is identical
  on both backends (no `macOSStyle` parameter on `DecoratedWindow`).
- `Modifier.newFullscreenControls()` / `macOSLargeCornerRadius()`: already
  shared in `decorated-window-core`, read differently per backend.
- Raw AWT access: `window.unsafe.awtWindow?.…` (e.g. `DeepLinkHandler`,
  `WindowFocusListener` for advanced cases).
- Raw Tao access: `window.unsafe.taoHandle?.let { … }` (native bridges,
  GraalVM diagnostics, etc.).

---

## 8. Out of scope for this plan

- Rewriting `DeepLinkHandler` to be tao-aware (separate ticket).
- Tao-specific feature parity (Windows snap layouts, mica, full a11y on
  non-macOS) — already tracked elsewhere.
- CI for tao native builds — already on the backlog.
