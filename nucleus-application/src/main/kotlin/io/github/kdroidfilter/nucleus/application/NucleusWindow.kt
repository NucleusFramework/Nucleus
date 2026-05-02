package io.github.kdroidfilter.nucleus.application

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import io.github.kdroidfilter.nucleus.window.DecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend-agnostic handle to a window opened by [DecoratedWindow]. Mirrors the
 * intersection of `ComposeWindow` and `TaoWindow`.
 *
 * Backend-specific bridges live behind [unsafe] — using them is an explicit
 * opt-out of the portable contract.
 */
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
    val taoWindow: io.github.kdroidfilter.nucleus.window.tao.TaoWindow? get() = null

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
