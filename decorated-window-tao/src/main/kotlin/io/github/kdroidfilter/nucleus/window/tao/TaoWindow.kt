package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.Platform
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 handle to a window owned by the Tao event loop.
 *
 * Native commands are thread-safe: they post commands as user events to the
 * event loop, which executes them on the platform event-loop thread. Listener
 * registration is also safe to call across threads.
 */
class TaoWindow internal constructor(
    val handle: Long,
    /**
     * `true` when the window was created with `resizable = true`. Surfaced to
     * Compose so [WindowControlsLinux] can hide the maximize button on
     * non-resizable windows (matches the `decorated-window-jni` behaviour
     * — `frame.isResizable` gates the maximize button there too).
     */
    val isResizable: Boolean = true,
) {
    @Volatile
    private var readyListener: ((Int, Int) -> Unit)? = null
    // Multi-cast: the imperative `openDecoratedWindow` registers a listener
    // for host-rendering, and the @Composable `DecoratedWindow` adds another
    // for state-sync. They must coexist.
    private val resizedListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    private val movedListeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()
    @Volatile
    private var scaleFactorListener: ((Float) -> Unit)? = null
    @Volatile
    private var closeRequestedListener: (() -> Unit)? = null
    @Volatile
    private var destroyedListener: (() -> Unit)? = null
    @Volatile
    private var redrawListener: (() -> Unit)? = null
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
    private val focusListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile
    private var pointerMoveListener: ((Int, Int) -> Unit)? = null
    @Volatile
    private var pointerExitedListener: (() -> Unit)? = null
    @Volatile
    private var pointerButtonListener: ((Int, Boolean) -> Unit)? = null
    @Volatile
    private var pointerScrollListener: ((dxAwt: Float, dyAwt: Float) -> Unit)? = null
    @Volatile
    private var trackpadGestureListener: TrackpadGestureListener? = null
    @Volatile
    private var touchInputListener: TouchInputListener? = null
    @Volatile
    private var keyListener: KeyEventListener? = null

    /**
     * macOS-only trackpad gesture listener. Receives raw magnify / rotate /
     * smart-magnify deltas already reshaped by the Rust bridge — see
     * [NativeTaoBridge.EventCallback.onTrackpadGesture] for the wire format.
     */
    fun interface TrackpadGestureListener {
        fun onGesture(
            kind: Int,         // TaoTrackpadGesture.MAGNIFY | ROTATE | SMART_MAGNIFY
            phase: Int,        // TaoTrackpadPhase.BEGAN | CHANGED | ENDED | CANCELLED
            xFixed: Int,       // physical pixels × 1024, view-relative, top-left
            yFixed: Int,
            valueFixed: Int,   // delta × 10000 (ratio for magnify, degrees for rotate)
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
    fun interface TouchInputListener {
        fun onTouch(
            phase: Int,        // TaoTouchEvent.PRESS | MOVE | RELEASE | CANCEL
            id: Long,          // OS-assigned finger id
            xFixed: Int,       // physical pixels × 1024
            yFixed: Int,
            forceFixed: Int,   // pressure × 10000, or TaoTouchEvent.FORCE_UNKNOWN
        )
    }

    /** Receives keyboard events shaped like AWT for direct consumption by Compose. */
    fun interface KeyEventListener {
        fun onKey(
            type: Int,        // TaoEventCode.KEY_DOWN | KEY_UP
            vkCode: Int,      // AWT KeyEvent.VK_*
            keyLocation: Int, // AWT KeyEvent.KEY_LOCATION_*
            modifiers: Int,   // TaoModifierMask bitmask
            codePoint: Int,   // First Unicode scalar of typed text (or 0)
        )
    }

    fun setTitle(title: String) {
        NativeTaoBridge.nativeSetTitle(handle, title)
    }

    fun requestRedraw() {
        if (!redrawPending.compareAndSet(false, true)) return
        NativeTaoBridge.nativeRequestRedraw(handle)
    }

    fun requestClose() {
        NativeTaoBridge.nativeRequestClose(handle)
    }

    /**
     * Fires the [onCloseRequested] listener as if the OS had emitted a close
     * event (clicking native X, Alt+F4, etc.). Use this from custom UI like
     * the title-bar close button so the user's `onCloseRequest` callback runs
     * and gets a chance to call `exitApplication()` — bypassing it via
     * [requestClose] destroys the window but leaves the event loop running.
     */
    fun requestUserClose() {
        closeRequestedListener?.invoke()
    }

    /** Starts a native window drag — call synchronously during a mouse press. */
    fun dragWindow() {
        NativeTaoBridge.nativeDragWindow(handle)
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
    val nativeHandle: Long
        get() = when (Platform.Current) {
            Platform.Windows -> NativeTaoBridge.nativeHwndHandle(handle)
            Platform.MacOS -> NativeTaoBridge.nativeNsViewHandle(handle)
            else -> 0L
        }

    val isMaximized: Boolean
        get() = NativeTaoBridge.nativeIsMaximized(handle)

    val isFullscreen: Boolean
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

    fun setMaximized(maximized: Boolean) {
        NativeTaoBridge.nativeSetMaximized(handle, maximized)
    }

    fun minimize() {
        NativeTaoBridge.nativeSetMinimized(handle, true)
    }

    fun setMinimized(minimized: Boolean) {
        NativeTaoBridge.nativeSetMinimized(handle, minimized)
    }

    /** Borderless fullscreen on the current monitor.
     *
     * On Windows we route through the WndProc subclass (saves WINDOWPLACEMENT,
     * synchronises the deco's `isFullscreen` flag, restores cleanly) — Tao's
     * own `set_fullscreen` doesn't coordinate with our custom WM_NCCALCSIZE
     * and leaves the maximize button + window position desynced after exit.
     * Other platforms use Tao's native path. */
    fun setFullscreen(fullscreen: Boolean) {
        if (Platform.Current == Platform.Windows && NativeTaoWindowsDecoBridge.isLoaded) {
            val hwnd = NativeTaoBridge.nativeHwndHandle(handle)
            if (hwnd != 0L) {
                NativeTaoWindowsDecoBridge.nativeSetFullscreen(hwnd, fullscreen)
                return
            }
        }
        NativeTaoBridge.nativeSetFullscreen(handle, fullscreen)
    }

    fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        NativeTaoBridge.nativeSetAlwaysOnTop(handle, alwaysOnTop)
    }

    fun setFocusable(focusable: Boolean) {
        NativeTaoBridge.nativeSetFocusable(handle, focusable)
    }

    /** Logical pixels. Pass `null` to clear the minimum. */
    fun setMinimumSize(widthDp: Double?, heightDp: Double?) {
        val w = widthDp ?: -1.0
        val h = heightDp ?: -1.0
        NativeTaoBridge.nativeSetMinInnerSize(handle, w, h)
    }

    /** [pixels] must be row-major premultiplied RGBA. Empty array clears. */
    fun setIcon(width: Int, height: Int, pixels: ByteArray) {
        NativeTaoBridge.nativeSetWindowIcon(handle, width, height, pixels)
    }

    /** Logical pixels (matches [TaoApplication.openWindow]'s `width`/`height`). */
    fun setInnerSize(widthDp: Double, heightDp: Double) {
        NativeTaoBridge.nativeSetInnerSize(handle, widthDp, heightDp)
    }

    /** Logical pixels. Top-left of the outer (decoration-inclusive) window. */
    fun setOuterPosition(xDp: Double, yDp: Double) {
        NativeTaoBridge.nativeSetOuterPosition(handle, xDp, yDp)
    }

    /** Multi-cast: fires on every native window move. [xPx]/[yPx] are physical pixels. */
    fun onMoved(block: (xPx: Int, yPx: Int) -> Unit) {
        movedListeners += block
    }

    fun show() {
        NativeTaoBridge.nativeSetVisible(handle, true)
    }

    fun hide() {
        NativeTaoBridge.nativeSetVisible(handle, false)
    }

    /** Raises the window, restores it if minimized, and gives it keyboard focus. */
    fun focus() {
        NativeTaoBridge.nativeFocus(handle)
    }

    /**
     * Fires once, right after the NSWindow is created. The NSView pointer is
     * already valid; the window may still be hidden if it was created with
     * `visible = false`. Use this to attach the rendering pipeline and render
     * the first frame **before** calling [show], so the window appears with
     * content already drawn.
     */
    fun onWindowReady(block: (width: Int, height: Int) -> Unit) {
        readyListener = block
    }

    /** Multi-cast: every call adds a listener; all of them fire on each resize. */
    fun onResized(block: (width: Int, height: Int) -> Unit) {
        resizedListeners += block
    }

    fun onScaleFactorChanged(block: (scale: Float) -> Unit) {
        scaleFactorListener = block
    }

    fun onCloseRequested(block: () -> Unit) {
        closeRequestedListener = block
    }

    fun onDestroyed(block: () -> Unit) {
        destroyedListener = block
    }

    fun onRedrawRequested(block: () -> Unit) {
        redrawListener = block
    }

    /** Multi-cast: every call adds a listener; all of them fire on each focus change. */
    fun onFocusChanged(block: (focused: Boolean) -> Unit) {
        focusListeners += block
    }

    fun onPointerMoved(block: (xFixed: Int, yFixed: Int) -> Unit) {
        pointerMoveListener = block
    }

    fun onPointerExited(block: () -> Unit) {
        pointerExitedListener = block
    }

    fun onPointerButton(block: (button: Int, pressed: Boolean) -> Unit) {
        pointerButtonListener = block
    }

    /**
     * Mouse-wheel / trackpad scroll. [dxAwt]/[dyAwt] are shaped like AWT's
     * `MouseWheelEvent.preciseWheelRotation` so they can be fed directly to
     * Compose's `MacOSCocoaConfig.calculateMouseWheelScroll` (which applies
     * `× 10dp.toPx() × -scrollAmount` and yields the right pixel scroll).
     */
    fun onPointerScroll(block: (dxAwt: Float, dyAwt: Float) -> Unit) {
        pointerScrollListener = block
    }

    fun onKeyEvent(listener: KeyEventListener) {
        keyListener = listener
    }

    /** macOS only — see [TrackpadGestureListener]. No-op on Windows / Linux. */
    fun onTrackpadGesture(listener: TrackpadGestureListener) {
        trackpadGestureListener = listener
    }

    /** Windows only — see [TouchInputListener]. No-op on Linux / macOS. */
    fun onTouchInput(listener: TouchInputListener) {
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
            TaoEventCode.RESIZED -> resizedListeners.forEach { it.invoke(a, b) }
            TaoEventCode.MOVED -> movedListeners.forEach { it.invoke(a, b) }
            TaoEventCode.SCALE_FACTOR_CHANGED -> scaleFactorListener?.invoke(a / 1000f)
            TaoEventCode.CLOSE_REQUESTED -> closeRequestedListener?.invoke()
            TaoEventCode.DESTROYED -> {
                destroyedListener?.invoke()
                TaoApplication.remove(handle)
            }
            TaoEventCode.REDRAW_REQUESTED -> {
                // Clear *before* invoking — if the listener (which renders) posts
                // another invalidate via `requestRedraw`, the next frame must go
                // through. Setting after would drop legitimate follow-up frames.
                redrawPending.set(false)
                redrawListener?.invoke()
            }
            TaoEventCode.FOCUSED -> focusListeners.forEach { it.invoke(true) }
            TaoEventCode.UNFOCUSED -> focusListeners.forEach { it.invoke(false) }
            TaoEventCode.CURSOR_MOVED -> pointerMoveListener?.invoke(a, b)
            TaoEventCode.CURSOR_LEFT -> pointerExitedListener?.invoke()
            TaoEventCode.MOUSE_DOWN -> pointerButtonListener?.invoke(a, true)
            TaoEventCode.MOUSE_UP -> pointerButtonListener?.invoke(a, false)
            TaoEventCode.SCROLL_LINE -> {
                // 1 NSEvent line = 1 mouse-wheel notch ≈ AWT preciseWheelRotation 1.0.
                // Negate to match AWT's "negative = away from user" convention.
                // Multiply by 3 to compensate for the missing AWT scrollAmount=3
                // (no AWT event present, so MacOSCocoaConfig defaults to 1).
                val dx = -(a / SCROLL_FIXED_SCALE) * AWT_SCROLL_AMOUNT_DEFAULT
                val dy = -(b / SCROLL_FIXED_SCALE) * AWT_SCROLL_AMOUNT_DEFAULT
                pointerScrollListener?.invoke(dx, dy)
            }
            TaoEventCode.SCROLL_PIXEL -> {
                // AWT's macOS NSEvent → MouseWheelEvent conversion divides
                // scrollingDelta by ~10 to obtain preciseWheelRotation; we mirror it.
                // Negate as above for the AWT sign convention.
                val dx = -(a / SCROLL_FIXED_SCALE) / AWT_PIXEL_TO_ROTATION
                val dy = -(b / SCROLL_FIXED_SCALE) / AWT_PIXEL_TO_ROTATION
                pointerScrollListener?.invoke(dx, dy)
            }
            // KEY_DOWN / KEY_UP: routed in Phase 2b (no logical-key encoding yet)
        }
    }

    private companion object {
        const val SCROLL_FIXED_SCALE: Float = 100f
        const val AWT_SCROLL_AMOUNT_DEFAULT: Float = 3f
        const val AWT_PIXEL_TO_ROTATION: Float = 10f
    }
}
