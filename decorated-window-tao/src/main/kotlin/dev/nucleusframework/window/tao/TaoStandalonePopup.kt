// #636: the window/dialog openers below are `@ComposableOpenTarget(-1)` with a
// `@UiComposable` content lambda — callable from any applier, always composing
// UI — so a non-UI composable called in the caller's scope cannot reclassify
// the window content. ktlint's `annotation` and `function-type-modifier-spacing`
// rules contradict each other on the resulting two-annotation parameter type.
@file:Suppress("ktlint:standard:annotation")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableOpenTarget
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.UiComposable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeLinux
import dev.nucleusframework.window.tao.popup.StandalonePopupHost
import dev.nucleusframework.window.tao.popup.TaoStandalonePopupHost
import dev.nucleusframework.window.tao.popup.TaoStandalonePopupHostLinux
import dev.nucleusframework.window.tao.popup.TaoStandalonePopupHostMac

/**
 * Whether [TaoStandalonePopup] can actually show a panel on this system.
 *
 * Always true on Windows and macOS (modulo native library loading). On
 * Linux the panel is a raw X11 window and needs a reachable X server —
 * native X11 sessions or XWayland (present on effectively all Wayland
 * desktops). Returns false on the rare Wayland-only setups; callers should
 * then fall back to a regular window.
 */
public fun isTaoStandalonePopupAvailable(): Boolean =
    when (Platform.Current) {
        Platform.Windows, Platform.MacOS -> true
        Platform.Linux ->
            PopupNativeBridgeLinux.isLoaded && PopupNativeBridgeLinux.nativeIsAvailable()
        else -> false
    }

/**
 * Standalone transparent popup: a top-level, ownerless, non-activating
 * native panel with per-pixel transparency, hosting [content] in its own
 * Compose scene. There is no backing "window" anywhere — nothing appears
 * in the taskbar/Dock or the app switcher — and rendering is driven on
 * demand (no owner window render loop). Built for system-tray popups.
 *
 * Windows uses an ownerless `WS_POPUP` + DComp surface; macOS an ownerless
 * non-activating `NSPanel` + `CAMetalLayer`; Linux an override-redirect
 * ARGB32 X11 window (through XWayland on Wayland sessions — see
 * [isTaoStandalonePopupAvailable] for the fallback signal when no X server
 * is reachable). When the native pipeline is unavailable the composable is
 * a no-op.
 *
 * Must be called inside `taoApplication { }` (directly or through
 * `nucleusApplication` on the Tao backend).
 *
 * @param visible shows/hides the panel; the composition (and [content] state)
 *   is retained while hidden.
 * @param position top-left corner in logical (dp) screen coordinates.
 * @param size panel size in dp.
 * @param focusable whether the panel can take keyboard focus on click.
 * @param onOutsideClick invoked when the user clicks anywhere outside the
 *   panel while it is visible (native mouse-hook / NSEvent monitor / XI2 raw
 *   button monitor, fires on mouse-down).
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
@ComposableOpenTarget(-1)
public fun TaoStandalonePopup(
    visible: Boolean,
    position: WindowPosition.Absolute,
    size: DpSize,
    focusable: Boolean = true,
    onOutsideClick: (() -> Unit)? = null,
    onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    content: @Composable @UiComposable () -> Unit,
) {
    if (Platform.Current != Platform.Windows &&
        Platform.Current != Platform.MacOS &&
        Platform.Current != Platform.Linux
    ) {
        return
    }

    // Native resources are allocated inside remember{}: if the composition
    // is abandoned before DisposableEffect registers, the panel leaks.
    // Standard Compose caveat, negligible for an application-scoped popup.
    val host: StandalonePopupHost =
        remember {
            when (Platform.Current) {
                Platform.MacOS -> TaoStandalonePopupHostMac()
                Platform.Linux -> TaoStandalonePopupHostLinux()
                else -> TaoStandalonePopupHost()
            }
        }
    if (!host.isValid) return

    DisposableEffect(Unit) {
        onDispose { host.dispose() }
    }

    // Bridge the caller's composition locals into the panel's own scene
    // (fresh scenes don't inherit locals), but keep the scene's density —
    // the outer application composition runs with GlobalDensity(1f).
    // Both the locals and the content go through State reads INSIDE the
    // panel composition, so outer changes (dark mode, strings…) recompose
    // the panel instead of freezing first-composition values.
    val outerLocals = rememberUpdatedState(currentCompositionLocalContext)
    val currentContent = rememberUpdatedState(content)
    val sceneDensity = Density(host.scale)

    SideEffect {
        host.onPreviewKeyEvent = onPreviewKeyEvent
        host.onKeyEvent = onKeyEvent
    }

    // All host mutations run AFTER the current composition pass: setContent
    // composes the panel's own scene, which must never nest inside an active
    // composition of the caller's composition.
    LaunchedEffect(host) {
        host.setContent {
            CompositionLocalProvider(outerLocals.value) {
                CompositionLocalProvider(LocalDensity provides sceneDensity) {
                    // Innermost, so the panel's own plumbing (its TextureView
                    // texture host) wins over the same locals replayed from the
                    // caller's window scene — see [StandalonePopupHost.ProvidePanelLocals].
                    host.ProvidePanelLocals {
                        currentContent.value()
                    }
                }
            }
        }
    }
    LaunchedEffect(host, position, size) {
        host.setFrame(
            xDp = position.x.value,
            yDp = position.y.value,
            widthDp = size.width.value,
            heightDp = size.height.value,
        )
    }
    LaunchedEffect(host, focusable) { host.setFocusable(focusable) }
    LaunchedEffect(host, visible) { host.setVisible(visible) }

    val outsideClickState = rememberUpdatedState(onOutsideClick)
    LaunchedEffect(host, onOutsideClick != null) {
        host.setOutsideClickListener(
            if (onOutsideClick != null) {
                { outsideClickState.value?.invoke() }
            } else {
                null
            },
        )
    }
}
