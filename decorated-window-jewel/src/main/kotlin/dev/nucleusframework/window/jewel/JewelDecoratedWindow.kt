package dev.nucleusframework.window.jewel

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.styling.TitleBarStyle
import dev.nucleusframework.application.DecoratedWindow as NucleusDecoratedWindowFn

private const val LUMINANCE_THRESHOLD = 0.5f

/**
 * Jewel-styled window. Use inside `nucleusApplication { … }`. Each window owns
 * its own `ComposeScene`, so the resolved styles are re-provided inside the new
 * scene.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.JewelDecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    // Fully borderless window (no macOS traffic lights) — for overlay/ghost windows.
    undecorated: Boolean = false,
    // Linux only: popup overlay of [popupFor] — on Wayland a wl_subsurface
    // of the parent, the only client-positionable window kind under xdg-shell
    // (parent-relative coordinates). For drag ghosts. Ignored elsewhere.
    popupFor: NucleusWindow? = null,
    // Replace Compose-drawn context menus with the OS-looking menu: `NSMenu`
    // on macOS, or a Compose flyout on Linux (Adwaita) / Windows (Fluent).
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (on Linux effective on X11/XWayland only).
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    titleBarStyle: TitleBarStyle? = null,
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
    // Materialise Compose Popup layers as native transparent windows
    // (NSPanel / WS_POPUP HWND) instead of drawing them inline in this
    // window's render target, so a popup can leave the window bounds.
    //
    // Jewel's own components get this for free: `LocalPopupRenderer`'s default
    // renderer delegates to `androidx.compose.ui.window.Popup`, so every
    // `ListComboBox`, `PopupMenu`, `Dropdown` and tooltip in this window flows
    // through the native layers — including their screen-aware placement
    // (#569), which keeps a combo box popup on the display when the window
    // sits at its bottom edge. Supported on all three platforms.
    nativePopupLayers: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    val windowStyle = rememberJewelWindowStyle()
    val jewelTitleBarStyle = rememberJewelTitleBarStyle()
    val resolvedTitleBarStyle = titleBarStyle ?: jewelTitleBarStyle
    val titleBarIsDark = resolvedTitleBarStyle.colors.background.luminance() < LUMINANCE_THRESHOLD

    NucleusDecoratedWindowTheme(
        isDark = titleBarIsDark,
        windowStyle = windowStyle,
        titleBarStyle = resolvedTitleBarStyle,
    ) {
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
            transparent = transparent,
            clickThrough = clickThrough,
            visibleOnAllWorkspaces = visibleOnAllWorkspaces,
            forceX11 = forceX11,
            undecorated = undecorated,
            popupFor = popupFor,
            nativePopupLayers = nativePopupLayers,
            nativeContextMenu = nativeContextMenu,
            hiddenFromDock = hiddenFromDock,
            minimumSize = minimumSize,
            onPreviewKeyEvent = onPreviewKeyEvent,
            onKeyEvent = onKeyEvent,
        ) {
            ProvideJewelSpellcheckMenu { content() }
        }
    }
}
