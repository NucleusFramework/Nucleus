package dev.nucleusframework.window.tao.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DecoratedWindow

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
 * @param screenRectPx outer frame of the ghost, physical screen pixels.
 * @param scaleFactor physical pixels per dp of the window the rect came from.
 *   The application scope this is composed in belongs to no window, so its
 *   density is always 1 and cannot be used to convert.
 * @param title the window title (invisible, but what a screen reader announces).
 * @param compositionLocalContext parent locals bridged into the ghost's scene.
 * @param content what the ghost shows; fills the window.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ApplicationScope.DragGhostWindow(
    screenRectPx: Rect,
    scaleFactor: Float,
    title: String,
    compositionLocalContext: CompositionLocalContext?,
    content: @Composable () -> Unit,
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
        compositionLocalContext = compositionLocalContext,
    ) {
        content()
    }
}
