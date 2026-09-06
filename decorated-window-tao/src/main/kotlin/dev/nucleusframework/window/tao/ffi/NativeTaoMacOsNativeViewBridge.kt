package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_tao_macos_native_view"

/**
 * JNI bridge to the macOS NSView interop helpers (`macos/native_view.m`,
 * `libnucleus_tao_macos_native_view.dylib`).
 *
 * Two distinct surfaces:
 *  - **Generic NSView interop** (`nativeAddSubview` /
 *    `nativeRemoveSubview` / `nativeSetSubviewFrame`) — used by the
 *    `NativeView` composable to mount user-supplied NSViews **below**
 *    the host content view so Compose can blend over them.
 *  - **Pointer redispatch** (`nativeDispatchPointer` /
 *    `nativeDispatchScroll`) — Compose sits on top and forwards events
 *    it does not consume to the embedded view.
 *  - **Sibling overlay NSView** (`nativeCreateOverlay` / …) — leftover
 *    second Compose surface; live `NativeView` content renders in the
 *    host scene. Kept for headful tests that fabricate an NSView.
 *
 * Threading: every entry point must run on the macOS main thread.
 */
@Suppress("TooManyFunctions")
internal object NativeTaoMacOsNativeViewBridge {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeTaoMacOsNativeViewBridge::class.java)

    // ── Generic NSView interop ────────────────────────────────────────

    @JvmStatic
    external fun nativeAddSubview(
        parentNsView: Long,
        childNsView: Long,
    )

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

    /**
     * Sets `CALayer.cornerRadius` + `masksToBounds` on the subview so it
     * renders with rounded/circular corners. Compose's `Modifier.clip()`
     * doesn't propagate to embedded AppKit views (same limitation as
     * `AndroidView` / `UIKitView`), so this is the workaround. [radiusPx]
     * is capped natively at `min(w, h) / 2`, so callers can pass
     * `Float.POSITIVE_INFINITY` to mean "fully circular".
     */
    @JvmStatic
    external fun nativeSetSubviewCornerRadius(
        parentNsView: Long,
        childNsView: Long,
        radiusPx: Float,
    )

    /**
     * Synthesises an AppKit mouse event onto [childNsView] at the Compose
     * (content-view local, top-left, physical pixels) coordinate.
     * [type]: 1 down, 2 up, 3 move. [button]: 0 none, 1 primary, 2 secondary.
     * A move with [pressed] `true` is delivered as a drag.
     */
    @JvmStatic
    external fun nativeDispatchPointer(
        contentNsView: Long,
        childNsView: Long,
        type: Int,
        xPx: Float,
        yPx: Float,
        button: Int,
        pressed: Boolean,
    )

    /** Forwards a Compose scroll delta as an `NSEventTypeScrollWheel`. */
    @JvmStatic
    external fun nativeDispatchScroll(
        contentNsView: Long,
        childNsView: Long,
        xPx: Float,
        yPx: Float,
        dx: Float,
        dy: Float,
    )

    /** Makes [nsView] the window's first responder (native IME / typing). */
    @JvmStatic
    external fun nativeMakeFirstResponder(nsView: Long)

    /** Restores the Tao content view as first responder after Compose consumes a click. */
    @JvmStatic
    external fun nativeMakeContentViewFirstResponder(contentNsView: Long)

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
        fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        )

        /** AppKit `scrollingDelta*` units. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        )

        /** [type] = 1 down, 2 up. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        )

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
    external fun nativeSetOverlayCallback(
        overlayNsView: Long,
        callback: OverlayEventCallback?,
    )

    /**
     * Replaces the overlay's interactive-region list. [rectsPx] is a
     * flat `(x, y, w, h)` × [count] array in physical pixels with a
     * top-left origin, **overlay-local** (relative to the overlay's
     * own bounds). The overlay's `hitTest:` returns hit only inside one
     * of these rects; `count = 0` clears the list (full passthrough).
     */
    @JvmStatic
    external fun nativeSetOverlayRegions(
        overlayNsView: Long,
        rectsPx: FloatArray,
        count: Int,
    )

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

    // ── Diagnostics for the headful suite ─────────────────────────────

    /**
     * A retained, unparented `NSTextField` for a headful case to embed
     * through `NativeView`. 0 on failure. Release with [nativeDiagReleaseView].
     */
    @JvmStatic
    external fun nativeDiagCreateTextField(): Long

    /** Removes a view from [nativeDiagCreateTextField] from its superview and releases it. */
    @JvmStatic
    external fun nativeDiagReleaseView(nsView: Long)

    /**
     * Whether [nsView] is editing: its window's first responder is the view
     * or the field editor working on its behalf — the AppKit shape of
     * "keystrokes go to the embed".
     */
    @JvmStatic
    external fun nativeDiagViewIsEditing(nsView: Long): Boolean

    /** Whether [contentNsView] itself is its window's first responder — keystrokes go to Compose. */
    @JvmStatic
    external fun nativeDiagViewIsFirstResponder(contentNsView: Long): Boolean

    /** The string value of a field from [nativeDiagCreateTextField], or null. */
    @JvmStatic
    external fun nativeDiagTextFieldString(nsView: Long): String?

    /** A subview's frame in physical px with a top-left origin, as `[x, y, w, h]`, or null. */
    @JvmStatic
    external fun nativeDiagViewFrame(nsView: Long): IntArray?
}
