package dev.nucleusframework.window.material2

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.application.DecoratedDialog as NucleusDecoratedDialog

/** Material 2 styled dialog. Use inside `nucleusApplication { … }`. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.MaterialDecoratedDialog(
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
    content: @Composable NucleusDecoratedDialogScope.() -> Unit,
) {
    val outerColors = MaterialTheme.colors
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColors)
    val titleBarStyle = rememberMaterialTitleBarStyle(outerColors)

    NucleusDecoratedWindowTheme(
        isDark = !outerColors.isLight,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle,
    ) {
        NucleusDecoratedDialog(
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
        ) {
            // Each window owns its own ComposeScene, so the outer theme tokens
            // must be re-provided inside the dialog content.
            MaterialTheme(
                colors = outerColors,
                typography = outerTypography,
                shapes = outerShapes,
            ) {
                content()
            }
        }
    }
}
