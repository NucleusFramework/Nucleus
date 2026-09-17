package dev.nucleusframework.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.application.DecoratedDialog as NucleusDecoratedDialog

/** Material 3 styled dialog. Use inside `nucleusApplication { … }`. */
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
    val outerColorScheme = MaterialTheme.colorScheme
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColorScheme)
    val titleBarStyle = rememberMaterialTitleBarStyle(outerColorScheme)
    val isDark = outerColorScheme.isDark()

    NucleusDecoratedWindowTheme(
        isDark = isDark,
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
            MaterialTheme(
                colorScheme = outerColorScheme,
                typography = outerTypography,
                shapes = outerShapes,
            ) {
                content()
            }
        }
    }
}
