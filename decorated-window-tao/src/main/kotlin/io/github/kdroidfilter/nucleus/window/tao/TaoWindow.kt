package io.github.kdroidfilter.nucleus.window.tao

/**
 * Phase 2 handle to a window owned by the Tao event loop.
 *
 * Methods are thread-safe: they post commands as user events to the event
 * loop, which executes them on the macOS main thread.
 */
class TaoWindow internal constructor(
    val handle: Long,
) {
    private var readyListener: ((Int, Int) -> Unit)? = null
    private var resizedListener: ((Int, Int) -> Unit)? = null
    private var scaleFactorListener: ((Float) -> Unit)? = null
    private var closeRequestedListener: (() -> Unit)? = null
    private var destroyedListener: (() -> Unit)? = null
    private var redrawListener: (() -> Unit)? = null
    private var focusListener: ((Boolean) -> Unit)? = null
    private var pointerMoveListener: ((Int, Int) -> Unit)? = null
    private var pointerExitedListener: (() -> Unit)? = null
    private var pointerButtonListener: ((Int, Boolean) -> Unit)? = null
    private var pointerScrollListener: ((dxAwt: Float, dyAwt: Float) -> Unit)? = null
    private var keyListener: KeyEventListener? = null

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

    val isMaximized: Boolean
        get() = NativeTaoBridge.nativeIsMaximized(handle)

    fun setMaximized(maximized: Boolean) {
        NativeTaoBridge.nativeSetMaximized(handle, maximized)
    }

    fun minimize() {
        NativeTaoBridge.nativeSetMinimized(handle, true)
    }

    fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        NativeTaoBridge.nativeSetAlwaysOnTop(handle, alwaysOnTop)
    }

    fun setFocusable(focusable: Boolean) {
        NativeTaoBridge.nativeSetFocusable(handle, focusable)
    }

    fun show() {
        NativeTaoBridge.nativeSetVisible(handle, true)
    }

    fun hide() {
        NativeTaoBridge.nativeSetVisible(handle, false)
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

    fun onResized(block: (width: Int, height: Int) -> Unit) {
        resizedListener = block
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

    fun onFocusChanged(block: (focused: Boolean) -> Unit) {
        focusListener = block
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
            TaoEventCode.RESIZED -> resizedListener?.invoke(a, b)
            TaoEventCode.SCALE_FACTOR_CHANGED -> scaleFactorListener?.invoke(a / 1000f)
            TaoEventCode.CLOSE_REQUESTED -> closeRequestedListener?.invoke()
            TaoEventCode.DESTROYED -> {
                destroyedListener?.invoke()
                TaoApplication.remove(handle)
            }
            TaoEventCode.REDRAW_REQUESTED -> redrawListener?.invoke()
            TaoEventCode.FOCUSED -> focusListener?.invoke(true)
            TaoEventCode.UNFOCUSED -> focusListener?.invoke(false)
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
