package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.rememberDialogState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedDialogScope
import dev.nucleusframework.window.DecoratedDialog
import dev.nucleusframework.window.DecoratedDialogScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import org.jetbrains.jewel.foundation.theme.JewelTheme
import dev.nucleusframework.application.DecoratedDialog as NucleusDecoratedDialogFn

/** AWT-backed (JBR / JNI) Jewel-styled wrapper for [DecoratedDialog]. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun JewelDecoratedDialog(
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

/**
 * Backend-agnostic Jewel-styled wrapper. Use inside `nucleusApplication { … }`
 * — works on AWT (JBR/JNI) and Tao with the same call site.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.JewelDecoratedDialog(
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
    val windowStyle = rememberJewelWindowStyle()
    val titleBarStyle = rememberJewelTitleBarStyle()

    NucleusDecoratedWindowTheme(
        isDark = JewelTheme.isDark,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle,
    ) {
        NucleusDecoratedDialogFn(
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
            content()
        }
    }
}
