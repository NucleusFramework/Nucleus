package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_macos_native_view"

/**
 * JNI bridge to the macOS NSView interop helpers (`macos/native_view.m`,
 * `libnucleus_tao_macos_native_view.dylib`).
 *
 * Two distinct surfaces:
 *  - **Generic NSView interop** (`nativeAddSubview` /
 *    `nativeRemoveSubview` / `nativeSetSubviewFrame`) — used by the
 *    `NativeView` composable to mount user-supplied NSViews as
 *    subviews of the host content view.
 *  - **Sibling overlay NSView** (`nativeCreateOverlay` /
 *    `nativeSetOverlayFrame` / `nativeSetOverlayCallback` /
 *    `nativeSetOverlayRegions` / `nativeReleaseOverlay`) — used by the
 *    overlay slot of `NativeView`. The overlay is itself an NSView
 *    sibling of the user's native subview; its `hitTest:` returns nil
 *    for points outside any registered interactive region, so AppKit
 *    falls through to the next sibling (the user's native view) for
 *    transparent regions. That's how a Compose watermark over a
 *    `WKWebView` can let the page underneath receive scroll/clicks.
 *
 * Threading: every entry point must run on the macOS main thread.
 */
internal object NativeTaoMacOsNativeViewBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoMacOsNativeViewBridge::class.java)

    // ── Generic NSView interop ────────────────────────────────────────

    @JvmStatic
    external fun nativeAddSubview(parentNsView: Long, childNsView: Long)

    @JvmStatic
    external fun nativeRemoveSubview(childNsView: Long)

    @JvmStatic
    external fun nativeSetSubviewFrame(
        parentNsView: Long,
        childNsView: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    // ── Sibling overlay NSView ────────────────────────────────────────

    /**
     * Receives raw AppKit events forwarded by the overlay NSView when
     * the cursor sits inside a registered interactive region. `x` / `y`
     * are overlay-local pixels (top-left origin) — matches what
     * `ComposeScene.sendPointerEvent` expects.
     */
    interface OverlayEventCallback {
        /** [type] = 1 down, 2 up, 3 move. [button] = 0 none, 1 primary, 2 secondary. */
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int)

        /** AppKit `scrollingDelta*` units. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(x: Float, y: Float, dx: Float, dy: Float)

        /** [type] = 1 down, 2 up. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int)

        /**
         * Fired when the overlay NSView ceases to be the host window's
         * first responder (user clicked on a sibling subview — typically
         * a `WKWebView` — or on the host's own NSView outside the
         * overlay's interactive regions). Used to clear the overlay
         * scene's keyboard focus so a previously focused `BasicTextField`
         * visually deselects.
         */
        fun onResignFirstResponder()
    }

    /**
     * Creates a transparent overlay NSView and adds it as the topmost
     * subview of [parentNsView]. The returned pointer should be handed
     * to [NativeMetalBridge.nativeAttachOverlay] to wire up a
     * transparent `CAMetalLayer` for Compose to render into.
     */
    @JvmStatic
    external fun nativeCreateOverlay(parentNsView: Long): Long

    /** Repositions the overlay inside its parent. Pixel-precise, top-left origin. */
    @JvmStatic
    external fun nativeSetOverlayFrame(
        overlayNsView: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /** Installs the JNI [OverlayEventCallback]. Pass `null` to remove. */
    @JvmStatic
    external fun nativeSetOverlayCallback(overlayNsView: Long, callback: OverlayEventCallback?)

    /**
     * Replaces the overlay's interactive-region list. [rectsPx] is a
     * flat `(x, y, w, h)` × [count] array in physical pixels with a
     * top-left origin, **overlay-local** (relative to the overlay's
     * own bounds). The overlay's `hitTest:` returns hit only inside one
     * of these rects; `count = 0` clears the list (full passthrough).
     */
    @JvmStatic
    external fun nativeSetOverlayRegions(overlayNsView: Long, rectsPx: FloatArray, count: Int)

    /** Detaches the overlay NSView and drops the JNI global ref on its callback. */
    @JvmStatic
    external fun nativeReleaseOverlay(overlayNsView: Long)

    /**
     * Returns `true` when the overlay NSView is the current first
     * responder of its host NSWindow. Used to route keystrokes coming
     * from the host's Tao key-forwarding pipeline to the overlay's
     * inner ComposeScene only when the overlay has logical focus.
     */
    @JvmStatic
    external fun nativeIsFirstResponder(overlayNsView: Long): Boolean
}
