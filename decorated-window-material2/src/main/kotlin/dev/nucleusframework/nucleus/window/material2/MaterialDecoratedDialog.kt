package dev.nucleusframework.nucleus.window.material2

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.nucleus.window.DecoratedDialog
import dev.nucleusframework.nucleus.window.DecoratedDialogScope
import dev.nucleusframework.nucleus.window.NucleusDecoratedWindowTheme

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun MaterialDecoratedDialog(
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
    val colors = MaterialTheme.colors
    val windowStyle = rememberMaterialWindowStyle(colors)
    val titleBarStyle = rememberMaterialTitleBarStyle(colors)

    NucleusDecoratedWindowTheme(
        isDark = !colors.isLight,
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
