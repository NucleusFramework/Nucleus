// #636: a window opener — `@ComposableOpenTarget(-1)` with a `@UiComposable`
// content lambda, callable from any applier. ktlint's `annotation` and
// `function-type-modifier-spacing` rules contradict each other on the
// resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.UiComposable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow

/**
 * A borderless, click-through, always-on-top window covering [screenRectPx]
 * and following it as the caller republishes the rect: the preview of
 * something being dragged out of a window.
 *
 * A real window rather than an overlay drawn inside the host, because the
 * whole point is that it leaves the host's bounds. It never takes focus and
 * never takes the pointer, so the drag gesture keeps running in the window
 * underneath.
 *
 * @param screenRectPx outer frame of the ghost, physical pixels — on screen,
 *   or relative to [popupFor] when it is given, which is the space a popup
 *   overlay is positioned in on a compositor-placed surface.
 * @param scaleFactor physical pixels per dp of the window the rect came from.
 *   The application scope this is composed in belongs to no window, so its
 *   density is always 1 and cannot be used to convert.
 * @param title the window title (invisible, but what a screen reader announces).
 * @param compositionLocalContext parent locals bridged into the ghost's scene.
 * @param popupFor the window this ghost overlays, on Linux: a popup of it
 *   rather than a toplevel of its own — a `wl_subsurface` on native Wayland,
 *   the only window kind a client may position there, so the ghost can follow
 *   the pointer at all. `null` is a plain window, placed on screen.
 * @param layoutDirection what [content] is laid out in: the direction of the
 *   strip or panel the ghost stands for, else the call site's. A scene of its
 *   own re-provides the global direction over the bridged locals, so the ghost
 *   cannot simply inherit one.
 * @param content what the ghost shows; fills the window, composed with the
 *   ghost window's scope like any window content.
 */
@Suppress("FunctionNaming")
@Composable
@ComposableOpenTarget(-1)
internal fun ApplicationScope.DragGhostWindow(
    screenRectPx: Rect,
    scaleFactor: Float,
    title: String,
    compositionLocalContext: CompositionLocalContext?,
    popupFor: TaoWindow? = null,
    layoutDirection: LayoutDirection = LocalLayoutDirection.current,
    content: @Composable @UiComposable TaoDecoratedWindowScope.() -> Unit,
) {
    val scale = scaleFactor.takeIf { it > 0f } ?: 1f
    val state =
        rememberWindowState(
            position = WindowPosition.Absolute((screenRectPx.left / scale).dp, (screenRectPx.top / scale).dp),
            size = DpSize((screenRectPx.width / scale).dp, (screenRectPx.height / scale).dp),
        )
    // Reactive follow: the caller republishes the rect on every pointer move,
    // and DecoratedWindow pushes state changes to the native window.
    SideEffect {
        state.position = WindowPosition.Absolute((screenRectPx.left / scale).dp, (screenRectPx.top / scale).dp)
        state.size = DpSize((screenRectPx.width / scale).dp, (screenRectPx.height / scale).dp)
    }
    DecoratedWindow(
        onCloseRequest = {},
        state = state,
        title = title,
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        clickThrough = true,
        alwaysOnTop = true,
        popupFor = popupFor,
        compositionLocalContext = compositionLocalContext,
    ) {
        val scope: TaoDecoratedWindowScope = this
        SideEffect { scope.window.closesOnQuit = false }
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) { scope.content() }
    }
}
