package dev.nucleusframework.window.tao.popup

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.TaoWindow
import kotlin.coroutines.CoroutineContext

/**
 * Linux counterpart to [TaoPopupHost] (macOS) / [TaoPopupHostWindows].
 * Plumbing the popup scene layers need from their host scene on Linux.
 *
 * macOS keys on `parentNsView`, Windows on `parentHwnd`; Linux keys on the
 * parent [TaoWindow] itself — popup layers are real Tao popup windows
 * (`openWindow(popupOf = parent)`: GTK_WINDOW_POPUP, override-redirect on
 * X11, `wl_subsurface` on Wayland) so they need the parent handle, not a
 * raw native pointer.
 *
 * Threading: every call must run on the Tao event-loop thread.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("TooManyFunctions")
internal interface TaoPopupHostLinux {
    /** Tao window hosting the main scene — the popup windows' `popupOf` parent. */
    val parentWindow: TaoWindow

    /**
     * The owner window's exception handler, so a popup scene reports failures
     * through the same channel as the window it belongs to. See
     * [TaoPopupHost.exceptionHandler].
     */
    val exceptionHandler: WindowExceptionHandler? get() = null

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
     * Parent window's content origin in global screen physical pixels.
     * X11 popups (override-redirect) are positioned in root coordinates,
     * so a layer's window-relative `boundsInWindow` must be offset by
     * this. On Wayland the popup is a `wl_subsurface` positioned
     * *relative to the parent surface*, so this is [IntOffset.Zero] and
     * `boundsInWindow` is used as-is.
     */
    val parentScreenOriginPx: IntOffset

    /**
     * [parentScreenOriginPx] paired with every display's work area, so a layer
     * can clamp its native frame into the real screen instead of the
     * window-rooted virtual one Compose positions against. See
     * [TaoPopupHost.popupScreenGeometry].
     *
     * `null` on Wayland: a popup there is a `wl_subsurface` placed relative to
     * the parent surface, and no global position exists to clamp against.
     */
    val popupScreenGeometry: PopupScreenGeometry? get() = null

    /** Coroutine context to feed inner scenes. */
    val sceneCoroutineContext: CoroutineContext

    /**
     * Offset added to a popup's `boundsInWindow` before positioning the
     * popup window. Non-zero when the popup originates from a nested
     * scene whose origin is not at the host window's top-left.
     *
     * The hidden-titlebar CSD content origin is **not** reported here —
     * [TaoWindow.setOuterPosition] applies it for every Linux `popupOf`
     * window so drag ghosts and in-scene layers share one code path.
     */
    val coordinateOffset: IntOffset get() = IntOffset.Zero

    /** The dialog scrims of this host's layers — see [TaoPopupHost.popupScrims]. */
    val popupScrims: PopupScrimRegistry

    fun requestRedraw()

    /**
     * Registers a per-frame render callback. The host invokes it at the end
     * of its own [TaoComposeSceneHostLinux.onRedrawRequested], after the main
     * scene's EGL context was released — each popup binds its *own* private
     * EGL context (Linux convention: one context per attachment), paints,
     * presents (swap interval 0, non-blocking) and releases.
     */
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
     * Registers a key handler consulted by the host's `onKeyEvent` before
     * the main scene's dispatch. Popup windows never own keyboard focus on
     * Linux (override-redirect windows on X11, subsurfaces on Wayland), so
     * key events keep arriving on the parent window and are forwarded here
     * — same piggy-back path as the macOS popupKeyHandlers chain.
     */
    fun registerKeyHandler(
        token: Any,
        handler: (KeyEvent) -> Boolean,
    )

    fun unregisterKeyHandler(token: Any)

    /**
     * Registers a callback invoked when the host window's screen position
     * changes. X11 popups are positioned in root coordinates and don't
     * auto-track their owner — each layer re-issues its frame here.
     * Never fires on Wayland (no global positions; subsurfaces are
     * parent-relative and follow for free).
     */
    fun registerOwnerMoveListener(
        token: Any,
        onMoved: () -> Unit,
    )

    fun unregisterOwnerMoveListener(token: Any)

    /**
     * Registers a callback invoked when a pointer press lands on the
     * *parent* window while this layer is alive. Popup windows own their
     * input region, so any press the parent scene receives is by
     * definition outside every popup — the Linux stand-in for macOS's
     * NSEvent monitor / Windows' WH_MOUSE_LL hook (neither of which has a
     * Wayland equivalent).
     */
    fun registerOutsidePressListener(
        token: Any,
        onPress: (PointerButton?) -> Unit,
    )

    fun unregisterOutsidePressListener(token: Any)

    /**
     * Delivers a pointer event that landed on a layer's **draw margin** to the
     * owner window's scene, at [positionPx] in owner-window physical pixels.
     *
     * A layer's window is inflated past the popup's layout bounds so shadows
     * and the appearance animation are not clipped ([popupDrawBounds]). That
     * margin is transparent, but on Linux it is still the popup's window as far
     * as the display server is concerned, so the press never reaches the owner
     * — a click on a button beside an open menu would dismiss the menu and
     * never press the button, and hovering past the menu's edge would freeze
     * the owner's hover state. Windows and macOS get the pass-through from the
     * OS (the layer hands it the *content* rect); GTK's own input shaping does
     * not take on a popup toplevel, so the layer routes the event here instead.
     *
     * A [PointerEventType.Press] is expected to behave exactly like a press
     * that reached the owner natively — including the outside-press listeners
     * and the recompose between them and the dispatch.
     */
    fun forwardMarginPointer(
        eventType: PointerEventType,
        positionPx: Offset,
        button: PointerButton?,
    )

    /**
     * Claims the parent's compositor-positioned popup for [token]. On native
     * Wayland a popup layer that gets it maps as an `xdg_popup` the compositor
     * keeps on screen ([TaoWindow.anchorPopupInParent]); an `xdg_popup` must be
     * its parent's topmost popup and GDK refuses to map a second one, so only
     * one layer at a time may take that path — the others stay subsurfaces.
     * Returns `false` while another layer holds it.
     */
    fun acquireCompositorPopup(token: Any): Boolean

    /** Releases [acquireCompositorPopup]'s claim; a no-op for a token that never held it. */
    fun releaseCompositorPopup(token: Any)
}
