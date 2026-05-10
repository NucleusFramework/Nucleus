package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.jetbrains.JBR
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
    remember {
        check(JBR.isAvailable()) {
            "DecoratedDialog requires JetBrains Runtime (JBR). " +
                "Please run your application on JBR."
        }
    }

    val undecorated = Platform.Linux == Platform.Current

    // Centre the dialog on its parent window by computing the position
    // before DialogWindow is composed.  This avoids any visible jump because
    // DialogWindow reads state.position and applies it immediately.
    val density = LocalDensity.current
    remember(state) {
        val parent =
            java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .focusedWindow
        if (parent != null && state.position == WindowPosition.PlatformDefault) {
            val dialogWidthPx = with(density) { state.size.width.toPx() }
            val dialogHeightPx = with(density) { state.size.height.toPx() }
            val x = parent.x + (parent.width - dialogWidthPx) / 2
            val y = parent.y + (parent.height - dialogHeightPx) / 2
            state.position =
                WindowPosition(
                    x = with(density) { x.toDp() },
                    y = with(density) { y.toDp() },
                )
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
