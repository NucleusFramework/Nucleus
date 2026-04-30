package io.github.kdroidfilter.nucleus.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.nucleus.window.DecoratedWindow
import io.github.kdroidfilter.nucleus.window.DecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.NucleusDecoratedWindowTheme
import io.github.kdroidfilter.nucleus.window.styling.TitleBarStyle

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun MaterialDecoratedWindow(
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
    /**
     * Optional override for the body background colour. Leave `null` for the
     * standard behaviour. Set to
     * [androidx.compose.ui.graphics.Color.Transparent] when hosting a native
     * NSView underneath (e.g. `MacWebView` from `webview-macos`).
     */
    bodyBackground: androidx.compose.ui.graphics.Color? = null,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val windowStyle = rememberMaterialWindowStyle(colorScheme)
    val materialTitleBarStyle = rememberMaterialTitleBarStyle(colorScheme)

    NucleusDecoratedWindowTheme(
        isDark = colorScheme.isDark(),
        windowStyle = windowStyle,
        titleBarStyle = titleBarStyle ?: materialTitleBarStyle,
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
            bodyBackground = bodyBackground,
            content = content,
        )
    }
}
