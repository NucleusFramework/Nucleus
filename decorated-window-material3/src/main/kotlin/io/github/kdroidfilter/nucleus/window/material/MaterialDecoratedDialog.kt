package io.github.kdroidfilter.nucleus.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.window.DecoratedDialog
import io.github.kdroidfilter.nucleus.window.DecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.tao.ApplicationScope as TaoApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.MacOSStyle
import io.github.kdroidfilter.nucleus.window.tao.DecoratedDialog as TaoDecoratedDialog

/** AWT-backed (JBR / JNI) Material 3 wrapper for [DecoratedDialog]. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun ApplicationScope.MaterialDecoratedDialog(
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
    val colorScheme = MaterialTheme.colorScheme
    val windowStyle = rememberMaterialWindowStyle(colorScheme)
    val titleBarStyle = rememberMaterialTitleBarStyle(colorScheme)

    NucleusDecoratedWindowTheme(
        isDark = colorScheme.isDark(),
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

/** Tao-backed Material 3 wrapper for [DecoratedDialog]. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TaoApplicationScope.MaterialDecoratedDialog(
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
    content: @Composable DecoratedDialogScope.() -> Unit,
) {
    val outerColorScheme = MaterialTheme.colorScheme
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColorScheme)
    val titleBarStyle = rememberMaterialTitleBarStyle(outerColorScheme)
    val isDark = outerColorScheme.isDark()

    TaoDecoratedDialog(
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
        macOSStyle = macOSStyle,
    ) {
        MaterialTheme(
            colorScheme = outerColorScheme,
            typography = outerTypography,
            shapes = outerShapes,
        ) {
            NucleusDecoratedWindowTheme(
                isDark = isDark,
                windowStyle = windowStyle,
                titleBarStyle = titleBarStyle,
            ) {
                content()
            }
        }
    }
}
