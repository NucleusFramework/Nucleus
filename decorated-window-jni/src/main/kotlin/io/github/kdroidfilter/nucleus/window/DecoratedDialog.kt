package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.core.runtime.Platform

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedDialog(
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
    content: @Composable AwtDecoratedDialogScope.() -> Unit,
) {
    val undecorated = Platform.Linux == Platform.Current || Platform.Windows == Platform.Current

    // Centre the dialog on its parent window before DialogWindow is composed.
    // AWT window coordinates and Compose Dp are 1:1 for window positioning,
    // so we can mix parent AWT bounds with state.size.value directly.
    remember(state) {
        val parent =
            java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .focusedWindow
        if (parent != null && !state.position.isSpecified) {
            val x = parent.x + (parent.width - state.size.width.value) / 2f
            val y = parent.y + (parent.height - state.size.height.value) / 2f
            state.position = WindowPosition(x = x.dp, y = y.dp)
        }
    }

    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        undecorated = undecorated,
        transparent = false,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        DecoratedDialogBody(
            title = title,
            icon = icon,
            undecorated = undecorated,
            content = content,
        )
    }
}
