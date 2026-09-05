@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoNonFatalCoroutineExceptionHandler
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTouchEvent
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.clearContentMeasurer
import dev.nucleusframework.window.tao.event.ProvideTaoWindowsScrollConfig
import dev.nucleusframework.window.tao.event.TaoWheelPinchZoom
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.taoKeyEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.event.taoTypedKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.event.win32WheelToAwtScrollEvent
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoGlBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsOverlayBridge
import dev.nucleusframework.window.tao.hasWindowsTextureImports
import dev.nucleusframework.window.tao.installContentMeasurer
import dev.nucleusframework.window.tao.popup.PopupScreenGeometry
import dev.nucleusframework.window.tao.popup.TaoPopupHostWindows
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayerWindows
import dev.nucleusframework.window.tao.releaseWindowsTextureImports
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Windows variant of [TaoComposeSceneHost]. Drives a Compose scene onto the
 * Tao-owned HWND via the ANGLE helper, with custom title-bar decoration applied
 * by [NativeTaoWindowsDecoBridge].
 *
 * Threading: every public method runs on the thread that owns the Tao event
 * loop (Windows imposes no main-thread constraint, but the GL context is bound
 * to whatever thread called `nativeAttach`, so all rendering must stay on it).
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
@Suppress("LargeClass", "TooManyFunctions")
internal class TaoComposeSceneHostWindows(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    // Full-window per-pixel transparency (#416). Creation-time; pairs with
    // tao `with_transparent` (DWM blur-behind empty region).
    private val fullyTransparent: Boolean = false,
    // Fully borderless overlay (`DecoratedWindow(undecorated = true)`): no
    // Compose CSD stroke and no DWM caption/border/shadow contour.
    private val borderlessChrome: Boolean = false,
) : AbstractTaoComposeSceneHost() {
    /**
     * IME preedit / commit routing (#558).
     *
     * No typed-key fallback: that argument exists for the macOS PressAndHold
     * accent picker, which has no Windows counterpart — IMM32 only ever
     * delivers text while a text-input session is up.
     */
    private val imeSession = TaoImeSession()

    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    /**
     * ARGB color the render loop clears the surface to each frame, pushed in
     * via [LocalRequestedClearColor] by the themed window (window background)
     * and by `TitleBar` (resolved title-bar background). Defaults to opaque
     * white until the first composition. Aligns
     * the Windows host with macOS / Linux (and the AWT backends) so a Compose
     * region without an explicit background matches the chrome color instead
     * of a hardcoded white. Fully transparent windows start at alpha 0.
     */
    val clearColorArgbState: androidx.compose.runtime.MutableState<Int> =
        androidx.compose.runtime.mutableStateOf(
            if (fullyTransparent) 0 else 0xFFFFFFFF.toInt(),
        )

    /**
     * Whether the client area must stay transparent — set while a DWM system
     * backdrop is applied (see `WindowsBackdrop`). The render loop then clears
     * to the backdrop tint instead of [clearColorArgbState], so the material
     * shows wherever Compose paints nothing.
     *
     * Fully transparent windows (#416) do **not** arm this flag: they clear
     * with [clearColorArgbState] (alpha-0 by default) on a top-level that
     * already has tao's DWM blur-behind empty region.
     *
     * The Windows counterpart of the macOS host's `glassBackgroundState`;
     * unlike macOS the surface needs no native flag to carry alpha — the ANGLE
     * swapchain already presents it (verified on the child render surface).
     */
    val transparentBackgroundState: androidx.compose.runtime.MutableState<Boolean> =
        androidx.compose.runtime.mutableStateOf(false)

    /**
     * ARGB the render loop clears to while [transparentBackgroundState] is
     * active — the app's tint layer over the DWM material, composited by the
     * per-pixel-alpha swapchain. `0` (fully transparent) shows the raw
     * material; an app-themed translucent colour is what keeps Acrylic — whose
     * DWM tint is a generic system grey — coherent with the app's palette.
     */
    val backdropTintArgbState: androidx.compose.runtime.MutableState<Int> =
        androidx.compose.runtime.mutableStateOf(0)

    /** App-level pre-dispatch hook. See [TaoComposeSceneHost.previewKeyHandler]. */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /** App-level post-dispatch hook. See [TaoComposeSceneHost.keyHandler]. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Wired through [WindowsTaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    /**
     * When true, Compose Popup / DropdownMenu / Tooltip layers materialise as
     * real per-pixel-transparent top-level HWNDs ([TaoPopupSceneLayerWindows])
     * instead of drawing inside this window's render target. Opt-in because
     * the inline default avoids Windows-only compositor artifacts in the
     * custom title-bar path. Set before [attach].
     */
    var nativePopupLayers: Boolean = false

    private val windowInfo = TaoWindowInfo()
    private var currentKeyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
    private var attachmentHandle: Long = 0
    private var hwnd: Long = 0
    private var directContext: DirectContext? = null

    /**
     * Handle for `TextureView`s composed in this window's scene. Narrower than
     * [popupHost] on purpose — see [TaoWindowsTextureHost].
     *
     * Published as **state**, like the Linux twin's `glTextureHostState`, for two
     * reasons: the composition reads it, so clearing it in [detach] takes effect
     * instead of leaving a live composition importing onto a context that is
     * about to be destroyed; and its identity is stable, whereas a value freshly
     * built on every read of the composition local would re-key the imports'
     * `remember` on every recomposition of the window root.
     */
    val windowsTextureHostState: MutableState<TaoWindowsTextureHost?> = mutableStateOf(null)

    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene

    init {
        // Reads `scene` lazily, so it is valid before the bundle exists (null)
        // and across bundle swaps; cleared in dispose().
        window.installContentMeasurer { constraints -> scene?.measureContent(constraints) }
    }

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null
    private val flushingDispatcher = FlushingMainDispatcher()

    /**
     * Scope for host-owned gesture work (trackpad-pinch idle-end debounce).
     * Runs on [flushingDispatcher] so resumed continuations land on the
     * event-loop thread; `delay` itself ticks on the shared coroutines
     * scheduler. Cancelled in [detach]. Deliberately NOT on the #622 fatal
     * path: gesture helpers are isolated (SupervisorJob) — a crash there
     * costs one gesture, logged at SEVERE.
     */
    private val gestureScope =
        CoroutineScope(coroutineContext + flushingDispatcher + SupervisorJob() + TaoNonFatalCoroutineExceptionHandler)

    /** Floating text-selection bar shown on touch selection. */
    private val textToolbar = TaoTextToolbar()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f
    private val scaleState: MutableState<Float> = mutableStateOf(1f)

    /** True while the OS modal resize/move loop is active. */
    private var resizeLoopActive: Boolean = false

    /** Monotonic ns of the last in-drag GPU cache purge (see [onResized]). */
    private var lastResizePurgeNs: Long = 0L

    /** A size change awaits push into the GL surface + ComposeScene at the next paint. */
    private var pendingResizeApply: Boolean = false

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // Sub-pixel deadband (#615): the wire delivers 1/1024-px positions, so
    // click jitter under 1 dp must not reach the scene — Compose's mouse
    // slop is 0.125 dp, and a parent drag gesture consuming that phantom
    // move cancels the child's tap ("buttons need two clicks"). Mouse events
    // dispatched to the scene use the deadband's position, never the raw
    // lastPointerX/Y (SyntheticEventSender would re-inject the difference);
    // the raw position keeps feeding the pinch-gesture centre.
    private val pointerDeadband = TaoPointerDeadband()

    /**
     * Renderers registered by overlay/popup scenes. Drained AFTER the
     * main scene's render in [onRedrawRequested] so each tick paints
     * into every live overlay/popup HWND in the same Tao event-loop wake.
     *
     * Cross-surface sync: before draining, the host surface was flushed
     * (flushAndSubmit) so the GPU sees host commands first; each renderer
     * binds its own pbuffer surface on the shared EGLContext and calls
     * `resetGLAll()` on the shared DirectContext; afterwards the host
     * re-binds its window surface before presenting.
     */
    private val popupRenderers: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Key handlers consulted before the main scene's key dispatch
     * (Phase 8). Overlay scenes register here when they hold a focusable
     * Compose node.
     */
    private val popupKeyHandlers: MutableMap<Any, (KeyEvent) -> Boolean> = LinkedHashMap()

    /** Callbacks invoked when the owner window's screen position changes. */
    private val ownerMoveListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /** Callbacks invoked when the host window loses keyboard focus. */
    private val ownerFocusLostListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /** Callbacks invoked when the host window regains keyboard focus. */
    private val ownerFocusGainedListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Callbacks invoked just before a popup scene layer
     * ([TaoPopupSceneLayerWindows]) destroys its HWND. Used by parent
     * scenes (overlay) to flush stuck focus state.
     */
    private val popupClosingListeners: MutableMap<Any, () -> Unit> = LinkedHashMap()

    /**
     * Set whenever something on the same thread might have changed the
     * bound EGL surface behind Skia's back — a popupRenderers tick ran
     * (each renderer binds its pbuffer surface). Consumed at the start
     * of [onRedrawRequested] — calls `directContext.resetGLAll()` on
     * the host's DirectContext so Skia re-fetches GL state before
     * `flushAndSubmit` issues commands.
     *
     * Without this, the host's DirectContext keeps a stale GL state
     * cache after an overlay's first paint and `flushAndSubmit` reaches
     * a NULL bind point inside the driver (reproduced on NVIDIA).
     */
    private var hostContextDirtied: Boolean = false

    private val nativeViewBlending = WindowsNativeViewBlendingOverlay(BlendingHost())

    /**
     * True while an unconsumed Compose pointer event is being replayed
     * onto a native view (synchronous SendMessage on this thread). For
     * hwnd==0 WebView2 composition hosting the message targets the main
     * HWND so the embedder's parent subclass can feed the
     * CompositionController — but Tao's WndProc then processes that same
     * message and would hand it to the ComposeScene a second time
     * (double text-field context menus, doubled moves). The guard drops
     * that synchronous echo; the native side still sees the message.
     */
    private var nativePointerRedispatchInFlight: Boolean = false

    // Frame pacing is delegated to VSync — `eglSwapInterval(1)` makes
    // eglSwapBuffers pace off the display refresh, which keeps Compose
    // animations (smooth scroll, etc.) aligned on the display cadence at the
    // monitor's native refresh rate (60/120/144/240 Hz — one frame per VBlank).
    // VSync stays on during the OS modal resize/move loop too: pacing the
    // per-WM_SIZE present at the display rate is what keeps the resize from
    // leaking native memory under native-image (see onResizeLoopChanged). The
    // present runs INLINE on the event-loop thread: a cross-thread present
    // on ANGLE's shared per-display D3D11 device deadlocks the global display
    // lock (seen when a sibling host such as a DecoratedDialog detaches).
    // ANGLE's eglSwapBuffers paces fine inline — the input starvation that
    // motivated the old WGL swap thread never applied to this backend.

    fun attach() {
        check(NativeTaoBridge.isLoaded && NativeTaoGlBridge.isLoaded && NativeTaoWindowsDecoBridge.isLoaded) {
            "Tao Windows native libraries not loaded"
        }
        hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        require(hwnd != 0L) { "HWND unavailable; window not yet realised" }

        // The swap after a show() must always happen, clean scene or not —
        // see [forcePresentOnce].
        window.showHook = { forcePresentOnce = true }

        // Install custom decoration (WndProc subclass + DwmExtendFrameIntoClientArea).
        // Title-bar height is set later — the value the TitleBar composable publishes
        // via SideEffect arrives after first composition.
        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f
        scaleState.value = scale
        // Borderless overlays have no caption chrome: keep the deco zone at 0
        // so we don't reserve a phantom 28px title-bar hit band.
        val initialTitleBarPx =
            if (borderlessChrome) {
                0
            } else {
                (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(28)
            }
        NativeTaoWindowsDecoBridge.nativeInstallDecoration(hwnd, initialTitleBarPx)
        if (borderlessChrome || fullyTransparent) {
            // Kill DWM 1px contour + shadow margin (Compose border is already
            // skipped by the openDecoratedWindowWindows undecorated path).
            // Fully transparent windows (#416) need the same treatment even
            // with chrome: the DWM frame, drop shadow and rounded clip all
            // trace the rectangular HWND, betraying the content-defined shape.
            NativeTaoWindowsDecoBridge.nativeSetBorderlessChrome(hwnd, true)
        }

        // ANGLE/D3D11 (WARP-capable on RDP/VMs) is the only Windows backend.
        // Skia needs an EGL-assembled GL interface — the default makeGL()
        // resolves entry points via WGL/opengl32 and fails under ANGLE.
        val handle = NativeTaoGlBridge.nativeAttach(hwnd)
        require(handle != 0L) {
            "Failed to create ANGLE render context for HWND " +
                "(libEGL/libGLESv2 missing or Direct3D 11 unavailable)"
        }
        val ctx =
            try {
                val intf = GLAssembledInterface.createFromNativePointers(0L, NativeTaoGlBridge.nativeEglGetProcFn())
                DirectContext.makeGLWithInterface(intf)
            } catch (_: RuntimeException) {
                null
            }
        attachmentHandle = handle
        directContext =
            (ctx ?: error("Failed to create Skia DirectContext on the ANGLE ES context")).also {
                // Bound the GPU resource cache. Each frame wraps the default
                // framebuffer in a fresh BackendRenderTarget + Surface, and Skia
                // allocates a stencil/scratch attachment sized to the current
                // window for it. During a border drag every new window size mints
                // new scratch resources; even with VSync pacing the present (see
                // onResizeLoopChanged) an explicit budget forces purgeAsNeeded on
                // each flush so the cache stays bounded, and onResizeLoopChanged
                // additionally purges the scratch accumulated across the drag.
                it.resourceCacheLimit = RESOURCE_CACHE_LIMIT_BYTES
            }
        attachedHostCount.incrementAndGet()

        @OptIn(ExperimentalComposeUiApi::class)
        val dndManager =
            dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager(
                getRootNode = { scene!!.rootDragAndDropNode },
                outboundLauncher = ::launchWindowsOutboundDrag,
            )
        // Match the Linux backend for the main scene: keep Compose Popup /
        // DropdownMenu / Tooltip layers inside the same GL render target
        // instead of materialising them as native WS_POPUP windows. This
        // avoids Windows-only GL/native-window compositor artifacts in the
        // custom title bar path. NativeView overlay scenes can still opt into
        // TaoComposeSceneContextWindows when they need popups outside their
        // overlay bounds.
        // IME callbacks edit the focused field through `TextEditingScope`, i.e.
        // they run user code straight off an IMM32 callback — the Tao
        // counterpart of AWT's guarded `inputMethodTextChanged`.
        window.imePreedit = { text -> exceptionHandler.catchExceptions { imeSession.preedit(text) } }
        window.imeCommit = { text -> exceptionHandler.catchExceptions { imeSession.commit(text) } }
        val platformContext =
            WindowsTaoPlatformContext(
                windowHandle = window.handle,
                // The custom title bar is drawn inside the same Compose scene as
                // the rest of the content, so it shares the (0, 0) origin with
                // everything else. We must NOT report it as a `PlatformInsets.top`:
                // Compose's `RootMeasurePolicy` (cf. RootMeasurePolicy.skiko.kt::
                // positionWithInsets) applies platform insets as an *additive
                // offset* on the popup position (designed for iOS notches /
                // Android status bars, where the safe area is outside the Compose
                // surface). Reporting `top = titleBarHeight` here shifts every
                // Popup, DropdownMenu, ContextMenu, and Tooltip down by that
                // amount — visible as a consistent "title-bar-height downward
                // drift" of every popup the user opens. Popups are free to
                // overlap the title bar zone; popup scene layers naturally float
                // above content via z-order. Same fix as Linux (commit 2d8ca500).
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
                // Opt-in path (e.g. tray popups): every Popup becomes a
                // transparent WS_POPUP HWND owned by this window, so popup
                // content can extend beyond — and float independently of —
                // the window bounds. popupHost() is non-null here: hwnd and
                // directContext were both set above.
                platformLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    composeSceneContext =
                        TaoComposeSceneContext(
                            platformContext = platformContext,
                        ) { density, layoutDirection, focusable, consumeOutside ->
                            TaoPopupSceneLayerWindows(
                                host = requireNotNull(popupHost()),
                                initialDensity = density,
                                initialLayoutDirection = layoutDirection,
                                initialFocusable = focusable,
                                initialConsumePointerInputOutside = consumeOutside,
                            )
                        },
                    requestFrame = { window.requestRedraw() },
                )
            } else {
                canvasLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    platformContext = platformContext,
                    requestFrame = { window.requestRedraw() },
                )
            }
        scene?.compositionLocalContext = pendingCompositionLocalContext
        // Frame failures (recomposition / layout / draw) are caught inside the
        // bundle, the single seam all three platforms render through.
        sceneBundle?.exceptionHandler = exceptionHandler

        publishWindowsTextureHost()
        // One source of truth for the scene's drop target: the callback below
        // resolves it through here, and so does an in-process driver.
        window.inboundDragAndDropNode = { scene?.rootDragAndDropNode }
        registerInboundDnD()
        registerTouchInput()

        // Notify overlay/popup layers when the host window moves on screen
        // — top-level WS_POPUP children of the owner don't auto-track.
        // Also re-present a frame: the ANGLE child-HWND swapchain presents
        // through the GDI redirection surface, clipped to the visible region,
        // so a window created partly off-screen has uninitialized (white)
        // pixels there — each move re-presents and fills the newly exposed
        // area. Free while the window is stationary (no WM_MOVE, no frame).
        window.onMoved { _, _ ->
            onOwnerMoved()
            window.requestRedraw()
        }

        // Notify overlay/popup layers when the host window loses keyboard
        // focus — for instance, the user clicked the embedded WebView,
        // which grabs Win32 focus and holds it. The overlay's
        // Compose-side TextField focus should release so its visual
        // indicator (highlight border, blinking caret) goes away.
        window.onFocusChanged { focused ->
            if (focused) onOwnerFocusGained() else onOwnerFocusLost()
        }
    }

    private fun onOwnerFocusLost() {
        if (ownerFocusLostListeners.isEmpty()) return
        for (cb in ownerFocusLostListeners.values.toList()) cb()
    }

    private fun onOwnerFocusGained() {
        if (ownerFocusGainedListeners.isEmpty()) return
        for (cb in ownerFocusGainedListeners.values.toList()) cb()
    }

    private fun markOwnerFocusedFromPointerInput() {
        if (windowInfo.isWindowFocused) return
        windowInfo.isWindowFocused = true
        onOwnerFocusGained()
    }

    // ── Touch (Windows) ───────────────────────────────────────────────────
    //
    // Tao routes Windows touchscreen input through WM_POINTER. Without routing
    // `WindowEvent::Touch` to Compose, `LazyColumn` scroll, drag gestures, and
    // `detectTransformGestures` (pinch / rotate) would not react on tablets /
    // 2-in-1s - same gap Compose Desktop officiel hits on this platform
    // (JBR-2702).
    //
    // The Rust side dispatches one event per finger update; we accumulate
    // the active set here and issue a single `sendPointerEvent` with the
    // full pointer list every time, since Compose treats absence as a
    // release.

    private data class ActiveTouch(
        val id: Long,
        var xPx: Float,
        var yPx: Float,
        var pressed: Boolean,
        var pressure: Float,
    )

    /** Insertion order matters for stable pointer ordering across events. */
    private val activeTouches = LinkedHashMap<Long, ActiveTouch>()

    private fun registerTouchInput() {
        // Touch runs user pointer-input code (clickable, drag) exactly like the
        // mouse path, so it gets the same guard as the pointer wraps in
        // DecoratedWindow; a rethrow unwinds into `EventDispatcher.guarded`,
        // i.e. the fatal path, just like every other input entry.
        window.onTouchInput { phase, id, xFixed, yFixed, forceFixed ->
            exceptionHandler.catchExceptions { onTouchInput(phase, id, xFixed, yFixed, forceFixed) }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun onTouchInput(
        phase: Int,
        id: Long,
        xFixed: Int,
        yFixed: Int,
        forceFixed: Int,
    ) {
        val sc = scene ?: return
        val xPx = xFixed / TOUCH_POSITION_SCALE
        val yPx = yFixed / TOUCH_POSITION_SCALE
        window.updateWindowsTitleBarTouchDrag(phase, id, xPx, yPx)
        val pressure =
            if (forceFixed == TaoTouchEvent.FORCE_UNKNOWN) {
                // No digitizer pressure data — Compose expects a non-zero value
                // for an active contact, so report the standard "average touch".
                1f
            } else {
                forceFixed / TOUCH_FORCE_SCALE
            }

        val composeType =
            when (phase) {
                TaoTouchEvent.PRESS -> {
                    markOwnerFocusedFromPointerInput()
                    activeTouches[id] = ActiveTouch(id, xPx, yPx, pressed = true, pressure = pressure)
                    PointerEventType.Press
                }
                TaoTouchEvent.MOVE -> {
                    val existing = activeTouches[id]
                    if (existing != null) {
                        existing.xPx = xPx
                        existing.yPx = yPx
                        existing.pressure = pressure
                        PointerEventType.Move
                    } else {
                        // Synthetic Press for an unknown id - defensive in case Tao
                        // ever forwards a Move without a prior Started (palm-reject
                        // race observed on some Surface drivers).
                        markOwnerFocusedFromPointerInput()
                        activeTouches[id] = ActiveTouch(id, xPx, yPx, pressed = true, pressure = pressure)
                        PointerEventType.Press
                    }
                }
                TaoTouchEvent.RELEASE, TaoTouchEvent.CANCEL -> {
                    val existing = activeTouches[id]
                    if (existing != null) {
                        existing.xPx = xPx
                        existing.yPx = yPx
                        existing.pressed = false
                    } else {
                        return
                    }
                    PointerEventType.Release
                }
                else -> return
            }

        val pointers =
            activeTouches.values.map { t ->
                ComposeScenePointer(
                    id = PointerId(t.id),
                    position = Offset(t.xPx, t.yPx),
                    pressed = t.pressed,
                    type = PointerType.Touch,
                    pressure = t.pressure,
                )
            }
        // Match Compose iOS (`ComposeSceneMediator.uikit.kt`): direct
        // touchscreen contacts are PointerType.Touch events with no
        // event-level button and an empty button mask. Skiko's primary
        // matcher treats Touch itself as primary; synthesising BUTTON1 here
        // prevents touch long-press/onClick matchers from recognizing it.
        sc.sendPointerEvent(
            eventType = composeType,
            pointers = pointers,
            keyboardModifiers = currentKeyboardModifiers,
        )

        // Purge after the dispatch so the JVM saw the released finger one
        // last time with `pressed=false` — same convention as Linux.
        if (phase == TaoTouchEvent.RELEASE || phase == TaoTouchEvent.CANCEL) {
            activeTouches.remove(id)
            if (phase == TaoTouchEvent.CANCEL) {
                sc.cancelPointerInput()
            }
        }
    }

    // ── Trackpad pinch-to-zoom (Ctrl-flagged WM_MOUSEWHEEL) ───────────────
    //
    // Windows delivers a precision-touchpad pinch (and a real Ctrl+wheel) as a
    // WM_MOUSEWHEEL carrying the Ctrl flag; the vendored Tao patch routes those
    // to the magnify hook (instead of a scroll, which would drive the
    // scrollable — the bug we're fixing). Each notch/tick is a discrete delta,
    // but pinch detection (`detectTransformGestures`) only crosses its touch
    // slop once distance has changed enough, so per-tick Press→Release bursts
    // would swallow fine touchpad zooms. We instead keep ONE continuous
    // two-finger Touch gesture: the first tick presses, every tick moves
    // (accumulating scale), and an idle debounce releases it — the same
    // continuous model the macOS path uses, so zoom is smooth and the gesture
    // never reaches the scrollable.

    private var pinchActive = false
    private var pinchScale = 1f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinchEndJob: Job? = null

    /**
     * Synthesises a two-finger pinch from one Ctrl+wheel tick. [valueFixed] is
     * the normalized wheel delta × [TRACKPAD_VALUE_SCALE] (positive = zoom in).
     * Only magnify gestures are produced on Windows, so kind/phase/x/y from the
     * shared `onTrackpadGesture` wire are ignored.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun onTrackpadGesture(
        @Suppress("UNUSED_PARAMETER") kind: Int,
        @Suppress("UNUSED_PARAMETER") phase: Int,
        @Suppress("UNUSED_PARAMETER") xFixed: Int,
        @Suppress("UNUSED_PARAMETER") yFixed: Int,
        valueFixed: Int,
    ) {
        if (scene == null) return
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers

        val value = valueFixed / TRACKPAD_VALUE_SCALE
        // Precision touchpads can deliver many fractional deltas; map the
        // WHEEL_DELTA-normalized value through a multiplicative curve so small
        // ticks accumulate smoothly without each message behaving like a large
        // zoom step.
        val step = TaoWheelPinchZoom.stepFromWheelDelta(value)

        if (!pinchActive) {
            pinchActive = true
            pinchScale = 1f
            // Centre on the cursor = zoom focal point (the pinch doesn't move it).
            pinchCenterX = lastPointerX
            pinchCenterY = lastPointerY
            sendPinchPointers(PointerEventType.Press)
        }
        pinchScale *= step
        sendPinchPointers(PointerEventType.Move)
        schedulePinchEnd()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sendPinchPointers(eventType: PointerEventType) {
        val sc = scene ?: return
        val radius = PINCH_BASE_RADIUS_PX * pinchScale
        val pressed = eventType != PointerEventType.Release
        val pointers =
            listOf(
                ComposeScenePointer(
                    id = PointerId(PINCH_POINTER_ID_A),
                    position = Offset(pinchCenterX - radius, pinchCenterY),
                    pressed = pressed,
                    type = PointerType.Touch,
                ),
                ComposeScenePointer(
                    id = PointerId(PINCH_POINTER_ID_B),
                    position = Offset(pinchCenterX + radius, pinchCenterY),
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

    /** Re-arms the idle timer that releases the synthetic pinch once ticks stop. */
    private fun schedulePinchEnd() {
        pinchEndJob?.cancel()
        pinchEndJob =
            gestureScope.launch {
                delay(PINCH_IDLE_END_MS.milliseconds)
                endPinchGesture()
            }
    }

    private fun endPinchGesture() {
        pinchEndJob = null
        if (!pinchActive) return
        sendPinchPointers(PointerEventType.Release)
        pinchActive = false
        pinchScale = 1f
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun launchWindowsOutboundDrag(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
        onCompleted: (androidx.compose.ui.draganddrop.DragAndDropTransferAction?) -> Unit,
    ): Boolean {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.isLoaded) return false
        if (hwnd == 0L) return false
        // Defer DoDragDrop by one event-loop iteration (#435). Compose calls
        // this launcher from inside `sendPointerEvent`; entering the modal
        // session here would make every frame painted by [OutboundDragPump]
        // re-enter the scene while that pointer dispatch is still on the
        // stack — a recomposition applied mid-dispatch can detach the node
        // whose pointer-input handler is suspended underneath. Started from
        // the dispatcher's pump instead, the session has no Compose dispatch
        // below it. The mouse button is still down when the block runs one
        // iteration later, so the session starts normally. The *thread* must
        // not change — DoDragDrop takes the mouse capture on the calling
        // thread and only the HWND owner ever sees the mouse-up.
        dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
            .dispatch(kotlin.coroutines.EmptyCoroutineContext) {
                onCompleted(runWindowsOutboundDrag(request))
            }
        return true
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun runWindowsOutboundDrag(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        // Re-checked at execution time: the window may have closed between
        // the pointer dispatch that scheduled the session and this tick.
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.isLoaded) return null
        if (hwnd == 0L) return null
        return dev.nucleusframework.window.tao.dnd.TaoSceneDnD.launchOutboundDrag(
            request = request,
            dropEffectCopy = dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY,
            dropEffectMove = dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_MOVE,
            dropEffectLink = dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_LINK,
        ) { files, text, allowedEffects ->
            // Drop VSync for the session, like the fullscreen transition does
            // (see fullscreenTransitionResized). Frames painted from inside
            // DoDragDrop's modal loop are presented inline on this thread, and
            // this thread is what the OS drag loop — holder of the system-wide
            // mouse capture — is waiting on. A vsync-paced present would park it
            // until the next VBlank on every frame, which is felt as a laggy
            // drag cursor and late drop-target feedback. Interval 0 also
            // replaces the queued frame rather than lining up behind it, so
            // what the user sees during the drag stays current.
            val pacedByVSync = attachmentHandle != 0L
            if (pacedByVSync) NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, false)
            try {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.nativeStartDrag(
                    hwnd = hwnd,
                    files = files,
                    text = text,
                    allowedEffects = allowedEffects,
                    pump = OutboundDragPump(),
                )
            } finally {
                if (pacedByVSync) NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, true)
                // Unwedge rendering: an invalidation raised during the drag
                // latched `redrawPending` while DoDragDrop's pump ate the
                // matching REDRAW_REQUESTED, which suppresses every later
                // request. See TaoWindow.resetRedrawLatch.
                window.resetRedrawLatch()
            }
        }
    }

    /**
     * Drives the host from inside `DoDragDrop`'s modal loop — see
     * [dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DragPump].
     *
     * No Compose reentrancy (#435): `DoDragDrop` is deferred onto
     * [dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher] by
     * [launchWindowsOutboundDrag], so the modal session starts from a pump
     * tick with no `sendPointerEvent` dispatch below it, and every frame
     * painted here is a plain top-level render.
     *
     * Named class (not a lambda) for GraalVM JNI reachability, same as
     * [InboundDnDCallback].
     */
    private inner class OutboundDragPump :
        dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DragPump {
        // Not a nanoTime sentinel: `System.nanoTime()`'s origin is arbitrary and
        // may be negative, in which case `now - 0L` is below any threshold and
        // the very first frame — and so every frame — would be throttled away,
        // silently restoring the freeze this class exists to fix.
        private var rendered = false
        private var lastRenderNanos = 0L

        override fun pump() {
            // Draining is cheap and never blocks, so it runs on every callback.
            dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
                .pump()

            // Rendering is not: the present is inline and VSync-paced
            // (eglSwapInterval(1)), so each frame parks this thread until the
            // next VBlank — and this thread is currently holding up the OS drag
            // loop, which owns the mouse capture system-wide. Windows calls
            // QueryContinueDrag on every mouse-move message, well above the
            // display rate, so without this throttle a fast drag would block on
            // VSync several times per frame and visibly lag the drag cursor and
            // the destination's drop feedback.
            val now = System.nanoTime()
            if (rendered && now - lastRenderNanos < MIN_DRAG_FRAME_INTERVAL_NANOS) return
            rendered = true
            lastRenderNanos = now
            onRedrawRequested()
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.isLoaded) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "windows DnD lib not loaded — inbound disabled",
            )
            return
        }
        val callback = InboundDnDCallback()
        val rc =
            dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
                .nativeRegister(hwnd, callback)
        dev.nucleusframework.window.tao.TaoDnDDiagnostics
            .log("RegisterDragDrop rc=$rc")
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback :
        dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.Callback {
        private fun node() = window.inboundDragAndDropNode?.invoke()

        override fun onDragEnter(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDragEnter x=$x y=$y hasFiles=$hasFiles",
            )
            if (!hasFiles) {
                return dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
            return if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragEnter(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int =
            if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragOver(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragLeave(hwnd: Long) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics
                .log("onDragLeave")
            dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                .onDragLeave(node())
        }

        override fun onDrop(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            files: Array<String>?,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDrop x=$x y=$y files=${files?.size ?: 0}",
            )
            return if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDrop(node(), x, y, files)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    // Guarded like AWT's `ComposeSceneMediator.setContent`: the first
    // composition runs inside this call, so content that throws while mounting
    // must reach the window's handler instead of unwinding into the Tao loop.
    fun setContent(content: @Composable () -> Unit) =
        exceptionHandler.catchExceptions {
            scene?.setContent {
                // Stock Compose Desktop Windows wheel behavior; only the
                // lines-per-notch factor is reapplied (see TaoWindowsScrollConfig).
                ProvideTaoWindowsScrollConfig {
                    TaoTextToolbarHost(textToolbar) {
                        CompositionLocalProvider(
                            LocalDensity provides Density(scaleState.value),
                        ) {
                            content()
                        }
                    }
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

    /**
     * Fullscreen-toggle pre-layout: measures + lays out the scene at the
     * TARGET client size WITHOUT presenting anything — the draw goes into a
     * throwaway recording canvas. Called before the toggle's geometry
     * change so the synchronous WM_WINDOWPOSCHANGED prepare (see
     * [NativeTaoWindowsDecoBridge.onFullscreenSizeChanged]) only has to
     * re-draw an already-laid-out scene, which keeps it within the geometry
     * change instead of leaving DWM a stale frame to composite. The Windows
     * analog of the macOS `windowWillEnterFullScreen:` prepare (issue 413).
     */
    fun fullscreenPreLayout(
        targetWidthPx: Int,
        targetHeightPx: Int,
    ) {
        val bundle = sceneBundle ?: return
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return
        bundle.scene.size = IntSize(targetWidthPx, targetHeightPx)
        // Apply pending snapshot writes (the chrome flip pushed just before
        // this call) so the warmed layout already has the right chrome.
        flushingDispatcher.drain()
        recordSceneToPicture(bundle, targetWidthPx, targetHeightPx).close()
    }

    /**
     * The fullscreen-transition render (invoked synchronously from the deco
     * WndProc's WM_WINDOWPOSCHANGED). Presents at swap interval 0: a
     * vsync-paced present would line up BEHIND the frames already queued in
     * the flip-model swapchain, reaching the screen 1-3 vblanks after the
     * geometry change no matter how early it was rendered — an interval-0
     * present replaces the queued frame instead.
     */
    fun fullscreenTransitionResized(
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        if (attachmentHandle == 0L) {
            onResized(widthPxNew, heightPxNew)
            return
        }
        NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, false)
        try {
            if (widthPxNew != widthPx || heightPxNew != heightPx) {
                // Resize the child + immediately present a themed clear:
                // DWM sees the HWND resize right away but the resized
                // swapchain's first buffer only lands at the next present —
                // a composition falling into that gap otherwise shows an
                // uninitialized black buffer (captured on the exit path).
                // The sub-ms clear shrinks the gap and colours it.
                NativeTaoGlBridge.nativeResize(attachmentHandle, widthPxNew, heightPxNew, scale)
                NativeTaoGlBridge.nativeClearPresent(attachmentHandle, resolveClientClearArgb())
                // Raw GL clear-color/scissor calls happened behind Skia's
                // state cache; resync before the Skia render below.
                directContext?.resetGLAll()
            }
            onResized(widthPxNew, heightPxNew)
        } finally {
            NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, true)
        }
    }

    fun onResized(
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        // Win32 emits WM_SIZE/SIZE_MINIMIZED as 0x0. Keep the last real
        // ComposeScene size so taskbar previews and restore do not collapse.
        if (widthPxNew <= 0 || heightPxNew <= 0) return
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        nativeViewBlending.syncFrame()
        // The GL surface child + ComposeScene size are pushed in
        // onRedrawRequested (see the pendingResizeApply block there), so a
        // throttled or async paint always renders the freshest size and keeps
        // the surface resize + present atomic (no black edge).
        pendingResizeApply = true

        // Every WM_SIZE of the OS modal resize/move loop renders + presents
        // inline, at swap interval 0 (see onResizeLoopChanged) — NEVER skip or
        // coalesce a frame here. A skipped frame leaves the parent HWND at its
        // new size while the child surface + content stay stale until the
        // async redraw lands, and DWM composites that mismatch as the window
        // trembling — the Windows twin of the macOS live-resize tremble
        // (#476). Rendering inline is atomic instead: the modal loop is
        // parked on this very call, so the geometry cannot advance while we
        // paint, and each presented frame matches the window bounds exactly.
        // The memory cost of the unpaced render loop (the #347 native-image
        // leak) is bounded by the per-flush 256 MiB cache budget plus a
        // periodic purge of the per-size GPU scratch accumulated by the drag;
        // the drag-end path in onResizeLoopChanged reclaims the rest.
        if (resizeLoopActive) {
            val now = System.nanoTime()
            if (now - lastResizePurgeNs >= RESIZE_PURGE_INTERVAL_NS) {
                lastResizePurgeNs = now
                purgeGpuResourceCache()
            }
        }
        onRedrawRequested()
    }

    /**
     * Frees the GPU resource cache synchronously: toggling the limit to 0 runs
     * Skia's `purgeAsNeeded` inline, releasing every unlocked resource, and
     * restoring the budget lets the next frame re-mint only what it needs.
     *
     * The purge issues `glDelete*`, so it MUST run with **this** host's ES
     * context current. Both callers sit on the resize path, where a sibling
     * host (a second `DecoratedWindow`, a `DecoratedDialog`) can have left its
     * own context bound after painting a frame of its own between two of our
     * WM_SIZEs — the modal size/move loop pumps the whole thread's messages,
     * so the sibling's WM_PAINT is dispatched in the middle of our drag.
     * Purging against that foreign context deletes ids in the *sibling's*
     * namespace (the two contexts are unshared, so both number their objects
     * from 1 and the ids collide wholesale): its live textures die while our
     * own are merely leaked. Skia keeps believing the sibling's glyph atlas is
     * resident, so that window goes on drawing geometry but loses every glyph
     * and icon — the parent window blanking behind a fast child resize (#514).
     *
     * Same foreign-context hazard the make-current in [detach] guards against.
     */
    private fun purgeGpuResourceCache() {
        val ctx = directContext ?: return
        if (attachmentHandle != 0L) NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)
        ctx.resourceCacheLimit = 0
        ctx.resourceCacheLimit = RESOURCE_CACHE_LIMIT_BYTES
    }

    /**
     * Enter/leave the OS modal resize/move loop (WM_ENTERSIZEMOVE /
     * WM_EXITSIZEMOVE). VSync is dropped for the whole loop, exactly like the
     * outbound-drag session ([launchWindowsOutboundDrag]): the per-WM_SIZE
     * present runs inline on this thread, and this thread is what the modal
     * size loop — holder of the mouse capture — is waiting on. A vsync-paced
     * present parks the WndProc until the next VBlank on every frame, so the
     * content visibly trails the border drag, worst on low-refresh displays
     * (#477, 60 Hz). Interval 0 also replaces the queued frame instead of
     * lining up behind it, keeping what the user sees current.
     *
     * The native-image RSS leak that once motivated keeping VSync on (an
     * unpaced loop minted a fresh BackendRenderTarget + Surface per size,
     * whose Skia GPU scratch and Compose layer backings only a GC reclaims)
     * is instead contained by the per-flush 256 MiB cache budget, a periodic
     * in-drag purge (see [onResized]), and the drag-end purge + GC nudge
     * below — bounding the accumulation, not blocking the modal loop, is
     * what that fix actually required. Every WM_SIZE is resized and painted
     * atomically, inline; skipping/coalescing frames instead lets the scene
     * geometry advance while the ANGLE child surface holds an older frame,
     * which DWM composites as trembling (the #476 macOS artifact, on Windows).
     */
    fun onResizeLoopChanged(active: Boolean) {
        if (attachmentHandle == 0L) return
        resizeLoopActive = active
        if (active) {
            // Keep VSync when the scene composites a TextureView or holds a
            // TaoGpuRenderContext consumer (#484). With interval 0 and no
            // other pacer, their `withFrameNanos`-driven producers re-enter
            // the render path at event-pump speed for the whole drag
            // (~1300 fps observed) — VSync is the only pacer available
            // inside the modal loop, so such a window trades the #477
            // border-drag responsiveness for a display-paced frame clock.
            // Windows without frame-paced GPU content keep the interval-0
            // regime and its resize behavior unchanged.
            val framePacedContent =
                directContext?.let {
                    hasWindowsTextureImports(it) ||
                        dev.nucleusframework.window.tao.TaoGpuRenderContextConsumers
                            .isActive(it)
                } == true
            if (!framePacedContent) {
                NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, false)
            }
        } else {
            NativeTaoGlBridge.nativeSetVSyncEnabled(attachmentHandle, true)
            // Paint the settled size once more so the first steady-state frame
            // is already vsync-paced and current.
            pendingResizeApply = true
            onRedrawRequested()
            // Reclaim the per-size scratch (stencil/render-target attachments)
            // accumulated across the drag; the next frame re-mints only what
            // the final size needs. Without this the drag's peak footprint is
            // released only by a later GC.
            purgeGpuResourceCache()
            // The purge above only frees Skia's GPU cache. Every remeasure of
            // the drag also minted Compose layers/pictures whose native Skia
            // memory is released by the skiko Cleaner only after a GC — and a
            // static scene allocates nothing after the drag, so no GC ever
            // comes and the drag's peak footprint stays resident. Nudge one
            // collection here so the Cleaner can run; bounded to drag end.
            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
        }
    }

    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scaleState.value = newScale
        scene?.density = Density(scale)
        NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        // Re-publish title-bar height in physical pixels so the deco WndProc
        // keeps its hit-test caption zone in sync after a DPI change.
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(
            hwnd,
            (titleBarHeightDpState.value * scale).toInt(),
        )
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onFocusChanged(focused: Boolean) {
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

    /**
     * One-shot present override, armed by [TaoWindow.showHook]: the redraw
     * following a show must swap even when the scene is clean, because DWM
     * does not reliably retain a pre-show present once ShowWindow composites
     * the window (see the matching re-request in event_loop.rs).
     */
    private var forcePresentOnce = true

    /**
     * The clear colour of the last presented frame. The resolved clear
     * (backdrop tint / transparency / themed background) is read outside the
     * composition, so a change never raises a scene invalidation — compare it
     * here so a tint or transparency flip still reaches the screen.
     */
    private var lastPresentedClearArgb: Int? = null

    fun onRedrawRequested() {
        val ctx = directContext ?: return
        val bundle = sceneBundle ?: return
        val sc = bundle.scene

        if (widthPx <= 0 || heightPx <= 0) return

        // Minimized: skip before the frame-clock tick below. Unlike
        // Linux/Wayland there's no vsync back-pressure while minimized (ANGLE's
        // flip-model swapchain never reports occlusion), so without this the
        // loop would spin recording + presenting into a hidden surface whenever
        // an animation keeps invalidating. Parks animations; restored via
        // TaoWindow.requestRedraw on the MINIMIZED-off event.
        if (window.isMinimized) return

        // Push a pending size into the ComposeScene + GL surface before the
        // frame-clock drain, so the size-change-driven recomposition (and any
        // coroutine keyed on the new size) is scheduled and drained this frame.
        // `nativeResize` grows the render-surface child HWND; doing it here,
        // in the same paint that presents, keeps the surface resize and the
        // present atomic — no exposed-strip black edge (the reason the old
        // onResized painted synchronously). resetGLAll after nativeResize is
        // unnecessary: the ES context/surface stay bound on this thread.
        val resizeApplied = pendingResizeApply
        if (pendingResizeApply) {
            sc.size = IntSize(widthPx, heightPx)
            updateWindowInfoSize()
            NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
            pendingResizeApply = false
        }

        val now = System.nanoTime()

        // ── Frame pump ────────────────────────────────────────────────────
        // Drain queued main-thread work (scroll dispatch, a11y, etc.) before
        // the frame. The scene's frame clock is ticked inside `bundle.render`
        // (via FrameRecomposer.performFrame), so `withFrameNanos`-driven
        // animation state is resumed and applied atomically with this frame's
        // recompose → layout → draw — no one-frame lag.
        flushingDispatcher.drain()

        // Make sure the ES context + host window surface are current on this
        // thread (defensive — they already were since `attach`, but overlay/
        // popup renderers re-bind their pbuffer surfaces between frames).
        NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)
        // Consume the dirtied flag: a popupRenderers loop swapped the bound
        // EGL surface since our last tick. Tell Skia "external code touched
        // GL state" so it re-fetches via glGet* before issuing flush/submit
        // commands. resetGLAll is cheap (state-cache invalidation only);
        // calling it on every frame unconditionally is too heavy for some
        // drivers, so we gate on the flag.
        // Sibling-host mode: another TaoComposeSceneHostWindows is alive
        // (e.g., DecoratedDialog over a DecoratedWindow). Each host owns
        // its own EGLContext + DirectContext, and the dialog's
        // onRedrawRequested can run between our frames — swapping the
        // current EGL binding behind our back. Our DirectContext's
        // per-context GL state cache is then stale, and the next
        // flushAndSubmit faults inside the driver. Force resetGLAll on
        // every frame entry while >1 host coexists; revert to the
        // popup-only flag-gated path once it's just us.
        if (hostContextDirtied || attachedHostCount.get() > 1) {
            ctx.resetGLAll()
            hostContextDirtied = false
        }

        // Wrap the default framebuffer (id 0). Skia's GL backend uses
        // BOTTOM_LEFT origin with the GL convention; SurfaceOrigin handles the
        // flip so Compose draws right-side up.
        val rt =
            BackendRenderTarget.makeGL(
                width = widthPx,
                height = heightPx,
                sampleCnt = 0,
                stencilBits = 8,
                fbId = 0,
                fbFormat = FramebufferFormat.GR_GL_RGBA8,
            )
        // Mica/Acrylic backdrops arm transparentBackgroundState at runtime and
        // the clear becomes a translucent tint over the DWM material — the
        // surface must drop LCD SurfaceProps then too, not only for
        // creation-time transparent windows. Re-evaluated every frame since
        // the surface is recreated per frame.
        val surface =
            makeTaoGlSurface(ctx, rt, fullyTransparent || transparentBackgroundState.value) ?: run {
                rt.close()
                return
            }

        // Sampled before the render (and OR-ed with the post-render value
        // below): an invalidation raised by this frame's own recompose/layout
        // still counts as visual, while a recomposer-only tick — the global
        // snapshot wake caused by a state write in ANOTHER window — leaves the
        // flag untouched. See [TaoSceneBundle.visualDirty].
        val dirtyBeforeRender = bundle.visualDirty.getAndSet(false)
        val clearArgb = resolveClientClearArgb()
        try {
            // Clear to the resolved title-bar background (pushed by `TitleBar`
            // via [LocalRequestedClearColor]) so a Compose region without an
            // explicit background matches the chrome color — aligned with the
            // macOS / Linux Tao hosts and the AWT backends.
            // While a system backdrop is active the clear is the app's tint
            // layer over the DWM material (0 = raw material); otherwise the
            // opaque themed background.
            // Backdrop mode: tint over the DWM material (0 = raw material).
            // Fully transparent without a backdrop: use the resolved clear
            // colour (alpha-0 by default, or a semi-transparent WindowBackground).
            // Opaque windows: themed clear as usual.
            surface.canvas.clear(clearArgb)
            bundle.render(surface.canvas, now)

            // `flushAndSubmit` issues the glFlush that commits the frame to
            // the back buffer; the present happens below, after the overlay/
            // popup renderers (they only need the flush, not the present).
            surface.flushAndSubmit(syncCpu = false)
        } finally {
            surface.close()
            rt.close()
        }

        // Post-record drain — pure CPU work; the host surface stays bound and
        // its frame is already committed by the flushAndSubmit above.
        //
        // A continuation returning from a worker thread (the canonical
        // `TextureView` producer: dispatched by the pre-render drain, back a
        // millisecond later) would otherwise not be picked up by the NEXT
        // frame's pre-render drain either: `markFrameAvailable` on the worker
        // has already requested the redraw, so that frame's WM_PAINT can start
        // before the continuation is even queued, and it then runs only AFTER
        // `sendFrame`. Its next `withFrameNanos` misses the tick and waits a
        // full extra frame — the producer animates at half the refresh rate.
        //
        // Draining here rather than after the present matters for jitter, not
        // just throughput. Whatever the drain point, the continuation re-arms
        // after this frame's tick, so its gap to the next producer frame is
        // `one frame + round trip`: under half a frame it reads as a 1-frame
        // gap, over it as 2. Draining after the blocking present leaves a round
        // trip straddling that threshold, which alternates 1 and 2 frames — a
        // higher average rate than the old cadence but visibly juddery.
        // Recording is the expensive part of the frame, so draining right after
        // it keeps the round trip a couple of milliseconds, well inside the
        // threshold, and the cadence stays flat.
        //
        // Bounded by the queue snapshot, so a self-redispatching continuation
        // cannot spin this thread — it just keeps requesting redraws, which
        // `dispatch` already did before this drain existed.
        flushingDispatcher.drain()

        // Drain overlay/popup renderers. Cross-surface sync:
        //   1. Host already flushed above (flushAndSubmit issues glFlush
        //      internally when committing the surface).
        //   2. Each renderer below binds its own pbuffer surface (same
        //      EGLContext), calls resetGLAll on the shared DirectContext,
        //      paints, presents via its DComp swapchain.
        //   3. We flag the host DirectContext dirty so the next frame's entry
        //      runs resetGLAll — Skia's GL state cache no longer reflects truth
        //      after the external surface switches.
        if (popupRenderers.isNotEmpty()) {
            val snapshot = popupRenderers.values.toList()
            for (render in snapshot) render()
            hostContextDirtied = true
        }

        // Present inline — but only when the frame carries visual changes.
        // A clean frame (recomposer tick with no resulting layout/draw
        // invalidation) skips eglSwapBuffers entirely: the swapchain already
        // holds identical content, and the VSync-paced swap would park this
        // shared event-loop thread until the next VBlank. With several
        // visible windows, presenting those clean frames made every window
        // re-present at the animating window's rate, serially blocking a
        // VBlank each — the whole app then crawled (multi-window lag).
        // Presents that must happen regardless of scene dirtiness:
        //  - a size/scale apply (the parent HWND already changed geometry);
        //  - the OS modal resize/move loop (every WM_SIZE must swap, #476);
        //  - the first swap after show() (DWM drops pre-show presents);
        //  - a resolved clear-colour change (read outside the composition,
        //    so it never raises a scene invalidation).
        // nativePresent defensively re-binds the host's window surface first
        // (a popup renderer may have left its pbuffer current) and
        // eglSwapBuffers paces on the display refresh.
        val visualFrame = dirtyBeforeRender || bundle.visualDirty.get()
        val mustPresent =
            visualFrame ||
                resizeApplied ||
                resizeLoopActive ||
                forcePresentOnce ||
                lastPresentedClearArgb != clearArgb
        if (mustPresent) {
            forcePresentOnce = false
            lastPresentedClearArgb = clearArgb
            NativeTaoGlBridge.nativePresent(attachmentHandle)
        }

        // Backstop for a continuation that landed after the post-record drain
        // (a worker slower than the record). Costs it the jitter threshold
        // above, but still beats waiting for the next frame's pre-render drain.
        flushingDispatcher.drain()
    }

    fun onPointerMove(
        aFixed: Int,
        bFixed: Int,
    ) {
        if (nativePointerRedispatchInFlight) return
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        if (!pointerDeadband.shouldDispatchMove(xPx, yPx, scale)) return
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    fun onPointerExited() {
        if (
            hwnd != 0L &&
            NativeTaoWindowsDecoBridge.isLoaded &&
            NativeTaoWindowsDecoBridge.nativeIsCursorOverWindowOrOwnedPopup(hwnd)
        ) {
            return
        }
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    fun onPointerButton(
        buttonCode: Int,
        pressed: Boolean,
    ) {
        if (nativePointerRedispatchInFlight) return
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = mapButton(buttonCode),
        )
    }

    fun onPointerScroll(event: TaoPointerScrollEvent) {
        if (nativePointerRedispatchInFlight) return
        // Stock Compose Desktop wheel path: the event goes straight into the
        // scene and MouseWheelScrollingLogic animates it (smooth-scroll
        // tween) — the same pipeline as upstream Compose on Windows and
        // compose-desktop-native. No input-layer animation on top.
        sendScrollToScene(event)

        // WM_PAINT-starvation mitigation. The frame clock only ticks in
        // [onRedrawRequested], fired from WM_PAINT — the lowest-priority
        // Win32 message, synthesized only when the queue is otherwise empty.
        // A wheel flood keeps the queue occupied, starving WM_PAINT: the
        // smooth-scroll tween freezes mid-gesture then lurches (judder).
        // Pump a frame inline instead: we run on the GL thread (onResized
        // renders synchronously the same way) and ANGLE's DXGI Present
        // blocks once its swap-chain queue fills, so the pump self-paces at
        // the display refresh — the input flood coalesces per frame. After
        // the flood the regular WM_PAINT path resumes and animates the tail.
        onRedrawRequested()
    }

    private fun sendScrollToScene(event: TaoPointerScrollEvent) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.dispatchAwtShapedScroll(
            x = pointerDeadband.x,
            y = pointerDeadband.y,
            event = event,
            keyboardModifiers = currentKeyboardModifiers,
        )
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
        // Overlay/popup scenes get a chance to consume the event before
        // the main scene. Mirrors the macOS popupKeyHandlers chain.
        for (handler in popupKeyHandlers.values) {
            if (handler(composeEvent)) return true
        }
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    /** Push the latest title-bar height (in dp) down to the deco WndProc so
     *  the caption hit-test zone matches the Compose layout. */
    fun syncTitleBarHeight() {
        if (hwnd == 0L) return
        val px = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(0)
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(hwnd, px)
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    /**
     * Publishes [windowsTextureHostState] for the scene's `TextureView`s.
     * Called from [attach], once `hwnd` and `directContext` are both set.
     */
    private fun publishWindowsTextureHost() {
        if (hwnd == 0L) return
        val ctx = directContext ?: return
        val outer = this
        windowsTextureHostState.value =
            object : TaoWindowsTextureHost {
                override val hostHwnd: Long get() = outer.hwnd
                override val directContext: DirectContext = ctx

                override fun requestRedraw() = outer.window.requestRedraw()

                override fun <T> withContextCurrent(block: () -> T): T? {
                    // Read live: 0 once the host detached, which keeps a late
                    // caller off a freed attachment (the Linux twin's rule).
                    val handle = outer.attachmentHandle
                    if (handle == 0L) return null
                    return preservingAngleBinding {
                        NativeTaoGlBridge.nativeMakeCurrent(handle)
                        block()
                    }
                }
            }
    }

    /**
     * #569: the client origin `nativeSetFrameInWindow` adds via
     * `ClientToScreen`, paired with every display's work area — so a popup
     * layer can clamp against the display it actually lands on instead of the
     * work-area-sized virtual screen Compose positions it in.
     *
     * Both halves are live reads rather than a cached snapshot: the layers
     * re-clamp on every owner move, so a window dragged to another monitor
     * re-resolves the display too.
     */
    private fun resolvePopupScreenGeometry(): PopupScreenGeometry? {
        if (!NativeTaoWindowsDecoBridge.isLoaded) return null
        val origin =
            NativeTaoWindowsDecoBridge
                .nativeClientToScreen(hwnd, 0, 0)
                ?.takeIf { it.size >= 2 }
                ?: return null
        val areas = TaoMonitors.all(window).map { it.workAreaPx }.ifEmpty { return null }
        return PopupScreenGeometry(
            parentContentOriginPx = IntOffset(origin[0], origin[1]),
            workAreasPx = areas,
        )
    }

    fun popupHost(): TaoPopupHostWindows? {
        if (hwnd == 0L) return null
        val ctx = directContext ?: return null
        val outer = this
        return object : TaoPopupHostWindows {
            override val parentHwnd: Long get() = outer.hwnd
            override val scale: Float get() = outer.scale
            override val isOwnerWindowTransparent: Boolean get() = outer.fullyTransparent
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val workAreaSize: IntSize get() {
                if (!NativeTaoWindowsDecoBridge.isLoaded) return parentWindowSize
                val area =
                    NativeTaoWindowsDecoBridge.nativeOwnerMonitorWorkArea(outer.hwnd)
                        ?: NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                        ?: return parentWindowSize
                if (area.size < 4) return parentWindowSize
                val w = area[2].toInt().coerceAtLeast(1)
                val h = area[3].toInt().coerceAtLeast(1)
                return IntSize(w, h)
            }

            override val popupScreenGeometry: PopupScreenGeometry?
                get() = outer.resolvePopupScreenGeometry()
            override val sceneCoroutineContext: kotlin.coroutines.CoroutineContext
                get() = outer.coroutineContext + outer.flushingDispatcher
            override val hostDirectContext: DirectContext get() = ctx

            override val exceptionHandler: WindowExceptionHandler?
                get() = outer.exceptionHandler

            override fun requestRedraw() = outer.window.requestRedraw()

            override fun registerRenderer(
                token: Any,
                render: () -> Unit,
            ) {
                outer.popupRenderers[token] = render
                // The renderer binds its own pbuffer surface between host
                // frames, leaving Skia's GL state cache stale — flag the
                // host context dirty so the next frame resets it.
                outer.hostContextDirtied = true
            }

            override fun unregisterRenderer(token: Any) {
                outer.popupRenderers.remove(token)
                outer.hostContextDirtied = true
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

            override fun registerOwnerFocusLostListener(
                token: Any,
                onLost: () -> Unit,
            ) {
                outer.ownerFocusLostListeners[token] = onLost
            }

            override fun unregisterOwnerFocusLostListener(token: Any) {
                outer.ownerFocusLostListeners.remove(token)
            }

            override fun registerOwnerFocusGainedListener(
                token: Any,
                onGained: () -> Unit,
            ) {
                outer.ownerFocusGainedListeners[token] = onGained
            }

            override fun unregisterOwnerFocusGainedListener(token: Any) {
                outer.ownerFocusGainedListeners.remove(token)
            }

            override fun notifyPopupClosing() {
                if (outer.popupClosingListeners.isEmpty()) return
                for (cb in outer.popupClosingListeners.values.toList()) cb()
            }

            override fun registerPopupClosingListener(
                token: Any,
                onClosing: () -> Unit,
            ) {
                outer.popupClosingListeners[token] = onClosing
            }

            override fun unregisterPopupClosingListener(token: Any) {
                outer.popupClosingListeners.remove(token)
            }
        }
    }

    /** Fired by the [TaoWindow.onMoved] hook installed in [attach]. */
    private fun onOwnerMoved() {
        if (ownerMoveListeners.isEmpty()) return
        for (cb in ownerMoveListeners.values.toList()) cb()
    }

    fun nativeViewHost(): dev.nucleusframework.window.tao.TaoNativeViewHost? {
        if (hwnd == 0L) return null
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge.isLoaded) return null
        val parent = hwnd
        val outer = this
        return object : dev.nucleusframework.window.tao.TaoNativeViewHost {
            override fun attach(
                childHandle: Long,
                regionToken: Any,
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                    .nativeAttach(parent, childHandle)
                outer.nativeViewBlending.retain()
            }

            override fun detach(
                childHandle: Long,
                regionToken: Any,
            ) {
                outer.nativeViewBlending.removeRect(regionToken)
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                    .nativeDetach(childHandle)
                outer.nativeViewBlending.release()
            }

            override fun setFrame(
                handle: Long,
                xPx: Int,
                yPx: Int,
                widthPx: Int,
                heightPx: Int,
                regionToken: Any,
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                    .nativeSetFrame(parent, handle, xPx, yPx, widthPx, heightPx)
                outer.nativeViewBlending.setRect(regionToken, xPx, yPx, widthPx, heightPx)
            }

            override fun setCornerRadius(
                handle: Long,
                radiusPx: Float,
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                    .nativeSetCornerRadius(parent, handle, radiusPx)
            }

            override fun dispatchPointerToNative(
                handle: Long,
                type: Int,
                xPx: Float,
                yPx: Float,
                button: Int,
                pressed: Boolean,
            ) {
                if (parent == 0L) return
                outer.nativePointerRedispatchInFlight = true
                try {
                    dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                        .nativeDispatchPointer(parent, handle, type, xPx, yPx, button, pressed)
                } finally {
                    outer.nativePointerRedispatchInFlight = false
                }
            }

            override fun dispatchScrollToNative(
                handle: Long,
                xPx: Float,
                yPx: Float,
                dx: Float,
                dy: Float,
            ) {
                if (parent == 0L) return
                outer.nativePointerRedispatchInFlight = true
                try {
                    dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
                        .nativeDispatchScroll(parent, handle, xPx, yPx, dx, dy)
                } finally {
                    outer.nativePointerRedispatchInFlight = false
                }
            }
        }
    }

    private inner class BlendingHost : WindowsNativeViewBlendingOverlay.Host {
        override val hwnd: Long get() = this@TaoComposeSceneHostWindows.hwnd
        override val widthPx: Int get() = this@TaoComposeSceneHostWindows.widthPx
        override val heightPx: Int get() = this@TaoComposeSceneHostWindows.heightPx
        override val popupRenderers: MutableMap<Any, () -> Unit>
            get() = this@TaoComposeSceneHostWindows.popupRenderers
        override var hostContextDirtied: Boolean
            get() = this@TaoComposeSceneHostWindows.hostContextDirtied
            set(value) {
                this@TaoComposeSceneHostWindows.hostContextDirtied = value
            }

        override fun requestRedraw() {
            window.requestRedraw()
        }

        override fun registerOwnerMoveListener(
            token: Any,
            onMoved: () -> Unit,
        ) {
            this@TaoComposeSceneHostWindows.ownerMoveListeners[token] = onMoved
        }

        override fun unregisterOwnerMoveListener(token: Any) {
            this@TaoComposeSceneHostWindows.ownerMoveListeners.remove(token)
        }

        override fun renderBlendingFrame(
            overlayHandle: Long,
            clipRectsPx: FloatArray,
        ) {
            val bundle = sceneBundle
            val ctx = directContext
            if (bundle == null || ctx == null) return
            if (this@TaoComposeSceneHostWindows.widthPx <= 0 ||
                this@TaoComposeSceneHostWindows.heightPx <= 0
            ) {
                return
            }
            if (!NativeTaoWindowsOverlayBridge.nativeMakeCurrent(overlayHandle)) return
            ctx.resetGLAll()
            renderGlFrame(
                widthPx = this@TaoComposeSceneHostWindows.widthPx,
                heightPx = this@TaoComposeSceneHostWindows.heightPx,
                directContext = ctx,
                clearColorArgb = 0,
                // The blending overlay is unconditionally a per-pixel-alpha
                // DComp swapchain (DXGI_ALPHA_MODE_PREMULTIPLIED) regardless of
                // the window's own transparency — no LCD SurfaceProps.
                windowTransparent = true,
                present = { NativeTaoWindowsOverlayBridge.nativeSwapBuffers(overlayHandle) },
            ) { canvas, nanoTime ->
                // Clip to the union of NativeView rects — SetWindowRgn
                // already hides everything outside them, so the second
                // scene pass only rasterizes the pixels the overlay shows.
                // Aliased clip to match the region's hard integer edges.
                if (clipRectsPx.size == 4) {
                    canvas.clipRect(
                        Rect.makeXYWH(
                            clipRectsPx[0],
                            clipRectsPx[1],
                            clipRectsPx[2],
                            clipRectsPx[3],
                        ),
                    )
                } else {
                    val builder = PathBuilder()
                    var i = 0
                    while (i < clipRectsPx.size) {
                        builder.addRect(
                            Rect.makeXYWH(
                                clipRectsPx[i],
                                clipRectsPx[i + 1],
                                clipRectsPx[i + 2],
                                clipRectsPx[i + 3],
                            ),
                        )
                        i += 4
                    }
                    builder.detach().use { clip -> canvas.clipPath(clip) }
                }
                bundle.render(canvas, nanoTime)
            }
        }

        override fun onBlendingPointer(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) {
            lastPointerX = x
            lastPointerY = y
            currentKeyboardModifiers = taoKeyboardModifiers(modifiers)
            windowInfo.keyboardModifiers = currentKeyboardModifiers
            val pointerButton =
                when (button) {
                    1 -> PointerButton.Primary
                    2 -> PointerButton.Secondary
                    3 -> PointerButton.Tertiary
                    else -> null
                }
            val eventType =
                when (type) {
                    1 -> PointerEventType.Press
                    2 -> PointerEventType.Release
                    else -> PointerEventType.Move
                }
            // Same sub-pixel deadband as the main stream (#615) — the
            // overlay WndProc shares the scene's single mouse pointer.
            if (eventType == PointerEventType.Move &&
                !pointerDeadband.shouldDispatchMove(x, y, scale)
            ) {
                return
            }
            scene?.sendPointerEvent(
                eventType = eventType,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                button = pointerButton,
                keyboardModifiers = currentKeyboardModifiers,
            )
        }

        override fun onBlendingScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        ) {
            lastPointerX = x
            lastPointerY = y
            // Track the position through the deadband so the scroll lands
            // where the scene last saw the pointer (#615).
            pointerDeadband.shouldDispatchMove(x, y, scale)
            // Overlay WndProc reports raw Win32 units; map like TaoWindow
            // then go through the same AWT-shaped dispatch as the window.
            scene?.dispatchAwtShapedScroll(
                x = pointerDeadband.x,
                y = pointerDeadband.y,
                event = win32WheelToAwtScrollEvent(dx, dy),
                keyboardModifiers = currentKeyboardModifiers,
            )
        }
    }

    // Hop the debounced semantics walk onto the render thread (it touches
    // Compose state) and request a redraw. See AbstractTaoComposeSceneHost.
    override fun dispatchA11yWalk(block: () -> Unit) {
        flushingDispatcher.enqueue(Runnable { block() })
        window.requestRedraw()
    }

    /**
     * Clear colour for the next present: backdrop tint while a system material
     * is armed, otherwise the resolved clear (alpha-0 for fully transparent
     * windows, themed otherwise).
     */
    private fun resolveClientClearArgb(): Int =
        if (transparentBackgroundState.value) {
            backdropTintArgbState.value
        } else {
            clearColorArgbState.value
        }

    /**
     * Reverts an active backdrop and presents one last opaque themed frame,
     * synchronously. Called from [TaoWindow.onPrepareClose] / [TaoWindow.requestClose]
     * on the **confirmed destroy** path only — not from cancelable
     * [TaoWindow.onCloseRequested] (caption X, Alt+F4), where a permanent
     * teardown would leave a still-composed [dev.nucleusframework.window.WindowsBackdrop]
     * dead after the user cancels.
     *
     * While a backdrop is active the render loop clears to the tint layer over
     * the DWM material (often alpha 0 for Mica — the raw material shows through).
     * Once [nativePrepareClose] reverts the DWM backdrop that transparent clear
     * stops compositing over a material and reads as black during the fade-out —
     * a dark flash, worst on light themes. Flipping
     * [transparentBackgroundState] off for this one frame makes the clear fall
     * back to [clearColorArgbState] (the opaque themed background), so the
     * fade-out snapshots an opaque window.
     *
     * Idempotent; a later detach() finds nothing to do.
     */
    fun prepareClose() {
        if (hwnd == 0L || !transparentBackgroundState.value) return
        NativeTaoWindowsDecoBridge.nativePrepareClose(hwnd)
        // Render the close frame with the opaque themed clear, not the
        // backdrop tint: the backdrop was just reverted above, so a transparent
        // clear would composite as black during the fade-out.
        transparentBackgroundState.value = false
        // Never let a teardown render take the close down with it.
        @Suppress("TooGenericExceptionCaught")
        try {
            onRedrawRequested()
        } catch (t: RuntimeException) {
            // Swallow: the window is being destroyed anyway.
            val ignored = t
        }
    }

    fun detach() {
        window.showHook = null
        window.inboundDragAndDropNode = null
        window.imePreedit = null
        window.imeCommit = null
        imeSession.onInputSession(null)
        nativeViewBlending.destroyOverlay()
        shutdownA11yScheduler()
        textToolbar.hide()
        // Stop the pinch idle timer; the scene is going away so no Release needed.
        pinchEndJob?.cancel()
        pinchEndJob = null
        pinchActive = false
        gestureScope.cancel()
        // Make THIS host's ES context current before tearing down Skia
        // resources. A sibling host (e.g. the main window opened while this
        // one — the onboarding window — closes) may have left its own
        // EGLContext current on the shared event-loop thread after its last
        // frame. Destroying our scene + DirectContext against a foreign
        // context makes Skia issue glDelete* on the wrong context and faults
        // inside the driver (0xC0000005). Same defensive make-current as
        // onRedrawRequested.
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)
        }
        sceneBundle?.close()
        window.clearContentMeasurer()
        sceneBundle = null
        if (directContext != null) {
            // Belt for TextureView imports a leaked composition may still hold:
            // scene.close() above released the leases of every live one. They
            // must go before the context they were adopted into.
            directContext?.let(::releaseWindowsTextureImports)
            windowsTextureHostState.value = null
            directContext?.close()
            directContext = null
            attachedHostCount.decrementAndGet()
        }
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
        if (hwnd != 0L) {
            if (dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge.isLoaded) {
                dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
                    .nativeRevoke(hwnd)
            }
            NativeTaoWindowsDecoBridge.nativeUninstallDecoration(hwnd)
            hwnd = 0L
        }
    }

    internal companion object {
        /**
         * Floor between two frames painted from inside `DoDragDrop`'s modal
         * loop — see [OutboundDragPump]. ~8 ms leaves headroom above 120 Hz
         * while still collapsing the burst of mouse-move callbacks Windows
         * fires between two VBlanks.
         */
        private const val MIN_DRAG_FRAME_INTERVAL_NANOS: Long = 8_000_000L

        // Wire scales — must match Rust `CURSOR_FIXED_SCALE` and
        // `TOUCH_FORCE_FIXED_SCALE` in `events.rs`.
        private const val TOUCH_POSITION_SCALE: Float = 1024f
        private const val TOUCH_FORCE_SCALE: Float = 10_000f

        /**
         * Trackpad pinch (Ctrl+wheel → magnify) wire scale — matches Rust
         * `TRACKPAD_VALUE_FIXED_SCALE` in `events.rs`.
         */
        private const val TRACKPAD_VALUE_SCALE: Float = 10_000f

        /** Half-distance of the synthetic two-finger pair at scale 1.0. */
        private const val PINCH_BASE_RADIUS_PX: Float = 120f

        /**
         * GPU resource cache budget for the host DirectContext. Bounds the
         * per-frame scratch (wrapped-framebuffer stencil/attachments) so an
         * uncapped resize flood — VSync is dropped during the OS modal
         * resize/move loop — can't grow the process unbounded. Sized to cover
         * a HiDPI window's render target plus Compose's layer/glyph caches
         * with headroom, while still far below the >1 GB the leak reached.
         */
        private const val RESOURCE_CACHE_LIMIT_BYTES: Long = 256L * 1024 * 1024

        /**
         * Gap between in-drag GPU cache purges during the OS modal
         * resize/move loop. Every frame of the drag mints render-target
         * scratch (stencil/attachments) at a size no later frame reuses;
         * the periodic limit-toggle purge in [onResized] releases that
         * accumulation mid-drag so the peak stays bounded even for long
         * drags, without skipping any resize frame (a skipped frame is
         * composited by DWM as a geometry/content mismatch — trembling).
         */
        private const val RESIZE_PURGE_INTERVAL_NS: Long = 250_000_000L

        // Stable ids well clear of real touch ids (raw WM_POINTER finger ids).
        private const val PINCH_POINTER_ID_A: Long = 0xA001L
        private const val PINCH_POINTER_ID_B: Long = 0xA002L

        /** Idle gap after the last tick before the synthetic pinch releases. */
        private const val PINCH_IDLE_END_MS: Long = 120L

        /**
         * Live attached-host count across the JVM. When > 1, every host
         * shares the process with at least one sibling that owns its own
         * EGLContext and DirectContext (e.g., main window + DecoratedDialog).
         * Skia's per-DirectContext GL state cache can drift any time the
         * other host's onRedrawRequested swaps the EGL binding behind our
         * back, so we resetGLAll on every frame entry in that regime.
         * The flag-gated path stays for the single-host case to keep the
         * single-window hot path cheap.
         *
         * internal: standalone popup hosts (TaoStandalonePopupHost) share
         * the process EGL context too and register themselves here so window
         * hosts re-sync their Skia GL state cache.
         */
        internal val attachedHostCount =
            java
                .util
                .concurrent
                .atomic
                .AtomicInteger(0)
    }

    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: KCoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
            window.requestRedraw()
        }

        fun enqueue(block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
        }
    }
}

