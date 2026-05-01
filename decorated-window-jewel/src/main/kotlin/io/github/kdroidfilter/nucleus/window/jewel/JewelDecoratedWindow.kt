package io.github.kdroidfilter.nucleus.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.window.AwtDecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle
import io.github.kdroidfilter.nucleus.window.tao.ApplicationScope as TaoApplicationScope
import io.github.kdroidfilter.nucleus.window.tao.MacOSStyle
import io.github.kdroidfilter.nucleus.window.tao.DecoratedWindow as TaoDecoratedWindow
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
 * Tao-backed Jewel-styled wrapper for [DecoratedWindow].
 *
 * Tao opens a fresh ComposeScene per window so [JewelTheme] does not propagate
 * across the boundary; the resolved Jewel + Nucleus styles are computed in the
 * outer composition and re-provided inside the new scene.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun TaoApplicationScope.JewelDecoratedWindow(
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
    macOSStyle: MacOSStyle = MacOSStyle.Auto,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()
    val resolvedTitleBarStyle = titleBarStyle ?: jewelTitleBarStyle
    val titleBarIsDark = resolvedTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    TaoDecoratedWindow(
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
        macOSStyle = macOSStyle,
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
