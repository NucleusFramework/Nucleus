package dev.nucleusframework.application

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
data class NucleusWindowBounds(
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
interface NucleusWindow {
    val isFocused: Boolean
    val isMinimized: Boolean
    val isMaximized: Boolean
    val isFullscreen: Boolean

    /**
     * Outer (decoration-inclusive) window bounds in logical screen coordinates,
     * or `null` while the native window isn't realized yet. Backend-agnostic:
     * AWT reads user-space coordinates directly; Tao converts the physical
     * window rect through the window's scale factor. Intended for cross-window
     * features (drag & drop hit-testing, window placement).
     */
    fun boundsOnScreen(): NucleusWindowBounds? = null

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

/**
 * Backend-specific escape hatches. The accessor matching the active backend
 * returns a non-null value; the others always return `null`. Access is
 * intentionally namespaced to flag uses that break portability.
 */
@Stable
interface NucleusWindowUnsafe {
    val awtWindow: ComposeWindow? get() = null

    val awtDialog: androidx.compose.ui.awt.ComposeDialog? get() = null

    /** Tao-owned window (no-AWT backend). */
    val taoWindow: dev.nucleusframework.window.tao.TaoWindow? get() = null

    /** Native `tao::Window` handle, opaque token suitable for FFI bridges. */
    val taoHandle: Long? get() = null
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
interface NucleusDecoratedWindowScope : DecoratedWindowScope {
    val nucleusWindow: NucleusWindow
}

/**
 * Decorated-dialog scope counterpart of [NucleusDecoratedWindowScope].
 */
@Stable
interface NucleusDecoratedDialogScope : DecoratedDialogScope {
    val nucleusWindow: NucleusWindow
}

/**
 * CompositionLocal version of [NucleusDecoratedWindowScope.nucleusWindow]:
 * lets a child composable reach the unified window handle without threading
 * the scope receiver through every call. Provided by [DecoratedWindow].
 */
val LocalNucleusWindow =
    staticCompositionLocalOf<NucleusWindow> {
        error("LocalNucleusWindow not provided — use it inside a Nucleus DecoratedWindow.")
    }
