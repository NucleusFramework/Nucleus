package io.github.kdroidfilter.nucleus.window.tao

import io.github.kdroidfilter.nucleus.core.runtime.NativeLibraryLoader
import io.github.kdroidfilter.nucleus.window.tao.render.MetalFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

private const val LIBRARY_NAME = "nucleus_tao_metal"

/**
 * JNI bridge to the ObjC helper that turns a Tao NSView into a Metal-rendering
 * surface usable from Skiko.
 *
 * All methods must be invoked on the macOS main thread (i.e. from inside a Tao
 * event handler) — they manipulate AppKit/Metal objects that are not
 * thread-safe.
 */
@Suppress("TooManyFunctions")
internal object NativeMetalBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeMetalBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // Register a shutdown hook to disable native → JVM callbacks before the
    // JVM tears down. Without this, the NSEvent menu bar monitor can fire
    // notifyMenuBarOffsetChanged during JVM_Halt → CallStaticVoidMethod on
    // a freed sBridgeClass global ref → EXC_BAD_ACCESS → abort.
    init {
        if (loaded) {
            Runtime.getRuntime().addShutdownHook(
                Thread({ nativeShutdown() }, "nucleus-tao-metal-shutdown"),
            )
        }
    }

    // ── Menu bar offset (event-driven via native NSEvent monitor) ──
    //
    // Mirrors `decorated-window-jni`'s JniMacTitleBarBridge. Keyed by NSView
    // pointer for consistency with the rest of this bridge (the JNI sibling
    // keys by NSWindow pointer because it owns AWT windows directly).

    private val menuBarOffsetFlows = ConcurrentHashMap<Long, MutableStateFlow<Float>>()
    private val emptyFlow = MutableStateFlow(0f)

    fun menuBarOffsetFlow(nsViewPtr: Long): StateFlow<Float> {
        if (nsViewPtr == 0L) return emptyFlow
        return menuBarOffsetFlows.getOrPut(nsViewPtr) { MutableStateFlow(0f) }
    }

    fun removeMenuBarOffsetFlow(nsViewPtr: Long) {
        menuBarOffsetFlows.remove(nsViewPtr)
    }

    // Called from native (macOS main thread) when the menu bar offset
    // changes. MutableStateFlow.value is thread-safe.
    @JvmStatic
    fun onMenuBarOffsetChanged(
        nsViewPtr: Long,
        offset: Float,
    ) {
        menuBarOffsetFlows.getOrPut(nsViewPtr) { MutableStateFlow(0f) }.value = offset
    }

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

    /**
     * Flips the AppKit traffic-light buttons to the right edge of the title bar
     * when [isRtl] is true, or back to the default left edge when false. Must
     * be called after [nativeApplyButtonLayout] has stashed the title-bar
     * height (otherwise this is a no-op until the height is published).
     *
     * Mirrors `decorated-window-jni`'s `JniMacTitleBarBridge.nativeSetRTL`.
     */
    @JvmStatic
    external fun nativeSetButtonLayoutRtl(
        nsViewPtr: Long,
        isRtl: Boolean,
    )

    /**
     * Stores the `newFullscreenControls` flag on the NSWindow backing the
     * given NSView. When enabled, the title bar and traffic-light buttons are
     * pushed down by the system menu bar height as it auto-shows in
     * fullscreen — mirroring Safari's fullscreen title bar behaviour.
     *
     * If the window is already in fullscreen, the menu bar event monitor is
     * installed/removed to match the new flag. Mirrors
     * `decorated-window-jni`'s `JniMacTitleBarBridge.nativeSetNewFullscreenControls`.
     */
    @JvmStatic
    external fun nativeSetNewFullscreenControls(
        nsViewPtr: Long,
        enabled: Boolean,
    )

    /**
     * Installs an NSEvent local monitor + NSMenuDidBeginTracking observers on
     * the window backing this NSView. When the system menu bar visibility
     * changes, the native side calls [onMenuBarOffsetChanged] via JNI so the
     * Compose layer can animate the title-bar offset.
     *
     * Mirrors `decorated-window-jni`'s `nativeInstallMenuBarMonitor`.
     */
    @JvmStatic
    external fun nativeInstallMenuBarMonitor(nsViewPtr: Long)

    /** Removes the monitor installed by [nativeInstallMenuBarMonitor]. */
    @JvmStatic
    external fun nativeRemoveMenuBarMonitor(nsViewPtr: Long)

    /**
     * Stores the current menu bar offset (in macOS points) on the window so
     * the replacement traffic-light buttons can follow the title bar as
     * Compose animates the offset. Triggers an immediate
     * `updateFullScreenButtonsPosition` on the macOS main thread.
     *
     * Mirrors `decorated-window-jni`'s `nativeSetMenuBarOffset`.
     */
    @JvmStatic
    external fun nativeSetMenuBarOffset(
        nsViewPtr: Long,
        offsetPt: Float,
    )

    /**
     * Forces the replacement fullscreen-button container to recompute its
     * frame from the stored title-bar height + menu-bar offset. Useful after
     * a layout pass that may have moved the contentView.
     *
     * Mirrors `decorated-window-jni`'s `nativeUpdateFullScreenButtons`.
     */
    @JvmStatic
    external fun nativeUpdateFullScreenButtons(nsViewPtr: Long)

    /**
     * Disables native → JVM callbacks and removes any active menu bar
     * monitors. Called from a JVM shutdown hook so AppKit can't fire a
     * callback into a half-destroyed JVM.
     */
    @JvmStatic
    private external fun nativeShutdown()
}
