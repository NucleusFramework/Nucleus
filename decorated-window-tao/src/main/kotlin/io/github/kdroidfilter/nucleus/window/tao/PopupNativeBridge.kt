package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to the macOS popup panel helper (`macos/popup_panel.m`,
 * shipped as `libnucleus_tao_macos_popup.dylib`).
 *
 * Phase 1 of the Compose-popup-via-NSPanel architecture: lets us mint a
 * borderless transparent `NSPanel` attached as a child window of the
 * host `NSWindow` and visually verify its position. No Skia, no
 * Compose, no event forwarding yet — those come in subsequent phases.
 *
 * Threading: every entry point must run on the macOS main thread (= Tao
 * event-loop thread = Compose dispatcher thread). The functions touch
 * AppKit objects directly.
 *
 * Wire format: coordinates are in **physical pixels**, top-left origin,
 * relative to the parent window's content area (matching Compose's
 * `boundsInWindow`). The native side flips Y and translates to AppKit's
 * bottom-left, points-based screen coords using the parent window's
 * `backingScaleFactor`.
 */
// `internal` once Phase 7 ships; kept package-public so the sample-tao
// Phase 1 smoke test can drive it from outside the module.
object PopupNativeBridge {
    private const val LIBRARY_NAME = "nucleus_tao_macos_popup"

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, PopupNativeBridge::class.java)

    /**
     * Allocates a transparent borderless `NSPanel` attached as a child
     * window of the `NSWindow` backing [parentNsView]. The Phase-1
     * implementation paints the panel's content view solid red so the
     * AppKit + JNI plumbing can be verified visually. Subsequent phases
     * will replace the red layer with a `CAMetalLayer` + `ComposeScene`.
     *
     * Returns a JVM-retained `Long` that owns the panel; release via
     * [nativeRelease].
     */
    @JvmStatic
    external fun nativeCreatePanel(
        parentNsView: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ): Long

    /** Repositions the panel using the same parent-window-pixels convention as [nativeCreatePanel]. */
    @JvmStatic
    external fun nativeSetFrameInWindow(
        panel: Long,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /** Brings the panel front (regardless of activation state). */
    @JvmStatic
    external fun nativeOrderFront(panel: Long)

    /** Hides the panel without releasing it. */
    @JvmStatic
    external fun nativeOrderOut(panel: Long)

    /**
     * Detaches the panel from the parent NSWindow's child-window list,
     * orders it out, and drops the explicit retain installed by
     * [nativeCreatePanel].
     */
    @JvmStatic
    external fun nativeRelease(panel: Long)

    /**
     * Returns the panel's content `NSView` pointer. Subsequent phases
     * will hand it to `NativeMetalBridge.nativeAttachOverlay` to wire
     * up a transparent `CAMetalLayer` for Compose rendering.
     */
    @JvmStatic
    external fun nativeContentNsView(panel: Long): Long

    // ── Phase 4: event forwarding & focus ────────────────────────────

    /**
     * Receives raw AppKit events forwarded by the popup's content view
     * when the user interacts inside the popup's bounds. Coordinates are
     * popup-local pixels (top-left origin) — matches what
     * `ComposeScene.sendPointerEvent` expects.
     */
    interface EventCallback {
        /** [type] = 1 down, 2 up, 3 move. [button] = 0 none, 1 primary, 2 secondary. */
        @Suppress("FunctionParameterNaming")
        fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int)

        /** AppKit `scrollingDelta*` units. */
        @Suppress("FunctionParameterNaming")
        fun onScroll(x: Float, y: Float, dx: Float, dy: Float)

        /** [type] = 1 down, 2 up. */
        @Suppress("FunctionParameterNaming")
        fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int)
    }

    /**
     * Receives a callback from the NSEvent local monitor whenever a
     * left/right/other mouseDown happens anywhere in the app outside this
     * popup's NSPanel. Compose's `Popup` framework wires this to
     * `dismissOnClickOutside`.
     */
    interface OutsideClickListener {
        /** [type] = 1 (always Press for monitor scope). [button] = 1 primary, 2 secondary, 3 other. */
        fun onOutsideClick(type: Int, button: Int)
    }

    /** Toggles the panel's `canBecomeKeyWindow` value. */
    @JvmStatic
    external fun nativeSetFocusable(panel: Long, focusable: Boolean)

    /**
     * When `ignore = true`, AppKit routes pointer events past this panel
     * to whatever sits underneath it. Used for paint-only "watermark"
     * popups that should be visible but not block interaction with the
     * native subview (e.g. a `WKWebView`) below.
     */
    @JvmStatic
    external fun nativeSetIgnoresMouseEvents(panel: Long, ignore: Boolean)

    /** Installs the JNI [EventCallback] on the panel's content view. Pass `null` to remove. */
    @JvmStatic
    external fun nativeSetEventCallback(panel: Long, callback: EventCallback?)

    /**
     * Installs an `NSEvent.addLocalMonitorForEventsMatchingMask:` for
     * left/right/other mouseDown events. Fires [listener] for every
     * event whose `event.window` is **not** this panel — i.e. clicks
     * that landed *outside* the popup.
     */
    @JvmStatic
    external fun nativeInstallOutsideClickMonitor(panel: Long, listener: OutsideClickListener)

    /** Removes any previously installed outside-click monitor for this panel. */
    @JvmStatic
    external fun nativeUninstallOutsideClickMonitor(panel: Long)

    /**
     * Enables region-based hit-testing on the panel. When enabled, the
     * panel's `hitTest:` returns hit only at points inside a registered
     * interactive region — points elsewhere fall through to the parent
     * window's subviews (typically a `WKWebView`). Disabled (default)
     * means the panel intercepts every event in its bounds.
     */
    @JvmStatic
    external fun nativeSetRegionHitTestEnabled(panel: Long, enable: Boolean)

    /**
     * Replaces the panel's interactive-region list. [rectsPx] is a flat
     * `(x, y, w, h)` × [count] array in physical pixels with a top-left
     * origin, panel-local. Effective only while region hit-test is
     * enabled. Pass `count = 0` to clear (full passthrough).
     */
    @JvmStatic
    external fun nativeSetInteractiveRegions(panel: Long, rectsPx: FloatArray, count: Int)
}
