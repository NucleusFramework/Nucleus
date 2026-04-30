package io.github.kdroidfilter.nucleus.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.jetbrains.JBR
import io.github.kdroidfilter.nucleus.core.runtime.Platform
import io.github.kdroidfilter.nucleus.window.internal.InstallMinimumSizeAfterCentering
import io.github.kdroidfilter.nucleus.window.internal.inflateToMinimumSize

@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun DecoratedWindow(
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
    /**
     * Optional override for the body background colour. Leave `null` for the
     * standard behaviour (title-bar colour fills the whole window, suppresses
     * the white flash during live resize on Windows). Set to
     * [androidx.compose.ui.graphics.Color.Transparent] when hosting a native
     * NSView underneath the Compose surface (e.g. WKWebView via
     * `webview-macos`).
     */
    bodyBackground: androidx.compose.ui.graphics.Color? = null,
    content: @Composable DecoratedWindowScope.() -> Unit,
) {
    remember {
        check(JBR.isAvailable()) {
            "DecoratedWindow requires JetBrains Runtime (JBR). " +
                "Please run your application on JBR."
        }
    }

    state.inflateToMinimumSize(minimumSize)

    val undecorated = Platform.Linux == Platform.Current

    Window(
        onCloseRequest,
        state,
        visible,
        title,
        icon,
        undecorated,
        transparent = false,
        resizable,
        enabled,
        focusable,
        alwaysOnTop,
        onPreviewKeyEvent,
        onKeyEvent,
    ) {
        InstallMinimumSizeAfterCentering(minimumSize)

        DecoratedWindowBody(
            title = title,
            icon = icon,
            undecorated = undecorated,
            onCloseRequest = onCloseRequest,
            bodyBackground = bodyBackground,
            content = content,
        )
    }
}
