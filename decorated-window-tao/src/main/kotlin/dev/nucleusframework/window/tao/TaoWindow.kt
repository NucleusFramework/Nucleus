@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao

import androidx.compose.runtime.mutableStateOf
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTouchBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 handle to a window owned by the Tao event loop.
 *
 * Native commands are thread-safe: they post commands as user events to the
 * event loop, which executes them on the platform event-loop thread. Listener
 * registration is also safe to call across threads.
 */
@Suppress("TooManyFunctions")
public class TaoWindow internal constructor(
    public val handle: Long,
    isResizable: Boolean = true,
    /**
     * `true` when the window was created as a popup overlay of another window
     * (`openWindow(popupOf = …)` — GTK_WINDOW_POPUP, mapped as a `wl_subsurface`
     * on Wayland). The Linux host disables Mesa's FIFO frame pacing for these:
     * their EGL child is a subsurface of GDK's own (synchronized) subsurface,
     * so FIFO commits stay cached compositor-side and the pending
     * `wp_commit_timer_v1` timestamp is never consumed — the next
     * `set_timestamp` then kills the client with a fatal
     * "Commit already has timestamp" protocol error.
     */
    public val isPopup: Boolean = false,
) {
    // Snapshot-backed so Compose consumers (WindowControls*, resize hit-test
    // gating) recompose when resizability changes at runtime — the AWT
    // backends get the same reactivity from DecoratedWindowState (#260).
    private val resizableState = mutableStateOf(isResizable)

    /**
     * `true` when the window can currently be resized by the user. Initially
     * the `resizable` flag the window was created with; tracks runtime
     * [setResizable] calls. Surfaced to Compose so [WindowControlsLinux] /
     * [WindowControlsWindows] can hide the maximize button on non-resizable
     * windows (matches the `decorated-window-jni` behaviour).
     */
    public val isResizable: Boolean
        get() = resizableState.value

    /** Enables/disables user resizing (borders, maximize) at runtime. */
    public fun setResizable(resizable: Boolean) {
        if (resizableState.value == resizable) return
        resizableState.value = resizable
        NativeTaoBridge.nativeSetResizable(handle, resizable)
    }

    @Volatile
    private var readyListener: ((Int, Int) -> Unit)? = null

    // Multi-cast: the imperative `openDecoratedWindow` registers a listener
    // for host-rendering, and the @Composable `DecoratedWindow` adds another
    // for state-sync. They must coexist.
    private val resizedListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    private val movedListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    private val minimizedListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    public var isMinimized: Boolean = false
        private set

    @Volatile
    private var scaleFactorListener: ((Float) -> Unit)? = null

    @Volatile
    private var closeRequestedListener: (() -> Unit)? = null

    /**
     * Fires synchronously at the start of [requestClose] — before the native
     * destroy — so the host can present an opaque last frame (backdrop
     * teardown) while the window and its GL surface are still alive.
     * Multi-cast: the Windows host registers prepare, and e2e probes may add
     * their own. Not invoked from [requestUserClose] / [onCloseRequested]:
     * that path is cancelable ("Save before quit?") and must not permanently
     * kill Mica.
     */
    private val prepareCloseListeners = CopyOnWriteArrayList<() -> Unit>()

    private val destroyedListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var redrawListener: (() -> Unit)? = null

    private var dragWindowListener: (() -> Unit)? = null

    // Coalesces concurrent `requestRedraw` calls into one pending native request:
    // tao on Linux only drains one entry from its `draws` channel per event-loop
    // iteration, but Compose readily produces multiple invalidations per frame
    // (FlushingDispatcher.dispatch, scene invalidate, onResized…). Without
    // coalescing, the channel is flooded by the active window's redraws and
    // requests for any *other* window (e.g. a freshly-opened DecoratedDialog)
    // get buried — observable as a dialog stuck on its initial frame and
    // displaying black. Cleared in `dispatch(REDRAW_REQUESTED)`, just before
    // the listener runs, so a redraw posted *during* render still gets through.
    private val redrawPending = AtomicBoolean(false)

    // Startup white-flash workaround: the themed WM_ERASEBKGND fill is armed on
    // show() and disabled once — on the first native redraw after show. Gating
    // on this flag keeps the disable off the per-frame redraw path.
    private var startupEraseActive = false
    private val focusListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile
    private var willHideListener: (() -> Unit)? = null
    private var shownListener: (() -> Unit)? = null

    @Volatile
    private var sizeMoveListener: ((Boolean) -> Unit)? = null
    private var pointerMoveListener: ((Int, Int) -> Unit)? = null

    @Volatile
    private var pointerExitedListener: (() -> Unit)? = null

    @Volatile
    private var pointerButtonListener: ((Int, Boolean) -> Unit)? = null

    @Volatile
    private var pointerScrollListener: ((TaoPointerScrollEvent) -> Unit)? = null

    @Volatile
    private var trackpadGestureListener: TrackpadGestureListener? = null

    @Volatile
    private var touchInputListener: TouchInputListener? = null

    @Volatile
    private var keyListener: KeyEventListener? = null

    @Volatile
    internal var modifierState: Int = 0
        private set

    /**
     * macOS-only trackpad gesture listener. Receives raw magnify / rotate /
     * smart-magnify deltas already reshaped by the Rust bridge — see
     * [NativeTaoBridge.EventCallback.onTrackpadGesture] for the wire format.
     */
    public fun interface TrackpadGestureListener {
        public fun onGesture(
            kind: Int, // TaoTrackpadGesture.MAGNIFY | ROTATE | SMART_MAGNIFY
            phase: Int, // TaoTrackpadPhase.BEGAN | CHANGED | ENDED | CANCELLED
            xFixed: Int, // physical pixels × 1024, view-relative, top-left
            yFixed: Int,
            valueFixed: Int, // delta × 10000 (ratio for magnify, degrees for rotate)
        )
    }

    /**
     * Windows-only touchscreen listener (Tao emits `WindowEvent::Touch` via
     * WM_POINTER / WM_TOUCH). One callback per finger update — the host is
     * responsible for aggregating the active set before issuing a Compose
     * `sendPointerEvent`. See [NativeTaoBridge.EventCallback.onTouchInput].
     *
     * Linux uses a separate per-window bridge ([NativeTaoLinuxTouchBridge])
     * because GTK 3 doesn't surface touch through Tao's event stream.
     */
    public fun interface TouchInputListener {
        public fun onTouch(
            phase: Int, // TaoTouchEvent.PRESS | MOVE | RELEASE | CANCEL
            id: Long, // OS-assigned finger id
            xFixed: Int, // physical pixels × 1024
            yFixed: Int,
            forceFixed: Int, // pressure × 10000, or TaoTouchEvent.FORCE_UNKNOWN
        )
    }

    /** Receives keyboard events shaped like AWT for direct consumption by Compose. */
    public fun interface KeyEventListener {
        public fun onKey(
            type: Int, // TaoEventCode.KEY_DOWN | KEY_UP
            vkCode: Int, // AWT KeyEvent.VK_*
            keyLocation: Int, // AWT KeyEvent.KEY_LOCATION_*
            modifiers: Int, // TaoModifierMask bitmask
            codePoint: Int, // First Unicode scalar of typed text (or 0)
        )
    }

    public fun setTitle(title: String) {
        NativeTaoBridge.nativeSetTitle(handle, title)
    }

    public fun requestRedraw() {
        if (!redrawPending.compareAndSet(false, true)) return
        NativeTaoBridge.nativeRequestRedraw(handle)
    }

    /**
     * Clears the [redrawPending] coalescing latch and re-issues a request.
     *
     * For callers returning from an OS modal loop (`DoDragDrop`, and anything
     * else that runs its own message pump): a redraw requested just before or
     * during that loop latches `redrawPending` true, and the matching
     * REDRAW_REQUESTED can be swallowed by the nested pump. The latch then
     * suppresses *every* later [requestRedraw] and the window silently stops
     * painting — until an unrelated event happens to clear it, which is why the
     * symptom reads as "frozen until I click on it again" (the FOCUSED branch
     * in [dispatch] clears the same latch for the same reason).
     *
     * No frame is lost by calling this: a genuinely in-flight redraw just
     * yields one extra, idempotent request.
     */
    internal fun resetRedrawLatch() {
        redrawPending.set(false)
        requestRedraw()
    }

    public fun requestClose() {
        // Actual destroy path (not the cancelable close-*request*). Present an
        // opaque themed frame first: a live backdrop's translucent clear would
        // composite towards black in the close animation. The host listener
        // also flips the Compose clear path; the native fallback covers raw
        // TaoWindow usage with no host attached.
        if (prepareCloseListeners.isEmpty()) {
            if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
                val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
                if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativePrepareClose(hwnd)
            }
        } else {
            for (listener in prepareCloseListeners) listener.invoke()
        }
        NativeTaoBridge.nativeRequestClose(handle)
    }

    /**
     * Fires the [onCloseRequested] listener as if the OS had emitted a close
     * event (clicking native X, Alt+F4, etc.). Use this from custom UI like
     * the title-bar close button so the user's `onCloseRequest` callback runs
     * and gets a chance to call `exitApplication()` — bypassing it via
     * [requestClose] destroys the window but leaves the event loop running.
     *
     * Does **not** tear down a [dev.nucleusframework.window.WindowsBackdrop]:
     * the request is cancelable, and a permanent native revert here would leave
     * a still-composed backdrop dead after "Cancel". The opaque last frame is
     * prepared in [requestClose] once destroy is confirmed.
     */
    public fun requestUserClose() {
        closeRequestedListener?.invoke()
    }

    /** Starts a native window drag — call synchronously during a mouse press. */
    public fun dragWindow() {
        // Notify listeners BEFORE the grab: the compositor swallows the button
        // release once the interactive move starts, so the host needs to reset
        // its Compose pointer state to avoid getting stuck "pressed".
        dragWindowListener?.invoke()
        NativeTaoBridge.nativeDragWindow(handle)
    }

    /** Fires synchronously when [dragWindow] is invoked (compositor move grab). */
    public fun onDragWindow(block: () -> Unit) {
        dragWindowListener = block
    }

    // ── Windows touch title-bar drag (driven from raw Tao touch events) ────
    // The Compose-side `pointerInput` modifier captures the press, then
    // [beginWindowsTitleBarTouchDrag] arms a per-window drag state. Subsequent
    // touch samples are routed here from [TaoComposeSceneHostWindows.onTouchInput]
    // BEFORE Compose's pointer dispatch, so the window-move pipeline keeps
    // running even if Compose pointer routing breaks (e.g. after the layout
    // size changes mid-drag-from-maximized).

    @Volatile
    private var windowsTitleBarTouchDrag: WindowsTitleBarTouchDrag? = null

    internal fun beginWindowsTitleBarTouchDrag(
        touchId: Long,
        hwnd: Long,
        startScreenX: Int,
        startScreenY: Int,
        startOuterX: Long,
        startOuterY: Long,
        maximized: Boolean,
    ) {
        if (Platform.Current != Platform.Windows ||
            !NativeTaoWindowsDecoBridge.isLoaded ||
            hwnd == 0L
        ) {
            return
        }
        windowsTitleBarTouchDrag =
            WindowsTitleBarTouchDrag(
                touchId = touchId,
                hwnd = hwnd,
                startScreenX = startScreenX,
                startScreenY = startScreenY,
                startOuterX = startOuterX,
                startOuterY = startOuterY,
                wasMaximized = maximized,
                prepared = !maximized,
                lastScreenX = startScreenX,
                lastScreenY = startScreenY,
            )
    }

    /**
     * Aborts any in-flight Windows touch title-bar drag. Called by the
     * Compose double-tap handler after it toggles maximize, so a small
     * finger jitter between the second press and its release doesn't run
     * `nativeSetWindowOuterPositionPx` against the now-maximized HWND.
     */
    internal fun cancelWindowsTitleBarTouchDrag() {
        windowsTitleBarTouchDrag = null
    }

    internal fun updateWindowsTitleBarTouchDrag(
        phase: Int,
        touchId: Long,
        xClientPx: Float,
        yClientPx: Float,
    ) {
        val drag = windowsTitleBarTouchDrag ?: return
        if (drag.touchId != touchId) return

        if (phase == TaoTouchEvent.CANCEL) {
            windowsTitleBarTouchDrag = null
            return
        }
        val screen =
            NativeTaoWindowsDecoBridge.nativeClientToScreen(
                drag.hwnd,
                xClientPx.toInt(),
                yClientPx.toInt(),
            )
        if (screen == null || screen.size != 2) {
            if (phase == TaoTouchEvent.RELEASE) {
                windowsTitleBarTouchDrag = null
            }
            return
        }
        drag.lastScreenX = screen[0]
        drag.lastScreenY = screen[1]

        if (phase == TaoTouchEvent.RELEASE) {
            windowsTitleBarTouchDrag = null
            return
        }
        if (phase != TaoTouchEvent.MOVE) return

        if (drag.wasMaximized && !drag.prepared) {
            val dx = screen[0] - drag.startScreenX
            val dy = screen[1] - drag.startScreenY
            if (kotlin.math.abs(dx) < WINDOWS_TOUCH_DRAG_THRESHOLD_PX &&
                kotlin.math.abs(dy) < WINDOWS_TOUCH_DRAG_THRESHOLD_PX
            ) {
                return
            }
            val rect =
                NativeTaoWindowsDecoBridge.nativePrepareTitleBarTouchDrag(
                    drag.hwnd,
                    screen[0],
                    screen[1],
                    drag.startScreenX,
                    drag.startScreenY,
                )
            if (rect == null || rect.size != 4) {
                windowsTitleBarTouchDrag = null
                return
            }
            drag.startOuterX = rect[0]
            drag.startOuterY = rect[1]
            drag.startScreenX = screen[0]
            drag.startScreenY = screen[1]
            drag.wasMaximized = false
            drag.prepared = true
            requestRedraw()
            return
        }

        val targetX = drag.startOuterX + (screen[0] - drag.startScreenX)
        val targetY = drag.startOuterY + (screen[1] - drag.startScreenY)
        NativeTaoWindowsDecoBridge.nativeSetWindowOuterPositionPx(
            drag.hwnd,
            targetX.toInt(),
            targetY.toInt(),
        )
        requestRedraw()
    }

    /**
     * Returns the underlying native window handle for the current platform:
     *  - Windows: HWND as a `Long` (0 if unavailable).
     *  - macOS: NSView pointer as a `Long` — the AppKit subview hosting the
     *    Compose surface. The owning NSWindow can be obtained via `[view window]`.
     *  - Linux: returns 0 (use [NativeTaoBridge.nativeLinuxHandles] for the
     *    full `[kind, display, nativeWindow]` triplet).
     *
     * Intended for cross-module integration (e.g. taskbar, notifications) that
     * need to address the OS window directly without going through Tao APIs.
     */
    public val nativeHandle: Long
        get() =
            when (Platform.Current) {
                Platform.Windows -> NativeTaoBridge.nativeHwndHandle(handle)
                Platform.MacOS -> NativeTaoBridge.nativeNsViewHandle(handle)
                else -> 0L
            }

    public val isMaximized: Boolean
        get() = NativeTaoBridge.nativeIsMaximized(handle)

    /**
     * Outer (decoration-inclusive) window bounds as `[x, y, width, height]` in
     * physical screen pixels with a top-left origin, or `null` while the native
     * window isn't realized / the platform bridge is unavailable. All three
     * platform bridges share the Win32 `GetWindowRect` convention.
     */
    public fun outerBoundsPx(): LongArray? =
        when (Platform.Current) {
            Platform.Windows -> {
                if (!NativeTaoWindowsDecoBridge.isLoaded) {
                    null
                } else {
                    NativeTaoBridge
                        .nativeHwndHandle(handle)
                        .takeIf { it != 0L }
                        ?.let { NativeTaoWindowsDecoBridge.nativeGetWindowRect(it) }
                }
            }
            Platform.MacOS -> {
                if (!NativeTaoMacOsDecoBridge.isLoaded) {
                    null
                } else {
                    nativeHandle
                        .takeIf { it != 0L }
                        ?.let { NativeTaoMacOsDecoBridge.nativeGetWindowRect(it) }
                }
            }
            Platform.Linux -> NativeTaoBridge.nativeLinuxGetWindowRect(handle)
            else -> null
        }

    /** The window's current monitor scale factor (1.0 on non-HiDPI displays). */
    public val scaleFactor: Float
        get() = NativeTaoBridge.nativeScaleFactor(handle).coerceAtLeast(1) / 1000f

    /**
     * Linux/GTK only: true when the compositor has tiled/snapped the window to a
     * screen edge (Aero Snap). Always `false` on Windows/macOS (the native lib
     * returns `false` outside the GTK backend). Used to drop the Compose-drawn
     * rounded corners when snapped, matching native client-side decorations.
     */
    public val isTiled: Boolean
        get() = NativeTaoBridge.nativeIsTiled(handle)

    public val isFullscreen: Boolean
        get() {
            // On Windows, fullscreen is owned by the WndProc subclass so its
            // `isFullscreen` flag stays in sync with WM_NCCALCSIZE / hit-test
            // logic. Tao's own fullscreen state would be FALSE in that case.
            if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
                val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
                if (hwnd != 0L) return NativeTaoWindowsDecoBridge.nativeIsFullscreen(hwnd)
            }
            return NativeTaoBridge.nativeIsFullscreen(handle)
        }

    public fun setMaximized(maximized: Boolean) {
        NativeTaoBridge.nativeSetMaximized(handle, maximized)
    }

    public fun minimize() {
        NativeTaoBridge.nativeSetMinimized(handle, true)
    }

    public fun setMinimized(minimized: Boolean) {
        NativeTaoBridge.nativeSetMinimized(handle, minimized)
    }

    // Windows fullscreen toggle: invoked with (targetW, targetH, fullscreen)
    // BEFORE the geometry change, so the render pipeline can pre-lay-out at
    // the final size (nothing is presented). The synchronous
    // WM_WINDOWPOSCHANGED prepare inside nativeSetFullscreen then only has
    // to re-draw — fast enough to finish within the geometry change.
    private val fullscreenPrepareListeners = CopyOnWriteArrayList<(Int, Int, Boolean) -> Unit>()

    /**
     * Windows only: registers the fullscreen pre-layout hook, invoked with
     * the target client size and target state before the toggle's geometry
     * change.
     */
    public fun onFullscreenPrepare(block: (width: Int, height: Int, fullscreen: Boolean) -> Unit) {
        fullscreenPrepareListeners += block
    }

    /** Borderless fullscreen on the current monitor.
     *
     * On Windows we route through the WndProc subclass (saves WINDOWPLACEMENT,
     * synchronises the deco's `isFullscreen` flag, restores cleanly) — Tao's
     * own `set_fullscreen` doesn't coordinate with our custom WM_NCCALCSIZE
     * and leaves the maximize button + window position desynced after exit.
     * Other platforms use Tao's native path. */
    public fun setFullscreen(fullscreen: Boolean) {
        if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
            val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
            if (hwnd != 0L) {
                setFullscreenWindows(hwnd, fullscreen)
                return
            }
        }
        NativeTaoBridge.nativeSetFullscreen(handle, fullscreen)
    }

    private fun setFullscreenWindows(
        hwnd: Long,
        fullscreen: Boolean,
    ) {
        // Pre-layout is main-thread only: the prepare hook renders on this
        // stack, and the EGL context lives on the Tao loop thread. An
        // off-thread caller still gets a correct (if less atomic) toggle
        // via the async RESIZED event.
        if (Thread.currentThread() === TaoMainDispatcher.taoMainThread) {
            val t = NativeTaoWindowsDecoBridge.nativeGetFullscreenTargetSize(hwnd, fullscreen)
            if (t != null && t.size == 2 && t.all { it > 0 }) {
                fullscreenPrepareListeners.forEach { it.invoke(t[0], t[1], fullscreen) }
            }
        }
        NativeTaoWindowsDecoBridge.nativeSetFullscreen(hwnd, fullscreen)
    }

    public fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        NativeTaoBridge.nativeSetAlwaysOnTop(handle, alwaysOnTop)
    }

    public fun setFocusable(focusable: Boolean) {
        NativeTaoBridge.nativeSetFocusable(handle, focusable)
    }

    /** Logical pixels. Pass `null` to clear the minimum. */
    public fun setMinimumSize(
        widthDp: Double?,
        heightDp: Double?,
    ) {
        val w = widthDp ?: -1.0
        val h = heightDp ?: -1.0
        NativeTaoBridge.nativeSetMinInnerSize(handle, w, h)
    }

    /** [pixels] must be row-major premultiplied RGBA. Empty array clears. */
    public fun setIcon(
        width: Int,
        height: Int,
        pixels: ByteArray,
    ) {
        NativeTaoBridge.nativeSetWindowIcon(handle, width, height, pixels)
    }

    /** Logical pixels (matches [TaoApplication.openWindow]'s `width`/`height`). */
    public fun setInnerSize(
        widthDp: Double,
        heightDp: Double,
    ) {
        NativeTaoBridge.nativeSetInnerSize(handle, widthDp, heightDp)
    }

    /** Logical pixels. Top-left of the outer (decoration-inclusive) window. */
    public fun setOuterPosition(
        xDp: Double,
        yDp: Double,
    ) {
        NativeTaoBridge.nativeSetOuterPosition(handle, xDp, yDp)
    }

    /** Multi-cast: fires on every native window move. [xPx]/[yPx] are physical pixels. */
    public fun onMoved(block: (xPx: Int, yPx: Int) -> Unit) {
        movedListeners += block
    }

    private fun setStartupBackgroundEraseEnabled(enabled: Boolean) {
        if (Platform.Current != Platform.Windows || !NativeTaoWindowsDecoBridge.isLoaded) return
        val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
        if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativeSetStartupBackgroundEraseEnabled(hwnd, enabled)
    }

    public fun show() {
        startupEraseActive = true
        setStartupBackgroundEraseEnabled(true)
        NativeTaoBridge.nativeSetVisible(handle, true)
    }

    public fun hide() {
        NativeTaoBridge.nativeSetVisible(handle, false)
    }

    /** Raises the window, restores it if minimized, and gives it keyboard focus. */
    public fun focus() {
        NativeTaoBridge.nativeFocus(handle)
    }

    /**
     * Fires once, right after the NSWindow is created. The NSView pointer is
     * already valid; the window may still be hidden if it was created with
     * `visible = false`. Use this to attach the rendering pipeline and render
     * the first frame **before** calling [show], so the window appears with
     * content already drawn.
     */
    public fun onWindowReady(block: (width: Int, height: Int) -> Unit) {
        readyListener = block
    }

    /** Multi-cast: every call adds a listener; all of them fire on each resize. */
    public fun onResized(block: (width: Int, height: Int) -> Unit) {
        resizedListeners += block
    }

    public fun onScaleFactorChanged(block: (scale: Float) -> Unit) {
        scaleFactorListener = block
    }

    public fun onCloseRequested(block: () -> Unit) {
        closeRequestedListener = block
    }

    /**
     * Host / probe hook: present the opaque close frame before [requestClose]
     * destroys the window. Multi-cast — the Windows DecoratedWindow host
     * registers first; e2e probes may append. See [requestClose].
     */
    internal fun onPrepareClose(block: () -> Unit) {
        prepareCloseListeners += block
    }

    /** Multi-cast: every call adds a listener; all of them fire when the window is destroyed. */
    public fun onDestroyed(block: () -> Unit) {
        destroyedListeners += block
    }

    public fun onRedrawRequested(block: () -> Unit) {
        redrawListener = block
    }

    /** Multi-cast: every call adds a listener; all of them fire on each focus change. */
    public fun onFocusChanged(block: (focused: Boolean) -> Unit) {
        focusListeners += block
    }

    /**
     * Multi-cast: fires whenever the window's minimized (iconified) state flips,
     * including OS-driven minimize (taskbar, Win+D, Dock, Cmd-M) and the
     * title-bar button.
     *
     * Wired on all three platforms: macOS (windowDidMiniaturize/Deminiaturize),
     * Windows (WM_SIZE hook), and Linux — X11 via the GTK window-state-event,
     * Wayland via an app-driven synthesis hack (our minimize button /
     * programmatic only; the protocol reports no iconified state).
     */
    public fun onMinimizedChanged(block: (minimized: Boolean) -> Unit) {
        minimizedListeners += block
    }

    /**
     * Linux only. Fires synchronously on the event-loop thread right before
     * the GTK window is hidden — the host must suspend EGL rendering before
     * Wayland's parent `wl_surface` is destroyed (see [TaoEventCode.WILL_HIDE]).
     */
    public fun onWillHide(block: () -> Unit) {
        willHideListener = block
    }

    /**
     * Linux only. Fires synchronously right after the GTK window is shown
     * again, once the GDK surface exists — the host may re-attach EGL.
     */
    public fun onShown(block: () -> Unit) {
        shownListener = block
    }

    /**
     * Windows only. Fires on the event-loop thread when the OS modal
     * resize/move loop starts (`true`, WM_ENTERSIZEMOVE) and ends (`false`,
     * WM_EXITSIZEMOVE). The host drops VSync while active so a border drag
     * isn't throttled to the display refresh. No source on macOS / Linux.
     */
    public fun onSizeMoveChanged(block: (active: Boolean) -> Unit) {
        sizeMoveListener = block
    }

    public fun onPointerMoved(block: (xFixed: Int, yFixed: Int) -> Unit) {
        pointerMoveListener = block
    }

    public fun onPointerExited(block: () -> Unit) {
        pointerExitedListener = block
    }

    public fun onPointerButton(block: (button: Int, pressed: Boolean) -> Unit) {
        pointerButtonListener = block
    }

    /**
     * Mouse-wheel / trackpad scroll. Deltas are shaped like AWT's
     * `MouseWheelEvent.preciseWheelRotation`; the event also carries the AWT
     * `scrollAmount` metadata Compose Desktop reads when calculating platform
     * scroll distance.
     */
    internal fun onPointerScroll(block: (TaoPointerScrollEvent) -> Unit) {
        pointerScrollListener = block
    }

    public fun onKeyEvent(listener: KeyEventListener) {
        keyListener = listener
    }

    /**
     * Trackpad gesture stream — see [TrackpadGestureListener]. macOS/Linux emit
     * magnify/rotate/smart-magnify; Windows emits magnify only (Ctrl+wheel /
     * precision-touchpad pinch). No native source on other configurations.
     */
    public fun onTrackpadGesture(listener: TrackpadGestureListener) {
        trackpadGestureListener = listener
    }

    /** Windows only — see [TouchInputListener]. No-op on Linux / macOS. */
    public fun onTouchInput(listener: TouchInputListener) {
        touchInputListener = listener
    }

    internal fun dispatchTouchInput(
        phase: Int,
        id: Long,
        xFixed: Int,
        yFixed: Int,
        forceFixed: Int,
    ) {
        touchInputListener?.onTouch(phase, id, xFixed, yFixed, forceFixed)
    }

    internal fun dispatchTrackpadGesture(
        kind: Int,
        phase: Int,
        xFixed: Int,
        yFixed: Int,
        valueFixed: Int,
    ) {
        trackpadGestureListener?.onGesture(kind, phase, xFixed, yFixed, valueFixed)
    }

    internal fun dispatchKey(
        type: Int,
        vkCode: Int,
        keyLocation: Int,
        modifiers: Int,
        codePoint: Int,
    ) {
        keyListener?.onKey(type, vkCode, keyLocation, modifiers, codePoint)
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun dispatch(
        code: Int,
        a: Int,
        b: Int,
    ) {
        when (code) {
            TaoEventCode.WINDOW_READY -> readyListener?.invoke(a, b)
            TaoEventCode.RESIZED -> {
                // Win32 emits WM_SIZE/SIZE_MINIMIZED as 0x0. Keep resize
                // listeners on the last real content size while minimized.
                if (a <= 0 || b <= 0) return
                resizedListeners.forEach { it.invoke(a, b) }
            }
            TaoEventCode.MOVED -> movedListeners.forEach { it.invoke(a, b) }
            TaoEventCode.SCALE_FACTOR_CHANGED -> scaleFactorListener?.invoke(a / 1000f)
            TaoEventCode.CLOSE_REQUESTED -> closeRequestedListener?.invoke()
            TaoEventCode.DESTROYED -> {
                destroyedListeners.forEach { it.invoke() }
                TaoApplication.remove(handle)
            }
            TaoEventCode.REDRAW_REQUESTED -> {
                // Clear *before* invoking — if the listener (which renders) posts
                // another invalidate via `requestRedraw`, the next frame must go
                // through. Setting after would drop legitimate follow-up frames.
                redrawPending.set(false)
                redrawListener?.invoke()
                // First real frame is now present in the visible surface; stop
                // the themed startup erase so it never flickers during resize.
                if (startupEraseActive) {
                    startupEraseActive = false
                    setStartupBackgroundEraseEnabled(false)
                }
            }
            TaoEventCode.FOCUSED -> {
                // A redraw request issued while this window was occluded by a
                // modal child (e.g. a DecoratedDialog) can be dropped by the OS
                // with no matching REDRAW_REQUESTED, latching `redrawPending`
                // true and silently suppressing every future frame until a
                // manual resize. Regaining focus means we are foreground again:
                // clear the latch and re-issue so a lost frame can't wedge
                // rendering. No frame is lost — a genuinely in-flight redraw
                // just yields one extra, idempotent request.
                redrawPending.set(false)
                requestRedraw()
                focusListeners.forEach { it.invoke(true) }
            }
            TaoEventCode.UNFOCUSED -> focusListeners.forEach { it.invoke(false) }
            TaoEventCode.MINIMIZED -> {
                val minimized = a != 0
                isMinimized = minimized
                // On restore, re-kick the render loop. While minimized the scene
                // host gates frames out before the frame clock ticks, so Compose
                // animations are parked and nothing re-arms a redraw on its own.
                if (!minimized) requestRedraw()
                minimizedListeners.forEach { it.invoke(minimized) }
            }
            TaoEventCode.CURSOR_MOVED -> pointerMoveListener?.invoke(a, b)
            TaoEventCode.CURSOR_LEFT -> pointerExitedListener?.invoke()
            TaoEventCode.MOUSE_DOWN -> pointerButtonListener?.invoke(a, true)
            TaoEventCode.MOUSE_UP -> pointerButtonListener?.invoke(a, false)
            TaoEventCode.MODIFIERS_CHANGED -> modifierState = a
            TaoEventCode.WILL_HIDE -> willHideListener?.invoke()
            TaoEventCode.SHOWN -> shownListener?.invoke()
            TaoEventCode.SIZE_MOVE -> sizeMoveListener?.invoke(a != 0)
            TaoEventCode.SCROLL_LINE -> {
                // AWT sends the wheel rotation as scrollDelta and leaves the
                // platform line-count policy in MouseWheelEvent.scrollAmount.
                // The Windows backend emits the raw notch count (1.0 per notch,
                // fractional for precision touchpads) — it deliberately does NOT
                // apply SPI_GETWHEELSCROLLLINES, so the line-count policy is
                // carried in [platformLineScrollAmount] and the notch→pixel
                // mapping is left to the downstream ScrollConfig. macOS AWT
                // reports scrollAmount=1; Linux mirrors AWT's common
                // three-lines-per-notch default here.
                val dx = -(a / SCROLL_FIXED_SCALE)
                val dy = -(b / SCROLL_FIXED_SCALE)
                pointerScrollListener?.invoke(
                    TaoPointerScrollEvent(
                        dxAwt = dx,
                        dyAwt = dy,
                        scrollAmount = platformLineScrollAmount,
                    ),
                )
            }
            TaoEventCode.SCROLL_PIXEL -> {
                // AWT's macOS NSEvent → MouseWheelEvent conversion divides
                // scrollingDelta by ~10 to obtain preciseWheelRotation; we mirror it.
                // Negate as above for the AWT sign convention.
                val dx = -(a / SCROLL_FIXED_SCALE) / AWT_PIXEL_TO_ROTATION
                val dy = -(b / SCROLL_FIXED_SCALE) / AWT_PIXEL_TO_ROTATION
                pointerScrollListener?.invoke(
                    TaoPointerScrollEvent(
                        dxAwt = dx,
                        dyAwt = dy,
                        scrollAmount = MACOS_AWT_SCROLL_AMOUNT,
                    ),
                )
            }
            // KEY_DOWN / KEY_UP: routed in Phase 2b (no logical-key encoding yet)
        }
    }

    private companion object {
        const val SCROLL_FIXED_SCALE: Float = 100f
        const val LINUX_AWT_SCROLL_AMOUNT_DEFAULT: Int = 3
        const val MACOS_AWT_SCROLL_AMOUNT: Int = 1
        const val AWT_PIXEL_TO_ROTATION: Float = 10f
        const val WINDOWS_TOUCH_DRAG_THRESHOLD_PX: Int = 16

        val platformLineScrollAmount: Int
            get() =
                when (Platform.Current) {
                    Platform.Linux -> LINUX_AWT_SCROLL_AMOUNT_DEFAULT
                    else -> MACOS_AWT_SCROLL_AMOUNT
                }
    }
}

internal data class TaoPointerScrollEvent(
    val dxAwt: Float,
    val dyAwt: Float,
    val scrollAmount: Int,
)

private data class WindowsTitleBarTouchDrag(
    val touchId: Long,
    val hwnd: Long,
    var startScreenX: Int,
    var startScreenY: Int,
    var startOuterX: Long,
    var startOuterY: Long,
    var wasMaximized: Boolean,
    var prepared: Boolean,
    var lastScreenX: Int,
    var lastScreenY: Int,
)
