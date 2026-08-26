package dev.nucleusframework.window.tao.ffi

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import dev.nucleusframework.window.tao.render.MetalFrame
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
    // Mirrors the legacy AWT backend's mac title-bar bridge. Keyed by NSView
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

    // ── Fullscreen transition prepare (macOS) ────────────────────────────
    //
    // AppKit resizes the window to its final size and snapshots it inside the
    // `windowWillEnterFullScreen:` dispatch, then stretches that snapshot for
    // the whole ~550ms animation. Anything rendered after the notification
    // returns is too late — the snapshot already holds the previous, smaller
    // buffer, which is why the content used to look frozen and undersized and
    // then snap into place (#327).
    //
    // The native `willEnterFS` handler therefore calls [onFullscreenPrepare]
    // *synchronously*, before returning, with the size the window is about to
    // take. The host renders one blocking frame at that size so the snapshot
    // captures the final layout; AppKit then scales it down into the current
    // frame and grows it to 1:1 — the ramp AppKit's own apps show.

    private val fullscreenPrepares = ConcurrentHashMap<Long, (Int, Int) -> Unit>()

    fun setFullscreenPrepare(
        nsViewPtr: Long,
        block: ((widthPx: Int, heightPx: Int) -> Unit)?,
    ) {
        if (nsViewPtr == 0L) return
        if (block == null) fullscreenPrepares.remove(nsViewPtr) else fullscreenPrepares[nsViewPtr] = block
    }

    /**
     * Called from native on the macOS main thread, inside
     * `windowWillEnterFullScreen:`. Runs on the caller's stack on purpose:
     * the frame has to be presented before this returns.
     */
    @JvmStatic
    fun onFullscreenPrepare(
        nsViewPtr: Long,
        widthPx: Int,
        heightPx: Int,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        fullscreenPrepares[nsViewPtr]?.invoke(widthPx, heightPx)
    }

    // ── VSync pacing (CVDisplayLink, AWT/skiko MetalVSyncer pattern) ──
    //
    // A CVDisplayLink runs continuously; [nativeVSyncWait] blocks the calling
    // (background) thread until the next refresh. The render loop's
    // [MetalVSyncer.waitForVSync] calls it after presenting to pace itself to
    // the display — instead of being push-triggered by the link.

    /** Start the per-window CVDisplayLink + its vsync semaphore. */
    @JvmStatic
    external fun nativeStartDisplayLink(handle: Long)

    /** Stop and release the window's CVDisplayLink + semaphore. */
    @JvmStatic
    external fun nativeStopDisplayLink(handle: Long)

    /**
     * Block until the next display refresh after this call. Must be invoked off
     * the Tao main thread (it parks the thread). Bounded (~2 refreshes) so a
     * paused link can't deadlock the loop.
     */
    @JvmStatic
    external fun nativeVSyncWait(handle: Long)

    /**
     * Attaches a fresh `CAMetalLayer` to the given NSView and creates a Metal
     * device + command queue. Returns an opaque attachment handle to be passed
     * to all other methods, or 0 on failure.
     */
    @JvmStatic
    external fun nativeAttach(nsViewPtr: Long): Long

    /**
     * Companion to [nativeAttach] for overlay surfaces (popup NSPanels,
     * in-window overlay subviews, …). Same Metal pipeline (begin / present /
     * resize / detach are interchangeable with the regular handle), but the
     * underlying `CAMetalLayer` is created with `opaque = NO` so a Compose
     * scene rendered into it can leave alpha-zero regions where the surface
     * beneath shows through.
     */
    @JvmStatic
    external fun nativeAttachOverlay(nsViewPtr: Long): Long

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
     * Visible-frame size (screen minus menu bar + dock) of the NSScreen
     * hosting the given NSView, in **physical pixels**, packed as
     * `(width shl 32) or (height and 0xFFFFFFFF)`. Returns 0 when the
     * NSView is not yet attached to a window — callers must fall back to
     * their owner-window size. Used as the upper-bound layout constraint
     * for popup inner scenes (see [render.TaoPopupSceneLayer]).
     */
    @JvmStatic
    external fun nativeOwnerWorkAreaSize(nsViewPtr: Long): Long

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

    /**
     * Updates the AppKit fallback background used behind the CAMetalLayer and
     * during fullscreen/title-bar animations. [argb] is Compose-style ARGB.
     */
    @JvmStatic
    external fun nativeSetWindowBackgroundColor(
        nsViewPtr: Long,
        argb: Int,
    )

    /**
     * Window-level transparency for the glass regions: the CAMetalLayer is
     * cleared to alpha 0 so the system material inserted below the content
     * shows through, while the window itself stays opaque — which is what
     * gives those materials the desktop-tinted backdrop. Ref-counted
     * Kotlin-side.
     */
    @JvmStatic
    external fun nativeSetWindowTransparencyMode(
        nsViewPtr: Long,
        enabled: Boolean,
    )

    /**
     * Full-window per-pixel transparency (#416). Sets the single native
     * transparency mode to FULL (`NSWindow.opaque = NO`, clear layers) so
     * alpha-0 Compose pixels composite the desktop. Glass REGIONS requests
     * cannot demote FULL. Creation-time only in practice; call after Metal attach.
     */
    @JvmStatic
    external fun nativeSetFullyTransparent(
        nsViewPtr: Long,
        enabled: Boolean,
    )

    /**
     * Current `NSWindow.isOpaque` for the window owning [nsViewPtr]. Used by
     * the #416 headful probe. Returns `true` when the view has no window yet.
     */
    @JvmStatic
    external fun nativeIsWindowOpaque(nsViewPtr: Long): Boolean

    /**
     * Inserts a hosted `NSSplitViewController` pane region below the content
     * view — AppKit backs it with the wallpaper-tinted system material
     * (System Settings sidebar look). [kindOrdinal] is
     * `WindowGlassRegionKind.nativeValue` (not enum ordinal). Returns a
     * retained pointer (0 if the window is not ready) — position it with
     * [nativeSetGlassRegionFrame] and release it with [nativeRemoveGlassRegion].
     */
    @JvmStatic
    external fun nativeAddGlassRegion(
        nsViewPtr: Long,
        kindOrdinal: Int,
    ): Long

    /**
     * Region frame in points, top-left origin in Compose scene coordinates.
     * [cornerRadius] rounds the region clip (for material behind rounded
     * panels), 0 for a rectangular region.
     */
    @JvmStatic
    external fun nativeSetGlassRegionFrame(
        regionPtr: Long,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
    )

    @JvmStatic
    external fun nativeRemoveGlassRegion(regionPtr: Long)

    /**
     * Forces the window's `NSAppearance` so native surfaces follow the app
     * theme rather than the system one. [mode]: 0 system, 1 light, 2 dark.
     */
    @JvmStatic
    external fun nativeSetWindowAppearance(
        nsViewPtr: Long,
        mode: Int,
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
     * Pushes an ObjC autorelease pool on the calling thread and returns its
     * opaque token, to be balanced with [nativeAutoreleasePoolPop] on the
     * same thread (#494).
     *
     * The dedicated Metal render threads are plain JVM threads with no pool;
     * without one drained per frame, the autoreleased `CAMetalDrawable` from
     * [nativeBeginFrame] and the command buffer autoreleased inside skiko's
     * `flushAndSubmit` leak on every rendered frame. The pool wraps the whole
     * per-frame render-thread task so skiko's flush — which runs between our
     * JNI calls — is covered too.
     */
    @JvmStatic
    external fun nativeAutoreleasePoolPush(): Long

    /**
     * Pops the pool pushed by [nativeAutoreleasePoolPush], releasing every
     * object autoreleased on this thread since. Must run on the pushing
     * thread. No-op for a 0 token.
     */
    @JvmStatic
    external fun nativeAutoreleasePoolPop(pool: Long)

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

    /**
     * Toggles `CAMetalLayer.presentsWithTransaction`. When `true`, the layer
     * defers its surface swap so it can be committed atomically inside the
     * enclosing `CATransaction` along with sibling AppKit mutations (subview
     * frame changes, etc). Mirror of Compose iOS's `metalLayer.presentsWithTransaction`
     * in `MetalRedrawer.ios.kt:335`.
     *
     * Cost: with the flag ON, presents block on `[commandBuffer waitUntilScheduled]`
     * before flipping (~1 frame of latency). Toggle OFF when no `NativeView`
     * is mounted to keep the fast async-present path.
     */
    @JvmStatic
    external fun nativeSetPresentsWithTransaction(
        handle: Long,
        enabled: Boolean,
    )

    /**
     * Present-with-transaction path used when interop AppKit subviews need
     * to commit atomically with the Compose frame. Performs, in order:
     *
     *  1. `[CATransaction begin]`
     *  2. submit a present-only `MTLCommandBuffer` and `[commit]`
     *  3. `[commandBuffer waitUntilScheduled]` (GPU has the frame queued)
     *  4. `[drawable present]` (joins the open `CATransaction`)
     *  5. invoke `interopActions.run()` — Kotlin runs queued AppKit mutations,
     *     each of which lands in the same `CATransaction`
     *  6. `[CATransaction commit]` — flushes Metal present + AppKit mutations
     *     atomically to the screen
     *
     * Requires [nativeSetPresentsWithTransaction] to have been called with
     * `true` for [handle] beforehand. Mirrors `MetalRedrawer.ios.kt:367-383`.
     */
    @JvmStatic
    external fun nativePresentWithInterop(
        handle: Long,
        drawablePtr: Long,
        interopActions: Runnable,
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
     * Mirrors the legacy AWT backend's `nativeSetRTL`.
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
     * the legacy AWT backend's `nativeSetNewFullscreenControls`.
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
     * Mirrors the legacy AWT backend's `nativeInstallMenuBarMonitor`.
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
     * Mirrors the legacy AWT backend's `nativeSetMenuBarOffset`.
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
     * Mirrors the legacy AWT backend's `nativeUpdateFullScreenButtons`.
     */
    @JvmStatic
    external fun nativeUpdateFullScreenButtons(nsViewPtr: Long)

    /**
     * Executes any pending [nativePresentWithInterop] main-thread callouts
     * while the caller — which must be the macOS main thread — is blocked
     * waiting on the render thread. The callouts are registered on the main
     * run loop in a private mode exactly for this: a main thread parked in
     * `future.get()` never drains its run loop, and the render thread's
     * present-with-transaction would otherwise wait on it forever (the
     * fullscreen-freeze deadlock with a live NativeView). Bounded to one
     * callout or ~4ms per call; no-op off the main thread or when no callout
     * is pending.
     */
    @JvmStatic
    external fun nativeInteropPump()

    /**
     * True on the AppKit main thread. [dispatch.TaoMainDispatcher.taoMainThread]
     * cannot answer this on macOS: `nativeRunBlocking` marshals the event loop
     * onto thread 0, so AppKit callbacks run on a different JVM thread than the
     * one that entered `taoApplication`.
     */
    @JvmStatic
    external fun nativeIsMainThread(): Boolean

    // ── Window-state diagnostics (headful e2e probes) ──────────────────
    //
    // Read-only probes for the stage-2 headful suite, mirroring the sheet
    // parent probe on NativeTaoBridge: they assert native invariants that
    // have no other JVM-visible signal. Not used by any production path.

    /**
     * Bitmask of the window-level state [nativeAttach] installs on the
     * view's NSWindow: bit 0 = primary attachment associated object,
     * bit 1 = fullscreen-transition observer (#327). `-1` when the view or
     * its window is gone.
     */
    @JvmStatic
    external fun nativeDiagWindowState(nsViewPtr: Long): Int

    /**
     * `CFGetRetainCount` of the view's NSWindow. Only deltas are meaningful
     * (AppKit holds its own references); the `set_focusable` leak regression
     * compares before/after a burst of calls. `-1` when view/window is gone.
     */
    @JvmStatic
    external fun nativeDiagWindowRetainCount(nsViewPtr: Long): Long

    /**
     * Frame size of an arbitrary NSView in physical pixels, packed
     * `(w shl 32) or h`. Lets the headful suite assert an embedded
     * `NativeView` subview actually tracked a layout change (fullscreen
     * round-trip). `0` when the view is gone.
     */
    @JvmStatic
    external fun nativeDiagViewFrameSize(nsViewPtr: Long): Long

    /**
     * Top-left origin of an NSView within its superview, physical pixels,
     * top-left coordinate convention, packed as two signed 32-bit values
     * `(x shl 32) or (y and 0xFFFFFFFF)`. [Long.MIN_VALUE] when the view is
     * gone. Complements [nativeDiagViewFrameSize]: right size at the wrong
     * offset is the fullscreen-transition failure mode (bottom-left AppKit
     * anchoring against a stale parent height).
     */
    @JvmStatic
    external fun nativeDiagViewTopLeftPx(nsViewPtr: Long): Long

    /**
     * Disables native → JVM callbacks and removes any active menu bar
     * monitors. Called from a JVM shutdown hook so AppKit can't fire a
     * callback into a half-destroyed JVM.
     */
    @JvmStatic
    private external fun nativeShutdown()
}
