@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.NativeTaoBridge
import dev.nucleusframework.window.tao.NativeTaoGlBridge
import dev.nucleusframework.window.tao.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTouchEvent
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
@OptIn(InternalComposeUiApi::class)
@Suppress("LargeClass", "TooManyFunctions")
internal class TaoComposeSceneHostWindows(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) {
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

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

    private val windowInfo = WindowsTaoWindowInfo()
    private var currentKeyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
    private var attachmentHandle: Long = 0
    private var hwnd: Long = 0
    private var directContext: DirectContext? = null

    private var scene: ComposeScene? = null

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null
    private val frameClock = BroadcastFrameClock()
    private val flushingDispatcher = FlushingMainDispatcher()

    /**
     * Scope for host-owned gesture work (trackpad-pinch idle-end debounce).
     * Runs on [flushingDispatcher] so resumed continuations land on the
     * event-loop thread; `delay` itself ticks on the shared coroutines
     * scheduler. Cancelled in [detach].
     */
    private val gestureScope =
        CoroutineScope(coroutineContext + flushingDispatcher + frameClock + SupervisorJob())

    /** Floating text-selection bar shown on touch selection. */
    private val textToolbar = TaoTextToolbar()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    /** True while the OS modal resize/move loop is active. */
    private var resizeLoopActive: Boolean = false

    /** Monotonic ns of the last applied resize frame; gates the WM_SIZE flood. */
    private var lastResizeApplyNs: Long = 0L

    /** A size change awaits push into the GL surface + ComposeScene at the next paint. */
    private var pendingResizeApply: Boolean = false

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

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

