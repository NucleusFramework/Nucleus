package dev.nucleusframework.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.internal.TaoDecoratedWindowAdapter
import dev.nucleusframework.window.AwtDecoratedWindowScope
import dev.nucleusframework.window.DecoratedWindowState
import dev.nucleusframework.window.DecoratedWindow as AwtDecoratedWindow

/**
 * Backend-agnostic decorated window. Inside [content], `window` is a
 * [NucleusWindow] usable on any backend; reach for `window.unsafe.*` only when
 * you genuinely need backend-specific behaviour.
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
    // Honoured by the Tao backend; the AWT backend currently ignores it.
    undecorated: Boolean = false,
    // Linux/Tao only: make this window a popup overlay of [popupFor]. On
    // Wayland it maps as a wl_subsurface of the parent — the only window kind
    // a client can freely position under xdg-shell (coordinates are
    // parent-relative). For cursor-following overlays such as drag ghosts.
    // Ignored by the AWT backend and on macOS/Windows.
    popupFor: NucleusWindow? = null,
    // Materialise Compose Popup layers as native transparent windows
    // (NSPanel / WS_POPUP HWND) instead of drawing them inline in this
    // window's render target. Honoured by the Tao backend on all three
    // platforms; ignored by AWT.
    nativePopupLayers: Boolean = false,
    // Replace Compose-drawn context menus (ContextMenuArea, text
    // Cut/Copy/Paste, spellcheck items) with the OS-looking menu. Tao +
    // macOS (`NSMenu`), or a Compose flyout on Linux (Adwaita) / Windows
    // (Fluent). No-op on AWT.
    // Independent of [nativePopupLayers].
    nativeContextMenu: Boolean = false,
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (macOS: NSApplication accessory policy, app-wide; Windows:
    // WS_EX_TOOLWINDOW, per-window; Linux: GTK skip-taskbar hint, per-window,
    // X11/XWayland only). Honoured by the Tao backend; ignored by AWT.
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
    // [undecorated]. Honoured by the Tao backend; the AWT backend ignores it.
    transparent: Boolean = false,
    // Click-through window: pointer events fall through to whatever sits
    // below, and the window never intercepts input. Pair with
    // `focusable = false` for passive overlays (watermarks, HUDs). Reactive.
    // Honoured by the Tao backend; the AWT backend ignores it.
    clickThrough: Boolean = false,
    // Show the window on every desktop instead of only the one it was created
    // on — macOS Spaces (`NSWindowCollectionBehaviorCanJoinAllSpaces`), Linux
    // workspaces (`gtk_window_stick`, X11/XWayland only — native Wayland has no
    // workspace protocol and logs a warning). No-op on Windows, where a
    // [hiddenFromDock] window already shows on every virtual desktop. Reactive.
    // Honoured by the Tao backend; the AWT backend ignores it.
    visibleOnAllWorkspaces: Boolean = false,
    // Linux only: give this window an X11 surface even when the app runs on a
    // native Wayland session (a second GdkDisplay opened on DISPLAY, i.e.
    // XWayland). Creation-time only. Wayland has no protocol for client-side
    // stacking, programmatic positioning or workspace stickiness, so an overlay
    // that needs them can take an X11 surface for itself while the rest of the
    // app keeps its Wayland surfaces. Honoured by the Tao backend; ignored by
    // the AWT backend and on other platforms.
    forceX11: Boolean = false,
    content: @Composable NucleusDecoratedWindowScope.() -> Unit,
) {
    when (this) {
        is AwtNucleusApplicationScope ->
            AwtDecoratedWindow(
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
                val awtScope: AwtDecoratedWindowScope = this
                val nucleusWindow =
                    remember(window) {
                        AwtNucleusWindow(window, state, onCloseRequest)
                    }
                val scope =
                    remember(awtScope, nucleusWindow) {
                        AwtNucleusDecoratedWindowScope(awtScope, nucleusWindow)
                    }
                ObserveSingleInstanceRestore(nucleusWindow)
                CompositionLocalProvider(
                    LocalNucleusBackend provides NucleusBackend.Awt,
                    LocalNucleusWindow provides nucleusWindow,
                ) {
                    scope.content()
                }
            }

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
        content = content,
    )
}

internal class AwtNucleusDecoratedWindowScope(
    private val delegate: AwtDecoratedWindowScope,
    override val nucleusWindow: NucleusWindow,
) : NucleusDecoratedWindowScope,
    AwtDecoratedWindowScope by delegate {
    override val state: DecoratedWindowState get() = delegate.state
}
