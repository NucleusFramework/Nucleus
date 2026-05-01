package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.window.DecoratedDialogState

/**
 * Tao-backed equivalent of `decorated-window-jni`'s `DecoratedDialog`.
 *
 * Defaults match the AWT-based backends: non-resizable, no maximize/minimize
 * affordance in the title bar (use [DialogTitleBar] which only renders the
 * close button). Lacks native parent-child modality — Tao does not yet expose
 * a child-window relationship, so the dialog is a regular top-level window
 * with dialog-friendly defaults. Consumers can still drive modality logically
 * by disabling the parent window's interactions while the dialog is up.
 */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
fun ApplicationScope.DecoratedDialog(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = false,
    enabled: Boolean = true,
    focusable: Boolean = true,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable TaoDecoratedDialogScope.() -> Unit,
) {
    // DialogState exposes size/position only; reuse the WindowState plumbing
    // by adapting size/position through DecoratedWindow underneath.
    val latestContent by rememberUpdatedState(content)

    val windowState = androidx.compose.ui.window.rememberWindowState(
        size = state.size,
        position = state.position,
    )

    DecoratedWindow(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = title,
        icon = icon,
        minimumSize = null,
        visible = visible,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = false,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        macOSStyle = macOSStyle,
        content = {
            val windowScope = this
            val dialogScope = object : TaoDecoratedDialogScope, ColumnScope by windowScope {
                override val window: TaoWindow = windowScope.window
                override val state: DecoratedDialogState
                    get() = DecoratedDialogState.of(active = windowScope.state.isActive)
            }
            with(dialogScope) { latestContent() }
        },
    )

    // Forward back native size/position into DialogState
    androidx.compose.runtime.LaunchedEffect(windowState.size) {
        if (state.size != windowState.size) state.size = windowState.size
    }
    androidx.compose.runtime.LaunchedEffect(windowState.position) {
        val p = windowState.position
        if (p is WindowPosition.Absolute && p != state.position) state.position = p
    }
    androidx.compose.runtime.LaunchedEffect(state.size) {
        if (state.size != windowState.size) windowState.size = state.size
    }
    androidx.compose.runtime.LaunchedEffect(state.position) {
        val p = state.position
        if (p is WindowPosition.Absolute && p != windowState.position) {
            windowState.position = p
        }
    }
}
