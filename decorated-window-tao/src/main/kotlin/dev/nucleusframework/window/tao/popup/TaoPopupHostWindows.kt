package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowExceptionHandler
import org.jetbrains.skia.DirectContext
import kotlin.coroutines.CoroutineContext

/**
 * Windows counterpart to [TaoPopupHost]. Plumbing the overlay scene
 * (and, in Phase 6+, popup scenes) need from their host scene on
 * Windows.
 *
 * macOS keys on `parentNsView`; Windows keys on `parentHwnd`. Otherwise
 * the surface is identical so the controller can be ported line-for-line.
 *
 * Threading: every call must run on the host HWND's UI thread.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalComposeUiApi::class)
internal interface TaoPopupHostWindows {
    /** HWND of the host (Tao main) window. */
    val parentHwnd: Long

    /** Backing-scale factor (logical→physical multiplier). */
    val scale: Float

    /** Host window's content size in physical pixels. */
    val parentWindowSize: IntSize

    /** The owner window's live `WindowInfo` — see [TaoPopupHost.parentWindowInfo]. */
    val parentWindowInfo: WindowInfo

    /**
     * Screen work area in physical pixels. Used as the inner scene's
     * layout size so a tall popup (DropdownMenu, expanded Tooltip) in a
     * small parent window lays out at full height instead of being
     * artificially clipped by the owner window's bounds. Mirrors the
     * macOS [TaoPopupHost.workAreaSize] contract. Defaults to
     * [parentWindowSize] when the host can't resolve the monitor.
     */
    val workAreaSize: IntSize get() = parentWindowSize

    /**
     * Owner client origin on screen + every display's work area, so a layer
     * can clamp its native frame into the real screen instead of the
     * window-rooted virtual one Compose positions against. See
     * [TaoPopupHost.popupScreenGeometry].
     */
    val popupScreenGeometry: PopupScreenGeometry? get() = null

    /** Coroutine context to feed inner scenes. */
    val sceneCoroutineContext: CoroutineContext

    /**
     * The owner window's exception handler, so a popup scene reports failures
     * through the same channel as the window it belongs to. See
     * [TaoPopupHost.exceptionHandler].
     */
    val exceptionHandler: WindowExceptionHandler? get() = null

    /**
     * Offset added to a popup's `boundsInWindow` before positioning the
     * popup HWND in screen coords. Non-zero when the popup originates
     * from a nested scene whose origin is not at the host window's
     * top-left.
     */
    val coordinateOffset: IntOffset get() = IntOffset.Zero

    /**
     * Whether the owner window was created per-pixel transparent
     * (`DecoratedWindow(transparent = true)`, #416). Overlay scenes render
     * inside the owner's surface, so they forward this as
     * `PlatformContext.isWindowTransparent` — the hint Compose uses to pick
     * the alpha-aware dialog-scrim blend mode (#559).
     */
    val isOwnerWindowTransparent: Boolean get() = false

    /**
     * The HOST scene's Skia DirectContext — shared with every
     * overlay/popup on Windows. Single-context architecture: every
     * overlay/popup renders through the host's EGLContext bound to its
     * own d3d-texture pbuffer surface (overlay_dcomp.cpp), so all
     * surfaces draw through one Skia context. resetGLAll() between
     * draws handles the default-framebuffer state delta when
     * eglMakeCurrent swaps surfaces.
     */
    val hostDirectContext: DirectContext

    /** The dialog scrims of this host's layers — see [TaoPopupHost.popupScrims]. */
    val popupScrims: PopupScrimRegistry

    fun requestRedraw()

    /**
     * Registers a callback invoked when the host window's screen
     * position changes (user drag, programmatic move, multi-monitor
     * arrangement). Overlay/popup HWNDs are top-level WS_POPUP windows
     * whose screen coords don't auto-track their owner — each layer
     * must re-issue its `nativeSetOverlayFrame` / `nativeSetFrameInWindow`
     * here to follow the owner.
     */
    fun registerOwnerMoveListener(
        token: Any,
        onMoved: () -> Unit,
    )

    fun unregisterOwnerMoveListener(token: Any)

    /**
     * Registers a callback invoked when the host window loses keyboard
     * focus (user clicked the embedded WebView, Alt-Tabbed to another
     * app, etc.). Overlay/popup scenes use this to clear their
     * Compose-side focused TextField so its visual indicator
     * (highlight border, blinking caret) goes away when the user has
     * clearly moved attention elsewhere.
     */
    fun registerOwnerFocusLostListener(
        token: Any,
        onLost: () -> Unit,
    )

    fun unregisterOwnerFocusLostListener(token: Any)

    /**
     * Registers a callback invoked when the host window regains keyboard
     * focus. Counterpart to [registerOwnerFocusLostListener]; overlay
     * scenes use this to restore their `WindowInfo.isWindowFocused` so
     * Compose resumes the caret blink on the previously-focused
     * TextField (its focus modifier state was preserved).
     */
    fun registerOwnerFocusGainedListener(
        token: Any,
        onGained: () -> Unit,
    )

    fun unregisterOwnerFocusGainedListener(token: Any)

    fun registerRenderer(
        token: Any,
        render: () -> Unit,
    )

    fun unregisterRenderer(token: Any)

    /**
     * A layer this host handed out has closed and must leave the host's live
     * set. Compose closes a native popup layer only when the layer's own
     * disappearance animation finishes; an owner window torn down before
     * that would otherwise leave the layer's window mapped for good, so the
     * host tracks its layers and closes the survivors on detach.
     */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun onLayerClosed(layer: androidx.compose.ui.scene.ComposeSceneLayer) {}

    /**
     * Notify the host that a popup [TaoPopupSceneLayerWindows] is about
     * to close. Lets parent scenes (e.g., the [NativeView] overlay) clear
     * any focus state that the popup left in a stuck "Captured" state in
     * its parent BasicTextField. Without this, the parent field can't be
     * re-focused by a subsequent click — Compose's `clearFocus(force)`
     * called from a Tao focus-lost event later (e.g., user clicked
     * WebView2) hits a different code path that fails to release the
     * captured focus.
     */
    fun notifyPopupClosing()

    fun registerPopupClosingListener(
        token: Any,
        onClosing: () -> Unit,
    )

    fun unregisterPopupClosingListener(token: Any)

    /**
     * Registers a key handler called from `TaoComposeSceneHostWindows.onKeyEvent`
     * before the main scene's key dispatch. Returning `true` consumes
     * the event. Wired by Phase 8.
     */
    fun registerKeyHandler(
        token: Any,
        handler: (KeyEvent) -> Boolean,
    )

    fun unregisterKeyHandler(token: Any)
}

internal val LocalTaoPopupHostWindows: ProvidableCompositionLocal<TaoPopupHostWindows?> =
    compositionLocalOf { null }
