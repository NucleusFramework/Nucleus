@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.internal.TaoDecoratedWindowAdapter
import androidx.compose.ui.window.v2.WindowState as WindowStateV2
import dev.nucleusframework.window.tao.v2.WindowState as NucleusWindowState

/**
 * Decorated window. Inside [content], `nucleusWindow` is a portable
 * [NucleusWindow] handle; reach for `nucleusWindow.unsafe.*` only when you
 * genuinely need the Tao-specific window.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.DecoratedWindow(
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
    // Linux only: make this window a popup overlay of [popupFor]. On Wayland
    // it maps as a wl_subsurface of the parent — the only window kind a client
    // can freely position under xdg-shell (coordinates are parent-relative).
    // For cursor-following overlays such as drag ghosts. Ignored on
    // macOS/Windows.
    popupFor: NucleusWindow? = null,
    // Materialise Compose Popup layers as native transparent windows
    // (NSPanel / WS_POPUP HWND) instead of drawing them inline in this
    // window's render target. Supported on all three platforms.
    nativePopupLayers: Boolean = false,
    // Replace Compose-drawn context menus (ContextMenuArea, text
    // Cut/Copy/Paste, spellcheck items) with the OS-looking menu: `NSMenu` on
    // macOS, or a Compose flyout on Linux (Adwaita) / Windows (Fluent).
    // Independent of [nativePopupLayers].
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (macOS: NSApplication accessory policy, app-wide; Windows:
    // WS_EX_TOOLWINDOW, per-window; Linux: GTK skip-taskbar hint, per-window,
    // X11/XWayland only).
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    // The overlay flags below are appended rather than grouped with
    // [undecorated]: inserting a parameter mid-list silently shifts every
    // positional call site. Keep new parameters here, before [content].
    //
    // Full-window per-pixel transparency: pixels the content leaves at alpha 0
    // show the desktop behind the window (#416). Creation-time only — cannot
    // change after the native window exists. Typically combined with
    // [undecorated].
    transparent: Boolean = false,
    // Click-through window: pointer events fall through to whatever sits
    // below, and the window never intercepts input. Pair with
    // `focusable = false` for passive overlays (watermarks, HUDs). Reactive.
    clickThrough: Boolean = false,
    // Show the window on every desktop instead of only the one it was created
    // on — macOS Spaces (`NSWindowCollectionBehaviorCanJoinAllSpaces`), Linux
    // workspaces (`gtk_window_stick`, X11/XWayland only — native Wayland has no
    // workspace protocol and logs a warning). No-op on Windows, where a
    // [hiddenFromDock] window already shows on every virtual desktop. Reactive.
    visibleOnAllWorkspaces: Boolean = false,
    // Linux only: give this window an X11 surface even when the app runs on a
    // native Wayland session (a second GdkDisplay opened on DISPLAY, i.e.
    // XWayland). Creation-time only. Wayland has no protocol for client-side
    // stacking, programmatic positioning or workspace stickiness, so an overlay
    // that needs them can take an X11 surface for itself while the rest of the
    // app keeps its Wayland surfaces. Ignored on other platforms.
    forceX11: Boolean = false,
    // Pin the window below every other window instead of above them — macOS
    // `NSWindowLevel.BelowNormal`, Windows `HWND_BOTTOM`, Linux
    // `gtk_window_set_keep_below` (X11/XWayland only, native Wayland has no
    // client-side stacking protocol). For wallpaper-level overlays such as
    // desktop widgets. Mutually exclusive with [alwaysOnTop] — last one set
    // wins. Reactive.
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.Window(
                scope = this,
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
                alwaysOnBottom = alwaysOnBottom,
                popupFor = popupFor,
                nativePopupLayers = nativePopupLayers,
                nativeContextMenu = nativeContextMenu,
                hiddenFromDock = hiddenFromDock,
                minimumSize = minimumSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

/**
 * Receiver-less [DecoratedWindow], resolving the application scope from
 * [LocalNucleusApplicationScope]. Parameters behave exactly like the
 * [NucleusApplicationScope] overload.
 *
 * Use it to open a window from anywhere in the composition — a navigation
 * destination, a row action — the way Compose Desktop's `Window` can be
 * called. Fails outside a `nucleusApplication { … }` block, where no scope
 * exists.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    transparent: Boolean = false,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.DecoratedWindow(
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
        popupFor = popupFor,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        hiddenFromDock = hiddenFromDock,
        minimumSize = minimumSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        transparent = transparent,
        clickThrough = clickThrough,
        visibleOnAllWorkspaces = visibleOnAllWorkspaces,
        forceX11 = forceX11,
        alwaysOnBottom = alwaysOnBottom,
        content = content,
    )
}

/**
 * [DecoratedWindow] overload for Compose Multiplatform 1.12's experimental
 * window API v2.
 *
 * [state] has no default so `DecoratedWindow(onCloseRequest) { }` still
 * resolves to the v1 overload.
 *
 * `requestScreen` / `screenId` are not applied on Tao (primary work area
 * only).
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowStateV2,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    transparent: Boolean = false,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.WindowV2(
                scope = this,
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
                alwaysOnBottom = alwaysOnBottom,
                popupFor = popupFor,
                nativePopupLayers = nativePopupLayers,
                nativeContextMenu = nativeContextMenu,
                hiddenFromDock = hiddenFromDock,
                minSize = minSize,
                maxSize = maxSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

/**
 * Receiver-less [DecoratedWindow] for Compose window API v2. See the
 * [NucleusApplicationScope] overload.
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: WindowStateV2,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    transparent: Boolean = false,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.DecoratedWindow(
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
        popupFor = popupFor,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        hiddenFromDock = hiddenFromDock,
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        transparent = transparent,
        clickThrough = clickThrough,
        visibleOnAllWorkspaces = visibleOnAllWorkspaces,
        forceX11 = forceX11,
        alwaysOnBottom = alwaysOnBottom,
        content = content,
    )
}

/**
 * [DecoratedWindow] overload for the AWT-free window API v2 clone.
 *
 * [state] has no default so `DecoratedWindow(onCloseRequest) { }` still
 * resolves to the v1 overload.
 *
 * Every request is applied here, `requestScreen` included — see
 * [dev.nucleusframework.window.tao.v2.rememberWindowState].
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun NucleusApplicationScope.DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    transparent: Boolean = false,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is TaoNucleusApplicationScope ->
            TaoDecoratedWindowAdapter.WindowNucleusV2(
                scope = this,
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
                alwaysOnBottom = alwaysOnBottom,
                popupFor = popupFor,
                nativePopupLayers = nativePopupLayers,
                nativeContextMenu = nativeContextMenu,
                hiddenFromDock = hiddenFromDock,
                minSize = minSize,
                maxSize = maxSize,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
                content = content,
            )
    }
}

/**
 * Receiver-less [DecoratedWindow] for the AWT-free window API v2 clone. See the
 * [NucleusApplicationScope] overload.
 */
