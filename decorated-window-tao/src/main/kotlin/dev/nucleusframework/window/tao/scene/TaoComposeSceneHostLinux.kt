@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.core.runtime.LinuxDesktopEnvironment
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoGpuRenderContextConsumers
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoNonFatalCoroutineExceptionHandler
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTouchEvent
import dev.nucleusframework.window.tao.TaoTrackpadGesture
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.clearContentMeasurer
import dev.nucleusframework.window.tao.clipboard.ProvideTaoClipboard
import dev.nucleusframework.window.tao.deco.ResizeFrameDecoration
import dev.nucleusframework.window.tao.deco.TaoLinuxOverlayController
import dev.nucleusframework.window.tao.deco.TaoLinuxOverlayControllerImpl
import dev.nucleusframework.window.tao.event.TaoWheelPinchZoom
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.taoKeyEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.event.taoTypedKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxTouchBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
import dev.nucleusframework.window.tao.hasGlTextureImports
import dev.nucleusframework.window.tao.installContentMeasurer
import dev.nucleusframework.window.tao.popup.PopupScreenGeometry
import dev.nucleusframework.window.tao.popup.PopupScrimRegistry
import dev.nucleusframework.window.tao.popup.TaoPopupHostLinux
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayerLinux
import dev.nucleusframework.window.tao.releaseGlTextureImports
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Linux variant of [TaoComposeSceneHost]. Drives a Compose scene onto the
 * Tao-owned GTK window via the EGL helper. Works on both X11 and Wayland — the
 * helper picks the right `EGLNativeWindowType` (Xlib XID vs `wl_egl_window`)
 * from the (kind, display, native_window) triple resolved at attach time.
 *
 * Threading: every public method runs on the thread that owns the Tao event
 * loop. EGL contexts are per-thread, so all rendering must stay there.
 *
 * Decorations on Linux follow the yaru.dart pattern: the GTK toplevel stays
 * `decorated` with a hidden `GtkHeaderBar` installed via
 * `gtk_window_set_titlebar()` (Wayland, non-popup), so GTK itself draws the
 * native theme drop shadow / rounded corners / resize border while the
 * user's [TitleBar] composable renders the visible chrome inside the content
 * area. The EGL content subsurface is positioned at GTK's content-area origin
 * (see [applyContentOffset]); X11 keeps the flat undecorated presentation.
 */
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Suppress("LargeClass", "TooManyFunctions")
internal class TaoComposeSceneHostLinux(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    // Full-window per-pixel transparency (#416). Creation-time; Linux always
    // builds with an ARGB visual for EGL, and this flag starts the clear at
    // alpha 0 so empty client areas show the desktop.
    private val fullyTransparent: Boolean = false,
) : AbstractTaoComposeSceneHost() {
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    /**
     * ARGB color the render loop clears the surface to each frame, pushed in
     * via [LocalRequestedClearColor] by the themed window (window background)
     * and by `TitleBar` (resolved title-bar background). Defaults to opaque
     * white until the first composition (alpha 0 when [fullyTransparent]).
     * The post-render carve ([applyFrameDecoration]) re-clears the rounded
     * corners to transparent regardless of this clear color.
     */
    val clearColorArgbState: androidx.compose.runtime.MutableState<Int> =
        androidx.compose.runtime.mutableStateOf(
            if (fullyTransparent) 0 else 0xFFFFFFFF.toInt(),
        )

    /**
     * IME preedit / commit routing (#558).
     *
     * No typed-key fallback: that argument exists for the macOS PressAndHold
     * accent picker, which has no GTK counterpart — an input method only ever
     * delivers text while a text-input session is up.
     */
    private val imeSession = TaoImeSession()

    /** App-level pre-dispatch hook. See [TaoComposeSceneHost.previewKeyHandler]. */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /** App-level post-dispatch hook. See [TaoComposeSceneHost.keyHandler]. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Forwarded through [LinuxTaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    /**
     * When true, Compose Popup / DropdownMenu / Tooltip layers materialise as
     * real Tao popup windows ([TaoPopupSceneLayerLinux] — override-redirect on
     * X11, `wl_subsurface` on Wayland) instead of drawing inside this window's
     * EGL render target. Opt-in — see the Windows/macOS counterparts. Set
     * before [attach].
     */
    var nativePopupLayers: Boolean = false

    /**
     * Renderers registered by popup layers. Drained AFTER the main scene's
     * render in [onRedrawRequested] — each popup binds its own private EGL
     * context (one context per attachment on Linux), paints, presents with
     * swap interval 0 and releases, so no state leaks into the host context.
     */
    private val popupRenderers: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Dialog scrims of the native popup layers, painted over the main scene at
     * the end of every frame — see [PopupScrimRegistry].
     */
    private val popupScrims =
        PopupScrimRegistry {
            sceneBundle?.visualDirty?.set(true)
            requestRedrawCoalesced()
        }

    /**
     * Dialog scrims of native popup layers land on the owner window's surface,
     * after its content — Compose Desktop's `onRenderOverlay`.
     */
    private fun paintPopupScrims(canvas: Canvas) {
        popupScrims.paintAll(
            canvas,
            Rect.makeWH(widthPx.toFloat(), heightPx.toFloat()),
            transparent = fullyTransparent,
        )
    }

    /**
     * Hooks every main-scene bundle gets: frame failures (recomposition /
     * layout / draw) go to the window's exception handler — the single seam
     * all three platforms render through — and popup scrims paint after the
     * content.
     */
    private fun configureSceneBundle() {
        val bundle = sceneBundle ?: return
        bundle.exceptionHandler = exceptionHandler
        bundle.renderOverlay = ::paintPopupScrims
    }

    /**
     * Key handlers consulted before the main scene's key dispatch. Popup
     * windows never own keyboard focus on Linux (override-redirect /
     * subsurface), so the parent forwards — mirrors the macOS chain.
     */
    private val popupKeyHandlers: MutableMap<Any, (KeyEvent) -> Boolean> = LinkedHashMap()

    /** The layer holding this window's `xdg_popup` slot — see [TaoPopupHostLinux.acquireCompositorPopup]. */
    private var compositorPopupOwner: Any? = null

    /** Callbacks invoked when the owner window's screen position changes (X11). */
    private val ownerMoveListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Callbacks invoked when a press reaches the parent scene while popup
     * layers are alive — the popup windows own their input region, so a
     * parent press is by definition outside every popup. See
     * [TaoPopupHostLinux.registerOutsidePressListener].
     */
    private val outsidePressListeners: MutableMap<Any, (PointerButton?) -> Unit> =
        LinkedHashMap()

    private val windowInfo = TaoWindowInfo()
    private var currentKeyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene

    init {
        // Reads `scene` lazily, so it is valid before the bundle exists (null)
        // and across bundle swaps; cleared in dispose().
        window.installContentMeasurer { constraints -> scene?.measureContent(constraints) }
    }

    /**
     * Handle `TextureView`s in this window's scene import onto — see
     * [TaoGlTextureHost]. A **state** rather than a plain field because a
     * Wayland hide/show cycle destroys and rebuilds the EGL attachment and the
     * Skia context ([suspendGpu] / [resumeGpu]): the composition reads it, so
     * imports made on the old context are dropped and redone on the new one
     * instead of silently drawing into a dead context.
     */
    val glTextureHostState: MutableState<TaoGlTextureHost?> = mutableStateOf(null)

    /**
     * Coroutine drains left for the current frame's swap window — see the
     * swap-in-flight branch of [onRedrawRequested]. Reset on every render.
     */
    private var skipDrainBudget: Int = SKIP_DRAIN_BUDGET_PER_FRAME

    /** Diagnostics for a frame the swap gate skipped — see [onRedrawRequested]. */
    private var skippedFrames: Int = 0
    private var skippedFrameStartNanos: Long = 0L

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null
    private val flushingDispatcher = FlushingMainDispatcher()

    /** Floating text-selection bar shown on touch selection. */
    private val textToolbar = TaoTextToolbar()

    /**
     * Coalesces `window.requestRedraw()` to one outstanding redraw per frame.
     * Multiple Compose call sites trigger redraws (the scene's `invalidate`
     * lambda, the FlushingMainDispatcher, a11y schedules, resize/scale
     * handlers); without this gate they spam Tao's `draw_tx` channel and
     * we render at the dispatch rate (>1k/sec on continuous animations).
     * Reset at the start of [onRedrawRequested].
     */
    private val redrawPending =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private fun requestRedrawCoalesced() {
        if (redrawPending.compareAndSet(false, true)) {
            window.requestRedraw()
        }
    }

    /**
     * Vsync swap thread. Owns the EGL context only during the
     * `eglSwapBuffers` call, which on Wayland blocks waiting for the
     * compositor's frame callback (and on X11 for the next refresh).
     * Running it on a *separate* thread is what makes `eglSwapInterval(1)`
     * usable — the GTK main thread keeps draining `wl_display` events
     * while the swap thread is parked on the swap, so the frame callback
     * that unblocks the swap can actually arrive. Swapping on the GTK
     * thread (the original implementation) deadlocks Mesa on Wayland.
     *
     * Pacing is intrinsic but *non-blocking*: the main thread renders only when
     * the swap is idle ([SwapThread.tryBeginRenderOrMarkOwed]); if a swap is in
     * flight it bails without waiting and the swap thread re-arms the redraw on
     * completion. So pacing still tracks the display refresh rate, but the
     * event-loop/input thread is never stalled on the swap.
     */
    private var swapThread: SwapThread? = null

    /** Last opaque region pushed: (logicalW, logicalH, cornerRadius). */
    private var lastOpaqueRegion: Triple<Int, Int, Int>? = null

    /**
     * GtkWidget handles currently embedded via [NativeView]. While any are
     * present, Compose punches transparent holes (`BlendMode.Clear`) so the
     * native widget shows through the EGL subsurface — that only works if the
     * compositor still blends GTK underneath us, so we must not declare an
     * opaque region. Tracked by handle so duplicate attach/detach is safe.
     */
    private val attachedNativeViews: MutableSet<Long> = linkedSetOf()

    /**
     * Handles whose detach has run and that have not been attached again —
     * a late `setFrame` for one of these must not touch the widget.
     * Cleared on attach: a new widget can be allocated at an old address.
     */
    private val detachedNativeViews: MutableSet<Long> = hashSetOf()
    private val nativeViewRects: MutableMap<Long, IntArray> = LinkedHashMap()

    /**
     * Latched on the first [NativeView] attach and never cleared: an embedded
     * widget with a GPU compositor (WebKitWebView) issues GL calls on this
     * same thread — between our frames, during its own teardown after detach,
     * and crucially **in the middle of our render pass**: composition effects
     * running inside `bundle.render` (a `loadUrl` in `update {}`, the realize
     * triggered by the mount) hand control to WebKit, which makes its own EGL
     * context current and does not restore ours. Skia only *issues* its GL at
     * flush time, so the whole frame's GPU work — including the glyph-atlas
     * uploads for every piece of text first drawn on that frame — would land
     * in the foreign context; those atlas entries stay blank forever and text
     * renders with randomly missing glyph instances (seen on Mutter+NVIDIA;
     * Weston's WebKit init path never swaps the context mid-frame).
     *
     * Once latched, every frame (1) re-binds our context and invalidates
     * Skia's GL state cache after `bundle.render`, before any GPU work, and
     * (2) `resetGLAll()`s at frame start for the between-frames case — same
     * protocol as [TaoGpuRenderContext.withContextCurrent] and the standalone
     * popup host.
     */
    private var foreignGlInterop = false

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    /**
     * Peer-level resize hit-test, mirrors JBR's `WLDecoratedPeer` calling
     * `FrameDecoration.processMouseEvent` before `super.postMouseEvent`. Only
     * active for resizable (non-maximized, non-fullscreen) undecorated windows
     * — Tao on Linux always presents the toplevel as `decorations=false` and
     * paints chrome via Compose. See [onPointerMove] / [onPointerButton].
     */
    private val resizeDecoration = ResizeFrameDecoration(window.handle)

    // Coalescing: `onResized`/`onScaleFactorChanged` arrive at 60–120 Hz during
    // a user drag. Doing the X11 round-trip (XResizeWindow + rounded-shape
    // XShape rebuild) on every event is what was deadlocking the NVIDIA driver
    // on Blackwell. We just stash the latest size+scale and let the next
    // `onRedrawRequested` apply them once before drawing.
    private var lastAppliedWidthPx: Int = -1
    private var lastAppliedHeightPx: Int = -1
    private var lastAppliedScale: Float = Float.NaN

    /**
     * Wayland: size of the EGL buffer currently in use for painting.
     * `wl_egl_window_resize` only takes effect on the next `eglSwapBuffers`.
     * Used only when [useDrawableSizedPaint] is true (KWin): paint at this size
     * and advance after present. Elsewhere (GNOME / main) paint at the window
     * size so layout stays in sync with the configure.
     */
    private var drawableWidthPx: Int = 0
    private var drawableHeightPx: Int = 0

    /**
     * KWin flashes if we paint at the window size into a still-old EGL FB
     * (BOTTOM_LEFT). GNOME does not need that trade-off — keep master's
     * window-sized paint there (and on every non-Plasma DE).
     */
    private val useDrawableSizedPaint: Boolean
        get() =
            attachedKind == 2 &&
                LinuxDesktopEnvironment.Current == LinuxDesktopEnvironment.KDE

    // Cache the Skia RT/Surface across frames — recreated only when the size
    // changes. Reallocating an FBO + GL surface every frame piles up driver
    // work that contributes to the resize-time GPU lockup.
    private var cachedRt: BackendRenderTarget? = null
    private var cachedSurface: Surface? = null

    // Scene-size update throttle. Compose's layout cache is keyed on size: the
    // first frame at any new size triggers a full remeasure (80-150ms for
    // complex content), subsequent frames at the same size use cached layout
    // (~10ms). On macOS, Core Animation throttles resize events to the display
    // refresh rate so the scene sees the same size for multiple frames and
    // benefits from the cache. GTK fires a resize event for every pixel of
    // mouse movement, so without throttling EVERY frame during a drag is an
    // expensive first-frame-at-new-size.
    //
    // Fix: update scene.size at most once per ~16ms (≈60fps). The EGL surface
    // still resizes every event (correct display area), but Compose layout
    // only recomputes at 60fps. Between updates the scene renders at the
    // previous size; during the brief interval the content may be slightly
    // clipped or have transparent margins, which is the same visual trade-off
    // macOS makes during live-resize.
    private var lastSceneSizeUpdateNs: Long = 0L

    /**
     * Interactive-resize burst (all Wayland DEs). While size is changing,
     * drop [eglSwapInterval] to 0 and queue catch-up paints so the buffer
     * from the pending `wl_egl_window_resize` is drawn without waiting on a
     * frame callback. Restores interval 1 after [RESIZE_BURST_HOLD_NS] idle.
     * Same visual path as before — no viewport stretch.
     */
    private var lastResizeEventNs: Long = 0L
    private var resizeBurstActive: Boolean = false

    /**
     * Whether the content sub-surface is in `set_sync` mode — entered with the
     * resize burst while an embed is attached, left when the burst ends. In
     * that mode a Compose buffer only shows with GTK's toplevel commit, which
     * is what makes it land atomically with the embed's new position; see
     * `NativeTaoEglBridge.nativeSetSubsurfaceSync`.
     */
    private var subsurfaceSynced: Boolean = false
    private var appliedSwapInterval: Int = 1
    private var pendingSwapInterval: Int? = null

    /**
     * Extra redraws after a size change so the buffer allocated by the next
     * `eglSwapBuffers` is actually painted. Written on the event-loop thread
     * ([onResized]), decremented on the swap thread after present.
     */
    private val postResizeCatchUpFrames = AtomicInteger(0)
    private val sceneSizeUpdateIntervalNs = 16_666_667L // 60fps

    /**
     * In-drag GPU cache purge, deferred to the next render pass. [onResized]
     * runs on the event-loop thread with no EGL context bound — the swap thread
     * may even hold ours for its `eglSwapBuffers` — so the timing decision is
     * taken here and the purge itself happens in [onRedrawRequested], the one
     * place this host's context is current on this thread.
     */
    private var lastResizePurgeNs: Long = 0L
    private var resizePurgeDue: Boolean = false

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // Sub-pixel deadband (#615): the wire delivers 1/1024-px positions, so
    // click jitter under 1 dp must not reach the scene — Compose's mouse
    // slop is 0.125 dp, and a parent drag gesture consuming that phantom
    // move cancels the child's tap ("buttons need two clicks"). Mouse events
    // dispatched to the scene use the deadband's position, never the raw
    // lastPointerX/Y (SyntheticEventSender would re-inject the difference);
    // the raw position keeps feeding the resize band and gesture centres.
    private val pointerDeadband = TaoPointerDeadband()

    /**
     * Codes of the currently-pressed mouse buttons. While non-empty a drag is
     * in flight: pointer positions may legitimately be OUTSIDE the window (the
     * platform grab keeps delivering them) and must reach Compose — the
     * resize-band hit-test must not swallow them.
     *
     * A set, not a counter, so it can't desync: the GTK backend delivers a
     * duplicate press for the same button when a click triggers a relayout
     * (e.g. the theme toggle re-dispatches the press at the same coords). A
     * counter would go 1→2→1 and stay stuck, permanently disabling the hover
     * resize hit-test; re-adding a code already in the set is a no-op.
     */
    private val pressedButtons = mutableSetOf<Int>()

    /**
     * Tao codes of the buttons whose press Compose handed to an embedded
     * native widget ([TaoNativeViewHost.dispatchPointerToNative]) and whose
     * release has not come back yet.
     *
     * Such a release routinely never comes: the embed's own context menu, or a
     * drag it starts, takes a grab and the release goes there. Compose is then
     * left holding a button forever, and — since a click needs a down
     * *transition* — every later click on Compose is dead, and hover no longer
     * updates the cursor. [healStaleNativePresses] asks GDK which buttons are
     * really down on the next motion and releases the phantoms; the next press
     * releases them regardless, the way the macOS host does.
     */
    private val forwardedNativeButtons = mutableSetOf<Int>()

    /** Whether the press being dispatched was handed to a native view — reset at every press. */
    private var nativePointerDispatchedThisEvent = false

    /**
     * Captured at the first composition via [setContent]. Exposes the
     * standard `FocusManager.clearFocus(force = true)` API which the
     * scene-level [androidx.compose.ui.scene.ComposeSceneFocusManager]
     * doesn't surface — needed to break a `BasicTextField`'s
     * "Captured" focus state when the user dismisses a context menu.
     */
    private var capturedFocusManager: androidx.compose.ui.focus.FocusManager? = null

    // Corner-radius mirrors `decorated-window-core/DecoratedWindowCore.kt`'s
    // `RoundRectangle2D.Float(0, 0, w, h, gnomeCornerArc, gnomeCornerArc)` —
    // RoundRectangle2D's `arcw`/`arch` arguments are the full arc *width*
    // (= 2 × radius), not the radius itself. So `gnomeCornerArc = 24f` paints
    // a 12 px radius, and `kdeCornerArc = 10f` paints a 5 px radius. These are
    // *logical* pixels: the carve path multiplies by `scale` before drawing
    // (the canvas works in physical pixels with no scale transform).
    private val cornerRadiusPx: Int =
        when (LinuxDesktopEnvironment.Current) {
            LinuxDesktopEnvironment.Gnome -> 12
            LinuxDesktopEnvironment.KDE -> 5
            else -> 0
        }

    /** Backend kind of the current EGL attachment: 1 = X11, 2 = Wayland. */
    private var attachedKind: Int = 0

    /** True once attached on the X11/XWayland backend (vs native Wayland). */
    val isX11: Boolean get() = attachedKind == 1

    /**
     * True while a compositor-driven interactive resize/move drag is in
     * flight. The compositor's grab makes GTK report a focus-out for the
     * whole drag, but a native GTK window keeps its active appearance while
     * being resized or moved — so focus loss is masked while this is set.
     * Cleared when focus comes back (the grab ended) or on the next real
     * button press (events only reach us once the grab is over).
     */
    private var compositorDragActive = false

    /**
     * Whether a compositor move or resize grab is currently in flight. Read by
     * [dev.nucleusframework.window.tao.openDecoratedWindow] to hold the
     * chrome's active appearance for the duration of the grab.
     */
    internal val isCompositorGrabActive: Boolean
        get() = compositorDragActive

    /** True while the EGL attachment is torn down because the window is hidden. */
    private var gpuSuspended: Boolean = false

    /**
     * True when this window was created with the yaru-style hidden-titlebar
     * CSD (decorated GTK toplevel + hidden GtkHeaderBar → GTK draws the native
     * shadow ring). Set by [dev.nucleusframework.window.tao.DecoratedWindow]
     * before [attach]; only effective on Wayland non-popup windows — the
     * native layer never latches CSD elsewhere.
     */
    var nativeCsdDecorations: Boolean = false

    /** Whether the GTK-drawn CSD frame (shadow ring) is live for this window. */
    private val isCsdActive: Boolean
        get() = nativeCsdDecorations && attachedKind == 2 && !window.isPopup

    fun attach() {
        attachGpu()
        if (isCsdActive) {
            // Round the GTK frame to the same radius as the Compose corner
            // carve so the native decoration and the content coincide.
            NativeTaoBridge.nativeLinuxSetCsdCornerRadius(window.handle, cornerRadiusPx)
        }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val dndManager =
            dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager(
                getRootNode = { scene!!.rootDragAndDropNode },
                outboundLauncher = ::launchLinuxOutboundDrag,
                // The cross-window gestures ride the DnD session on native
                // Wayland; their token-only payload is meaningful here.
                acceptsPrivateData = true,
            )
        liveHosts += this
        window.contentSnapshot = ::snapshotContent
        // IME callbacks edit the focused field through `TextEditingScope`, i.e.
        // they run user code straight off a GTK IM callback — the Tao
        // counterpart of AWT's guarded `inputMethodTextChanged`.
        window.imePreedit = { text -> exceptionHandler.catchExceptions { imeSession.preedit(text) } }
        window.imeCommit = { text -> exceptionHandler.catchExceptions { imeSession.commit(text) } }
        val platformContext =
            LinuxTaoPlatformContext(
                windowHandle = window.handle,
                // The custom CSD title bar is drawn inside the same Compose
                // scene as the rest of the content, so it shares the (0, 0)
                // origin with everything else. We must NOT report it as a
                // `PlatformInsets.top`: Compose's `RootMeasurePolicy` (cf.
                // `RootMeasurePolicy.skiko.kt::positionWithInsets`) applies
                // platform insets as an *additive offset* on the popup
                // position (designed for iOS notches / Android status
                // bars, where the safe area is outside the Compose surface).
                // Reporting `top = titleBarHeight` here shifts every Popup,
                // DropdownMenu, ContextMenu, and Tooltip down by that
                // amount — visible as a consistent "title-bar-height
                // downward drift" of every popup the user opens. Popups
                // are free to overlap the title bar zone; the title bar
                // composable's own z-order keeps it visually on top of
                // the page content but popups (rendered in a higher
                // ComposeSceneLayer) naturally float above both.
                topInsetPx = { 0 },
                scaleProvider = { scale },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
                textToolbar = textToolbar,
                onInputSession = { imeSession.onInputSession(it) },
                isWindowTransparent = fullyTransparent,
            )
        sceneBundle =
            if (nativePopupLayers) {
                // Opt-in path: every Popup becomes a Tao popup window owned by
                // this window (override-redirect on X11, wl_subsurface on
                // Wayland), so popup content can extend beyond — and float
                // independently of — the window bounds.
                platformLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    composeSceneContext = TaoComposeSceneContext(platformContext, nativePopupLayerFactory()),
                    requestFrame = { requestRedrawCoalesced() },
                )
            } else {
                // Default: Compose Popup / DropdownMenu / Tooltip content stays
                // in the same EGL render target as the rest of the UI.
                canvasLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    platformContext = platformContext,
                    requestFrame = { requestRedrawCoalesced() },
                )
            }
        scene?.compositionLocalContext = pendingCompositionLocalContext
        configureSceneBundle()

        // Notify popup layers when the host window moves on screen — X11
        // popups are positioned in root coordinates and don't auto-track.
        window.onMoved { _, _ ->
            if (ownerMoveListeners.isNotEmpty()) {
                for (cb in ownerMoveListeners.values.toList()) cb()
            }
        }

        // One source of truth for the scene's drop target: the callback below
        // resolves it through here, and so does an in-process driver.
        window.inboundDragAndDropNode = { scene?.rootDragAndDropNode }
        registerInboundDnD()
        registerTouch()
    }

    /**
     * EGL + Skia half of [attach]: resolves the native window handles, binds
     * an EGL context/surface, creates the per-window [DirectContext] and
     * starts the swap thread. Split out so [resumeGpu] can rebuild the GPU
     * side alone after a hide/show cycle destroyed the native surface.
     */
    private fun attachGpu() {
        check(NativeTaoBridge.isLoaded && NativeTaoEglBridge.isLoaded) {
            "Tao Linux native libraries not loaded"
        }
        // (kind, display, native_window) — see NativeTaoBridge.nativeLinuxHandles.
        //   kind=1 → Xlib  (`display` = X Display*, `native_window` = XID)
        //   kind=2 → Wayland (`display` = wl_display*, `native_window` = wl_surface*)
        // GDK auto-picks the backend: native Wayland on Wayland sessions,
        // X11 on X11 sessions or when NUCLEUS_TAO_LINUX_RENDERER=x11 forces
        // GDK_BACKEND=x11 (see lib.rs).
        val handles = NativeTaoBridge.nativeLinuxHandles(window.handle)
        require(handles != null && handles.size == 3 && handles[0].toInt() != 0) {
            "Linux window handles unavailable; window not yet realised"
        }
        val kind = handles[0].toInt()
        val display = handles[1]
        val nativeWin = handles[2]
        check(kind == 1 || kind == 2) {
            "Unsupported Tao window kind=$kind"
        }

        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f

        // Initial buffer / child-window size. If we already know widthPx/heightPx
        // (post-Resized) pass those; the X11 helper otherwise queries the
        // parent via XGetWindowAttributes, the Wayland helper falls back to 1×1.
        val initialW = widthPx.coerceAtLeast(0)
        val initialH = heightPx.coerceAtLeast(0)

        attachmentHandle =
            when (kind) {
                1 -> {
                    val h = NativeTaoEglBridge.nativeAttachX11(display, nativeWin, initialW, initialH)
                    require(h != 0L) { "Failed to create EGL context for XID=$nativeWin" }
                    h
                }
                2 -> {
                    // Wayland: render into a wl_subsurface child of GTK's surface
                    // (see nucleus_tao_egl.c). initialW/initialH are already
                    // physical pixels (logical × scale), so they ARE the buffer
                    // size — do NOT multiply by scale again. We pass the integer
                    // surface scale so the child sets `buffer_scale` to match
                    // GTK's parent: a `logical × scale` px buffer is then read as
                    // `logical` surface units, fixing the oversize and input
                    // miscalibration. GTK3 reports integer scale only; true
                    // fractional (wp_viewporter + wp_fractional_scale_v1) is a
                    // future, toplevel-owning effort.
                    val physW = initialW.coerceAtLeast(1)
                    val physH = initialH.coerceAtLeast(1)
                    val bufferScale = scale.roundToInt().coerceAtLeast(1)
                    // Popup overlays: swap interval 0 — their EGL child hangs
                    // off GDK's own synchronized wl_subsurface, where Mesa's
                    // FIFO commit-timing state is never consumed and the next
                    // set_timestamp is a fatal protocol error (see
                    // TaoWindow.isPopup). Pacing there is event-driven anyway.
                    val swapInterval = if (window.isPopup) 0 else 1
                    val h =
                        NativeTaoEglBridge.nativeAttachWayland(
                            display,
                            nativeWin,
                            physW,
                            physH,
                            bufferScale,
                            swapInterval,
                        )
                    require(h != 0L) {
                        "Failed to create EGL context for wl_surface=$nativeWin — libwayland-egl missing?"
                    }
                    h
                }
                else -> error("unreachable")
            }

        // 1 GrDirectContext per window, paired with its own EGL context (see
        // nucleus_tao_egl.c). Skia's intended ownership model: one direct
        // context exclusively drives one GL context, no FBO 0 ambiguity, no
        // manual GL-state reset between frames.
        //
        // We hand Skia an `eglGetProcAddress`-backed proc loader through
        // `GLAssembledInterface` — same trick Skiko uses for Angle on Windows.
        val fnPtr = NativeTaoEglBridge.nativeGetProcAddrFunctionPointer()
        require(fnPtr != 0L) {
            "NativeTaoEglBridge.nativeGetProcAddrFunctionPointer returned 0 — libEGL.so.1 missing?"
        }
        val iface = GLAssembledInterface.createFromNativePointers(0L, fnPtr)
        val ctx = DirectContext.makeGLWithInterface(iface)
        // Anchor the GPU resource cache budget while the fresh EGL context is
        // still the one the native attach left current — writing the limit
        // purges to fit, so like every other use of the context it belongs
        // where the context is usable. The value itself changes nothing today
        // (see GPU_RESOURCE_CACHE_LIMIT_BYTES); what reclaims the per-size
        // scratch of a drag is [purgeResizeScratchIfDue].
        ctx.resourceCacheLimit = GPU_RESOURCE_CACHE_LIMIT_BYTES
        directContext = ctx
        // Publish the TextureView handle for the fresh EGL context / Skia
        // context pair (see glTextureHostState).
        val ownAttachment = attachmentHandle
        glTextureHostState.value =
            object : TaoGlTextureHost {
                override val directContext: DirectContext = ctx

                // Bound only while this pair is the live one. A Wayland
                // hide/show rebuilds the EGL context *and* the DirectContext:
                // reading the outer attachment live would bind the *new* EGL
                // context for a consumer still holding this object's closed
                // `ctx` — a `flushAndSubmit` on it is a SIGSEGV in Skia. Once
                // the outer handle moved on (or went to 0 on detach) this
                // pair is gone, and the caller's null means "context gone".
                override fun <T> withContextCurrent(block: () -> T): T? =
                    if (attachmentHandle != ownAttachment ||
                        directContext !== this@TaoComposeSceneHostLinux.directContext
                    ) {
                        null
                    } else {
                        withEglContextCurrent(ownAttachment, block)
                    }
            }

        // The native attach binds the EGL context to *this* thread (the GTK
        // main thread). Release it so the swap thread can take it for
        // `eglSwapBuffers`. We re-bind on the main thread for every render
        // pass via [bindContextForRender].
        NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
        swapThread = SwapThread(attachmentHandle).also { it.start() }
        attachedKind = kind
        // Force the next render to re-push size/scale into the fresh EGL
        // surface and rebuild the Skia render target.
        lastAppliedWidthPx = -1
        lastAppliedHeightPx = -1
        lastAppliedScale = Float.NaN
        // Attach creates the wl_egl_window at the current physical size.
        drawableWidthPx = widthPx.coerceAtLeast(0)
        drawableHeightPx = heightPx.coerceAtLeast(0)
    }

    /**
     * Tears down the GPU side (swap thread, Skia, EGL attachment) while the
     * window is hidden. Wayland only: `gtk_widget_hide` destroys the parent
     * `wl_surface`, and any `eglSwapBuffers` racing that destruction commits
     * to an orphaned subsurface — the compositor answers with a fatal
     * protocol error (GDK "Error 71", observed as
     * `wp_commit_timer_v1: "Commit already has timestamp"`). On X11 the XID
     * survives a hide, so the attachment is kept.
     *
     * Called synchronously (via [dev.nucleusframework.window.tao.TaoWindow.onWillHide])
     * on the event-loop thread BEFORE the GTK hide runs.
     */
    fun suspendGpu() {
        if (attachmentHandle == 0L || gpuSuspended || attachedKind != 2) return
        gpuSuspended = true
        // Wait out any in-flight swap; after the join no other thread touches
        // the EGL context (same protocol as [detach]).
        swapThread?.shutdownAndJoin()
        swapThread = null
        NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
        cachedSurface?.close()
        cachedSurface = null
        cachedRt?.close()
        cachedRt = null
        drawableWidthPx = 0
        drawableHeightPx = 0
        // Drop TextureView imports made on this context while it is still
        // current and alive; the composition survives the hide, so its leases
        // would otherwise hold Skia images on a destroyed context.
        directContext?.let(::releaseGlTextureImports)
        glTextureHostState.value = null
        // The DirectContext is bound to the EGL context being destroyed; the
        // scene itself survives and renders again once [resumeGpu] rebuilds it.
        directContext?.close()
        directContext = null
        NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
        NativeTaoEglBridge.nativeDetach(attachmentHandle)
        attachmentHandle = 0L
        // The sub-surface went with the attachment; a fresh one starts desync.
        subsurfaceSynced = false
    }

    /**
     * Rebuilds the GPU side after the GTK window was shown again — GDK has
     * created a brand-new `wl_surface`, so the EGL attachment is recreated
     * from scratch. No-op unless [suspendGpu] ran.
     */
    fun resumeGpu() {
        if (!gpuSuspended) return
        gpuSuspended = false
        attachGpu()
        // The redraw gate may have latched while hidden (invalidations with no
        // draw ever arriving); clear it so the re-arm below goes through.
        redrawPending.set(false)
        requestRedrawCoalesced()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun launchLinuxOutboundDrag(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
        onCompleted: (androidx.compose.ui.draganddrop.DragAndDropTransferAction?) -> Unit,
    ): Boolean {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.isLoaded) return false
        if (window.handle == 0L) return false
        // Synchronous path, unlike Windows (#435): the session cooperatively
        // pumps the GTK main loop, so it completes before this returns and
        // the result is reported inline.
        val action: androidx.compose.ui.draganddrop.DragAndDropTransferAction? =
            dev.nucleusframework.window.tao.dnd.TaoSceneDnD.launchOutboundDrag(
                request = request,
                dropEffectCopy = dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY,
                dropEffectMove = dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_MOVE,
                dropEffectLink = dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_LINK,
            ) { files, text, allowedEffects ->
                // No VSync dance and no post-drag `window.resetRedrawLatch()`,
                // unlike the Windows counterpart: the session's GTK pump consumes
                // no tao event, so the `REDRAW_REQUESTED` matching a latched
                // `redrawPending` still sits in tao's draw channel when the drag
                // ends and the latch un-wedges itself on delivery.
                val icon = rasterizeDragDecoration(request)
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.nativeStartDrag(
                    handle = window.handle,
                    files = files,
                    text = text,
                    privateData = request.privateData,
                    allowedEffects = allowedEffects,
                    iconArgb = icon?.argb,
                    iconWidth = icon?.width ?: 0,
                    iconHeight = icon?.height ?: 0,
                    iconScale = icon?.scale ?: 1f,
                    iconHotX = icon?.hotX ?: 0,
                    iconHotY = icon?.hotY ?: 0,
                    pump = OutboundDragPump(),
                )
            }
        onCompleted(action)
        return true
    }

    /**
     * Draws the scene's current composition into a raster bitmap and returns
     * [rectPx] of it (content pixels), or the whole content when `null`. The
     * same recompose-layout-draw pass the GL frame runs, aimed at a CPU
     * surface, so it costs one extra frame and needs no context. Cleared to
     * the chrome colour like a real frame, so regions without an explicit
     * background come out as the window looks and not transparent.
     */
    private fun snapshotContent(rectPx: IntRect?): androidx.compose.ui.graphics.ImageBitmap? {
        val bundle = sceneBundle ?: return null
        val width = widthPx
        val height = heightPx
        if (width <= 0 || height <= 0) return null
        val full =
            androidx.compose.ui.graphics
                .ImageBitmap(width, height)
        val canvas = Canvas(full.asSkiaBitmap())
        canvas.clear(clearColorArgbState.value)
        bundle.render(canvas, System.nanoTime())
        val crop = rectPx?.intersect(IntRect(0, 0, width, height)) ?: return full
        if (crop.width <= 0 || crop.height <= 0) return null
        if (crop == IntRect(0, 0, width, height)) return full
        val region =
            androidx.compose.ui.graphics
                .ImageBitmap(crop.width, crop.height)
        androidx.compose.ui.graphics.Canvas(region).drawImageRect(
            image = full,
            srcOffset = crop.topLeft,
            srcSize = IntSize(crop.width, crop.height),
            dstSize = IntSize(crop.width, crop.height),
            paint =
                androidx.compose.ui.graphics
                    .Paint(),
        )
        return region
    }

    /** A rasterized drag decoration, in the shape `nativeStartDrag` takes. */
    private class DragIcon(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val scale: Float,
        val hotX: Int,
        val hotY: Int,
    )

    /**
     * Renders the request's drag decoration to premultiplied ARGB device
     * pixels for GTK's drag icon, at this window's scale so it stays crisp on
     * HiDPI. `null` for an empty decoration, which leaves GTK's default icon.
     *
     * Compose only ever hands a decoration to the manager — the source node
     * draws it into whatever the platform provides — so this is where the
     * Linux host turns it into pixels; the other two hosts still show their
     * platform default.
     */
    private fun rasterizeDragDecoration(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
    ): DragIcon? {
        val width = request.decorationSize.width.toInt()
        val height = request.decorationSize.height.toInt()
        if (width <= 0 || height <= 0 || width > MAX_DRAG_ICON_PX || height > MAX_DRAG_ICON_PX) return null
        val scale = window.scaleFactor.takeIf { it > 0f } ?: 1f
        val bitmap =
            androidx.compose.ui.graphics
                .ImageBitmap(width, height)
        androidx.compose.ui.graphics.drawscope
            .CanvasDrawScope()
            .draw(
                Density(scale),
                androidx.compose.ui.unit.LayoutDirection.Ltr,
                androidx.compose.ui.graphics
                    .Canvas(bitmap),
                request.decorationSize,
            ) { with(request) { drawDragDecoration() } }
        val pixels = IntArray(width * height)
        bitmap.readPixels(pixels)
        // readPixels is straight (un-premultiplied) ARGB; cairo wants premultiplied.
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = px ushr ALPHA_SHIFT
            if (a == 0) {
                pixels[i] = 0
            } else if (a != CHANNEL_MAX) {
                val r = ((px shr RED_SHIFT) and CHANNEL_MAX) * a / CHANNEL_MAX
                val g = ((px shr GREEN_SHIFT) and CHANNEL_MAX) * a / CHANNEL_MAX
                val b = (px and CHANNEL_MAX) * a / CHANNEL_MAX
                pixels[i] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
            }
        }
        return DragIcon(
            argb = pixels,
            width = width,
            height = height,
            scale = scale,
            hotX =
                request.decorationHotspot.x
                    .toInt()
                    .coerceIn(0, width),
            hotY =
                request.decorationHotspot.y
                    .toInt()
                    .coerceIn(0, height),
        )
    }

    /**
     * Drives the host while an outbound drag session owns the GTK main thread —
     * see [dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DragPump].
     *
     * Paints directly instead of going through [requestRedrawCoalesced], like
     * the Windows host and unlike the macOS one: on Linux the render happens
     * inline on this thread, and a `requestRedraw` issued during the session
     * would only land in tao's draw channel — undelivered until the drag is
     * over, which is the freeze itself. The timer is therefore the only frame
     * driver for the session, including after a tick that the swap-in-flight
     * gate skipped (the swap thread's re-arm goes through that same dead
     * channel).
     *
     * No VSync toggle and no frame throttle, unlike Windows: `eglSwapBuffers`
     * and its vsync wait run on the swap thread, and [onRedrawRequested]
     * returns immediately rather than blocking when a swap is still in flight,
     * so a tick never parks the GTK pump the drag is running on.
     *
     * Reentrancy, deliberately accepted: every frame painted here renders the
     * scene with a pointer dispatch still on the stack, since Compose enters the
     * session from inside `sendPointerEvent`. There is no way to render during
     * the drag *without* that nesting — refusing to render would just restore
     * the freeze this exists to fix — so the scene is re-entered knowingly. If
     * it proves unsafe, the principled fix is to defer `nativeStartDrag` onto
     * the main dispatcher so the session starts one loop iteration later, with
     * no Compose dispatch below it.
     *
     * Named class (not a lambda) for GraalVM JNI reachability, same as
     * [InboundDnDCallback].
     */
    private inner class OutboundDragPump :
        dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DragPump {
        override fun pump() {
            dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
                .pump()
            onRedrawRequested()
            // The other windows are frozen by the same dead draw channel, and
            // they are where a cross-window drag shows its feedback — the dock
            // zones lighting up in the window the pointer is over. Paint the
            // ones that asked to; their latched `redrawPending` is exactly the
            // request tao could not deliver.
            for (host in liveHosts) {
                if (host !== this@TaoComposeSceneHostLinux && host.redrawPending.get()) host.onRedrawRequested()
            }
        }
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.isLoaded) return
        val callback = InboundDnDCallback()
        dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge
            .nativeRegister(window.handle, callback)
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback : dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.Callback {
        private fun node() = window.inboundDragAndDropNode?.invoke()

        // Linux keeps neither the macOS/Windows diagnostic logging nor their
        // `if (!hasFiles) return NONE` guard, so its overrides delegate straight
        // to the shared helper. Folding those in via TaoSceneDnD would change
        // Linux behaviour (rejecting non-file drags at enter).
        override fun onDragEnter(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int =
            if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragEnter(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragOver(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int =
            if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragOver(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragLeave(handle: Long) =
            dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                .onDragLeave(node())

        override fun onDrop(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int =
            if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDrop(node(), x, y, files)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }
    }

    // ── Touch & trackpad gestures (Linux) ─────────────────────────────────
    //
    // Touchscreen multi-touch and trackpad pinch / rotate are bridged from
    // GTK 3 via `platform/linux/touch.rs` (see TOUCH_LINUX_RESEARCH_RESPONSE.md
    // for the full design). The native side translates GdkEventTouch and
    // GdkEventTouchpadPinch into the wire format below; we marshal them
    // into Compose pointer events here.
    //
    // Trackpad gesture path: same trick as the macOS host — synthesise two
    // ComposeScenePointer Touch points around the gesture focal point with
    // distance varying by accumulated scale and angle by accumulated
    // rotation, so `detectTransformGestures` reacts to pinch/rotate with
    // strictly cross-platform application code. Smart-magnify is macOS-only
    // and is never reported on Linux (no GDK equivalent).

    private fun registerTouch() {
        if (!NativeTaoLinuxTouchBridge.isLoaded) return
        val callback = InboundTouchCallback()
        NativeTaoLinuxTouchBridge.nativeRegister(window.handle, callback)
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability
     * metadata can register it explicitly — same pattern as
     * [InboundDnDCallback].
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private inner class InboundTouchCallback : NativeTaoLinuxTouchBridge.Callback {
        override fun onTouchEvent(
            handle: Long,
            eventType: Int,
            count: Int,
            ids: LongArray,
            xsFixed: LongArray,
            ysFixed: LongArray,
            pressedMask: Long,
        ) {
            // Touch runs user pointer-input code exactly like the mouse path,
            // but this bridge calls back outside `EventDispatcher.guarded`, so
            // both of the mouse wire's layers are reproduced by the guard: the
            // window's handler gets the first look, and a rethrow takes the
            // fatal path instead of unwinding into the JNI callback frame.
            guardBridgeCallback {
                val sc = scene ?: return
                if (count <= 0) return

                // Single-finger press in the resize edge band starts a native resize
                // drag — mirrors the mouse path in [onPointerButton]. The press is
                // consumed (never forwarded to Compose) so the compositor owns the
                // whole sequence, exactly like the mouse-driven resize. Positions are
                // physical px (`/ TOUCH_POSITION_SCALE`), matching what
                // [currentResizeDirection] expects. `begin_resize_drag` works during a
                // touch grab the same way `begin_move_drag` does for title-bar touch
                // drag (see the compositor pointer-grab note in [onNativeWindowDragStarted]).
                if (eventType == TaoTouchEvent.PRESS && count == 1) {
                    val direction =
                        currentResizeDirection(
                            xsFixed[0] / TOUCH_POSITION_SCALE,
                            ysFixed[0] / TOUCH_POSITION_SCALE,
                            forTouch = true,
                        )
                    if (resizeDecoration.onLeftPress(direction)) {
                        compositorDragActive = true
                        return
                    }
                }

                val pointers = ArrayList<ComposeScenePointer>(count)
                for (i in 0 until count) {
                    val pressed = (pressedMask and (1L shl i)) != 0L
                    pointers.add(
                        ComposeScenePointer(
                            id = PointerId(ids[i]),
                            position =
                                Offset(
                                    xsFixed[i] / TOUCH_POSITION_SCALE,
                                    ysFixed[i] / TOUCH_POSITION_SCALE,
                                ),
                            pressed = pressed,
                            type = PointerType.Touch,
                        ),
                    )
                }
                val composeType =
                    when (eventType) {
                        TaoTouchEvent.PRESS -> PointerEventType.Press
                        TaoTouchEvent.MOVE -> PointerEventType.Move
                        TaoTouchEvent.RELEASE, TaoTouchEvent.CANCEL -> PointerEventType.Release
                        else -> return
                    }
                sc.sendPointerEvent(
                    eventType = composeType,
                    pointers = pointers,
                    keyboardModifiers = currentKeyboardModifiers,
                )
                if (eventType == TaoTouchEvent.CANCEL) {
                    sc.cancelPointerInput()
                }
            }
        }

        override fun onTrackpadGesture(
            handle: Long,
            kind: Int,
            phase: Int,
            xFixed: Long,
            yFixed: Long,
            valueFixed: Long,
        ) {
            guardBridgeCallback { dispatchTrackpadGesture(kind, phase, xFixed, yFixed, valueFixed) }
        }

        // The native bridge invokes these callbacks directly from its JNI
        // thread, outside `EventDispatcher.guarded`, so reproduce the mouse
        // wire's two layers here: the window's handler first, then the fatal
        // path for anything it rethrows.
        @Suppress("TooGenericExceptionCaught") // a rethrowing handler may throw anything
        private inline fun guardBridgeCallback(block: () -> Unit) {
            try {
                exceptionHandler.catchExceptions(block)
            } catch (t: Throwable) {
                TaoApplication.reportFatal(t)
            }
        }
    }

    // Mirrors `TaoComposeSceneHost.onTrackpadGesture` (macOS) — kept inline
    // rather than abstracted into a shared helper because the two hosts have
    // diverged in other dimensions (rendering, scale handling, lifecycle)
    // and a thin shared trait would obscure more than it factors.
    private var gestureActive = false
    private var gestureCenterX = 0f
    private var gestureCenterY = 0f
    private var gestureScale = 1f
    private var gestureAngle = 0f

    // Ctrl+wheel is a discrete stream with no ENDED phase (unlike a native trackpad
    // gesture), so the synthetic magnify is released by an idle timer on this scope.
    // Deliberately NOT on the #622 fatal path: gesture helpers are isolated
    // (SupervisorJob) — a crash there costs one gesture, logged at SEVERE.
    private val gestureScope =
        CoroutineScope(coroutineContext + flushingDispatcher + SupervisorJob() + TaoNonFatalCoroutineExceptionHandler)
    private var wheelZoomEndJob: Job? = null

    @OptIn(ExperimentalComposeUiApi::class)
    private fun dispatchTrackpadGesture(
        kind: Int,
        phase: Int,
        xFixed: Long,
        yFixed: Long,
        valueFixed: Long,
    ) {
        if (scene == null) return
        val xPx = xFixed / TOUCH_POSITION_SCALE
        val yPx = yFixed / TOUCH_POSITION_SCALE
        val value = valueFixed / TRACKPAD_VALUE_SCALE
        when (phase) {
            TaoTrackpadPhase.BEGAN -> {
                startGesture(xPx, yPx)
                applyGestureDelta(kind, value)
                sendGesturePointers(PointerEventType.Press)
            }
            TaoTrackpadPhase.CHANGED -> {
                if (!gestureActive) {
                    startGesture(xPx, yPx)
                } else {
                    // Track the focal point on every tick so a pinch-while-
                    // dragging keeps its pan component (the synthetic centroid
                    // moves with the focal point between events).
                    gestureCenterX = xPx
                    gestureCenterY = yPx
                }
                applyGestureDelta(kind, value)
                sendGesturePointers(PointerEventType.Move)
            }
            TaoTrackpadPhase.ENDED -> endGesture(cancelled = false)
            TaoTrackpadPhase.CANCELLED -> endGesture(cancelled = true)
        }
    }

    private fun startGesture(
        centerX: Float,
        centerY: Float,
    ) {
        gestureActive = true
        gestureCenterX = centerX
        gestureCenterY = centerY
        gestureScale = 1f
        gestureAngle = 0f
    }

    private fun applyGestureDelta(
        kind: Int,
        value: Float,
    ) {
        when (kind) {
            TaoTrackpadGesture.MAGNIFY ->
                gestureScale *= (1f + value).coerceAtLeast(MIN_GESTURE_SCALE)
            TaoTrackpadGesture.ROTATE -> {
                // Rust converts GDK's per-event radians into degrees so this
                // matches the macOS NSEvent.rotation contract exactly. Sign
                // flip for Compose's y-down screen frame.
                gestureAngle -= value * (Math.PI.toFloat() / DEGREES_PER_RADIAN)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sendGesturePointers(eventType: PointerEventType) {
        val sc = scene ?: return
        val radius = TRACKPAD_BASE_RADIUS_PX * gestureScale
        val cosA = cos(gestureAngle)
        val sinA = sin(gestureAngle)
        val dx = radius * cosA
        val dy = radius * sinA
        val pressed = eventType != PointerEventType.Release
        val pointers =
            listOf(
                ComposeScenePointer(
                    id = PointerId(TRACKPAD_POINTER_ID_A),
                    position = Offset(gestureCenterX - dx, gestureCenterY - dy),
                    pressed = pressed,
                    type = PointerType.Touch,
                ),
                ComposeScenePointer(
                    id = PointerId(TRACKPAD_POINTER_ID_B),
                    position = Offset(gestureCenterX + dx, gestureCenterY + dy),
                    pressed = pressed,
                    type = PointerType.Touch,
                ),
            )
        sc.sendPointerEvent(
            eventType = eventType,
            pointers = pointers,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    private fun endGesture(cancelled: Boolean) {
        if (!gestureActive) return
        sendGesturePointers(PointerEventType.Release)
        gestureActive = false
        gestureScale = 1f
        gestureAngle = 0f
        if (cancelled) scene?.cancelPointerInput()
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    // Hop the debounced semantics walk onto the GTK main thread (it touches
    // Compose state) and coalesce a redraw. See AbstractTaoComposeSceneHost.
    override fun dispatchA11yWalk(block: () -> Unit) {
        flushingDispatcher.enqueue(Runnable { block() })
        requestRedrawCoalesced()
    }

    // Guarded like AWT's `ComposeSceneMediator.setContent`: the first
    // composition runs inside this call, so content that throws while mounting
    // must reach the window's handler instead of unwinding into the Tao loop.
    fun setContent(content: @Composable () -> Unit) =
        exceptionHandler.catchExceptions {
            scene?.setContent {
                // Capture the standard FocusManager from the composition
                // so the overlay controller can call `clearFocus(force =
                // true)` to break a `BasicTextField`'s "Captured" focus
                // state when a context menu dismisses (the scene-level
                // `releaseFocus()` only clears Active/ActiveParent and
                // leaves the caret visible).
                val fm = androidx.compose.ui.platform.LocalFocusManager.current
                androidx.compose.runtime.SideEffect {
                    capturedFocusManager = fm
                }
                // GTK clipboard instead of AWT's X11-only one: the window lives on
                // whichever GDK backend the session provides, and on Wayland the
                // two selections are not the same one (issue #582).
                ProvideTaoClipboard {
                    TaoTextToolbarHost(textToolbar, content)
                }
            }
        }

    /**
     * Forwards a parent composition's locals into this scene via
     * `ComposeScene.compositionLocalContext` — applied above the scene's own
     * `LocalComposeSceneContext`, so popups keep routing into THIS scene. See
     * [dev.nucleusframework.window.tao.LocalTaoCompositionLocalContextBridge].
     */
    fun setSceneCompositionLocalContext(context: androidx.compose.runtime.CompositionLocalContext?) {
        pendingCompositionLocalContext = context
        scene?.compositionLocalContext = context
    }

    fun onResized(
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        // Live DPI changes don't reach us through ScaleFactorChanged on the GTK
        // backend: tao's `connect_scale_factor_notify` only stores the new
        // factor, it never emits the event (unlike Windows/macOS). An integer
        // scale crossing (e.g. 100%→125%, which flips GDK scale 1→2) does fire a
        // GTK configure → a Resized with the new *physical* size, so we re-read
        // the live scale here and apply it before sizing the scene. Without this
        // the new physical size lands with a stale density / buffer_scale and the
        // window renders ~scale× oversized until the app is restarted.
        val liveScale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f
        if (liveScale > 0f && liveScale != scale) {
            onScaleFactorChanged(liveScale)
        }
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew

        // Enter / refresh the resize burst: drop vsync so the next buffer can
        // land without waiting on a frame callback. Wayland only — X11 has no
        // subsurface/geometry lag of this kind. All DEs (GNOME, KDE, …).
        lastResizeEventNs = System.nanoTime()
        if (attachedKind == 2 && !window.isPopup) {
            // Keep VSync when the scene composites a TextureView or holds a
            // TaoGpuRenderContext consumer — the Linux twin of the Windows
            // modal-loop rule (#484). At interval 0 their withFrameNanos
            // producers re-enter the render path at event-pump speed for the
            // whole drag; such a window trades the burst's catch-up latency
            // for a display-paced frame clock. Windows without frame-paced
            // GPU content keep the interval-0 burst unchanged.
            val framePacedContent =
                directContext?.let {
                    hasGlTextureImports(it) || TaoGpuRenderContextConsumers.isActive(it)
                } == true
            if (!resizeBurstActive && !framePacedContent) {
                resizeBurstActive = true
                pendingSwapInterval = 0
            }
            if (!subsurfaceSynced && attachedNativeViews.isNotEmpty() && attachmentHandle != 0L) {
                subsurfaceSynced = true
                NativeTaoEglBridge.nativeSetSubsurfaceSync(attachmentHandle, true)
            }
            // Two catch-up frames: (1) swap that allocates the new buffer,
            // (2) paint into it. Refreshed on every motion so a continuous
            // drag always has headroom after the last pixel.
            postResizeCatchUpFrames.set(2)
        }

        // Throttle scene.size updates to ~60fps so Compose can reuse its
        // layout cache between resize events. GTK fires an event for every
        // pixel of mouse movement; without throttling every frame is an
        // expensive first-frame-at-new-size (full remeasure: 80-150ms).
        // The EGL surface resize is deferred to `applyPendingNativeResize` in
        // `onRedrawRequested`, so the native side is always in sync.
        val now = System.nanoTime()
        if (now - lastSceneSizeUpdateNs >= sceneSizeUpdateIntervalNs) {
            scene?.size = IntSize(widthPx, heightPx)
            updateWindowInfoSize()
            lastSceneSizeUpdateNs = now
        }
        val opaqueScale = scale.roundToInt().coerceAtLeast(1)
        pushOpaqueRegion(
            (widthPx / opaqueScale).coerceAtLeast(1),
            (heightPx / opaqueScale).coerceAtLeast(1),
        )
        // Arm the periodic in-drag purge of the per-size GPU scratch — see
        // [resizePurgeDue] for why it can't run right here.
        if (now - lastResizePurgeNs >= GPU_RESIZE_PURGE_INTERVAL_NS) {
            lastResizePurgeNs = now
            resizePurgeDue = true
        }
        requestRedrawCoalesced()
    }

    /**
     * Applies a pending [pendingSwapInterval] while the EGL context is current.
     * Ends the resize burst once the window has been stable for
     * [RESIZE_BURST_HOLD_NS].
     */
    private fun updateResizeBurstSwapInterval() {
        if (attachmentHandle == 0L || attachedKind != 2 || window.isPopup) return
        val burstOver = lastResizeEventNs > 0L && System.nanoTime() - lastResizeEventNs >= RESIZE_BURST_HOLD_NS
        if (resizeBurstActive && burstOver) {
            resizeBurstActive = false
            pendingSwapInterval = 1
        }
        if (subsurfaceSynced && burstOver) {
            subsurfaceSynced = false
            // `set_desync` applies whatever the compositor still caches, so
            // the last frame of the burst is never stranded.
            NativeTaoEglBridge.nativeSetSubsurfaceSync(attachmentHandle, false)
        }
        val want = pendingSwapInterval ?: return
        pendingSwapInterval = null
        if (want == appliedSwapInterval) return
        NativeTaoEglBridge.nativeSetSwapInterval(attachmentHandle, want)
        appliedSwapInterval = want
    }

    /**
     * Keeps the content subsurface aligned with GTK's content area. With the
     * yaru-style hidden-titlebar CSD (Wayland, non-popup), GTK draws its
     * native drop shadow into the toplevel surface and allocates the content
     * child at (marginLeft, marginTop); the EGL subsurface must sit exactly
     * there. (0,0) otherwise — and after maximize/fullscreen/tile, where GTK
     * collapses the margins. Called once per rendered frame; both the origin
     * query and the native set are cheap, and the C side no-ops when the
     * offset is unchanged.
     */
    private fun applyContentOffset() {
        if (attachmentHandle == 0L || attachedKind != 2 || window.handle == 0L) return
        val packed = NativeTaoBridge.nativeLinuxContentOrigin(window.handle)
        val xLogical = (packed shr 32).toInt()
        val yLogical = packed.toInt()
        if (NativeTaoEglBridge.nativeSetContentOffset(attachmentHandle, xLogical, yLogical)) {
            // The new position is pending parent state: GTK's next commit
            // applies it, and after a maximize/restore GTK is idle — ask it
            // to paint. (Committing the parent ourselves is not safe; see the
            // native side.)
            val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
            if (gtkWindow != 0L && NativeTaoLinuxWidgetBridge.isLoaded) {
                NativeTaoLinuxWidgetBridge.nativeQueueToplevelDraw(gtkWindow)
            }
        }
    }

    /**
     * Tells the compositor which part of our surface is fully opaque, so it can
     * skip compositing the drop shadow's interior and GTK's toplevel underneath
     * us. Nothing declared this before, so the compositor blended all three
     * across the whole window on every frame — which slows its frame callbacks,
     * which throttles GDK's frame clock, which is what caps how fast the window
     * edge moves during a resize.
     *
     * Cleared when:
     *  - the window is genuinely translucent: [clearColorArgbState] alpha < 255
     *  - a [NativeView] GtkWidget is attached: Compose clears that rect to
     *    alpha 0 so the native widget can show through; claiming the surface
     *    opaque there makes the compositor drop GTK/WebKit underneath and
     *    produces damage/cursor trails
     *
     * The rounded corners are excluded for the same reason — see
     * [applyFrameDecoration], which carves them out.
     */
    private fun pushOpaqueRegion(
        logicalW: Int,
        logicalH: Int,
    ) {
        if (attachmentHandle == 0L) return
        val opaque =
            (clearColorArgbState.value ushr 24) and 0xFF == 0xFF &&
                attachedNativeViews.isEmpty()
        if (!opaque) {
            if (lastOpaqueRegion != null) {
                NativeTaoEglBridge.nativeSetOpaqueRegion(attachmentHandle, 0, 0, 0)
                lastOpaqueRegion = null
            }
            return
        }
        val squared = window.isMaximized || window.isFullscreen || window.isTiled
        val radius = if (cornerRadiusPx > 0 && !squared) cornerRadiusPx else 0
        val key = Triple(logicalW, logicalH, radius)
        if (key == lastOpaqueRegion) return
        lastOpaqueRegion = key
        NativeTaoEglBridge.nativeSetOpaqueRegion(attachmentHandle, logicalW, logicalH, radius)
    }

    /** Re-pushes the opaque region for the current size (e.g. after NativeView attach). */
    private fun refreshOpaqueRegion() {
        if (widthPx <= 0 || heightPx <= 0) return
        val opaqueScale = scale.roundToInt().coerceAtLeast(1)
        pushOpaqueRegion(
            (widthPx / opaqueScale).coerceAtLeast(1),
            (heightPx / opaqueScale).coerceAtLeast(1),
        )
    }

    /**
     * Applies (or clears) the rounded-rectangle XShape on the GL surface.
     * Called on every resize and any time the maximized/fullscreen flag may
     * have changed. Mirrors `decorated-window-core/DecoratedWindowCore.kt`'s
     * `updateWindowShape()`: rectangular when the window fills the screen,
     * rounded otherwise.
     */
    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        updateWindowInfoSize()
        requestRedrawCoalesced()
    }

    /**
     * Pushes the current `widthPx`/`heightPx`/`scale` to the GLX child window
     * + rounded-shape + Skia surface cache, but only if any of them has
     * changed since the last apply. Called from [onRedrawRequested] so a
     * burst of resize events collapses to one X11 round-trip per actual frame.
     */
    private fun applyPendingNativeResize() {
        if (attachmentHandle == 0L) return
        if (widthPx <= 0 || heightPx <= 0) return
        // GNOME / main: scene tracks the window. KWin drawable path sets scene
        // size from the paint size below (may lag the window by one present).
        if (!useDrawableSizedPaint) {
            val currentSize = IntSize(widthPx, heightPx)
            if (scene?.size != currentSize) {
                scene?.size = currentSize
                updateWindowInfoSize()
                lastSceneSizeUpdateNs = System.nanoTime()
            }
        }
        if (widthPx == lastAppliedWidthPx &&
            heightPx == lastAppliedHeightPx &&
            scale == lastAppliedScale
        ) {
            return
        }
        NativeTaoEglBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        if (!useDrawableSizedPaint) {
            // Master behaviour: paint size follows the window immediately.
            if (widthPx != lastAppliedWidthPx ||
                heightPx != lastAppliedHeightPx ||
                scale != lastAppliedScale
            ) {
                cachedSurface?.close()
                cachedSurface = null
                cachedRt?.close()
                cachedRt = null
            }
            drawableWidthPx = widthPx
            drawableHeightPx = heightPx
        } else if (scale != lastAppliedScale) {
            // KWin: keep drawable lagging on size-only changes; rebuild on scale.
            cachedSurface?.close()
            cachedSurface = null
            cachedRt?.close()
            cachedRt = null
            drawableWidthPx = widthPx
            drawableHeightPx = heightPx
        }
        lastAppliedWidthPx = widthPx
        lastAppliedHeightPx = heightPx
        lastAppliedScale = scale
    }

    /**
     * Reclaims the per-size GPU scratch a live resize mints, while the sizes
     * are still streaming — the Linux half of what
     * [TaoComposeSceneHostWindows.onResized] does inside the OS modal
     * resize/move loop. Toggling the limit to 0 runs Skia's `purgeAsNeeded`
     * inline, releasing every unlocked resource; restoring the budget lets the
     * next frame re-mint only what it needs. The only purge primitive skiko
     * exposes — see [GPU_RESOURCE_CACHE_LIMIT_BYTES].
     *
     * Called from the render pass, right after [applyPendingNativeResize] has
     * closed the [cachedSurface]/[cachedRt] of the previous size: their backing
     * render target and stencil are unlocked at exactly this point, so this is
     * where the toggle actually returns memory rather than merely walking the
     * cache. It is also the only point where this host's EGL context is current
     * on this thread — the purge issues `glDelete*`, and the same foreign-context
     * hazard the Windows host documents on its own purge applies here, only
     * worse: every Linux surface owns a *private*, unshared context (a popup
     * layer, a tray panel, a sibling window), so ids collide wholesale and a
     * purge against the wrong binding deletes a sibling's live textures.
     * Binding from [onResized] instead would be both racy (the swap thread may
     * hold our context) and pointless, since the frame that follows re-binds
     * anyway.
     *
     * Deliberately only the *in-drag* half of the Windows behaviour: there is
     * no settle purge and no `System.gc()` nudge, for the same reason macOS has
     * none (see [TaoComposeSceneHost.purgeResizeScratchIfDue]). GTK gives us no
     * drag-end signal to hang them on — the compositor-driven resize grab ends
     * with nothing more than pointer events resuming — and a timer standing in
     * for it buys a stop-the-world collection after every zoom, snap and
     * programmatic resize. The reclaim #638 is really after is at rest, not at
     * drag end.
     */
    private fun purgeResizeScratchIfDue(ctx: DirectContext) {
        if (!resizePurgeDue) return
        resizePurgeDue = false
        ctx.resourceCacheLimit = 0
        ctx.resourceCacheLimit = GPU_RESOURCE_CACHE_LIMIT_BYTES
    }

    /**
     * KWin only: after a present, the pending `wl_egl_window_resize` is in
     * effect — advance the paint size and re-arm a frame if still behind.
     */
    private fun onDrawablePresented() {
        if (!useDrawableSizedPaint) return
        if (lastAppliedWidthPx <= 0 || lastAppliedHeightPx <= 0) return
        if (drawableWidthPx == lastAppliedWidthPx && drawableHeightPx == lastAppliedHeightPx) {
            return
        }
        drawableWidthPx = lastAppliedWidthPx
        drawableHeightPx = lastAppliedHeightPx
        cachedSurface?.close()
        cachedSurface = null
        cachedRt?.close()
        cachedRt = null
        requestRedrawCoalesced()
    }

    fun onFocusChanged(focused: Boolean) {
        // NB: do NOT clear compositorDragActive on focus-in here. GNOME toggles
        // keyboard focus *during* a compositor resize/move grab, and clearing on
        // that mid-grab focus-in would unmask the following focus-out and flip
        // the chrome inactive for the rest of the drag. The grab-ended signal
        // is real pointer input resuming (see [onPointerMove] / [onPointerButton]),
        // which the compositor withholds for the whole grab.
        windowInfo.isWindowFocused = focused
    }

    private fun updateWindowInfoSize() {
        windowInfo.containerSize = IntSize(widthPx, heightPx)
        if (scale > 0f) {
            val dpW = (widthPx / scale)
            val dpH = (heightPx / scale)
            windowInfo.containerDpSize = DpSize(dpW.dp, dpH.dp)
        }
    }

    fun onRedrawRequested() {
        // Open the redraw gate first thing: any invalidation triggered while
        // we're in this method (state writes inside scene.render, animation
        // continuations resuming under sendFrame, observers firing during
        // sendApplyNotifications) can re-arm a redraw for the next tick.
        // Resetting *after* the early-return below would leave the gate
        // armed permanently if we skip this frame, and Compose would never
        // be able to schedule another redraw — i.e. the app would freeze.
        redrawPending.set(false)

        // Minimized: skip before the frame-clock tick so animations park and
        // the loop goes idle. Belt-and-suspenders here — the swap-in-flight
        // back-pressure below already throttles an occluded/minimised window —
        // but this also covers the app-synthesised minimize (Wayland reports no
        // iconified state). redrawPending is already cleared above, so restore's
        // requestRedraw re-arms cleanly.
        if (window.isMinimized) return

        // Wait for the previous frame's `eglSwapBuffers` to complete on the
        // swap thread before issuing the next render. This is what gives us
        // hardware vsync without melting CPU: the swap thread parks in
        // `eglSwapBuffers` until the compositor signals it can present
        // (16.7 ms on a 60 Hz display, 6.9 ms on a 144 Hz display, etc.),
        // and only then releases the EGL context back to us.
        //
        // If the swap is still in flight after the timeout (occluded /
        // minimised window — Wayland compositors stop sending frame
        // callbacks in that state), skip this redraw. Compose's
        // invalidation machinery will naturally re-arm via
        // [requestRedrawCoalesced] when there's actual work; binding the
        // context now would race the swap thread.
        // During active resize use a very short idle-wait timeout. The swap
        // thread is doing eglSwapBuffers (which on EGL/Wayland blocks for the
        // compositor's frame callback ~16ms). Waiting the full 100ms makes the
        // Non-blocking pacing: if the previous frame's swap is still in flight,
        // do NOT stall this thread — it is the Tao event-loop thread and also
        // dispatches all input. Record that a render is owed and return
        // immediately; the swap thread re-arms the redraw the instant it
        // finishes presenting, so the frame lands on the next tick without ever
        // freezing input. (Blocking here on the swap is what made a
        // subsurface-backed dialog feel unresponsive while its parent kept
        // rendering — the parent's swap latency was paid on the input thread.)
        val st = swapThread
        if (st != null && !st.tryBeginRenderOrMarkOwed()) {
            // The GPU is busy presenting; the CPU is not. Drain the scene's
            // coroutine queue anyway — pure CPU work, with no GL context bound
            // (the same state as the drain in the render path below).
            //
            // Without this, a continuation that lands while a swap is in flight
            // waits for the *next* render pass, i.e. a full frame. A coroutine
            // that hops to a worker and back once per frame — a `TextureView`
            // producer pulling frames off the frame clock is the canonical case
            // — then advances only every other frame and animates at half the
            // refresh rate. Measured on an 89.8 Hz panel: 11.1 ms round trip and
            // 45 producer fps before, 0.25 ms and 90 fps after, at identical CPU
            // (the extra event-loop wakeups replace work that was merely being
            // deferred).
            //
            // Budgeted per frame because draining re-arms the redraw whenever the
            // queue is left non-empty: a continuation that immediately
            // re-dispatches on this dispatcher (a main-confined `yield()` loop, a
            // Channel ping-pong) would otherwise spin this thread — which also
            // dispatches all input — for as long as the swap takes, i.e. forever
            // on an occluded Wayland window whose frame callbacks stopped coming.
            // Legitimate per-frame traffic is a couple of continuations; past the
            // budget the frame behaves as it did before, deferring to the render.
            if (skipDrainBudget > 0) {
                skipDrainBudget--
                flushingDispatcher.drain()
            }
            skippedFrames++
            if (skippedFrameStartNanos == 0L) skippedFrameStartNanos = System.nanoTime()
            return
        }
        if (skippedFrameStartNanos != 0L) {
            val stalledMs = (System.nanoTime() - skippedFrameStartNanos) / 1_000_000
            if (stalledMs >= FRAME_STALL_TRACE_MILLIS) {
                linuxHostLogger.fine("frame stalled ${stalledMs}ms on the swap ($skippedFrames skipped)")
            }
            skippedFrameStartNanos = 0L
            skippedFrames = 0
        }
        skipDrainBudget = SKIP_DRAIN_BUDGET_PER_FRAME

        val ctx = directContext ?: return
        val bundle = sceneBundle ?: return
        if (widthPx <= 0 || heightPx <= 0) return

        val now = System.nanoTime()

        // Drain queued main-thread work before the frame. The scene's frame
        // clock is ticked inside `bundle.render` (FrameRecomposer.performFrame),
        // so `withFrameNanos`-driven animations apply on the current frame
        // instead of lagging by one — same guarantee as before, now atomic with
        // the recompose → layout → draw the render call performs.
        flushingDispatcher.drain()

        NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
        // An embedded NativeView's GPU compositor ran GL on this thread since
        // the last frame — drop Skia's cached GL state before any GPU work.
        if (foreignGlInterop) ctx.resetGLAll()
        // Coalesced size/scale change is committed here, after the GL context
        // is current — applyPendingNativeResize closes the stale Skia cache.
        applyPendingNativeResize()
        purgeResizeScratchIfDue(ctx)
        updateResizeBurstSwapInterval()

        val paintSize = resolvePaintSize()
        if (bundle.scene.size != paintSize) {
            bundle.scene.size = paintSize
            lastSceneSizeUpdateNs = now
        }

        val surface = ensurePaintSurface(ctx, paintSize.width, paintSize.height) ?: return

        // Clear to the resolved title-bar background (pushed by `TitleBar` via
        // [LocalRequestedClearColor]) so any Compose region without an explicit
        // background matches the chrome color — aligned with the macOS / Windows
        // Tao hosts and the AWT backends, instead of showing the desktop through
        // a transparent clear. The rounded corners are carved back to
        // transparent by [applyFrameDecoration] below.
        surface.canvas.clear(clearColorArgbState.value)
        bundle.render(surface.canvas, now)
        // bundle.render runs composition effects: an embed's WebKit can do
        // GL right here (webkit_web_view_load_uri in update{}, realize on
        // mount) and leave ITS EGL context current on this thread. All the
        // GL Skia issues below — the whole frame's flush, including the new
        // tab's glyph-atlas uploads — would then land in the foreign
        // context: those atlas entries stay blank forever and text renders
        // with randomly missing glyphs (Mutter+NVIDIA, #NativeView). Re-bind
        // before any GPU work.
        if (foreignGlInterop) {
            NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
            ctx.resetGLAll()
        }
        applyFrameDecoration(surface.canvas, paintSize.width, paintSize.height)

        surface.flushAndSubmit(syncCpu = false)
        NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
        swapThread?.requestSwap()
        if (subsurfaceSynced) {
            // In sync mode this frame only shows with GTK's next commit; make
            // sure there is one, also once the pointer has stopped moving.
            val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
            if (gtkWindow != 0L && NativeTaoLinuxWidgetBridge.isLoaded) {
                NativeTaoLinuxWidgetBridge.nativeQueueToplevelDraw(gtkWindow)
            }
        }

        // Re-align the content subsurface with GTK's content area AFTER the
        // swap was requested, so the repositioning (which the native side
        // applies with an explicit parent commit) lands in the compositor in
        // the same frame as the newly-sized buffer — offset changes only ever
        // accompany a size change (maximize/restore/tile collapse the CSD
        // shadow margins).
        applyContentOffset()
        drainPopupRenderers()
    }

    /**
     * KWin: paint at lagging drawable (avoids BOTTOM_LEFT flash).
     * GNOME / others: paint at window size (master — no layout lag).
     */
    private fun resolvePaintSize(): IntSize {
        val paintW =
            if (useDrawableSizedPaint && drawableWidthPx > 0) drawableWidthPx else widthPx
        val paintH =
            if (useDrawableSizedPaint && drawableHeightPx > 0) drawableHeightPx else heightPx
        return IntSize(paintW, paintH)
    }

    /**
     * Rebuilds the Skia RT/surface when the paint size changed. Returns null if
     * surface creation fails (EGL context already released by this call).
     */
    private fun ensurePaintSurface(
        ctx: DirectContext,
        paintW: Int,
        paintH: Int,
    ): Surface? {
        val existing = cachedSurface
        if (existing != null && existing.width == paintW && existing.height == paintH) {
            return existing
        }
        cachedSurface?.close()
        cachedSurface = null
        cachedRt?.close()
        cachedRt = null
        val rt =
            BackendRenderTarget.makeGL(
                width = paintW,
                height = paintH,
                sampleCnt = 0,
                stencilBits = 8,
                fbId = 0,
                fbFormat = FramebufferFormat.GR_GL_RGBA8,
            )
        val surface = makeTaoGlSurface(ctx, rt, fullyTransparent)
        if (surface == null) {
            rt.close()
            NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
            return null
        }
        cachedRt = rt
        cachedSurface = surface
        return surface
    }

    /**
     * Drain popup-layer renderers after the host context was released.
     * Each layer binds its own private EGL context on this thread (the
     * swap thread holds the *host* context on its own thread — EGL allows
     * one current context per thread), paints, presents with swap
     * interval 0 (non-blocking) and releases. Snapshot: rendering one
     * layer can recompose and close a sibling.
     */
    private fun drainPopupRenderers() {
        if (popupRenderers.isEmpty()) return
        val snapshot = popupRenderers.values.toList()
        for (render in snapshot) render()
    }

    /**
     * Post-render frame decoration: carves the rounded corners out of the
     * fully-rendered surface. Clears everything outside the rounded frame to
     * transparent so the compositor blends the content behind those corner
     * pixels — dropped for maximized, fullscreen and tiled windows, which sit
     * flush against a screen edge and square off.
     */
    private fun applyFrameDecoration(
        canvas: Canvas,
        surfaceW: Int = widthPx,
        surfaceH: Int = heightPx,
    ) {
        val isMaximized = window.isMaximized
        val isFullscreen = window.isFullscreen
        val isTiled = window.isTiled
        // Drop the rounding when tiled/snapped (Aero Snap): a half/quarter
        // screen window sits flush against the screen edge, so rounded corners
        // there look wrong — native CSD windows square off when tiled too.
        val roundCorners = cornerRadiusPx > 0 && !isMaximized && !isFullscreen && !isTiled
        if (roundCorners) {
            // Coordinates are physical pixels and the canvas has no scale
            // transform, so scale the logical radius up to physical to keep the
            // corner curvature constant across DPI.
            val radiusPhysical = (cornerRadiusPx * scale).roundToInt().coerceAtLeast(1)
            carveOutsideFrame(
                canvas,
                left = 0,
                top = 0,
                right = surfaceW,
                bottom = surfaceH,
                surfaceW = surfaceW,
                surfaceH = surfaceH,
                radius = radiusPhysical,
            )
        }
    }

    /**
     * Alpha-blended clip of everything outside the visible rounded frame.
     * Paints the cut-outs with `BlendMode.CLEAR` (destination alpha → 0) so
     * the compositor blends the content behind those pixels — works
     * uniformly on X11 and Wayland (no XShape needed).
     *
     * The frame equals the surface, so this clears exactly the four corner
     * pieces.
     *
     * The path is `surface_rect XOR rounded_frame_rect` via `EVEN_ODD` fill;
     * AA at the rounded edge stays in the destination, only the strictly
     * outside pixels are zeroed. All coordinates are physical pixels.
     */
    @Suppress("LongParameterList")
    private fun carveOutsideFrame(
        canvas: Canvas,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        surfaceW: Int,
        surfaceH: Int,
        radius: Int,
    ) {
        if (right <= left || bottom <= top) return
        PathBuilder(PathFillMode.EVEN_ODD)
            .addRect(Rect.makeXYWH(0f, 0f, surfaceW.toFloat(), surfaceH.toFloat()))
            .addRRect(
                RRect.makeLTRB(
                    left.toFloat(),
                    top.toFloat(),
                    right.toFloat(),
                    bottom.toFloat(),
                    radius.toFloat(),
                ),
            ).detach()
            .use { frame ->
                Paint().use { paint ->
                    paint.blendMode = BlendMode.CLEAR
                    paint.isAntiAlias = true
                    canvas.drawPath(frame, paint)
                }
            }
    }

    fun onPointerMove(
        aFixed: Int,
        bFixed: Int,
    ) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        if (forwardedNativeButtons.isNotEmpty()) healStaleNativePresses()
        // Real pointer motion resuming means the compositor released any
        // resize/move grab — that's our grab-ended signal (the compositor
        // withholds motion for the whole grab), so drop the focus mask here
        // rather than on focus-in, which can toggle mid-grab. See
        // [onFocusChanged].
        if (compositorDragActive) {
            compositorDragActive = false
        }
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers

        // JBR-style peer hook: hit-test the resize edge band BEFORE forwarding
        // the move to Compose. When the pointer is inside the band we set the
        // resize cursor and swallow the event so Compose's own cursor /
        // `PointerIcon` plumbing can't overwrite it on the next motion.
        //
        // Skip entirely while a button is held: during a drag the platform
        // grab delivers positions outside the window, which the band test
        // would otherwise classify as "on the edge" and swallow — freezing
        // any Compose gesture (e.g. a cross-window tab drag) the moment the
        // pointer crosses the window border.
        val direction = if (pressedButtons.isEmpty()) currentResizeDirection(xPx, yPx) else null
        if (resizeDecoration.onMove(direction)) return

        if (!pointerDeadband.shouldDispatchMove(xPx, yPx, scale)) return
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    /**
     * Called when a native compositor-driven window move begins (title-bar
     * drag → [dev.nucleusframework.window.tao.TaoWindow.dragWindow]). The
     * compositor takes a pointer grab and swallows the button release, so
     * Compose never sees it: its gesture detectors stay stuck "pressed" and the
     * window ignores hover/clicks until a fresh click completes the sequence.
     * Reset the scene's pointer state to recover — same mechanism the touch
     * CANCEL path uses. Deferred onto the main dispatcher because this fires
     * reentrantly from inside the very Move dispatch that started the drag.
     */
    fun onNativeWindowDragStarted() {
        // The move grab also steals the focus notify — keep the active
        // chrome for the whole drag (see [compositorDragActive]).
        compositorDragActive = true
        // The compositor's interactive-move grab swallows the button release, so
        // neither the Compose scene nor the title-bar drag gesture ever see it:
        // the pointer stays "pressed" and the window ignores hover/clicks until a
        // fresh click. Synthesize the missing LEFT release to complete the
        // press/release pair (a Cancel isn't enough — the title-bar gesture only
        // resets its flags on a real Release). Deferred onto the main dispatcher
        // because this fires reentrantly from inside the Move dispatch that
        // started the drag.
        flushingDispatcher.enqueue(
            Runnable {
                onPointerButton(dev.nucleusframework.window.tao.TaoMouseButton.LEFT, pressed = false)
            },
        )
    }

    fun onPointerExited() {
        // ⚠️ Don't dispatch PointerEventType.Exit here on Linux.
        //
        // tao's GTK backend turns every `leave-notify` GDK event into a
        // CursorLeft event — including the "virtual" leaves GTK fires every
        // time the pointer crosses an internal sub-widget boundary, even
        // though the pointer is still over the same logical window. Forwarding
        // those as Exit invalidates Compose's hover state, so Compose
        // re-Enters on the next Move and we get oscillating PointerIcon
        // updates whose visible effect is "the I-beam only flashes for one
        // pixel as you cross widget seams".
        //
        // Compose's hit-test on Move is enough to track hover state cleanly;
        // when the pointer truly leaves the OS window, no further Move events
        // are sent and the hover modifier naturally stays inactive.
    }

    fun onPointerButton(
        buttonCode: Int,
        pressed: Boolean,
    ) {
        // JBR-style peer hook: a LMB press inside the resize band starts the
        // native resize drag and is NOT forwarded to Compose. Matches
        // `WLDecoratedPeer.postMouseEvent` calling
        // `FrameDecoration.processMouseEvent` first.
        //
        // Checked BEFORE the pressedButtons bookkeeping: the compositor's
        // resize grab swallows the matching button release, so recording this
        // press would leave the button stuck in the set — and the hover
        // hit-test only runs while no button is held, so the resize cursor
        // would never show again after the first edge drag.
        if (pressed && buttonCode == dev.nucleusframework.window.tao.TaoMouseButton.LEFT) {
            val direction = currentResizeDirection(lastPointerX, lastPointerY)
            if (resizeDecoration.onLeftPress(direction)) {
                compositorDragActive = true
                return
            }
        }
        // Any other real press means no compositor grab is in flight.
        if (pressed) {
            compositorDragActive = false
            nativePointerDispatchedThisEvent = false
            // A button an embed swallowed the release of must not still be
            // "down" when this press is hit-tested — see [forwardedNativeButtons].
            for (stale in forwardedNativeButtons.toList()) {
                if (stale != buttonCode && stale in pressedButtons) onPointerButton(stale, pressed = false)
            }
        } else {
            forwardedNativeButtons.remove(buttonCode)
        }
        if (pressed) pressedButtons.add(buttonCode) else pressedButtons.remove(buttonCode)

        // A press reaching the parent scene is outside every popup layer — the
        // Linux stand-in for macOS's NSEvent monitor / Windows' WH_MOUSE_LL hook.
        if (pressed) dismissPopupsBeforePress(mapButton(buttonCode))

        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = mapButton(buttonCode),
        )
        if (pressed && !nativePointerDispatchedThisEvent && attachedNativeViews.isNotEmpty()) {
            // Compose kept the press, so the keyboard is Compose's: an embed
            // the user clicked into earlier would otherwise keep GTK focus
            // and every keystroke, while Compose shows a focused text field.
            // The macOS host does the same with `makeFirstResponder`.
            val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
            if (gtkWindow != 0L && NativeTaoLinuxWidgetBridge.isLoaded) {
                NativeTaoLinuxWidgetBridge.nativeClaimKeyboardForCompose(gtkWindow)
            }
        }
    }

    /**
     * Releases every [forwardedNativeButtons] entry GDK reports as up. Only
     * called while there is one, so a window without embeds never pays the
     * device query.
     */
    private fun healStaleNativePresses() {
        if (!NativeTaoLinuxWidgetBridge.isLoaded) return
        val gtkWindow = NativeTaoBridge.nativeLinuxGtkWindow(window.handle)
        if (gtkWindow == 0L) return
        val mask = NativeTaoLinuxWidgetBridge.nativeQueryPointerButtons(gtkWindow)
        if (mask < 0) return
        for (button in forwardedNativeButtons.toList()) {
            val bit =
                when (button) {
                    dev.nucleusframework.window.tao.TaoMouseButton.LEFT -> GDK_BUTTON1_MASK
                    dev.nucleusframework.window.tao.TaoMouseButton.MIDDLE -> GDK_BUTTON2_MASK
                    dev.nucleusframework.window.tao.TaoMouseButton.RIGHT -> GDK_BUTTON3_MASK
                    else -> 0
                }
            if (mask and bit == 0) {
                forwardedNativeButtons.remove(button)
                if (button in pressedButtons) onPointerButton(button, pressed = false)
            }
        }
    }

    /**
     * Runs the popup dismissal a press outside every layer implies, and lets
     * the scene apply it before that press is dispatched.
     *
     * The listeners close whatever popup was open by writing Compose state, and
     * the press is about to be dispatched in the same turn — so a node that is
     * *disabled while the popup is open* would still be disabled when the press
     * arrives, and the press would do nothing. Compose's own
     * `contextMenuOpenDetector` is exactly that node, which is why a second
     * right click used to close the context menu instead of moving it to the
     * new spot, the way every OS menu does. One extra composition per outside
     * press, and only while a popup is open.
     */
    private fun dismissPopupsBeforePress(button: PointerButton?) {
        if (outsidePressListeners.isEmpty()) return
        for (cb in outsidePressListeners.values.toList()) cb(button)
        Snapshot.sendApplyNotifications()
        sceneBundle?.composeAndLayoutNow()
    }

    /**
     * Hit-test the resize band at the given **physical**-pixel pointer
     * position. Returns `null` (no resize) when the window is non-resizable,
     * maximized, or fullscreen — same gating as JBR's
     * `peer.isInteractivelyResizable()`.
     *
     * [onPointerMove] ships physical pixels (`aFixed / 1024`), but
     * [ResizeFrameDecoration.hitTest] works in logical pixels (its `edge` band
     * is 5 logical px). So we divide BOTH the pointer and the frame size by
     * [scale] — comparing physical coords against a logical frame would treat
     * the entire right/bottom half of a HiDPI window as the resize edge and
     * swallow every event there (input dead outside the top-left quadrant).
     */
    private fun currentResizeDirection(
        xPx: Float,
        yPx: Float,
        forTouch: Boolean = false,
    ): ResizeFrameDecoration.Direction? {
        if (!window.isResizable) return null
        if (window.isFullscreen) return null
        if (window.isMaximized) return null
        val s = if (scale > 0f) scale else 1f
        var xl = xPx / s
        var yl = yPx / s
        val wl = (widthPx / s).toInt()
        val hl = (heightPx / s).toInt()
        // With the native CSD frame, GTK's shadow ring around the content is
        // part of the window: a pointer inside the ring resolves to the
        // nearest content edge so this band is the single resize authority
        // over the whole frame — ring included — with no dead zone between
        // the GTK margins and the Compose edge band.
        val outside = xl < 0f || yl < 0f || xl >= wl || yl >= hl
        if (isCsdActive && outside) {
            val inRing =
                xl >= -CSD_RING_MAX_LOGICAL &&
                    yl >= -CSD_RING_MAX_LOGICAL &&
                    xl <= wl + CSD_RING_MAX_LOGICAL &&
                    yl <= hl + CSD_RING_MAX_LOGICAL
            if (!inRing) return null
            xl = xl.coerceIn(0f, (wl - 1).toFloat())
            yl = yl.coerceIn(0f, (hl - 1).toFloat())
        }
        return resizeDecoration.hitTest(xl, yl, wl, hl, forTouch)
    }

    fun onPointerScroll(event: TaoPointerScrollEvent) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers

        // Ctrl+wheel → synthetic magnify gesture, never a scroll. On Windows the native
        // layer routes WM_MOUSEWHEEL+Ctrl to the magnify hook; GTK delivers it here as a
        // plain scroll, so we do the same routing in Kotlin. Keeps Ctrl+wheel = zoom (not
        // zoom-and-scroll) and matches the Windows backend — the AWT backend has no
        // pinch-zoom to mirror. Real (non-Ctrl) scroll falls through to the list.
        if ((window.modifierState and TaoModifierMask.CONTROL) != 0) {
            val delta = if (abs(event.dyAwt) >= abs(event.dxAwt)) event.dyAwt else event.dxAwt
            onCtrlWheelZoom(delta)
            return
        }

        scene?.dispatchAwtShapedScroll(
            x = pointerDeadband.x,
            y = pointerDeadband.y,
            event = event,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    /**
     * Feeds one Ctrl+wheel tick into the shared magnify-gesture machinery (Touch pinch),
     * so the app's pinch-zoom handler receives it exactly like a trackpad pinch. The
     * gesture is opened on the first tick, moved on each tick, and released by an idle
     * timer once ticks stop ([scheduleWheelZoomEnd]).
     */
    private fun onCtrlWheelZoom(deltaAwt: Float) {
        if (scene == null) return
        // AWT sign: wheel-up (zoom in) is a negative rotation, so negate to get a
        // positive magnify value that grows the gesture scale.
        val step = TaoWheelPinchZoom.stepFromWheelDelta(-deltaAwt)
        if (!gestureActive) {
            startGesture(lastPointerX, lastPointerY)
            sendGesturePointers(PointerEventType.Press)
        } else {
            gestureCenterX = lastPointerX
            gestureCenterY = lastPointerY
        }
        gestureScale *= step
        sendGesturePointers(PointerEventType.Move)
        scheduleWheelZoomEnd()
    }

    /** Re-arms the idle timer that releases the synthetic wheel-driven magnify. */
    private fun scheduleWheelZoomEnd() {
        wheelZoomEndJob?.cancel()
        wheelZoomEndJob =
            gestureScope.launch {
                delay(WHEEL_ZOOM_IDLE_END_MS)
                wheelZoomEndJob = null
                endGesture(cancelled = false)
            }
    }

    fun onKeyEvent(
        type: Int,
        vkCode: Int,
        keyLocation: Int,
        modifiers: Int,
        codePoint: Int,
    ): Boolean {
        val sc = scene ?: return false
        currentKeyboardModifiers = taoKeyboardModifiers(modifiers)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        val isCtrl = (modifiers and TaoModifierMask.CONTROL) != 0
        val isMeta = (modifiers and TaoModifierMask.META) != 0
        val isAlt = (modifiers and TaoModifierMask.ALT) != 0
        val isShift = (modifiers and TaoModifierMask.SHIFT) != 0
        val composeEvent =
            when (type) {
                TaoEventCode.KEY_DOWN, TaoEventCode.KEY_UP ->
                    taoKeyEvent(
                        keyDown = type == TaoEventCode.KEY_DOWN,
                        vkCode = vkCode,
                        keyLocation = keyLocation,
                        isShift = isShift,
                        isCtrl = isCtrl,
                        isAlt = isAlt,
                        isMeta = isMeta,
                        codePoint = codePoint,
                    )
                TaoEventCode.KEY_TYPED ->
                    taoTypedKeyEvent(codePoint, keyLocation, isShift, isCtrl, isAlt, isMeta)
                else -> return false
            }
        if (previewKeyHandler?.invoke(composeEvent) == true) return true
        // Popup layers get a chance to consume the event before the main
        // scene — popup windows never own keyboard focus on Linux, so the
        // parent forwards. Mirrors the macOS popupKeyHandlers chain.
        if (popupKeyHandlers.isNotEmpty()) {
            for (handler in popupKeyHandlers.values.toList()) {
                if (handler(composeEvent)) return true
            }
        }
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    /** Native popup layers handed out by [nativePopupLayerFactory] and not yet closed — swept by [detach]. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private val liveNativePopupLayers = linkedSetOf<androidx.compose.ui.scene.ComposeSceneLayer>()

    /**
     * Builds this window's native popup layers ([TaoPopupSceneLayerLinux]).
     * The factory behind [nativePopupLayers], and the one `NativePopupLayers { }`
     * hands to a subtree that wants native surfaces while the window's own
     * popups stay in-scene. [popupHost] is resolved per layer, as it always
     * was: a Wayland hide/show rebuilds the EGL pair and the host reads the
     * live one.
     */

    fun nativePopupLayerFactory(): TaoPopupLayerFactory =
        { density, layoutDirection, focusable, consumeOutside ->
            TaoPopupSceneLayerLinux(
                host = popupHost(),
                initialDensity = density,
                initialLayoutDirection = layoutDirection,
                initialFocusable = focusable,
                initialConsumePointerInputOutside = consumeOutside,
            ).also { liveNativePopupLayers += it }
        }

    /**
     * Plumbing handed to [TaoPopupSceneLayerLinux] instances by
     * [nativePopupLayerFactory]. Mirrors the Windows
     * [TaoComposeSceneHostWindows.popupHost] contract, adapted to the Linux
     * backend: layers are Tao popup windows keyed on [parentWindow], and each
     * owns a private EGL context so there is no shared DirectContext.
     */
    private fun popupHost(): TaoPopupHostLinux {
        val outer = this
        return object : TaoPopupHostLinux {
            override val parentWindow: TaoWindow get() = outer.window
            override val scale: Float get() = outer.scale
            override val exceptionHandler: WindowExceptionHandler?
                get() = outer.exceptionHandler
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val parentWindowInfo: androidx.compose.ui.platform.WindowInfo get() = outer.windowInfo
            override val workAreaSize: IntSize get() =
                NativeTaoBridge
                    .nativeLinuxPrimaryMonitorWorkArea(outer.window.handle)
                    ?.takeIf { it.size >= 4 && it[2] > 0 && it[3] > 0 }
                    ?.let { IntSize(it[2].toInt(), it[3].toInt()) }
                    ?: parentWindowSize

            // Wayland popups are subsurfaces positioned relative to the
            // parent surface — no global origin exists (or is needed).
            override val parentScreenOriginPx: IntOffset get() =
                if (outer.attachedKind != 1) {
                    IntOffset.Zero
                } else {
                    NativeTaoBridge
                        .nativeLinuxGetWindowRect(outer.window.handle)
                        ?.takeIf { it.size >= 2 }
                        ?.let { IntOffset(it[0].toInt(), it[1].toInt()) }
                        ?: IntOffset.Zero
                }

            // #569: clamp popups into the real display's work area instead of
            // the work-area-sized virtual screen Compose positions against.
            // Null on Wayland for the same reason parentScreenOriginPx is zero
            // there — a subsurface has no global position to clamp.
            override val popupScreenGeometry: PopupScreenGeometry? get() {
                if (!outer.isX11) return null
                val origin = parentScreenOriginPx
                // `reported`, not `all` — see the macOS resolver: a synthesized
                // monitor is a guess, and a clamp is only safe on a real one.
                val areas = TaoMonitors.reported(outer.window).map { it.workAreaPx }
                if (areas.isEmpty()) return null
                return PopupScreenGeometry(parentContentOriginPx = origin, workAreasPx = areas)
            }

            /**
             * Nested-scene origin only. The hidden-titlebar CSD content origin
             * used to live here, but [TaoWindow.setOuterPosition] now applies it
             * for every Linux popup overlay (`popupOf`) — including in-scene
             * layers and app-level drag ghosts — so callers can stay in parent
             * **content** coordinates. Adding it again would double-offset.
             */
            override val coordinateOffset: IntOffset get() = IntOffset.Zero

            override val sceneCoroutineContext: CoroutineContext
                get() = outer.coroutineContext + outer.flushingDispatcher

            override val popupScrims: PopupScrimRegistry get() = outer.popupScrims

            override fun requestRedraw() = outer.requestRedrawCoalesced()

            override fun registerRenderer(
                token: Any,
                render: () -> Unit,
            ) {
                outer.popupRenderers[token] = render
            }

            override fun unregisterRenderer(token: Any) {
                outer.popupRenderers.remove(token)
            }

            @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
            override fun onLayerClosed(layer: androidx.compose.ui.scene.ComposeSceneLayer) {
                outer.liveNativePopupLayers.remove(layer)
            }

            override fun registerKeyHandler(
                token: Any,
                handler: (KeyEvent) -> Boolean,
            ) {
                outer.popupKeyHandlers[token] = handler
            }

            override fun unregisterKeyHandler(token: Any) {
                outer.popupKeyHandlers.remove(token)
            }

            override fun registerOwnerMoveListener(
                token: Any,
                onMoved: () -> Unit,
            ) {
                outer.ownerMoveListeners[token] = onMoved
            }

            override fun unregisterOwnerMoveListener(token: Any) {
                outer.ownerMoveListeners.remove(token)
            }

            override fun registerOutsidePressListener(
                token: Any,
                onPress: (androidx.compose.ui.input.pointer.PointerButton?) -> Unit,
            ) {
                outer.outsidePressListeners[token] = onPress
            }

            override fun unregisterOutsidePressListener(token: Any) {
                outer.outsidePressListeners.remove(token)
            }

            override fun forwardMarginPointer(
                eventType: PointerEventType,
                positionPx: Offset,
                button: PointerButton?,
            ) {
                if (eventType == PointerEventType.Press) outer.dismissPopupsBeforePress(button)
                outer.currentKeyboardModifiers = taoKeyboardModifiers(outer.window.modifierState)
                outer.windowInfo.keyboardModifiers = outer.currentKeyboardModifiers
                outer.scene?.sendPointerEvent(
                    eventType = eventType,
                    position = positionPx,
                    type = PointerType.Mouse,
                    keyboardModifiers = outer.currentKeyboardModifiers,
                    button = button,
                )
            }

            override fun acquireCompositorPopup(token: Any): Boolean {
                val owner = outer.compositorPopupOwner
                if (owner != null && owner !== token) return false
                outer.compositorPopupOwner = token
                return true
            }

            override fun releaseCompositorPopup(token: Any) {
                if (outer.compositorPopupOwner === token) outer.compositorPopupOwner = null
            }
        }
    }

    /**
     * One host instance per scene. The composition local built from it keys
     * `NativeView`'s attach effect: a fresh object on every recomposition of
     * the window root would detach and re-attach every embed each time.
     */
    private var nativeViewHostInstance: dev.nucleusframework.window.tao.TaoNativeViewHost? = null

    fun nativeViewHost(): dev.nucleusframework.window.tao.TaoNativeViewHost? =
        nativeViewHostInstance ?: createNativeViewHost()?.also { nativeViewHostInstance = it }

    /**
     * Plumbing for the `GtkWidget` variant of `NucleusPlatformView`.
     * Resolves Tao's `GtkApplicationWindow*` once (it doesn't change
     * for the lifetime of the window), routes attach/detach/setFrame
     * calls to the C-side widget bridge, and converts Compose's
     * physical-pixel coords to GTK's logical-pixel coords.
     *
     * Returns null until [attach] has run *and* the widget bridge
     * library is available (missing on non-Linux builds and on Linux
     * builds that didn't ship the .so).
     */
    private fun createNativeViewHost(): dev.nucleusframework.window.tao.TaoNativeViewHost? {
        if (window.handle == 0L) return null
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge.isLoaded) return null
        val gtkWindow =
            dev.nucleusframework.window.tao.ffi.NativeTaoBridge
                .nativeLinuxGtkWindow(window.handle)
        if (gtkWindow == 0L) return null
        val outer = this
        return object : dev.nucleusframework.window.tao.TaoNativeViewHost {
            override fun attach(
                childHandle: Long,
                regionToken: Any,
            ) {
                // The sink must be the first focusable child of the overlay,
                // ahead of the embed — see [TaoLinuxOverlayControllerImpl.ensureFocusSink].
                outer.overlayController.ensureFocusSink()
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
                    .nativeAttach(gtkWindow, childHandle)
                outer.foreignGlInterop = true
                outer.detachedNativeViews.remove(childHandle)
                if (childHandle != 0L && outer.attachedNativeViews.add(childHandle)) {
                    // Force a re-push: lastOpaqueRegion may still hold the full
                    // opaque key from before the embed existed.
                    outer.lastOpaqueRegion = Triple(-1, -1, -1)
                    outer.refreshOpaqueRegion()
                }
            }

            override fun detach(
                childHandle: Long,
                regionToken: Any,
            ) {
                outer.nativeViewRects.remove(childHandle)
                outer.overlayController.unregisterRegion(regionToken)
                outer.detachedNativeViews += childHandle
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
                    .nativeDetach(childHandle)
                if (childHandle != 0L && outer.attachedNativeViews.remove(childHandle)) {
                    outer.lastOpaqueRegion = null
                    outer.refreshOpaqueRegion()
                }
            }

            override fun setFrame(
                handle: Long,
                xPx: Int,
                yPx: Int,
                widthPx: Int,
                heightPx: Int,
                regionToken: Any,
            ) {
                // A layout pass can still report the slot of an embed whose
                // detach already ran (the node is placed once more in the
                // frame that removes it); the widget may be gone by then. Only
                // *detached* handles are refused: the first setFrame routinely
                // lands before the attach effect, and it is what mounts the
                // widget (the C side defers the mount to the first real rect).
                if (handle in outer.detachedNativeViews) return
                // Compose feeds physical pixels; GTK 3 lays out in
                // logical pixels (the compositor applies the device
                // scale on its own).
                val s = if (outer.scale > 0f) outer.scale else 1f
                val xLogical = (xPx / s).toInt()
                val yLogical = (yPx / s).toInt()
                val wLogical = (widthPx / s).toInt().coerceAtLeast(1)
                val hLogical = (heightPx / s).toInt().coerceAtLeast(1)
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
                    .nativeSetFrame(gtkWindow, handle, xLogical, yLogical, wLogical, hLogical)
                // Capture the whole NativeView rect in a GtkEventBox so
                // Compose sees hits first (siblings / content slot);
                // unconsumed events are synthesised back onto the widget.
                outer.nativeViewRects[handle] = intArrayOf(xPx, yPx, widthPx, heightPx)
                outer.overlayController.registerRegion(regionToken, xPx, yPx, widthPx, heightPx)
            }

            override fun setCornerRadius(
                handle: Long,
                radiusPx: Float,
            ) {
                // Per-widget rounded clipping isn't trivial in GTK 3
                // (would need a GtkCssProvider with a unique class
                // name and a `border-radius` declaration). Leaving as
                // a no-op for now; callers that need rounded corners
                // on Linux fall back to drawing a Compose
                // RoundedCornerShape on top of the widget area.
            }

            override fun dispatchPointerToNative(
                handle: Long,
                type: Int,
                xPx: Float,
                yPx: Float,
                button: Int,
                pressed: Boolean,
            ) {
                val s = if (outer.scale > 0f) outer.scale else 1f
                val rect = outer.nativeViewRects[handle]
                val xLogical = ((xPx - (rect?.get(0)?.toFloat() ?: 0f)) / s).toInt()
                val yLogical = ((yPx - (rect?.get(1)?.toFloat() ?: 0f)) / s).toInt()
                if (type == NATIVE_POINTER_PRESS) {
                    // NativeView numbers buttons 1 = primary, 2 = secondary.
                    outer.forwardedNativeButtons +=
                        if (button == NATIVE_SECONDARY_BUTTON) {
                            dev.nucleusframework.window.tao.TaoMouseButton.RIGHT
                        } else {
                            dev.nucleusframework.window.tao.TaoMouseButton.LEFT
                        }
                    // The embed takes the keyboard with this press (the bridge
                    // grabs GTK focus for it before forwarding): a Compose text
                    // field must not keep showing a caret beside the embed's.
                    // Deferred — this runs inside the Press dispatch.
                    outer.flushingDispatcher.enqueue(
                        Runnable { outer.capturedFocusManager?.clearFocus(force = true) },
                    )
                }
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
                    .nativeDispatchPointer(handle, type, xLogical, yLogical, button, pressed)
            }

            override fun noteNativePointerDispatch() {
                outer.nativePointerDispatchedThisEvent = true
            }

            override fun dispatchScrollToNative(
                handle: Long,
                xPx: Float,
                yPx: Float,
                dx: Float,
                dy: Float,
            ) {
                val s = if (outer.scale > 0f) outer.scale else 1f
                val rect = outer.nativeViewRects[handle]
                val xLogical = ((xPx - (rect?.get(0)?.toFloat() ?: 0f)) / s).toInt()
                val yLogical = ((yPx - (rect?.get(1)?.toFloat() ?: 0f)) / s).toInt()
                dev.nucleusframework.window.tao.ffi.NativeTaoLinuxWidgetBridge
                    .nativeDispatchScroll(handle, xLogical, yLogical, dx, dy)
            }
        }
    }

    /**
     * GtkEventBox capture for NativeView rects. The EGL subsurface is
     * input-transparent; each NativeView registers its bounds so Compose
     * sees hits first. One controller per window.
     */
    private val overlayController: TaoLinuxOverlayControllerImpl =
        TaoLinuxOverlayControllerImpl(
            // Resolve lazily — the GtkApplicationWindow handle is
            // stable after attach() but Tao may not have wired it
            // yet at host construction time.
            gtkWindowProvider = {
                if (window.handle == 0L) {
                    0L
                } else {
                    dev.nucleusframework.window.tao.ffi.NativeTaoBridge
                        .nativeLinuxGtkWindow(window.handle)
                }
            },
            scaleProvider = { scale },
            hostSizeProvider = { IntSize(widthPx, heightPx) },
            moveDispatcher = { xPx, yPx ->
                // Reuse the same fixed-precision wire format as Tao's
                // native CursorMoved dispatcher (×1024). `onPointerMove`
                // divides back by 1024 to recover the physical-px
                // float position.
                onPointerMove(xPx * 1024, yPx * 1024)
            },
            buttonDispatcher = { button, pressed ->
                onPointerButton(button, pressed)
            },
            scrollDispatcher = { xPx, yPx, dx, dy ->
                // Route the position through the regular move dispatch so the
                // sub-pixel deadband tracks it — the scroll then lands at the
                // deadband position like every other scene event (#615).
                onPointerMove(xPx * 1024, yPx * 1024)
                onPointerScroll(
                    TaoPointerScrollEvent(dxAwt = dx, dyAwt = dy, scrollAmount = 1),
                )
            },
            focusReleaseDispatcher = {
                // 1) Deselect the currently-focused widget (e.g. the
                //    URL field's BasicTextField) — mirrors macOS's
                //    `resignFirstResponder` callback. Without this,
                //    a focused TextField keeps showing the caret
                //    after the user clicks elsewhere.
                //    `clearFocus(force = true)` (via the standard
                //    `FocusManager` captured in [setContent]) is
                //    needed to break a TextField's "Captured" focus
                //    state during active editing — the scene-level
                //    `releaseFocus()` only clears Active/ActiveParent.
                capturedFocusManager?.clearFocus(force = true)
                    ?: scene?.focusManager?.releaseFocus()

                // 2) Synthesize an outside-click so any open Compose
                //    Popup (e.g. the BasicTextField's Cut/Copy/Paste
                //    context menu) hits its `dismissOnClickOutside`
                //    handler and closes. focusManager.releaseFocus()
                //    alone doesn't dismiss popups — they're tied to
                //    pointer hit-testing, not the focus chain. We
                //    target window-corner (1, 1): inside window
                //    bounds (so Compose accepts the event) but
                //    outside any Compose interactive widget in the
                //    sample, so no other onClick fires.
                val sc = scene ?: return@TaoLinuxOverlayControllerImpl
                val dismissPos =
                    androidx.compose.ui.geometry
                        .Offset(1f, 1f)
                sc.sendPointerEvent(
                    eventType = androidx.compose.ui.input.pointer.PointerEventType.Move,
                    position = dismissPos,
                    type = androidx.compose.ui.input.pointer.PointerType.Mouse,
                )
                sc.sendPointerEvent(
                    eventType = androidx.compose.ui.input.pointer.PointerEventType.Press,
                    position = dismissPos,
                    type = androidx.compose.ui.input.pointer.PointerType.Mouse,
                    button = androidx.compose.ui.input.pointer.PointerButton.Primary,
                )
                sc.sendPointerEvent(
                    eventType = androidx.compose.ui.input.pointer.PointerEventType.Release,
                    position = dismissPos,
                    type = androidx.compose.ui.input.pointer.PointerType.Mouse,
                    button = androidx.compose.ui.input.pointer.PointerButton.Primary,
                )
            },
        )

    fun overlayController(): TaoLinuxOverlayController? {
        if (window.handle == 0L) return null
        return overlayController
    }

    fun detach() {
        liveHosts -= this
        // Layers whose dismiss animation was still running: Compose closes a
        // native popup layer only when its own disappearance finishes, so an
        // owner destroyed mid-animation left the layer's popup window mapped
        // for good — an invisible rectangle eating every click under it.
        for (layer in liveNativePopupLayers.toList()) layer.close()
        liveNativePopupLayers.clear()
        window.contentSnapshot = null
        window.inboundDragAndDropNode = null
        window.imePreedit = null
        window.imeCommit = null
        imeSession.onInputSession(null)
        shutdownA11yScheduler()
        textToolbar.hide()
        if (dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge.isLoaded &&
            window.handle != 0L
        ) {
            dev.nucleusframework.window.tao.ffi.NativeTaoLinuxDndBridge
                .nativeRevoke(window.handle)
        }
        if (NativeTaoLinuxTouchBridge.isLoaded && window.handle != 0L) {
            NativeTaoLinuxTouchBridge.nativeRevoke(window.handle)
        }
        // Stop the swap thread first. It may be parked inside
        // `eglSwapBuffers` waiting on a frame callback — joining without a
        // wakeup would hang. `shutdownAndJoin` requests shutdown before
        // signalling, and the swap thread bails out once the current swap
        // (if any) returns. After it joins, no other thread holds the EGL
        // context, so we can safely re-bind here for Skia teardown.
        swapThread?.shutdownAndJoin()
        swapThread = null

        // Close the scene BEFORE re-binding the host EGL context: with
        // nativePopupLayers, closing a PlatformLayersComposeScene tears down
        // its live popup layers, and each layer binds *its own* EGL context
        // to free its Skia resources — leaving that context current. The
        // host re-bind below must come after so the host's GPU releases land
        // on the right context.
        sceneBundle?.close()
        window.clearContentMeasurer()
        sceneBundle = null

        // Re-bind THIS window's EGL context before tearing down Skia. The
        // GPU-resource releases that follow (glDeleteFramebuffers /
        // glDeleteTextures inside Surface.close + DirectContext.close) reach
        // GL through the `GrGLInterface` function pointers we resolved via
        // eglGetProcAddress; those pointers expect *some* valid context to
        // be current, and on a multi-window app (main + popup/dialog) the
        // currently-current context may belong to another window or be
        // unbound altogether — leading to a segfault deep inside the
        // driver. Making the local context current first guarantees the
        // releases land on the right resources.
        if (attachmentHandle != 0L) {
            NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
        }
        cachedSurface?.close()
        cachedSurface = null
        cachedRt?.close()
        cachedRt = null
        // Belt for TextureView imports a leaked composition may still hold:
        // scene.close() above released the leases of every live one.
        directContext?.let(::releaseGlTextureImports)
        glTextureHostState.value = null
        directContext?.close()
        directContext = null
        // Clear any input region we may have set while the window was
        // alive; harmless even if the EGL surface is about to go away.
        overlayController.dispose()
        if (attachmentHandle != 0L) {
            NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
            NativeTaoEglBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
            // The sub-surface went with the attachment; a fresh one starts desync.
            subsurfaceSynced = false
        }
    }

    private companion object {
        /** A run of skipped frames is only worth a line past this. */
        private const val FRAME_STALL_TRACE_MILLIS = 100L

        /**
         * Every attached Linux host, so an outbound drag session can keep
         * painting the windows it is *not* running in (see [OutboundDragPump]).
         * Touched on the event-loop thread only; copy-on-write so the pump can
         * iterate while a drop closes a window.
         */
        val liveHosts = java.util.concurrent.CopyOnWriteArrayList<TaoComposeSceneHostLinux>()

        /** A drag icon larger than this is not a decoration, it is a bug (or a fullscreen source). */
        const val MAX_DRAG_ICON_PX = 4096
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val CHANNEL_MAX = 0xFF

        /** Keep swap-interval 0 briefly after the last pixel of resize motion. */
        private const val RESIZE_BURST_HOLD_NS = 100_000_000L // 100 ms

        /**
         * How far outside the content (logical px) a pointer still counts as
         * the CSD shadow ring for resize hit-testing. Theme margins run
         * ~23-30px; anything farther is a stray coordinate from a drag grab.
         */
        private const val CSD_RING_MAX_LOGICAL: Float = 48f

        // Wire scales — must match Rust `CURSOR_FIXED_SCALE` and
        // `TRACKPAD_VALUE_FIXED_SCALE` in `events.rs`.
        private const val TOUCH_POSITION_SCALE: Float = 1024f
        private const val TRACKPAD_VALUE_SCALE: Float = 10_000f

        // Synth pinch radius / pointer ids — same values as the macOS host
        // (see `TaoComposeSceneHost`'s companion); kept in sync manually.
        private const val TRACKPAD_BASE_RADIUS_PX: Float = 120f
        private const val TRACKPAD_POINTER_ID_A: Long = 0xA001L
        private const val TRACKPAD_POINTER_ID_B: Long = 0xA002L
        private const val DEGREES_PER_RADIAN: Float = 180f
        private const val MIN_GESTURE_SCALE: Float = 0.05f
        private const val WHEEL_ZOOM_IDLE_END_MS: Long = 120L

        /**
         * How many times a single swap window may drain the scene's coroutine
         * queue. Generous next to real per-frame traffic (a worker round trip is
         * one or two continuations), small enough that a self-redispatching
         * coroutine can't turn the event-loop thread into a spin loop.
         */
        private const val SKIP_DRAIN_BUDGET_PER_FRAME: Int = 8
    }

    /**
     * Owns the EGL context during `eglSwapBuffers`. The render thread (GTK
     * main thread) hands the context off via
     * [NativeTaoEglBridge.nativeReleaseCurrent] before signalling
     * [requestSwap]; the swap thread then re-binds via `nativeMakeCurrent`,
     * presents (blocking on the compositor's vsync), and releases the
     * context again. The render thread gates on [tryBeginRenderOrMarkOwed]
     * (non-blocking) before its next render; the swap thread re-arms a skipped
     * frame on completion — that's what gives us hardware-vsync pacing without
     * ever stalling the event-loop thread.
     *
     * The two threads never hold the context simultaneously: the render
     * thread always releases before `requestSwap`, the swap thread waits
     * on the work signal before binding, releases before signalling done.
     */
    private inner class SwapThread(
        private val handle: Long,
    ) : Thread("TaoSwapThread-${java.lang.Long.toHexString(handle)}") {
        private val lock = ReentrantLock()
        private val workCond = lock.newCondition()
        private var swapPending = false
        private var swapping = false
        private var shutdown = false

        // Set (under [lock]) when [tryBeginRenderOrMarkOwed] finds a swap in
        // flight and the render thread bails instead of blocking. The swap
        // thread re-arms exactly one redraw when it finishes presenting, so the
        // skipped frame lands on the next event-loop tick without the render
        // (= input) thread ever having stalled on the swap.
        private var renderOwed = false

        init {
            isDaemon = true
        }

        /** Called on the GTK main thread after `flushAndSubmit` + release. */
        fun requestSwap() {
            lock.withLock {
                swapPending = true
                workCond.signal()
            }
        }

        /**
         * Non-blocking render gate for [onRedrawRequested]. Returns `true` when
         * the EGL context is free and the caller may render now. Returns `false`
         * when a swap is still in flight — and atomically records that a render
         * is owed, so [run]'s swap-completion path re-arms the redraw. Never
         * blocks the calling (event-loop / input) thread, which is the whole
         * point: blocking here previously stalled input for the full swap
         * latency, making a subsurface-backed dialog feel unresponsive.
         */
        fun tryBeginRenderOrMarkOwed(): Boolean =
            lock.withLock {
                if (swapPending || swapping) {
                    renderOwed = true
                    false
                } else {
                    true
                }
            }

        fun shutdownAndJoin() {
            lock.withLock {
                shutdown = true
                workCond.signalAll()
            }
            // Best-effort join. If the swap thread is parked inside
            // `eglSwapBuffers` (waiting on a frame callback that GTK is
            // about to deliver), the join can take up to one vsync
            // interval. Two frames worth of headroom is plenty in
            // practice — past that, leak the thread rather than risk
            // hanging the host shutdown.
            join(50)
        }

        @Suppress("NestedBlockDepth", "TooGenericExceptionCaught", "PrintStackTrace")
        override fun run() {
            try {
                while (true) {
                    val doSwap =
                        lock.withLock {
                            while (!shutdown && !swapPending) workCond.await()
                            if (shutdown) return
                            swapPending = false
                            swapping = true
                            true
                        }
                    if (doSwap) {
                        try {
                            NativeTaoEglBridge.nativeMakeCurrent(handle)
                            NativeTaoEglBridge.nativePresent(handle)
                        } catch (t: Throwable) {
                            linuxHostLogger.log(java.util.logging.Level.WARNING, "EGL present failed", t)
                        } finally {
                            try {
                                NativeTaoEglBridge.nativeReleaseCurrent(handle)
                            } catch (_: Throwable) {
                                // Detached underneath us; the host's
                                // detach() handles cleanup.
                            }
                            val rearm =
                                lock.withLock {
                                    swapping = false
                                    // Decoupled pacing: hand the owed frame back
                                    // to the render thread now that the context
                                    // is free. Checked + cleared under the same
                                    // lock as the render thread's mark, so there
                                    // is no lost-wakeup window.
                                    val owed = renderOwed
                                    renderOwed = false
                                    owed
                                }
                            // KWin: drawable advances only after this present.
                            if (useDrawableSizedPaint) {
                                dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
                                    .dispatch(
                                        EmptyCoroutineContext,
                                        Runnable { onDrawablePresented() },
                                    )
                            }
                            // Catch-up after size change: the buffer matching the
                            // request only exists *after* this swap — paint it
                            // without waiting for more motion (all Wayland DEs).
                            val catchUp = postResizeCatchUpFrames.get() > 0
                            if (catchUp) {
                                postResizeCatchUpFrames.updateAndGet { n ->
                                    (n - 1).coerceAtLeast(0)
                                }
                            }
                            if (rearm || catchUp) requestRedrawCoalesced()
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: KCoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
            requestRedrawCoalesced()
        }

        /** Same effect as `dispatch` but skips the no-op coroutine context. */
        fun enqueue(block: Runnable) {
            queue.add(block)
            requestRedrawCoalesced()
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
            if (!queue.isEmpty()) {
                requestRedrawCoalesced()
            }
        }
    }
}

@OptIn(InternalComposeUiApi::class)
private class LinuxTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    /** Live px-per-dp factor of the owning scene — see [TaoPlatformContextBase.sceneScale]. */
    private val scaleProvider: () -> Float,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener?,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
    /** Publishes the active text-input session to the host's [TaoImeSession] (#558). */
    private val onInputSession: (androidx.compose.ui.platform.PlatformTextInputMethodRequest?) -> Unit = {},
    // #559: forwarded to Compose so `CanvasLayersComposeScene` picks the
    // alpha-aware dialog-scrim blend mode (`BlendMode.SrcAtop`) on windows
    // created with `transparent = true` — same as Compose Desktop's
    // `DesktopPlatformContext` forwarding `windowContext.isWindowTransparent`.
    override val isWindowTransparent: Boolean,
) : TaoPlatformContextBase() {
    override val sceneScale: Float get() = scaleProvider()

    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform
                    .PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    /**
     * Keeps the GTK input context anchored to the caret for as long as a field
     * owns the input (#558).
     *
     * The macOS twin also has to activate the view's `NSTextInputContext`
     * first; GTK needs no such step, because the context is created with — and
     * follows the focus of — the window itself. So this only mirrors the caret
     * rect, through the same `nativeSetImeRect` contract Windows uses.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override suspend fun startInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
    ): Nothing {
        onInputSession(request)
        try {
            coroutineScope {
                launch {
                    androidx.compose.runtime
                        .snapshotFlow {
                            request.focusedRectInRoot()
                        }.collect { rect ->
                            if (rect != null) {
                                NativeTaoBridge.nativeSetImeRect(
                                    windowHandle,
                                    rect.left.toInt(),
                                    rect.top.toInt(),
                                    rect.width.toInt().coerceAtLeast(1),
                                    rect.height.toInt().coerceAtLeast(1),
                                )
                            }
                        }
                }
                awaitCancellation()
            }
        } finally {
            onInputSession(null)
        }
    }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        // The Rust side maps the code to a freedesktop cursor name and goes
        // through `gdk_window_set_device_cursor` for every master pointer of
        // the seat — required because GTK 3 manages cursors via XInput 2's
        // per-device table, which masks legacy `XDefineCursor`.
        NativeTaoBridge.setCursorIcon(windowHandle, mapPointerIcon(pointerIcon))
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int = icon.toTaoCursorIconCode()
}

private val linuxHostLogger: Logger = Logger.getLogger("dev.nucleusframework.window.tao.scene")

/** `TaoNativeViewHost.dispatchPointerToNative` type codes and button numbers, as `NativeView` sends them. */
private const val NATIVE_POINTER_PRESS = 1
private const val NATIVE_SECONDARY_BUTTON = 2

/** GDK button bits in a modifier mask. */
private const val GDK_BUTTON1_MASK = 1 shl 8
private const val GDK_BUTTON2_MASK = 1 shl 9
private const val GDK_BUTTON3_MASK = 1 shl 10
