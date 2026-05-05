package io.github.kdroidfilter.nucleus.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import io.github.kdroidfilter.nucleus.application.NucleusApplicationScope
import io.github.kdroidfilter.nucleus.application.NucleusDecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.AwtDecoratedDialogScope
import io.github.kdroidfilter.nucleus.window.DecoratedDialog
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.application.DecoratedDialog as NucleusDecoratedDialog

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
    content: @Composable AwtDecoratedDialogScope.() -> Unit,
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

/**
 * Material 3 wrapper that picks the correct backend automatically. Use inside
 * `nucleusApplication { … }` — works on AWT (JBR/JNI) and Tao with the same
 * call site.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun NucleusApplicationScope.MaterialDecoratedDialog(
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
