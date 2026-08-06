package dev.nucleusframework.application

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import dev.nucleusframework.window.DecoratedDialogScope
import dev.nucleusframework.window.DecoratedWindowScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Outer window bounds in logical (dp) screen coordinates, top-left origin.
 * The unit matches Compose's `WindowState` values on both backends.
 */
public data class NucleusWindowBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Backend-agnostic handle to a window opened by [DecoratedWindow]. Mirrors the
 * intersection of `ComposeWindow` and `TaoWindow`.
 *
 * Backend-specific bridges live behind [unsafe] — using them is an explicit
 * opt-out of the portable contract.
 */
@Suppress("TooManyFunctions")
@Stable
public interface NucleusWindow {
    public val isFocused: Boolean
    public val isMinimized: Boolean
    public val isMaximized: Boolean
    public val isFullscreen: Boolean

    /**
     * Outer (decoration-inclusive) window bounds in logical screen coordinates,
     * or `null` while the native window isn't realized yet. Backend-agnostic:
     * AWT reads user-space coordinates directly; Tao converts the physical
     * window rect through the window's scale factor. Intended for cross-window
     * features (drag & drop hit-testing, window placement).
     */
    public fun boundsOnScreen(): NucleusWindowBounds? = null

    public fun show()

    public fun hide()

    public fun toFront()

    public fun requestFocus()

    public fun setMinimized(minimized: Boolean)

    public fun setMaximized(maximized: Boolean)

    public fun setFullscreen(fullscreen: Boolean)

    public fun setAlwaysOnTop(alwaysOnTop: Boolean)

    public fun setMinimumSize(size: DpSize?)

    public fun setIcon(painter: Painter?)

    public fun close()

    public val focusFlow: StateFlow<Boolean>
    public val minimizedFlow: StateFlow<Boolean>
    public val maximizedFlow: StateFlow<Boolean>
    public val fullscreenFlow: StateFlow<Boolean>

    public val unsafe: NucleusWindowUnsafe
}

/**
 * Backend-specific escape hatches. The accessor matching the active backend
 * returns a non-null value; the others always return `null`. Access is
 * intentionally namespaced to flag uses that break portability.
 */
@Stable
public interface NucleusWindowUnsafe {
    public val awtWindow: ComposeWindow? get() = null

    public val awtDialog: androidx.compose.ui.awt.ComposeDialog? get() = null

    /** Tao-owned window (no-AWT backend). */
    public val taoWindow: dev.nucleusframework.window.tao.TaoWindow? get() = null

    /** Native `tao::Window` handle, opaque token suitable for FFI bridges. */
    public val taoHandle: Long? get() = null
}

/**
 * Decorated-window scope exposing a backend-agnostic [nucleusWindow]. Returned
 * inside the `content` lambda of [DecoratedWindow]. The concrete adapter also
 * implements the active backend's scope (`AwtDecoratedWindowScope` /
 * `TaoDecoratedWindowScope`), so the existing `TitleBar { … }` extension works
 * unchanged. The backend-specific `window` is reachable from those scopes;
 * use [nucleusWindow] (or [LocalNucleusWindow]) for portable code.
 */
@Stable
public interface NucleusDecoratedWindowScope : DecoratedWindowScope {
    public val nucleusWindow: NucleusWindow
}

/**
 * Decorated-dialog scope counterpart of [NucleusDecoratedWindowScope].
 */
@Stable
public interface NucleusDecoratedDialogScope : DecoratedDialogScope {
    public val nucleusWindow: NucleusWindow
}

/**
 * CompositionLocal version of [NucleusDecoratedWindowScope.nucleusWindow]:
 * lets a child composable reach the unified window handle without threading
 * the scope receiver through every call. Provided by [DecoratedWindow].
 */
public val LocalNucleusWindow: ProvidableCompositionLocal<NucleusWindow> =
    staticCompositionLocalOf<NucleusWindow> {
        error("LocalNucleusWindow not provided — use it inside a Nucleus DecoratedWindow.")
    }
