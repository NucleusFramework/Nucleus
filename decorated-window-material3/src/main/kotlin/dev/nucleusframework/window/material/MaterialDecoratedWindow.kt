package dev.nucleusframework.window.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.application.DecoratedWindow as NucleusDecoratedWindow

/**
 * Material 3 styled window. Use inside `nucleusApplication { … }`: picks
 * Material colors via [rememberMaterialTitleBarStyle] and wraps the window with
 * [NucleusDecoratedWindowTheme].
 *
 * Theme tokens captured from the outer composition are re-provided inside the
 * window content, because each window owns its own ComposeScene and
 * CompositionLocals don't propagate across scenes.
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
    // extend past the window bounds.
    nativePopupLayers: Boolean = false,
    // Replace Compose-drawn context menus with the OS-looking menu: `NSMenu`
    // on macOS, or a Compose flyout on Linux (Adwaita) / Windows (Fluent).
    // The flyout always opens in a native popup surface, whatever
    // `nativePopupLayers` says.
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (on Linux effective on X11/XWayland only).
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
    // Fully borderless window (no macOS traffic lights, no CSD outline) — for
    // overlay/ghost windows.
    undecorated: Boolean = false,
    // The overlay flags below mirror `dev.nucleusframework.application.DecoratedWindow`.
    //
    // Full-window per-pixel transparency: pixels the content leaves at alpha 0
    // show the desktop behind the window. Creation-time only, normally paired
    // with [undecorated].
    transparent: Boolean = false,
    // Click-through window: pointer events fall through to whatever sits below
    // and the window never intercepts input. Pair with `focusable = false` for
    // passive overlays. Reactive.
    clickThrough: Boolean = false,
    // Show the window on every desktop / macOS Space / Windows virtual desktop
    // instead of only the one it was created on. Reactive.
    visibleOnAllWorkspaces: Boolean = false,
    // Linux only: give this window an X11 surface even when the app runs on a
    // native Wayland session, for the window management Wayland has no protocol
    // for (stacking, positioning, workspace stickiness). Creation-time only.
    forceX11: Boolean = false,
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
            undecorated = undecorated,
            transparent = transparent,
            clickThrough = clickThrough,
            visibleOnAllWorkspaces = visibleOnAllWorkspaces,
            forceX11 = forceX11,
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
