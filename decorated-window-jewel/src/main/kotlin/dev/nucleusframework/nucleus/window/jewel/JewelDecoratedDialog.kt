package dev.nucleusframework.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.nucleus.window.DecoratedDialog
import dev.nucleusframework.nucleus.window.DecoratedDialogScope
import dev.nucleusframework.nucleus.window.NucleusDecoratedWindowTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun JewelDecoratedDialog(
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
    content: @Composable DecoratedDialogScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val titleBarStyle = rememberJewelTitleBarStyle()

    NucleusDecoratedWindowTheme(
        isDark = JewelTheme.isDark,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle,
    ) {
        DecoratedDialog(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            content = content,
        )
    }
}