@OptIn(InternalComposeUiApi::class)
private class WindowsTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    /** Live px-per-dp factor of the owning scene — see [TaoPlatformContextBase.sceneScale]. */
    private val scaleProvider: () -> Float,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
    /** Publishes the active text-input session to the host's [TaoImeSession] (#558). */
    private val onInputSession: (androidx.compose.ui.platform.PlatformTextInputMethodRequest?) -> Unit = {},
    // #559: forwarded to Compose so `CanvasLayersComposeScene` picks the
    // alpha-aware dialog-scrim blend mode (`BlendMode.SrcAtop`) on windows
    // created with `transparent = true` — same as Compose Desktop's
    // `DesktopPlatformContext` forwarding `windowContext.isWindowTransparent`.
    override val isWindowTransparent: Boolean = false,
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
     * Keeps IMM32 anchored to the caret for as long as a field owns the input
     * (#558).
     *
     * The macOS twin also has to activate the view's `NSTextInputContext`
     * first; Windows needs no such step, because the HWND already owns an
     * input context. So this only mirrors the caret rect, through the same
     * `nativeSetImeRect` contract.
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
        NativeTaoBridge.nativeSetCursorIcon(
            windowHandle,
            mapPointerIcon(pointerIcon),
        )
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int = icon.toTaoCursorIconCode()
}
