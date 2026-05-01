package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader
import io.github.kdroidfilter.nucleus.window.tao.render.MetalFrame

private const val LIBRARY_NAME = "nucleus_tao_metal"

/**
 * JNI bridge to the ObjC helper that turns a Tao NSView into a Metal-rendering
 * surface usable from Skiko.
 *
 * All methods must be invoked on the macOS main thread (i.e. from inside a Tao
 * event handler) — they manipulate AppKit/Metal objects that are not
 * thread-safe.
 */
internal object NativeMetalBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMetalBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /**
     * Attaches a fresh `CAMetalLayer` to the given NSView and creates a Metal
     * device + command queue. Returns an opaque attachment handle to be passed
     * to all other methods, or 0 on failure.
     */
    @JvmStatic
    external fun nativeAttach(nsViewPtr: Long): Long

    /**
     * Applies the macOS chrome trick: full-size content view + transparent
     * title bar + hidden title. The native traffic-light buttons remain
     * visible at the top-left while our Compose content fills the window.
     */
    @JvmStatic
    external fun nativeConfigureChrome(nsViewPtr: Long)

    /** True on macOS 26 (Tahoe) or later. Cached on the native side. */
    @JvmStatic
    external fun nativeIsMacOSTahoeOrLater(): Boolean

    /**
     * Toggles the macOS 26+ "Liquid Glass" / large-corner-radius treatment by
     * attaching an invisible NSToolbar to the parent NSWindow. No-op on
     * earlier macOS releases (the toolbar would only add chrome height for
     * no visual benefit pre-Tahoe).
     */
    @JvmStatic
    external fun nativeApplyLargeCornerRadius(
        nsViewPtr: Long,
        enabled: Boolean,
    )

    @JvmStatic
    external fun nativeDetach(handle: Long)

    /** Raw pointer to `id<MTLDevice>`. */
    @JvmStatic
    external fun nativeDevicePtr(handle: Long): Long

    /** Raw pointer to `id<MTLCommandQueue>`. */
    @JvmStatic
    external fun nativeQueuePtr(handle: Long): Long

    /**
     * Updates the layer's drawable size and contentsScale to match a new
     * window size or DPI change.
     */
    @JvmStatic
    external fun nativeResize(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        scale: Float,
    )

    /**
     * Acquires the next CAMetalLayer drawable. Returns null if the system
     * is not ready to render this frame (e.g. no drawable available).
     *
     * The returned [MetalFrame.drawablePtr] **must** later be released via
     * [nativePresent] — otherwise the drawable leaks.
     */
    @JvmStatic
    external fun nativeBeginFrame(handle: Long): MetalFrame?

    /**
     * Presents a previously acquired drawable. The Skia surface must have been
     * `flushAndSubmit()`-ed first so its command buffer is queued before this
     * call schedules the present.
     */
    @JvmStatic
    external fun nativePresent(
        handle: Long,
        drawablePtr: Long,
    )

    /** True while AppKit is animating a fullscreen transition on the window. */
    @JvmStatic
    external fun nativeIsInTransition(handle: Long): Boolean

    /**
     * Repositions the standard NSWindow buttons (close / miniaturise / zoom)
     * so they sit centred inside a custom-height title bar. Uses Apple's own
     * sizing formula — same offsets as Finder/Safari with custom title bars.
     *
     * @param titleBarHeight in macOS points (= dp on macOS at 1.0 scale).
     */
    @JvmStatic
    external fun nativeApplyButtonLayout(
        nsViewPtr: Long,
        titleBarHeight: Float,
    )
}
