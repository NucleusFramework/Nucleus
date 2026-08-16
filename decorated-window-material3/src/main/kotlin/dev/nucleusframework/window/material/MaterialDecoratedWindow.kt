@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.nucleusframework.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlin.internal.LowPriorityInOverloadResolution
import dev.nucleusframework.application.DecoratedWindow as NucleusDecoratedWindow

/**
 * Material 3 wrapper around the AWT-based `DecoratedWindow` (JBR / JNI
 * backends). Picks Material colors via [rememberMaterialTitleBarStyle] and
 * wraps with [NucleusDecoratedWindowTheme].
 *
 * For new code, prefer the [NucleusApplicationScope] overload below — it
 * works the same on AWT and Tao without changing the call site.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
// Low priority: NucleusApplicationScope implements ApplicationScope, so inside
// nucleusApplication both overloads are applicable — the Nucleus one must win.
@LowPriorityInOverloadResolution
public fun ApplicationScope.MaterialDecoratedWindow(
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
            content = content,
        )
    }
}

/**
 * Material 3 wrapper that picks the correct backend automatically. Use this
 * inside `nucleusApplication { … }` — works on AWT (JBR/JNI) and Tao with the
 * same call site.
 *
 * Theme tokens captured from the outer composition are re-provided inside the
 * window content, which matters on Tao (each window owns its own ComposeScene
 * and CompositionLocals don't propagate across scenes).
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
    // Replace Compose-drawn context menus with the OS menu. Tao + macOS
    // only; no-op on Windows, Linux, and AWT.
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
    val outerColorScheme = MaterialTheme.colorScheme
    val outerTypography = MaterialTheme.typography
    val outerShapes = MaterialTheme.shapes
    val windowStyle = rememberMaterialWindowStyle(outerColorScheme)
    val resolvedTitleBarStyle = titleBarStyle ?: rememberMaterialTitleBarStyle(outerColorScheme)
    val isDark = outerColorScheme.isDark()

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
                colorScheme = outerColorScheme,
                typography = outerTypography,
                shapes = outerShapes,
            ) {
                content()
            }
        }
    }
}