        // Install custom decoration (WndProc subclass + DwmExtendFrameIntoClientArea).
        // Title-bar height is set later — the value the TitleBar composable publishes
        // via SideEffect arrives after first composition.
        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f
        val initialTitleBarPx = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(28)
        NativeTaoWindowsDecoBridge.nativeInstallDecoration(hwnd, initialTitleBarPx)

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
        directContext = (ctx ?: error("Failed to create Skia DirectContext on the ANGLE ES context")).also {
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
            dev.nucleusframework.window.tao.TaoDragAndDropManager(
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
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
                textToolbar = textToolbar,
            )
        scene =
            if (nativePopupLayers) {
                // Opt-in path (e.g. tray popups): every Popup becomes a
                // transparent WS_POPUP HWND owned by this window, so popup
                // content can extend beyond — and float independently of —
                // the window bounds. popupHost() is non-null here: hwnd and
                // directContext were both set above.
                PlatformLayersComposeScene(
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    coroutineContext = coroutineContext + frameClock + flushingDispatcher,
                    composeSceneContext =
                        TaoComposeSceneContextWindows(
                            platformContext = platformContext,
                            popupHost = requireNotNull(popupHost()),
                        ),
                    invalidate = { window.requestRedraw() },
                ).apply { compositionLocalContext = pendingCompositionLocalContext }
            } else {
                CanvasLayersComposeScene(
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    coroutineContext = coroutineContext + frameClock + flushingDispatcher,
                    platformContext = platformContext,
                    invalidate = { window.requestRedraw() },
                ).apply { compositionLocalContext = pendingCompositionLocalContext }
            }

        registerInboundDnD()
        registerTouchInput()

        // Notify overlay/popup layers when the host window moves on screen
        // — top-level WS_POPUP children of the owner don't auto-track.
        window.onMoved { _, _ -> onOwnerMoved() }

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
        window.onTouchInput { phase, id, xFixed, yFixed, forceFixed ->
            onTouchInput(phase, id, xFixed, yFixed, forceFixed)
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
        request: dev.nucleusframework.window.tao.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) return null
        if (hwnd == 0L) return null

        val allowed =
            request.supportedActions
                .fold(0) { acc, action ->
                    acc or
                        when (action) {
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_MOVE
                            androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link ->
                                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_LINK
                            else -> 0
                        }
                }.let {
                    if (it == 0) {
                        dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
                    } else {
                        it
                    }
                }

        val files =
            request.files
                .takeIf { it.isNotEmpty() }
                ?.map { it.absolutePath }
                ?.toTypedArray()
        val effect =
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.nativeStartDrag(
                hwnd = hwnd,
                files = files,
                text = request.text,
                allowedEffects = allowed,
            )
        return when (effect) {
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_MOVE ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_LINK ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link
            else -> null
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "windows DnD lib not loaded — inbound disabled",
            )
            return
        }
        val callback = InboundDnDCallback()
        val rc =
            dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge
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
        dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.Callback {
        private fun rootNode() = scene?.rootDragAndDropNode

        private fun makeDragEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload =
                dev.nucleusframework.window.tao.TaoDragAndDropPayload(
                    files = files?.toList() ?: emptyList(),
                )
            val transferable =
                dev.nucleusframework.window.tao.TaoFilesTransferable(
                    files = payload.files.map { java.io.File(it) },
                )
            val native =
                dev.nucleusframework.window.tao.TaoSyntheticDragEvent(
                    cursorLocn = java.awt.Point(xPx, yPx),
                    dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl =
                    androidx.compose.ui.geometry
                        .Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        private fun makeDropEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload =
                dev.nucleusframework.window.tao.TaoDragAndDropPayload(
                    files = files?.toList() ?: emptyList(),
                )
            val transferable =
                dev.nucleusframework.window.tao.TaoFilesTransferable(
                    files = payload.files.map { java.io.File(it) },
                )
            val native =
                dev.nucleusframework.window.tao.TaoSyntheticDropEvent(
                    cursorLocn = java.awt.Point(xPx, yPx),
                    dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                    backingTransferable = transferable,
                    payload = payload,
                )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl =
                    androidx.compose.ui.geometry
                        .Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

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
                return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            val accepted = node.acceptDragAndDropTransfer(ev)
            if (accepted) {
                node.onStarted(ev)
                node.onEntered(ev)
            }
            return if (accepted) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int {
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            node.onMoved(ev)
            return if (node.hasEligibleDropTarget) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragLeave(hwnd: Long) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics
                .log("onDragLeave")
            val node = rootNode() ?: return
            val ev = makeDragEvent(-1, -1, null)
            node.onExited(ev)
            node.onEnded(ev)
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
            val node =
                rootNode()
                    ?: return dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDropEvent(x, y, files)
            val accepted = node.onDrop(ev)
            node.onEnded(ev)
            return if (accepted) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent {
            // Stock Compose Desktop Windows wheel behavior; only the
            // lines-per-notch factor is reapplied (see TaoWindowsScrollConfig).
            ProvideTaoWindowsScrollConfig {
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
        // Win32 emits WM_SIZE/SIZE_MINIMIZED as 0x0. Keep the last real
        // ComposeScene size so taskbar previews and restore do not collapse.
        if (widthPxNew <= 0 || heightPxNew <= 0) return
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        // The GL surface child + ComposeScene size are pushed in
        // onRedrawRequested (see the pendingResizeApply block there), so a
        // throttled or async paint always renders the freshest size and keeps
        // the surface resize + present atomic (no black edge).
        pendingResizeApply = true

        // Cap the resize render/remeasure rate during the OS modal resize/move
        // loop. VSync paces the present at the display refresh (see
        // onResizeLoopChanged), but a fast border drag still floods WM_SIZE, so
        // coalesce: only let a resize frame through every RESIZE_APPLY_INTERVAL_NS.
        // Every scene remeasure rebuilds size-dependent content whose Skia-backed
        // native objects are reclaimed lazily by the skiko Cleaner after a GC;
        // the coalesced trailing size is flushed by the async redraw below and,
        // on drag end, by onResizeLoopChanged.
        if (resizeLoopActive) {
            val now = System.nanoTime()
            if (now - lastResizeApplyNs < RESIZE_APPLY_INTERVAL_NS) {
                window.requestRedraw()
                return
            }
            lastResizeApplyNs = now
        }
        onRedrawRequested()
    }

    /**
     * Enter/leave the OS modal resize/move loop (WM_ENTERSIZEMOVE /
     * WM_EXITSIZEMOVE). VSync stays **enabled** throughout — the per-WM_SIZE
     * present paces off the display refresh, exactly like macOS (CVDisplayLink)
     * and the Linux EGL swap thread. Dropping VSync here let the modal loop
     * render at ~1 kHz: every new window size minted a fresh
     * BackendRenderTarget + Surface whose Skia GPU scratch and Compose layer
     * backings are reclaimed only lazily, and under native-image's Serial GC
     * that reclamation never caught up for lean apps — the process climbed
     * past 1 GB and stayed there. Pacing the resize at the display rate keeps
     * allocation within what the GC/Cleaner can reclaim, matching the
     * non-leaking platforms. Every WM_SIZE is still resized and painted
     * atomically; allowing the scene size to advance while the ANGLE child
     * surface remains at an older size makes DWM stretch the old frame and
     * visibly shifts the title bar.
     */
    fun onResizeLoopChanged(active: Boolean) {
        if (attachmentHandle == 0L) return
        resizeLoopActive = active
        if (active) {
            // VSync stays on — see the doc above. The throttle in [onResized]
            // coalesces the WM_SIZE flood so only display-rate-paced frames
            // actually render.
        } else {
            // Flush the settled size once: the last WM_SIZE of the drag may have
            // been coalesced by the throttle in onResized, so force the final
            // dimensions into the surface + scene and paint them.
            pendingResizeApply = true
            onRedrawRequested()
            // Reclaim the per-size scratch (stencil/render-target attachments)
            // accumulated across the drag. Toggling the limit to 0 runs
            // purgeAsNeeded synchronously, freeing every unlocked resource; the
            // next frame re-mints only what the final size needs. Without this
            // the drag's peak footprint is released only by a later GC.
            directContext?.let {
                it.resourceCacheLimit = 0
                it.resourceCacheLimit = RESOURCE_CACHE_LIMIT_BYTES
            }
        }
    }

    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
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

    fun onRedrawRequested() {
        val ctx = directContext ?: return
        val sc = scene ?: return

        if (widthPx <= 0 || heightPx <= 0) return

        // Push a pending size into the ComposeScene + GL surface before the
        // frame-clock drain, so the size-change-driven recomposition (and any
        // coroutine keyed on the new size) is scheduled and drained this frame.
        // `nativeResize` grows the render-surface child HWND; doing it here,
        // in the same paint that presents, keeps the surface resize and the
        // present atomic — no exposed-strip black edge (the reason the old
        // onResized painted synchronously). resetGLAll after nativeResize is
        // unnecessary: the ES context/surface stay bound on this thread.
        if (pendingResizeApply) {
            sc.size = IntSize(widthPx, heightPx)
            updateWindowInfoSize()
            NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
            pendingResizeApply = false
        }

        val now = System.nanoTime()

        // ── Frame clock ordering ──────────────────────────────────────────
        // Tick the frame clock BEFORE rendering and drain twice. Without this
        // the smooth-scroll animation (and any other `withFrameNanos`-driven
        // animation) lags one frame behind: `sendFrame` resumes the awaiting
        // continuations which then mutate state, but if we render first the
        // composition reads the *previous* frame's state. JNI / Skiko's
        // default loop ticks before render, so to match that feel we mirror
        // the order here.
        flushingDispatcher.drain()
        frameClock.sendFrame(now)
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
        val surface =
            Surface.makeFromBackendRenderTarget(
                context = ctx,
                rt = rt,
                origin = SurfaceOrigin.BOTTOM_LEFT,
                colorFormat = SurfaceColorFormat.RGBA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: run {
                rt.close()
                return
            }

        try {
            surface.canvas.clear(0xFFFFFFFF.toInt())
            sc.render(surface.canvas.asComposeCanvas(), now)
            // `flushAndSubmit` issues the glFlush that commits the frame to
            // the back buffer; the present happens below, after the overlay/
            // popup renderers (they only need the flush, not the present).
            surface.flushAndSubmit(syncCpu = false)
        } finally {
            surface.close()
            rt.close()
        }

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

        // Present inline. nativePresent defensively re-binds the host's
        // window surface first (a popup renderer may have left its pbuffer
        // current) and eglSwapBuffers paces on the display refresh.
        NativeTaoGlBridge.nativePresent(attachmentHandle)
    }

    fun onPointerMove(
        aFixed: Int,
        bFixed: Int,
    ) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(xPx, yPx),
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
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    fun onPointerButton(
        buttonCode: Int,
        pressed: Boolean,
    ) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = mapButton(buttonCode),
        )
    }

    fun onPointerScroll(event: TaoPointerScrollEvent) {
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
        scene?.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(lastPointerX, lastPointerY),
            scrollDelta = Offset(event.dxAwt, event.dyAwt),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            nativeEvent =
                TaoSyntheticMouseWheelEvent.create(
                    event = event,
                    x = lastPointerX,
                    y = lastPointerY,
                    keyboardModifiers = currentKeyboardModifiers,
                ),
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

    fun setTitleBarBackgroundColor(argb: Int) {
        if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativeSetBackgroundColor(hwnd, argb)
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    fun popupHost(): TaoPopupHostWindows? {
        if (hwnd == 0L) return null
        val ctx = directContext ?: return null
        val outer = this
        return object : TaoPopupHostWindows {
            override val parentHwnd: Long get() = outer.hwnd
            override val scale: Float get() = outer.scale
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val workAreaSize: IntSize get() {
                // Use the primary monitor's work area resolved via the
                // existing JNI bridge — avoids touching AWT
                // (GraphicsEnvironment.getLocalGraphicsEnvironment) on the
                // Tao UI thread, which on Windows can lazily initialise
                // Java2D's D3D pipeline and conflict with the ES context
                // bound to this thread (manifested as a hang + crash when
                // a second host attached, e.g. on DecoratedDialog open).
                if (!NativeTaoWindowsDecoBridge.isLoaded) return parentWindowSize
                val area =
                    NativeTaoWindowsDecoBridge.nativeGetPrimaryMonitorWorkArea()
                        ?: return parentWindowSize
                if (area.size < 4) return parentWindowSize
                val w = area[2].toInt().coerceAtLeast(1)
                val h = area[3].toInt().coerceAtLeast(1)
                return IntSize(w, h)
            }
            override val sceneCoroutineContext: kotlin.coroutines.CoroutineContext
                get() = outer.coroutineContext + outer.frameClock + outer.flushingDispatcher
            override val hostDirectContext: DirectContext get() = ctx

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
        if (!dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge.isLoaded) return null
        val parent = hwnd
        return object : dev.nucleusframework.window.tao.TaoNativeViewHost {
            override fun attach(childHandle: Long) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeAttach(parent, childHandle)
            }

            override fun detach(childHandle: Long) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeDetach(childHandle)
            }

            override fun setFrame(
                handle: Long,
                xPx: Int,
                yPx: Int,
                widthPx: Int,
                heightPx: Int,
            ) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeSetFrame(parent, handle, xPx, yPx, widthPx, heightPx)
            }

            override fun setCornerRadius(
                handle: Long,
                radiusPx: Float,
            ) {
                dev.nucleusframework.window.tao.NativeTaoWindowsNativeViewBridge
                    .nativeSetCornerRadius(parent, handle, radiusPx)
            }
        }
    }

    // A11y sync is debounced on a timer rather than run once per render tick.
    // The SemanticsOwner walk in TaoSemanticsObserver is O(N); during a scroll
    // `onLayoutChange`/`onSemanticsChange` fire every frame, so a per-frame walk
    // stutters scrolling — most visibly once a UIA client (Narrator, NVDA) is
    // attached. Debouncing collapses a burst of changes into a single walk once
    // activity settles (trailing edge), with a max-wait so sustained activity
    // still refreshes the tree periodically for assistive tech. The tree
    // therefore stays fresh enough for on-demand AX queries without ever
    // running on the per-frame hot path. Mirrors the macOS [TaoComposeSceneHost].
    private val a11yScheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TaoA11yDebounce").apply { isDaemon = true }
        }

    @Volatile
    private var a11yPendingBlock: (() -> Unit)? = null

    @Volatile
    private var a11yFuture: ScheduledFuture<*>? = null
    private var a11yFirstRequestNs = 0L

    /**
     * Schedules [block] (a SemanticsOwner walk + snapshot push) to run on the
     * render thread after changes settle. Coalesces a burst of per-frame change
     * notifications into one debounced run; see the field comment above.
     */
    fun scheduleA11ySync(block: () -> Unit) {
        if (a11yScheduler.isShutdown) return
        a11yPendingBlock = block
        val now = System.nanoTime()
        if (a11yFirstRequestNs == 0L) a11yFirstRequestNs = now
        val waitedMs = (now - a11yFirstRequestNs) / 1_000_000L
        val delayMs = if (waitedMs >= A11Y_SYNC_MAX_WAIT_MS) 0L else A11Y_SYNC_DEBOUNCE_MS
        a11yFuture?.cancel(false)
        a11yFuture =
            try {
                a11yScheduler.schedule(
                    {
                        val b = a11yPendingBlock
                        a11yPendingBlock = null
                        a11yFirstRequestNs = 0L
                        if (b != null) {
                            // Hop to the render thread — the walk touches Compose state.
                            flushingDispatcher.enqueue(Runnable { b() })
                            window.requestRedraw()
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                null
            }
    }

    fun detach() {
        a11yFuture?.cancel(false)
        a11yScheduler.shutdownNow()
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
        scene?.close()
        scene = null
        if (directContext != null) {
            directContext?.close()
            directContext = null
            attachedHostCount.decrementAndGet()
        }
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
        if (hwnd != 0L) {
            if (dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
                dev.nucleusframework.window.tao.NativeTaoWindowsDndBridge
                    .nativeRevoke(hwnd)
            }
            NativeTaoWindowsDecoBridge.nativeUninstallDecoration(hwnd)
            hwnd = 0L
        }
    }

    internal companion object {
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
         * Minimum gap between applied resize frames during the OS modal
         * resize/move loop (~120 Hz). Caps the render/remeasure rate so a
         * high-poll-mouse WM_SIZE flood can't rebuild size-dependent content
         * (e.g. a lets-plot chart) uncapped and pile up Cleaner-freed native
         * memory. Well above the display refresh, so the drag stays smooth.
         */
        private const val RESIZE_APPLY_INTERVAL_NS: Long = 8_333_333L

        // Stable ids well clear of real touch ids (raw WM_POINTER finger ids).
        private const val PINCH_POINTER_ID_A: Long = 0xA001L
        private const val PINCH_POINTER_ID_B: Long = 0xA002L

        /** Idle gap after the last tick before the synthetic pinch releases. */
        private const val PINCH_IDLE_END_MS: Long = 120L

        // A11y debounce: run the SemanticsOwner walk ~this long after the last
        // change (so a scroll's per-frame change burst collapses to one walk
        // once it settles), but never wait longer than the max so assistive
        // tech still sees periodic refreshes during sustained scrolling.
        private const val A11Y_SYNC_DEBOUNCE_MS: Long = 120L
        private const val A11Y_SYNC_MAX_WAIT_MS: Long = 600L

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

    private fun mapButton(code: Int): PointerButton =
        when (code) {
            dev.nucleusframework.window.tao.TaoMouseButton.LEFT ->
                PointerButton.Primary
            dev.nucleusframework.window.tao.TaoMouseButton.RIGHT ->
                PointerButton.Secondary
            dev.nucleusframework.window.tao.TaoMouseButton.MIDDLE ->
                PointerButton.Tertiary
            else -> PointerButton.Primary
        }
}

internal class WindowsTaoWindowInfo : androidx.compose.ui.platform.WindowInfo {
    override var isWindowFocused: Boolean by androidx.compose.runtime.mutableStateOf(true)
    override var keyboardModifiers: PointerKeyboardModifiers
        by androidx.compose.runtime.mutableStateOf(PointerKeyboardModifiers())
    override var containerSize: IntSize by androidx.compose.runtime.mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by androidx.compose.runtime.mutableStateOf(DpSize.Zero)
}

@OptIn(InternalComposeUiApi::class)
private class WindowsTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
) : androidx.compose.ui.platform.PlatformContext.Empty() {
    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform
                    .PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        NativeTaoBridge.nativeSetCursorIcon(
            windowHandle,
            mapPointerIcon(pointerIcon),
        )
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int {
        when {
            icon === androidx.compose.ui.input.pointer.PointerIcon.Default ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Text ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.TEXT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Hand ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.HAND
            icon === androidx.compose.ui.input.pointer.PointerIcon.Crosshair ->
                return dev.nucleusframework.window.tao.TaoCursorIcon.CROSSHAIR
        }
        return runCatching {
            val cursor = icon.javaClass.getMethod("getCursor").invoke(icon) as? java.awt.Cursor
            when (cursor?.type) {
                java.awt.Cursor.TEXT_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.TEXT
                java.awt.Cursor.HAND_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.HAND
                java.awt.Cursor.CROSSHAIR_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.CROSSHAIR
                java.awt.Cursor.WAIT_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.WAIT
                java.awt.Cursor.MOVE_CURSOR -> dev.nucleusframework.window.tao.TaoCursorIcon.MOVE
                java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.EW_RESIZE
                java.awt.Cursor.N_RESIZE_CURSOR, java.awt.Cursor.S_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NS_RESIZE
                java.awt.Cursor.NE_RESIZE_CURSOR, java.awt.Cursor.SW_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NESW_RESIZE
                java.awt.Cursor.NW_RESIZE_CURSOR, java.awt.Cursor.SE_RESIZE_CURSOR ->
                    dev.nucleusframework.window.tao.TaoCursorIcon.NWSE_RESIZE
                else -> dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT
            }
        }.getOrDefault(dev.nucleusframework.window.tao.TaoCursorIcon.DEFAULT)
    }
}