@ExperimentalComposeUiApi
@Suppress("FunctionNaming", "LongParameterList")
@Composable
public fun DecoratedWindow(
    onCloseRequest: () -> Unit,
    state: NucleusWindowState,
    visible: Boolean = true,
    title: String = "",
    icon: Painter? = null,
    resizable: Boolean = true,
    enabled: Boolean = true,
    focusable: Boolean = true,
    alwaysOnTop: Boolean = false,
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    nativeContextMenu: Boolean = false,
    hiddenFromDock: Boolean = false,
    minSize: DpSize = DpSize.Unspecified,
    maxSize: DpSize = DpSize.Unspecified,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    transparent: Boolean = false,
    clickThrough: Boolean = false,
    visibleOnAllWorkspaces: Boolean = false,
    forceX11: Boolean = false,
    alwaysOnBottom: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    LocalNucleusApplicationScope.current.DecoratedWindow(
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
        popupFor = popupFor,
        nativePopupLayers = nativePopupLayers,
        nativeContextMenu = nativeContextMenu,
        hiddenFromDock = hiddenFromDock,
        minSize = minSize,
        maxSize = maxSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
        transparent = transparent,
        clickThrough = clickThrough,
        visibleOnAllWorkspaces = visibleOnAllWorkspaces,
        forceX11 = forceX11,
        alwaysOnBottom = alwaysOnBottom,
        content = content,
    )
}
