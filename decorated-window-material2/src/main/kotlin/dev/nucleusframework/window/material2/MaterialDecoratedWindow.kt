package dev.nucleusframework.window.material2

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.application.DecoratedWindow as NucleusDecoratedWindow

@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun MaterialDecoratedWindow(
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
    val colors = MaterialTheme.colors
    val windowStyle = rememberMaterialWindowStyle(colors)
    val materialTitleBarStyle = rememberMaterialTitleBarStyle(colors)

    NucleusDecoratedWindowTheme(
        isDark = !colors.isLight,
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
            content = content,
        )
    }
}

/**
 * Material 2 wrapper that picks the correct backend automatically. Use inside
 * `nucleusApplication { … }` — works on AWT (JBR/JNI) and Tao with the same
 * call site.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.MaterialDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    // Materialise Compose Popup layers as native transparent windows
    // (NSPanel / WS_POPUP HWND / Tao popup window on Linux) so menus can
    // extend past the window bounds. Honoured by the Tao backend; ignored by AWT.
    nativePopupLayers: Boolean = false,
    // Replace Compose-drawn context menus with the OS-looking menu. Tao +
    // macOS (`NSMenu`), or a Compose flyout on Linux (Adwaita) / Windows
    // (Fluent). No-op on AWT.
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (Tao backend; on Linux effective on X11/XWayland only).
    // No-op on AWT.
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    val outerColors = MaterialTheme.colors
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColors)
    val resolvedTitleBarStyle = titleBarStyle ?: rememberMaterialTitleBarStyle(outerColors)
    val isDark = !outerColors.isLight

    NucleusDecoratedWindowTheme(
        isDark = isDark,
        windowStyle = windowStyle,
        titleBarStyle = resolvedTitleBarStyle,
    ) {
        NucleusDecoratedWindow(
            onCloseRequest = onCloseRequest,
            state = state,
            visible = visible,
            title = title,
            icon = icon,
            resizable = resizable,
            enabled = enabled,
            focusable = focusable,
            alwaysOnTop = alwaysOnTop,
            nativePopupLayers = nativePopupLayers,
            nativeContextMenu = nativeContextMenu,
            hiddenFromDock = hiddenFromDock,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
        ) {
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
