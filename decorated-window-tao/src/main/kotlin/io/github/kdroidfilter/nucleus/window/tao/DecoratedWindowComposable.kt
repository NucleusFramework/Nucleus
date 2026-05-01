package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize

/**
 * Composable variant of [openDecoratedWindow]. API mirrors
 * `decorated-window-jni`'s `DecoratedWindow` (sans `state`, which lands in a
 * future iteration). Reactive parameters (`title`, `alwaysOnTop`, `visible`,
 * `focusable`, `minimumSize`, `icon`) push to the underlying [TaoWindow] via
 * `LaunchedEffect`. Callback parameters (`onCloseRequest`, `onPreviewKeyEvent`,
 * `onKeyEvent`) are reactive without recreating the window.
 *
 * Limitations vs. `decorated-window-jni`:
 *  - `state: WindowState` not yet wired (size/position fixed at construction).
 *  - `enabled` only applies at construction.
 *  - `width`/`height` are construction-only (replaced by `state` when wired).
 *  - User `content` lambda captures latest via `rememberUpdatedState`, so
 *    state declared inside `content` recomposes normally; state declared in
 *    the parent application scope and *read* inside `content` propagates via
 *    snapshot, but does NOT share a CompositionContext (each window owns its
 *    own scene Composition).
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
fun ApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    title: String = "",
    icon: Painter? = null,
    width: Double = 800.0,
    height: Double = 600.0,
    minimumSize: DpSize? = null,
    visible: Boolean = true,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val latestOnClose by rememberUpdatedState(onCloseRequest)
    val latestPreview by rememberUpdatedState(onPreviewKeyEvent)
    val latestKey by rememberUpdatedState(onKeyEvent)
    val latestContent by rememberUpdatedState(content)

    val window = remember {
        openDecoratedWindow(
            onCloseRequest = { latestOnClose() },
            title = title,
            icon = icon,
            width = width,
            height = height,
            minimumSize = minimumSize,
            visible = false, // controlled below by LaunchedEffect
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = false, // applied below
            onPreviewKeyEvent = { latestPreview(it) },
            onKeyEvent = { latestKey(it) },
            macOSStyle = macOSStyle,
            content = { latestContent.invoke(this) },
        )
    }

    DisposableEffect(window) {
        onDispose { window.requestClose() }
    }

    LaunchedEffect(window, title) { window.setTitle(title) }
    LaunchedEffect(window, alwaysOnTop) { window.setAlwaysOnTop(alwaysOnTop) }
    LaunchedEffect(window, focusable) { window.setFocusable(focusable) }
    LaunchedEffect(window, visible) {
        if (visible) window.show() else window.hide()
    }
    LaunchedEffect(window, minimumSize) {
        if (minimumSize != null) {
            window.setMinimumSize(
                minimumSize.width.value.toDouble(),
                minimumSize.height.value.toDouble(),
            )
        } else {
            window.setMinimumSize(null, null)
        }
    }
    LaunchedEffect(window, icon) {
        if (icon != null) {
            icon.toRgbaIcon()?.let { (w, h, px) -> window.setIcon(w, h, px) }
        } else {
            window.setIcon(0, 0, ByteArray(0))
        }
    }
}
