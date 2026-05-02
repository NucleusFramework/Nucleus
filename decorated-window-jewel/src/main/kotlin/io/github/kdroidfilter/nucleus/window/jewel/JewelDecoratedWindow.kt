package io.github.kdroidfilter.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.application.DecoratedWindow as NucleusDecoratedWindowFn
import io.github.kdroidfilter.nucleus.application.NucleusApplicationScope
import io.github.kdroidfilter.nucleus.application.NucleusDecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.AwtDecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import org.jetbrains.jewel.foundation.theme.JewelTheme

private const val LUMINANCE_THRESHOLD = 0.5f

/** AWT-backed (JBR / JNI) Jewel-styled wrapper for [DecoratedWindow]. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun ApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable AwtDecoratedWindowScope.() -> Unit,
) {
    val colorScheme = JewelTheme.globalColors
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()

    val titleBarIsDark = jewelTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    NucleusDecoratedWindowTheme(
        isDark = titleBarIsDark,
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle ?: jewelTitleBarStyle,
    ) {
        DecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
            content = content,
        )
    }
}

/**
 * Backend-agnostic Jewel-styled wrapper. Use inside `nucleusApplication { … }`
 * — works on AWT (JBR/JNI) and Tao with the same call site. The Tao
 * `ComposeScene` boundary is handled by re-providing the resolved styles
 * inside the new scene.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun NucleusApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()
    val resolvedTitleBarStyle = titleBarStyle ?: jewelTitleBarStyle
    val titleBarIsDark = resolvedTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    NucleusDecoratedWindowFn(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = title,
        icon = icon,
        resizable = resizable,
        enabled = enabled,
        focusable = focusable,
        alwaysOnTop = alwaysOnTop,
        minimumSize = minimumSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
    ) {
        NucleusDecoratedWindowTheme(
            isDark = titleBarIsDark,
            windowStyle = windowStyle,
            titleBarStyle = resolvedTitleBarStyle,
        ) {
            content()
        }
    }
}

