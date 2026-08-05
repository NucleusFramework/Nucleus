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
fun NucleusApplicationScope.DecoratedWindow(
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
    // Hide this window from the OS taskbar/Dock while it stays visible and
    // focusable (macOS: NSApplication accessory policy, app-wide; Windows:
    // WS_EX_TOOLWINDOW, per-window; Linux: GTK skip-taskbar hint, per-window,
    // X11/XWayland only). Honoured by the Tao backend; ignored by AWT.
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
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
                popupFor = popupFor,
                nativePopupLayers = nativePopupLayers,
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
    undecorated: Boolean = false,
    popupFor: NucleusWindow? = null,
    nativePopupLayers: Boolean = false,
    hiddenFromDock: Boolean = false,
    minimumSize: DpSize? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    onKeyEvent: (KeyEvent) -> Boolean = { false },
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
        hiddenFromDock = hiddenFromDock,
        minimumSize = minimumSize,
        onPreviewKeyEvent = onPreviewKeyEvent,
        onKeyEvent = onKeyEvent,
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
