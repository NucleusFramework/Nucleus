@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import dev.nucleusframework.window.WindowTransparencyMode
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.MacOSStyle
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoFatalCoroutineExceptionHandler
import dev.nucleusframework.window.tao.TaoKeyLocation
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoNativeViewHost
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTrackpadGesture
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.clearContentMeasurer
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.taoKeyEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.event.taoTypedKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.initialMacOsScaleFactor
import dev.nucleusframework.window.tao.installContentMeasurer
import dev.nucleusframework.window.tao.popup.PopupScreenGeometry
import dev.nucleusframework.window.tao.popup.PopupScrimRegistry
import dev.nucleusframework.window.tao.popup.TaoPopupHost
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayer
import dev.nucleusframework.window.tao.render.LocalTaoTextSelectionA11yPublisher
import dev.nucleusframework.window.tao.render.TaoSelectionAccessibilityObserver
import dev.nucleusframework.window.tao.shouldApplyLargeCornerRadius
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Rect
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Drives a Compose scene onto a Tao-owned NSView via the Metal helper.
 *
 * Threading: every public method **must** run on the macOS main thread. The
 * Tao event loop calls us back there; pointer/redraw events are dispatched
 * synchronously to keep ordering. This class is *not* a generic
 * thread-safe component.
 *
 * Lifecycle:
 *  1. `attach()` once the Tao window has produced its first Resized event
 *     (so `ns_view_handle()` returns a valid pointer and we know the size).
 *  2. `setContent { ... }` to mount the user composable.
 *  3. Tao events are pumped via [onResized], [onPointerMove], [onPointerButton].
 *  4. [onRedrawRequested] renders one frame.
 *  5. [detach] tears everything down before the window is destroyed.
 *
 * Skiko/Compose APIs used here (`MultiLayerComposeScene`, `DirectContext.makeMetal`,
 * `BackendRenderTarget.makeMetal`, `Surface.makeFromBackendRenderTarget`) are
 * stable on the JVM target of Compose Multiplatform 1.10+. Some are annotated
 * `@InternalComposeUiApi`; we opt-in below.
 */
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Suppress("TooManyFunctions", "LargeClass")
internal class TaoComposeSceneHost(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val macOSStyle: MacOSStyle = MacOSStyle.Auto,
    // macOS-only: hide this app's Dock icon (app-wide activation policy).
    private val hiddenFromDock: Boolean = false,
    // Full-window per-pixel transparency (#416). Creation-time; pairs with
    // tao `with_transparent` so alpha-0 Skia clears show the desktop.
    private val fullyTransparent: Boolean = false,
) : AbstractTaoComposeSceneHost() {
    /** IME preedit / commit / PressAndHold routing (#595). */
    private val imeSession = TaoImeSession(::emitImeTypedFallback)

    /**
     * CoreTextField session not up yet: same gated sequence Compose AWT
     * uses (delete the replaced characters, then commit). Never used for
     * ordinary typing — only for a replacement commit that beat the
     * text-input session, where [deleteBefore] is the length the input
     * method asked to replace (0 for a pure insertion).
     */
    private fun emitImeTypedFallback(
        text: String,
        deleteBefore: Int,
    ) {
        repeat(deleteBefore) {
            onKeyEvent(TaoEventCode.KEY_DOWN, 8, TaoKeyLocation.STANDARD, 0, 0)
            onKeyEvent(TaoEventCode.KEY_UP, 8, TaoKeyLocation.STANDARD, 0, 0)
        }
        for (ch in text) {
            onKeyEvent(TaoEventCode.KEY_TYPED, 0, TaoKeyLocation.STANDARD, 0, ch.code)
        }
    }

    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    // ARGB clear color for the Skia surface. Defaults to opaque white to
    // preserve the AWT/Compose-Desktop look, but the window/theme and TitleBar
    // composables update it so any Compose region without an explicit
    // background — most visibly animation gaps around fullscreen/title-bar
    // transitions — matches the active chrome instead of flashing white.
    // Fully transparent windows start at alpha 0 so empty client areas show
    // the desktop before the first composition rewrites the style layer.
    val clearColorArgbState: androidx.compose.runtime.MutableState<Int> =
        androidx.compose.runtime.mutableStateOf(
            if (fullyTransparent) 0 else 0xFFFFFFFF.toInt(),
        )

    // Behind-window glass background (see NativeMetalBridge.nativeSetGlassBackground).
    // While active, the render loop clears the Skia surface to transparent so
    // the native glass material shows through wherever the Compose scene has
    // no opaque pixels; the themed clear color is ignored.
    val glassBackgroundState: androidx.compose.runtime.MutableState<Boolean> =
        androidx.compose.runtime.mutableStateOf(false)

    /**
     * App-level pre-dispatch hook. Receives every Compose [KeyEvent] before it
     * reaches the scene; returning `true` consumes the event and prevents
     * propagation. Mirrors AWT's `Window.setComponentZOrder`-pre-dispatch logic
     * used by the legacy AWT backend's `onPreviewKeyEvent`.
     */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * App-level post-dispatch hook. Fires only when the scene did not consume
     * the event. Returning `true` marks it as handled. Mirrors
     * the legacy AWT backend's `onKeyEvent`.
     */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Forwarded through the [TaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: PlatformContext.SemanticsOwnerListener? = null

    /**
     * When true, Compose Popup / DropdownMenu / Tooltip layers materialise as
     * native NSPanels ([TaoPopupSceneLayer]) instead of drawing inside this
     * window's Metal render target. Opt-in — see the Windows counterpart.
     * Set before [attach].
     */
    var nativePopupLayers: Boolean = false

    // Mirrors `PlatformWindowContext.desktop.kt` — Compose's `Popup` framework
    // reads `LocalWindowInfo.current.containerSize` to know how large the host
    // window is, which is the basis for the popup positioning math (see
    // `ContextMenuPopupPositionProvider.calculatePosition` and
    // `Popup.skiko.kt:positionWithInsets`). Without a real value the popup
    // collapses the available area to zero and consistently places itself
    // above the click → the "inverted" feel reported by users.
    private val windowInfo = TaoWindowInfo()
    private var currentKeyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers()
    private var attachmentHandle: Long = 0
    private var nsViewHandle: Long = 0
    private var directContext: DirectContext? = null
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene

    init {
        // Reads `scene` lazily, so it is valid before the bundle exists (null)
        // and across bundle swaps; cleared in dispose().
        window.installContentMeasurer { constraints -> scene?.measureContent(constraints) }
    }

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null

    // Dispatcher that funnels Compose's async work (notably MouseWheel scroll
    // dispatching, which uses the scene's coroutineContext) onto the render
    // thread. Without it, Compose attempts measure/layout from a worker
    // coroutine and throws "performMeasureAndLayout called during measure
    // layout".
    private val flushingDispatcher = FlushingMainDispatcher()

    /** Floating text-selection bar shown on touch selection. */
    private val textToolbar = TaoTextToolbar()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    // Sub-pixel deadband (#615): the wire delivers 1/1024-px positions and
    // macOS emits a CursorMoved before every mouseDown/mouseUp, so click
    // jitter under 1 dp must not reach the scene — Compose's mouse slop is
    // 0.125 dp, and a parent drag gesture consuming that phantom move
    // cancels the child's tap ("buttons need two clicks"). Every event
    // dispatched to the scene uses the deadband's position, never the raw
    // one (SyntheticEventSender would re-inject the difference).
    private val pointerDeadband = TaoPointerDeadband()

    // ── Interop transaction (mirrors UIKitInteropTransaction) ────────
    //
    // AppKit subview mutations made via the `NativeView` composable are
    // queued here and drained once per frame, atomically with the Metal
    // present, so a frame change on the embedded NSView can't visually
    // lag the Compose frame by one tick. Lifecycle:
    //  1. NativeView.attach/detach/setFrame/... on this host's
    //     `nativeViewHost()` → `transaction.add { ... }`
    //  2. onRedrawRequested retrieves + swaps the queue, sets
    //     `presentsWithTransaction` on the layer, and either drives
    //     `nativePresentWithInterop` (sync path) or the regular
    //     async `nativePresent` when no interop is active.

    private var transaction = MutableTaoInteropTransaction(isInteropActive = false)
    private var interopAttachCount: Int = 0

    /** Set by NativeView pointer-interop when a Press was forwarded to AppKit. */
    private var nativePointerDispatchedThisEvent: Boolean = false

    /** Renderer's view of whether interop is currently active — lags the
     *  transaction's flag by one frame on the OFF transition so the
     *  final sync flush still goes through `presentsWithTransaction`. */
    private var rendererIsInteropActive: Boolean = false

    /** Cached state of `CAMetalLayer.presentsWithTransaction` to avoid
     *  redundant JNI calls on every frame. */
    private var layerPresentsWithTransaction: Boolean = false

    private fun retrieveTransaction(): TaoInteropTransaction {
        val result = transaction
        transaction = MutableTaoInteropTransaction(isInteropActive = interopAttachCount > 0)
        return result
    }

    // Tracks whether Compose's pointer state believes the mouse is currently
    // down. We can't simply forward every Press / Release Tao gives us — on
    // macOS we observed at least one spurious Press event being delivered
    // very early (before the user could possibly have clicked, and without
    // a matching Release). Compose's PointerInputChangeEventProducer caches
    // that "still-down" state for the default PointerId(0); from then on
    // every real Press is reclassified as a Move-along-the-old-hit-path,
    // and clicks are routed to whatever element happened to be under the
    // phantom press's hit-test position rather than to the actual layout
    // under the cursor.
    //
    // Defensive contract: a Press received while already pressed first
    // emits a Release *of the stuck button* at the last known position to
    // close out the stale interaction, then emits the new Press. A Release
    // received while not pressed is dropped (Compose would otherwise crash
    // inside the input processor on a Release for an unknown pointer).
    //
    // The stale state is not hypothetical: any native session that takes
    // over event delivery mid-click swallows the matching MOUSE_UP. The
    // canonical case is the native context menu — its tracking session eats
    // the right-button up, `isPressed` latches true, and the *next* left
    // Press used to close the stale interaction by releasing the *new*
    // (left) button, which the scene never saw pressed. The scene's stream
    // stayed unbalanced and every later click was misrouted until an
    // accidental right-click re-synced it — observed as "the widget can't
    // be dragged after opening its context menu". Hence [pressedButtonCode]:
    // the synthetic Release must name the button that is actually stuck.
    private var isPressed: Boolean = false

    // Tao button code of the Press that set [isPressed]; the synthetic
    // Release in [onPointerButton] and [onFocusChanged] must release this
    // button, not whichever button the new event carries.
    private var pressedButtonCode: Int = 0

    // Set the first time we see a CursorMoved from Tao. Until then, any
    // button event is dropped — a real user click cannot occur without the
    // cursor first being inside the window (which generates at least one
    // Move). Without this guard, the startup phantom Press gets through and
    // poisons Compose's pointer state for PointerId(0): subsequent Move
    // events are then interpreted as drag (the pointer is "still down"), so
    // hover effects don't fire until the user manually clicks once and our
    // dedup logic above sends a synthetic Release that clears the state.
    private var hasReceivedCursorMove: Boolean = false

    // Frame pacing is delegated to the CAMetalLayer's `displaySyncEnabled`
    // (default YES): `nextDrawable` blocks for vsync, naturally capping the
    // loop at the display refresh rate. Mirrors Windows/Linux where Tao
    // backends rely on the EGL / GLX swap interval. A software
    // throttle here only drops frames the GPU is ready to present.

    fun attach() {
        check(NativeTaoBridge.isLoaded && NativeMetalBridge.isLoaded) {
            "Tao native libraries not loaded"
        }
        val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
        require(nsView != 0L) { "NSView handle unavailable; window not yet realised" }
        nsViewHandle = nsView

        // Apply transparent / full-size title bar so the native traffic-light
        // buttons stay visible while our content fills the entire window.
        NativeMetalBridge.nativeConfigureChrome(nsView)

        // macOS 26 (Tahoe) modern chrome — silently no-op on older systems.
        NativeMetalBridge.nativeApplyLargeCornerRadius(
            nsView,
            macOSStyle.shouldApplyLargeCornerRadius(),
        )

        // Hide the app's Dock icon when requested. Activation policy is
        // app-wide, so only act on the opt-in value — a default (false) window
        // must not force a sibling that intentionally hid the app back into the
        // Dock. Runs on the main thread (attach() contract), as the bridge
        // requires.
        if (hiddenFromDock && NativeTaoMacOsDecoBridge.isLoaded) {
            NativeTaoMacOsDecoBridge.nativeSetHiddenFromDock(true)
        }

        val handle = NativeMetalBridge.nativeAttach(nsView)
        require(handle != 0L) { "Failed to attach CAMetalLayer to NSView" }
        attachmentHandle = handle

        // #416: keep NSWindow non-opaque and layers clear after attach.
        // tao already set opaque=false at builder time; our background / glass
        // paths must not force it back to YES.
        if (fullyTransparent) {
            NativeMetalBridge.nativeSetFullyTransparent(nsView, true)
            NativeMetalBridge.nativeSetWindowBackgroundColor(nsView, clearColorArgbState.value)
        }

        // Render loop, AWT/skiko MetalVSyncer pattern: a FrameDispatcher
        // (coalescing) drives one frame per `invalidate`; each frame renders then
        // `waitForVSync()` (CVDisplayLink-backed, suspends — Tao loop stays free)
        // to pace to the display. Replaces the push-triggered display-link model.
        NativeMetalBridge.nativeStartDisplayLink(handle)
        startRenderLoop(handle)

        val devicePtr = NativeMetalBridge.nativeDevicePtr(handle)
        val queuePtr = NativeMetalBridge.nativeQueuePtr(handle)
        // The Skia Metal DirectContext is thread-affine: create it on the render
        // thread that will use it for every frame's GPU encode + present. The
        // resource-cache budget is anchored in the same hop — writing it purges
        // to fit, so it belongs on the owning thread like every other use of
        // the context. See GPU_RESOURCE_CACHE_LIMIT_BYTES for why the value
        // itself changes nothing today, and [purgeGpuResourceCache] for what
        // actually reclaims.
        directContext =
            runOnRenderThread {
                DirectContext.makeMetal(devicePtr, queuePtr).also {
                    it.resourceCacheLimit = GPU_RESOURCE_CACHE_LIMIT_BYTES
                }
            }

        scale = initialMacOsScaleFactor(window)

        // Present the fullscreen layout before AppKit snapshots the window for
        // its transition animation. Runs re-entrantly on the AppKit main
        // thread from inside `windowWillEnterFullScreen:` — see
        // NativeMetalBridge.onFullscreenPrepare and #327.
        NativeMetalBridge.setFullscreenPrepare(nsViewHandle) { targetW, targetH ->
            prepareFullscreenFrame(targetW, targetH)
        }

        // The DnD manager needs lazy access to the scene's rootDragAndDropNode,
        // but the scene cannot be constructed before we hand it the
        // PlatformContext that owns the manager. Resolve on each call.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val dndManager =
            dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager(
                getRootNode = { scene!!.rootDragAndDropNode },
                outboundLauncher = ::launchMacOsOutboundDrag,
            )

        // IME callbacks edit the focused field through `TextEditingScope`, i.e.
        // they run user code straight off a native AppKit callback — the Tao
        // counterpart of AWT's guarded `inputMethodTextChanged`.
        window.imeReplaceCommit = { text, start, length ->
            exceptionHandler.catchExceptions { imeSession.replaceCommit(text, start, length) }
        }
        window.imePreedit = { text -> exceptionHandler.catchExceptions { imeSession.preedit(text) } }
        window.imeCommit = { text -> exceptionHandler.catchExceptions { imeSession.commit(text) } }
        val taoPlatformContext =
            TaoPlatformContext(
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
                // above content via z-order. Same fix as Linux (commit 2d8ca500)
                // and Windows (commit 910879d0).
                topInsetPx = { 0 },
                scaleProvider = { scale },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
                textToolbar = textToolbar,
                onInputSession = { imeSession.onInputSession(it) },
                isWindowTransparent = fullyTransparent,
            )

        val nativeLayerFactory = if (nativePopupLayers) nativePopupLayerFactory() else null
        // The scene's MonotonicFrameClock is owned by the FrameRecomposer inside the
        // bundle (Compose 1.12). It matters that the clock exists: without one the
        // recomposer can't tell when a frame finished and re-fires the invalidation
        // after every render, saturating the main thread. The recomposer now ticks it
        // itself in `performFrame` (one frame per FrameDispatcher tick, re-scheduling
        // only while animations remain), so the host no longer sends frames manually.
        sceneBundle =
            if (nativeLayerFactory != null) {
                // Opt-in path (e.g. tray popups): every Popup becomes a native
                // NSPanel owned by this window, so popup content can extend
                // beyond — and float independently of — the window bounds.
                platformLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    size = IntSize(widthPx, heightPx),
                    composeSceneContext = TaoComposeSceneContext(taoPlatformContext, nativeLayerFactory),
                    // Schedule a frame on the render loop (coalesced); it renders
                    // then waits for the next vsync. See startRenderLoop.
                    requestFrame = { frameDispatcher?.scheduleFrame() },
                )
            } else {
                // Match Windows and Linux for the main host scene: Compose
                // Popup / DropdownMenu / Tooltip content stays in the same
                // Metal render target instead of becoming a native NSPanel.
                // NativeView overlay scenes still opt into TaoComposeSceneContext
                // when their popups must float above an embedded AppKit view.
                canvasLayersSceneBundle(
                    coroutineContext = coroutineContext + flushingDispatcher,
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    size = IntSize(widthPx, heightPx),
                    platformContext = taoPlatformContext,
                    requestFrame = { frameDispatcher?.scheduleFrame() },
                )
            }
        scene?.compositionLocalContext = pendingCompositionLocalContext
        configureSceneBundle()

        // One source of truth for the scene's drop target: the callback below
        // resolves it through here, and so does an in-process driver.
        window.inboundDragAndDropNode = { scene?.rootDragAndDropNode }
        registerInboundDnD()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun launchMacOsOutboundDrag(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
        onCompleted: (androidx.compose.ui.draganddrop.DragAndDropTransferAction?) -> Unit,
    ): Boolean {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.isLoaded) return false
        if (nsViewHandle == 0L) return false
        // Synchronous path, unlike Windows (#435): `beginDraggingSession`
        // cooperatively pumps the AppKit run loop, so the session completes
        // before this returns and the result is reported inline.
        val action: androidx.compose.ui.draganddrop.DragAndDropTransferAction? =
            dev.nucleusframework.window.tao.dnd.TaoSceneDnD.launchOutboundDrag(
                request = request,
                dropEffectCopy = dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY,
                dropEffectMove = dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_MOVE,
                dropEffectLink = dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_LINK,
            ) { files, text, allowedEffects ->
                // No VSync dance and no post-drag `window.resetRedrawLatch()`, unlike
                // the Windows counterpart. Nothing is consumed while tao's tick is
                // suppressed: `AppState::cleared` returns early before it drains
                // either the user-event channel (`RequestRedraw`) or the pending
                // redraw list, so both survive the session and the latch un-wedges
                // itself on the first tick after it.
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.nativeStartDrag(
                    nsView = nsViewHandle,
                    files = files,
                    text = text,
                    allowedEffects = allowedEffects,
                    pump = OutboundDragPump(),
                )
            }
        onCompleted(action)
        return true
    }

    /**
     * Drives the host while an outbound drag session owns the main thread — see
     * [dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DragPump].
     *
     * [requestFrame] is not optional here: Compose enters the session from
     * inside `sendPointerEvent`, and `BaseComposeScene.postponeInvalidation`
     * holds the scene's `invalidate` callback off for that whole dispatch — so
     * nothing schedules a frame on its own, however diligently we drain.
     *
     * Mirrors `TaoComposeSceneHostWindows.OutboundDragPump` minus its VSync
     * toggle and frame throttle: a frame is recorded on this thread but
     * replayed, presented and paced (`nativeVSyncWait`) on the render thread, so
     * a tick never parks AppKit's drag tracking, and skiko's `FrameDispatcher`
     * coalescing plus that pacing already cap the rate at the display's.
     *
     * Reentrancy, deliberately accepted: every frame recorded here renders the
     * scene with a pointer dispatch still on the stack. There is no way to
     * render during the drag *without* that nesting — refusing to render would
     * just restore the freeze this exists to fix — so the scene is re-entered
     * knowingly. If it proves unsafe, the principled fix is to defer
     * `nativeStartDrag` onto the main dispatcher so the session starts one loop
     * iteration later, with no Compose dispatch below it.
     *
     * Named class (not a lambda) for GraalVM JNI reachability, same as
     * [InboundDnDCallback].
     */
    private inner class OutboundDragPump :
        dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DragPump {
        override fun pump() {
            // Schedule before draining, so the frame runs in this very drain
            // instead of waiting for the next tick.
            requestFrame()
            TaoMainDispatcher.pump()
        }
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.isLoaded) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "macOS DnD lib not loaded — inbound disabled",
            )
            return
        }
        val callback = InboundDnDCallback()
        dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.nativeRegister(
            nsView = nsViewHandle,
            callback = callback,
        )
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback : dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.Callback {
        private fun node() = window.inboundDragAndDropNode?.invoke()

        override fun onDragEnter(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDragEnter x=$x y=$y hasFiles=$hasFiles",
            )
            if (!hasFiles) {
                return dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
            return if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragEnter(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int =
            if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDragOver(node(), x, y)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragLeave(nsView: Long) {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics
                .log("onDragLeave")
            dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                .onDragLeave(node())
        }

        override fun onDrop(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int {
            dev.nucleusframework.window.tao.TaoDnDDiagnostics.log(
                "onDrop x=$x y=$y files=${files?.size ?: 0}",
            )
            return if (dev.nucleusframework.window.tao.dnd.TaoSceneDnD
                    .onDrop(node(), x, y, files)
            ) {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    /**
     * Wired by the window to the a11y controller so Compose's non-editable text
     * selection (`SelectionContainer`) can be published to native accessibility
     * (PopClip et al.). `(selectedText, editable)`; see
     * [TaoSelectionAccessibilityObserver]. Null = no a11y bridge.
     */
    var onTextSelectionForA11y: ((text: String, editable: Boolean, sourceId: Int) -> Unit)? = null

    // Guarded like AWT's `ComposeSceneMediator.setContent`: the first
    // composition runs inside this call, so content that throws while mounting
    // must reach the window's handler instead of unwinding into the Tao loop.
    fun setContent(content: @Composable () -> Unit) =
        exceptionHandler.catchExceptions {
            scene?.setContent {
                TaoTextToolbarHost(textToolbar) {
                    val onSel = onTextSelectionForA11y
                    // Expose the publisher so themed wrappers (nucleus-application) can
                    // re-install the observer inside their theme's own LocalTextContextMenu.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalTaoTextSelectionA11yPublisher provides onSel,
                    ) {
                        if (onSel != null) {
                            TaoSelectionAccessibilityObserver(onSelection = onSel, content = content)
                        } else {
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

    fun onResized(
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        window.requestRedraw()
        purgeResizeScratchIfDue()
    }

    private var lastResizePurgeNs: Long = 0

    /**
     * Reclaims the per-size GPU scratch a live resize mints, while the sizes are
     * still streaming — the macOS half of what
     * [TaoComposeSceneHostWindows.onResized] does inside the OS modal
     * resize/move loop. Skia's budget caps the cache, but a capped cache full of
     * scratch no frame will ever ask for again is still 256 MiB resident.
     *
     * Deliberately only the *in-drag* half of the Windows behaviour. There is no
     * settle purge and no `System.gc()` nudge here, because macOS has no
     * `WM_EXITSIZEMOVE` to hang them on and a timer standing in for it proved a
     * bad trade twice over: the drag's own frames are display-link paced, so
     * macOS never accumulates the way Windows' unpaced modal loop does (a
     * 60-step storm moved the graphics footprint 68 MB → 72 MB, and a purge + GC
     * at the end of it returned essentially none of that), while the pair landed
     * on an animating window as a visible stall — a window with a live
     * `NativeView` embed dropped below 4 frames per 400 ms right after a storm.
     * Cost with no measured benefit. The reclaim that #638 is actually after is
     * at rest, not at drag end, and belongs on the idle path.
     */
    private fun purgeResizeScratchIfDue() {
        val now = System.nanoTime()
        if (now - lastResizePurgeNs < GPU_RESIZE_PURGE_INTERVAL_NS) return
        lastResizePurgeNs = now
        purgeGpuResourceCache()
    }

    /**
     * Frees the GPU resource cache: toggling the limit to 0 runs Skia's
     * `purgeAsNeeded` inline, releasing every unlocked resource, and restoring
     * the budget lets the next frame re-mint only what it needs. The only purge
     * primitive skiko exposes — see [GPU_RESOURCE_CACHE_LIMIT_BYTES].
     *
     * Metal has no notion of a *current* context, so none of the foreign-context
     * hazard the ANGLE/EGL hosts guard against (#514) applies here: the danger
     * on this backend is thread affinity instead. The `DirectContext` is created
     * on, and only ever touched from, [renderExecutor], so the toggle hops
     * there — submitted rather than awaited, because the caller is the Tao main
     * thread on the resize path and blocking it would park the drag behind the
     * in-flight replay. FIFO ordering puts the purge cleanly between two frames,
     * where nothing the host caches is live (each frame wraps the drawable's
     * texture in a fresh `BackendRenderTarget`), and once [detach] has nulled
     * the context this returns before submitting anything.
     */
    private fun purgeGpuResourceCache() {
        val ctx = directContext ?: return
        // Rejected once detach() shut the executor down; a purge is never worth
        // routing to the fatal handler.
        runCatching {
            renderExecutor.submit {
                ctx.resourceCacheLimit = 0
                ctx.resourceCacheLimit = GPU_RESOURCE_CACHE_LIMIT_BYTES
            }
        }
    }

    /**
     * Resizes the scene to the size the window is about to take and presents
     * one frame synchronously. Called from AppKit's
     * `windowWillEnterFullScreen:` (via [NativeMetalBridge.onFullscreenPrepare])
     * so the transition snapshot holds the final layout instead of the previous
     * one — see #327. The regular [onResized] follows right after with the same
     * size and short-circuits.
     */
    private fun prepareFullscreenFrame(
        targetWidthPx: Int,
        targetHeightPx: Int,
    ) {
        if (attachmentHandle == 0L) return
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return
        if (targetWidthPx == widthPx && targetHeightPx == heightPx) return
        widthPx = targetWidthPx
        heightPx = targetHeightPx
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        renderFrameBlocking()
    }

    /**
     * Density-only. The physical size that goes with [newScale] arrives as the
     * [onResized] the event loop dispatches right behind the scale change —
     * including on a macOS display hop, where AppKit itself sends no resize
     * (#418). Deriving the new pixel size here instead would be a guess:
     * `scale` is seeded from `initialMacOsScaleFactor`, which takes the *max*
     * of the window's and the primary monitor's scale, so it is not
     * necessarily the scale `widthPx`/`heightPx` were produced at.
     */
    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onFocusChanged(focused: Boolean) {
        windowInfo.isWindowFocused = focused
        if (!focused && isPressed) {
            // Whatever stole focus mid-click (a native context-menu tracking
            // session, a compositor drag) owns the pointer now and will eat
            // the matching MOUSE_UP — it is never coming. Close the stale
            // interaction here so the next Press hit-tests fresh instead of
            // being misrouted along the stuck button's gesture (see
            // [isPressed]).
            scene?.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                keyboardModifiers = currentKeyboardModifiers,
                button = mapButton(pressedButtonCode),
            )
            isPressed = false
        }
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    // A11y sync is debounced on a timer rather than run once per render tick.
    // Hop the debounced semantics walk onto the Tao main thread (it touches
    // Compose state) and request a redraw. See AbstractTaoComposeSceneHost.
    override fun dispatchA11yWalk(block: () -> Unit) {
        flushingDispatcher.enqueue(Runnable { block() })
        window.requestRedraw()
    }

    // Per-popup render callbacks invoked during this host's own redraw
    // pass so each popup paints a fresh frame whenever the main scene
    // does. Keyed by an opaque token so registrations don't collapse into
    // each other when multiple popups are active.
    private val popupRenderers: MutableMap<Any, () -> TaoRecordedSurface?> = LinkedHashMap()

    /**
     * Dialog scrims of the native popup layers, painted over the main scene at
     * the end of every frame — see [PopupScrimRegistry].
     */
    private val popupScrims =
        PopupScrimRegistry {
            sceneBundle?.visualDirty?.set(true)
            window.requestRedraw()
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

    // Tao's macOS pipeline intercepts keys before AppKit's responder
    // chain, so an overlay NSView can't receive `keyDown:` natively. The
    // host's `onKeyEvent` consults these handlers first; returning `true`
    // consumes the event before the main scene sees it.
    private val popupKeyHandlers: MutableMap<Any, (KeyEvent) -> Boolean> = LinkedHashMap()

    /**
     * One host instance per scene. The composition local built from it keys
     * `NativeView`'s attach effect: a fresh object on every recomposition of
     * the window root would detach and re-attach every embed each time.
     */
    private var nativeViewHostInstance: dev.nucleusframework.window.tao.TaoNativeViewHost? = null

    fun nativeViewHost(): dev.nucleusframework.window.tao.TaoNativeViewHost? =
        nativeViewHostInstance ?: createNativeViewHost()?.also { nativeViewHostInstance = it }

    private fun createNativeViewHost(): TaoNativeViewHost? {
        if (nsViewHandle == 0L) return null
        if (!NativeTaoMacOsNativeViewBridge.isLoaded) return null
        val outer = this
        @Suppress("UnusedParameter")
        return object : TaoNativeViewHost {
            override fun attach(
                childHandle: Long,
                regionToken: Any,
            ) {
                // Eager: NativeView.kt's DisposableEffect mounts the
                // child as soon as the composable enters the tree.
                // Visual sync with Compose is for *reposition*
                // (`scheduleInteropAction` + presentsWithTransaction),
                // not for the initial add.
                if (outer.interopAttachCount == 0) {
                    outer.transaction.isInteropActive = true
                    // Punch-through blending needs a non-opaque CAMetalLayer
                    // and alpha-0 Skia clears — same latch as glass regions.
                    WindowTransparencyMode.acquire(outer.window, outer.glassBackgroundState)
                }
                outer.interopAttachCount++
                NativeTaoMacOsNativeViewBridge.nativeAddSubview(outer.nsViewHandle, childHandle)
            }

            override fun detach(
                childHandle: Long,
                regionToken: Any,
            ) {
                NativeTaoMacOsNativeViewBridge.nativeRemoveSubview(childHandle)
                outer.interopAttachCount--
                if (outer.interopAttachCount == 0) {
                    outer.transaction.isInteropActive = false
                    WindowTransparencyMode.release(outer.window, outer.glassBackgroundState)
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
                outer.scheduleInteropAction {
                    NativeTaoMacOsNativeViewBridge
                        .nativeSetSubviewFrame(outer.nsViewHandle, handle, xPx, yPx, widthPx, heightPx)
                }
            }

            override fun setCornerRadius(
                handle: Long,
                radiusPx: Float,
            ) {
                outer.scheduleInteropAction {
                    NativeTaoMacOsNativeViewBridge
                        .nativeSetSubviewCornerRadius(outer.nsViewHandle, handle, radiusPx)
                }
            }

            override fun dispatchPointerToNative(
                handle: Long,
                type: Int,
                xPx: Float,
                yPx: Float,
                button: Int,
                pressed: Boolean,
            ) {
                if (outer.nsViewHandle == 0L || handle == 0L) return
                NativeTaoMacOsNativeViewBridge.nativeDispatchPointer(
                    outer.nsViewHandle,
                    handle,
                    type,
                    xPx,
                    yPx,
                    button,
                    pressed,
                )
            }

            override fun dispatchScrollToNative(
                handle: Long,
                xPx: Float,
                yPx: Float,
                dx: Float,
                dy: Float,
            ) {
                if (outer.nsViewHandle == 0L || handle == 0L) return
                NativeTaoMacOsNativeViewBridge.nativeDispatchScroll(
                    outer.nsViewHandle,
                    handle,
                    xPx,
                    yPx,
                    dx,
                    dy,
                )
            }

            override fun noteNativePointerDispatch() {
                outer.nativePointerDispatchedThisEvent = true
            }
        }
    }

    /**
     * Enqueues an AppKit mutation to be drained inside the next frame's
     * transaction, atomically with the Compose Metal present.
     */
    internal fun scheduleInteropAction(action: TaoInteropAction) {
        transaction.add(action)
        window.requestRedraw()
    }

    /**
     * #569: the NSView's own origin on screen — not the window frame's, a
     * native title bar sits between them — paired with every screen's
     * `visibleFrame`, so a popup layer can clamp against the display it lands
     * on instead of the work-area-sized virtual screen Compose positions it in.
     */
    private fun resolvePopupScreenGeometry(): PopupScreenGeometry? {
        if (!NativeTaoMacOsDecoBridge.isLoaded) return null
        val content =
            NativeTaoMacOsDecoBridge
                .nativeGetContentRect(nsViewHandle)
                ?.takeIf { it.size >= 2 }
                ?: return null
        // `reported`, not `all`: `all` invents a monitor when the platform
        // names none, and clamping a popup into an invented work area moves it
        // somewhere no display is. No geometry means no clamp.
        val areas = TaoMonitors.reported(window).map { it.workAreaPx }.ifEmpty { return null }
        return PopupScreenGeometry(
            parentContentOriginPx = IntOffset(content[0].toInt(), content[1].toInt()),
            workAreasPx = areas,
        )
    }

    /** Native popup layers handed out by [nativePopupLayerFactory] and not yet closed — swept by [detach]. */
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private val liveNativePopupLayers = linkedSetOf<androidx.compose.ui.scene.ComposeSceneLayer>()

    /**
     * Builds this window's native popup layers ([TaoPopupSceneLayer]). The
     * factory behind [nativePopupLayers], and the one `NativePopupLayers { }`
     * hands to a subtree that wants native surfaces while the window's own
     * popups stay in-scene. `null` before the NSView is attached.
     */

    fun nativePopupLayerFactory(): TaoPopupLayerFactory? {
        val popupHost = popupHost() ?: return null
        return { density, layoutDirection, focusable, consumeOutside ->
            TaoPopupSceneLayer(
                host = popupHost,
                initialDensity = density,
                initialLayoutDirection = layoutDirection,
                initialFocusable = focusable,
                initialConsumePointerInputOutside = consumeOutside,
            ).also { liveNativePopupLayers += it }
        }
    }

    fun popupHost(): TaoPopupHost? {
        if (nsViewHandle == 0L) return null
        val outer = this
        return object : TaoPopupHost {
            override val parentNsView: Long get() = outer.nsViewHandle
            override val scale: Float get() = outer.scale
            override val isOwnerWindowTransparent: Boolean get() = outer.fullyTransparent
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val parentWindowInfo: androidx.compose.ui.platform.WindowInfo get() = outer.windowInfo
            override val workAreaSize: IntSize get() {
                val packed = NativeMetalBridge.nativeOwnerWorkAreaSize(outer.nsViewHandle)
                if (packed == 0L) return parentWindowSize
                val w = (packed ushr 32).toInt()
                val h = (packed and 0xFFFFFFFFL).toInt()
                return if (w > 0 && h > 0) IntSize(w, h) else parentWindowSize
            }

            override val popupScreenGeometry: PopupScreenGeometry?
                get() = outer.resolvePopupScreenGeometry()
            override val sceneCoroutineContext: CoroutineContext
                get() = outer.coroutineContext + outer.flushingDispatcher

            override val exceptionHandler: WindowExceptionHandler?
                get() = outer.exceptionHandler

            override val popupScrims: PopupScrimRegistry get() = outer.popupScrims

            override fun requestRedraw() = outer.window.requestRedraw()

            override fun registerRenderer(
                token: Any,
                record: () -> TaoRecordedSurface?,
            ) {
                popupRenderers[token] = record
            }

            override fun unregisterRenderer(token: Any) {
                popupRenderers.remove(token)
            }

            @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
            override fun onLayerClosed(layer: androidx.compose.ui.scene.ComposeSceneLayer) {
                liveNativePopupLayers.remove(layer)
            }

            override fun <T> runOnRenderThread(block: () -> T): T = outer.runOnRenderThread(block)

            override fun registerKeyHandler(
                token: Any,
                handler: (KeyEvent) -> Boolean,
            ) {
                popupKeyHandlers[token] = handler
            }

            override fun unregisterKeyHandler(token: Any) {
                popupKeyHandlers.remove(token)
            }

            override fun setCursor(iconCode: Int) {
                NativeTaoBridge.setCursorIcon(outer.window.handle, iconCode)
            }
        }
    }

    private val metalTextureHostCache = MetalTextureHostCache()

    /**
     * Window scene's handle for the `TextureView` composable: the Metal device
     * this window renders with, its Skia context, and its render thread. Null
     * until [attach] has created them — see [MetalTextureHostCache].
     */
    fun metalTextureHost(): TaoMetalTextureHost? {
        val outer = this
        return metalTextureHostCache.get(attachmentHandle, directContext) { device, ctx ->
            object : TaoMetalTextureHost {
                override val metalDevicePtr: Long = device
                override val directContext: DirectContext = ctx

                override fun <T> runOnRenderThread(block: () -> T): T = outer.runOnRenderThread(block)
            }
        }
    }

    private fun updateWindowInfoSize() {
        windowInfo.containerSize = IntSize(widthPx, heightPx)
        // `containerDpSize` is what Compose surfaces to user code via
        // `LocalWindowInfo.current.containerDpSize` (e.g. for breakpoints).
        if (scale > 0f) {
            val dpW = (widthPx / scale)
            val dpH = (heightPx / scale)
            windowInfo.containerDpSize = DpSize(dpW.dp, dpH.dp)
        }
    }

    // [aFixed] / [bFixed] are physical pixels × 1024 (see `CURSOR_FIXED_SCALE`).

    // HACK: hover effects on macOS don't render until the user clicks once
    //  anywhere in the window. Move events ARE delivered to Compose (verified
    //  via logging — `isPressed` is false at startup, the first Move arrives
    //  before any Press, hit-testing is correct), and `MutableInteractionSource`
    //  emits `HoverInteraction.Enter()`, but `collectIsHoveredAsState()`'s
    //  underlying State write doesn't propagate visually until something else
    //  triggers a redraw. The first click, processed via `onPointerButton`,
    //  somehow "unblocks" the chain — afterwards hover works for the rest of
    //  the session. Calling `window.requestRedraw()` after every Move event
    //  was tried and did NOT fix it, so the issue isn't a missing redraw
    //  request; the recomposer / Snapshot apply pass itself isn't running on
    //  hover-only state changes. Likely related to the FlushingMainDispatcher /
    //  TaoMainDispatcher / BroadcastFrameClock interaction during early
    //  startup, before any frame has actually been driven by a real input
    //  event. Independent of the Press dedup fix below.
    fun onPointerMove(
        aFixed: Int,
        bFixed: Int,
    ) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        hasReceivedCursorMove = true
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
        if (!hasReceivedCursorMove) {
            // No cursor position has been observed yet — this button event
            // cannot correspond to a real user click. Drop it. See the
            // comment on `hasReceivedCursorMove` for the rationale.
            return
        }
        val composeButton = mapButton(buttonCode)
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        if (pressed && isPressed) {
            // Stale "still-down" state — close it out before opening a new
            // interaction so Compose hit-tests this Press fresh. Release the
            // button that is actually stuck, not the one this event carries.
            // See the comment on `isPressed` for the rationale.
            scene?.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(pointerDeadband.x, pointerDeadband.y),
                type = PointerType.Mouse,
                keyboardModifiers = currentKeyboardModifiers,
                button = mapButton(pressedButtonCode),
            )
        } else if (!pressed && !isPressed) {
            // Stray Release without a matching Press — drop it.
            return
        }
        isPressed = pressed
        if (pressed) pressedButtonCode = buttonCode
        if (pressed) nativePointerDispatchedThisEvent = false
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(pointerDeadband.x, pointerDeadband.y),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = composeButton,
        )
        if (pressed &&
            !nativePointerDispatchedThisEvent &&
            nsViewHandle != 0L &&
            NativeTaoMacOsNativeViewBridge.isLoaded
        ) {
            // Compose consumed the click (or it missed every NativeView):
            // take first-responder back so typing goes to Compose, not a
            // previously focused WKWebView sitting under the Metal layer.
            NativeTaoMacOsNativeViewBridge.nativeMakeContentViewFirstResponder(nsViewHandle)
        }
    }

    /**
     * [event] is pre-shaped to match AWT `MouseWheelEvent.preciseWheelRotation`
     * and carries a synthetic native event so Compose's desktop scroll config
     * can read `scrollAmount` and precise-wheel metadata like the AWT backend.
     */
    fun onPointerScroll(event: TaoPointerScrollEvent) {
        currentKeyboardModifiers = taoKeyboardModifiers(window.modifierState)
        windowInfo.keyboardModifiers = currentKeyboardModifiers
        scene?.dispatchAwtShapedScroll(
            x = pointerDeadband.x,
            y = pointerDeadband.y,
            event = event,
            keyboardModifiers = currentKeyboardModifiers,
        )
    }

    // ── Trackpad gestures (macOS pinch / rotate / smart-magnify) ──────────
    //
    // Tao 0.35 doesn't expose these events; an NSEvent local monitor in
    // `macos/touchpad_gestures.m` intercepts them and forwards through
    // `EventCallback.onTrackpadGesture`. We synthesize two ComposeScenePointer
    // Touch points around the gesture centre — distance varies with the
    // accumulated magnification factor, angle with the accumulated rotation.
    // detectTransformGestures reacts to the changes between consecutive Move
    // events, so pinch-zoom / rotate / pan all work with no app-side change.

    private var gestureActive = false

    // Centre of the gesture in physical pixels (top-left origin).
    private var gestureCenterX = 0f
    private var gestureCenterY = 0f

    // Cumulative scale (1.0 at gesture start; multiplied by (1 + magnification)
    // on each Magnify event) and angle in radians.
    private var gestureScale = 1f
    private var gestureAngle = 0f

    /**
     * Synthesises a two-finger Touch gesture for `detectTransformGestures`.
     * Wire format mirrors `TaoTrackpadGesture` / `TaoTrackpadPhase` constants.
     * [valueFixed] is the per-event delta × 10 000 (ratio for magnify, degrees
     * for rotate, ignored for smart-magnify).
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun onTrackpadGesture(
        kind: Int,
        phase: Int,
        xFixed: Int,
        yFixed: Int,
        valueFixed: Int,
    ) {
        if (scene == null) return
        val xPx = xFixed / TRACKPAD_POSITION_SCALE
        val yPx = yFixed / TRACKPAD_POSITION_SCALE
        val value = valueFixed / TRACKPAD_VALUE_SCALE

        // Smart-magnify is one-shot: synthesise a Press → Move → Release burst
        // around a fixed scale step so detectTransformGestures sees a discrete
        // zoom change.
        if (kind == TaoTrackpadGesture.SMART_MAGNIFY) {
            startGesture(xPx, yPx)
            sendGesturePointers(PointerEventType.Press)
            gestureScale *= SMART_MAGNIFY_FACTOR
            sendGesturePointers(PointerEventType.Move)
            endGesture(cancelled = false)
            return
        }

        when (phase) {
            TaoTrackpadPhase.BEGAN -> {
                startGesture(xPx, yPx)
                applyDelta(kind, value)
                sendGesturePointers(PointerEventType.Press)
            }
            TaoTrackpadPhase.CHANGED -> {
                if (!gestureActive) {
                    startGesture(xPx, yPx)
                } else {
                    // Track the real cursor on every tick so the synthesised
                    // centroid moves with `Δcursor` between events. Without
                    // this, `calculatePan` would always report 0 from the
                    // synthetic pair (centroid pinned at gesture start), and
                    // a pinch-while-dragging would silently lose the pan
                    // component. Stable PointerIds + symmetric offsets around
                    // the live cursor = honest pan.
                    gestureCenterX = xPx
                    gestureCenterY = yPx
                }
                applyDelta(kind, value)
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

    private fun applyDelta(
        kind: Int,
        value: Float,
    ) {
        when (kind) {
            TaoTrackpadGesture.MAGNIFY -> {
                // Compose's pinch detection responds to relative distance change,
                // so multiplying preserves the (1 + delta) semantics of
                // NSEvent.magnification across the gesture.
                gestureScale *= (1f + value).coerceAtLeast(MIN_GESTURE_SCALE)
            }
            TaoTrackpadGesture.ROTATE -> {
                // NSEvent.rotation is positive counter-clockwise in NSView's
                // bottom-left (y-up) frame. Compose lives in screen y-down,
                // where positive rotation is clockwise — flip the sign so the
                // synthesised pointer rotation matches the user's gesture
                // direction once detectTransformGestures applies it back to
                // graphicsLayer.rotationZ.
                gestureAngle -= value * (Math.PI.toFloat() / DEGREES_PER_RADIAN)
            }
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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

    /**
     * Converts a Tao keyboard event (already reshaped to AWT-style values by
     * `keymap.rs` / `TaoWindow.dispatchKey`) into a Compose `KeyEvent` and
     * forwards it. Mirrors `KeyEvent.desktop.kt`'s `toComposeEvent()`.
     *
     * `KEY_TYPED` events come from `WindowEvent::ReceivedImeText` (one per
     * Unicode scalar) and need a synthetic `java.awt.event.KeyEvent` with
     * `id=KEY_TYPED` as `nativeEvent` so Compose's desktop-actual
     * `KeyEvent.isTypedEvent` returns true — that's the gate `BasicTextField`
     * uses to insert the character into the field.
     */
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
        if (popupKeyHandlers.isNotEmpty()) {
            for (token in popupKeyHandlers.keys.toList()) {
                val handler = popupKeyHandlers[token] ?: continue
                if (handler(composeEvent)) return true
            }
        }
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    private companion object {
        // Pause between interop pumps in [runOnRenderThread]'s cooperative
        // wait — the pump is a non-blocking single pass, so this park is the
        // whole per-iteration cost. Kept small: the wait sits on TextureView's
        // per-video-frame snapshot hop, where every fixed microsecond is paid
        // once per frame.
        private const val PUMP_PARK_NANOS = 50_000L // 50 µs

        // Wire scales (must match `TRACKPAD_VALUE_FIXED_SCALE` and
        // `CURSOR_FIXED_SCALE` on the Rust side).
        private const val TRACKPAD_POSITION_SCALE: Float = 1024f
        private const val TRACKPAD_VALUE_SCALE: Float = 10_000f

        // Two synthesised touch pointers separated by 2 × this radius at scale 1.
        //
        // Sized to defeat Compose's `detectTransformGestures` touch-slop check
        // for zoom-OUT: that check computes
        //     zoomMotion = abs(1 - cumulativeZoom) × previousCentroidSize
        // and only fires the callback once it exceeds `viewConfiguration.touchSlop`.
        // For zoom-out, `previousCentroidSize` shrinks together with the zoom,
        // so `zoomMotion` has a hard ceiling ≈ radius × 0.25. With a 50 px
        // radius the ceiling sat at ~13 px — below the default 18 px slop, so
        // zoom-out gestures were silently dropped. 120 px gives a ceiling of
        // ~31 px, comfortably above any reasonable slop value, while the
        // initial 240 px pointer separation still fits inside common
        // interactive targets (≥ 120 dp at 2× retina).
        private const val TRACKPAD_BASE_RADIUS_PX: Float = 120f

        private const val TRACKPAD_POINTER_ID_A: Long = 0xA001L
        private const val TRACKPAD_POINTER_ID_B: Long = 0xA002L

        // Smart-magnify maps to a single discrete zoom step. macOS's smart-zoom
        // toggles between a "fitted" view and a 2× zoom; 1.5× is a reasonable
        // default that still triggers detectTransformGestures' zoom callback.
        private const val SMART_MAGNIFY_FACTOR: Float = 1.5f

        private const val DEGREES_PER_RADIAN: Float = 180f
        private const val MIN_GESTURE_SCALE: Float = 0.05f
    }

    // ── Background render thread (AWT/skiko `dispatcherToBlockOn` pattern) ──
    //
    // Stage 2 of the macOS scroll-fluidity work: the per-frame Skia/Metal GPU
    // encode + present is moved off the Tao main thread. A single dedicated
    // thread owns the Skia Metal `DirectContext` (which is thread-affine): it
    // is created, used (nextDrawable + drawPicture + flushAndSubmit + present),
    // and closed only here. The main thread only *records* the Compose scene
    // into a `Picture` (CPU), then suspends while this thread replays it.
    //
    // Lifetime invariant that keeps overlay/popup teardown simple: the
    // FrameDispatcher is the SINGLE render driver (every redraw funnels through
    // `requestFrame()`), and it never starts frame N+1 until frame N's replay
    // coroutine has resumed. So whenever the main thread is inside a record
    // pass — which is also when Compose disposal (popup/overlay close) runs —
    // this render thread is idle. Overlay surfaces can therefore close their
    // `DirectContext` here (blocking) and detach natively on the main thread
    // without racing an in-flight replay.
    // Every task drains its own ObjC autorelease pool — see
    // newMetalRenderExecutor (#494).
    private val renderExecutor: ExecutorService = newMetalRenderExecutor("TaoMetalRender")
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    /**
     * Runs [block] on the render thread and blocks until it returns. Used for
     * `DirectContext` create/use/close that must respect Skia's Metal context
     * thread-affinity.
     *
     * The wait is cooperative when called on the Tao main thread: an in-flight
     * or queued replay may be inside `nativePresentWithInterop`, whose
     * CATransaction callout runs on the main thread — parking in a bare
     * `get()` would deadlock (the fullscreen freeze with a live `NativeView`:
     * `windowWillEnterFullScreen` → [prepareFullscreenFrame] →
     * [renderFrameBlocking] blocks main while the render thread waits on the
     * main run loop; same shape for the overlay first-attach). Pumping the
     * private interop run-loop mode executes exactly those callouts — and
     * nothing else — while we wait.
     */
    fun <T> runOnRenderThread(block: () -> T): T {
        val future = renderExecutor.submit(Callable { block() })
        // nativeIsMainThread, not taoMainThread: on macOS the event loop is
        // marshalled onto AppKit thread 0, which is a different JVM thread
        // than the one that entered taoApplication.
        if (NativeMetalBridge.isLoaded && NativeMetalBridge.nativeIsMainThread()) {
            while (!future.isDone) {
                NativeMetalBridge.nativeInteropPump()
                // The pump returns immediately when no callout is queued in the
                // private mode; park briefly so the wait isn't a hot spin.
                if (!future.isDone) LockSupport.parkNanos(PUMP_PARK_NANOS)
            }
        }
        return future.get()
    }

    // ── VSync-paced render loop (AWT/skiko MetalVSyncer pattern) ──
    private var frameDispatcher: org.jetbrains.skiko.FrameDispatcher? = null
    private val renderLoopJob = kotlinx.coroutines.SupervisorJob()

    /** Schedules a single coalesced frame on the render loop. The sole entry
     *  point for "please repaint" — both Compose `invalidate` and Tao
     *  `RedrawRequested` events funnel through here so frames stay serialized. */
    fun requestFrame() {
        frameDispatcher?.scheduleFrame()
    }

    /**
     * Starts the FrameDispatcher render loop. `invalidate` (and Tao redraw
     * events) schedule a frame; each frame records the scene on the main thread,
     * then replays + presents + waits for the next display refresh on the render
     * thread (suspending — the Tao main loop stays free for input meanwhile).
     */
    private fun startRenderLoop(handle: Long) {
        // FrameDispatcher runs ONE long-lived coroutine: an exception in a
        // frame kills it for good, and the SupervisorJob would swallow the
        // failure — the window silently stops repainting (#622). Route it to
        // the fatal path instead (SEVERE log, native dialog, clean exit).
        val scope =
            kotlinx.coroutines.CoroutineScope(
                coroutineContext + TaoMainDispatcher + renderLoopJob + TaoFatalCoroutineExceptionHandler,
            )
        frameDispatcher =
            org.jetbrains.skiko.FrameDispatcher(scope) {
                renderFrameSuspending(handle)
            }
    }

    /**
     * One full frame: record on the main thread, then replay + present + pace on
     * the render thread. The `withContext(renderDispatcher)` boundary is what
     * frees the Tao main loop during GPU encode + present + vsync wait.
     */
    private suspend fun renderFrameSuspending(handle: Long) {
        val bundle = sceneBundle ?: return
        val ctx = directContext ?: return
        if (attachmentHandle == 0L || widthPx <= 0 || heightPx <= 0) return

        // Minimized: nothing is visible. Drop the frame before recording +
        // advancing the frame clock — that parks Compose animations (no
        // withFrameNanos tick → no re-invalidation), so the FrameDispatcher
        // quiesces instead of rendering into a hidden surface. Restored via
        // TaoWindow's requestRedraw on the MINIMIZED-off event.
        if (window.isMinimized) return

        // ── interop transaction snapshot (main) ──
        val tx = retrieveTransaction()
        val needsTransaction =
            tx.actions.isNotEmpty() || rendererIsInteropActive != tx.isInteropActive
        if (needsTransaction != layerPresentsWithTransaction && attachmentHandle != 0L) {
            NativeMetalBridge.nativeSetPresentsWithTransaction(attachmentHandle, needsTransaction)
            layerPresentsWithTransaction = needsTransaction
        }
        if (tx.isInteropActive) rendererIsInteropActive = true

        // ── record (main) ──
        // Clear to the current themed fallback color, not hard-coded white, so
        // fullscreen/title-bar animation gaps don't flash. The clear itself runs
        // at replay time on the recorded surface.
        val mainClear = if (glassBackgroundState.value) 0 else clearColorArgbState.value
        val mainPicture = recordSceneToPicture(bundle, widthPx, heightPx)
        val popupSurfaces = recordPopupSurfaces()
        // Drain Compose's async work (sendFrame continuations, recomposer steps)
        // synchronously so their state writes happen now and trigger invalidate →
        // next requestFrame in the same Tao loop iteration. Mirrors Skiko's
        // FrameDispatcher pattern (previously the `onAfterPresent` hook).
        TaoMainDispatcher.pump()

        // ── replay + present + pace (render thread) ──
        var mainPresented = false
        withContext(renderDispatcher) {
            try {
                mainPresented =
                    replayPictureToFrame(handle, ctx, mainPicture, mainClear) { h, d ->
                        if (needsTransaction) {
                            // nativePresentWithInterop hops to the main queue
                            // internally for the CATransaction + AppKit mutations;
                            // the Runnable below therefore runs on the main thread.
                            NativeMetalBridge.nativePresentWithInterop(
                                h,
                                d,
                                Runnable {
                                    tx.performTransaction()
                                    if (!tx.isInteropActive) rendererIsInteropActive = false
                                },
                            )
                        } else {
                            NativeMetalBridge.nativePresent(h, d)
                        }
                    }
            } finally {
                mainPicture.close()
            }
            replayPopups(popupSurfaces)
            // Pace to the display: park a background thread on the vsync
            // semaphore. Bounded native-side so a paused link can't deadlock.
            NativeMetalBridge.nativeVSyncWait(handle)
        }

        // ── interop skip-drain (main) ──
        // If the main frame was skipped before its present lambda fired
        // (nativeBeginFrame returned null), the queued AppKit mutations would
        // otherwise leak until the next successful frame. Apply best-effort.
        if (needsTransaction && !mainPresented && tx.actions.isNotEmpty()) {
            tx.performTransaction()
            if (!tx.isInteropActive) rendererIsInteropActive = false
        }
    }

    /**
     * Records each registered overlay/popup surface into a [TaoRecordedSurface]
     * on the main thread. Iterates by token + live lookup so a surface disposed
     * mid-pass (e.g. via a sibling popup's record) is skipped. Disposal runs on
     * this same main thread, so the list is stable for the rest of the pass.
     */
    private fun recordPopupSurfaces(): List<TaoRecordedSurface> {
        if (popupRenderers.isEmpty()) return emptyList()
        val out = ArrayList<TaoRecordedSurface>(popupRenderers.size)
        for (token in popupRenderers.keys.toList()) {
            val surface = popupRenderers[token]?.invoke()
            if (surface != null) out += surface
        }
        return out
    }

    /** Replays previously-recorded overlay/popup surfaces on the render thread. */
    private fun replayPopups(surfaces: List<TaoRecordedSurface>) {
        for (s in surfaces) {
            try {
                // Re-check liveness: a surface can be disposed between record and
                // replay (its `close()` zeroes the handle + closes its context on
                // this thread). Skip rather than replay against a dead surface.
                if (s.isAlive()) {
                    replayPictureToFrame(
                        s.attachmentHandle,
                        s.directContext,
                        s.picture,
                        s.clearColor,
                        s.pictureOffset,
                        s.present,
                    )
                }
            } finally {
                s.picture.close()
            }
        }
    }

    /**
     * Renders one frame synchronously (record on main + blocking replay on the
     * render thread). Used only for the initial paint at window build, where the
     * render thread is idle and no interop is active; the steady-state loop uses
     * [renderFrameSuspending].
     */
    fun renderFrameBlocking() {
        val bundle = sceneBundle ?: return
        val ctx = directContext ?: return
        if (attachmentHandle == 0L || widthPx <= 0 || heightPx <= 0) return
        val mainClear = if (glassBackgroundState.value) 0 else clearColorArgbState.value
        val mainPicture = recordSceneToPicture(bundle, widthPx, heightPx)
        val popupSurfaces = recordPopupSurfaces()
        TaoMainDispatcher.pump()
        val handle = attachmentHandle
        runOnRenderThread {
            try {
                replayPictureToFrame(handle, ctx, mainPicture, mainClear)
            } finally {
                mainPicture.close()
            }
            replayPopups(popupSurfaces)
        }
    }

    fun detach() {
        // Layers whose dismiss animation was still running: Compose closes a
        // native popup layer only when its own disappearance finishes, so an
        // owner destroyed mid-animation left the layer's popup window mapped
        // for good — an invisible rectangle eating every click under it.
        for (layer in liveNativePopupLayers.toList()) layer.close()
        liveNativePopupLayers.clear()
        window.inboundDragAndDropNode = null
        window.imeReplaceCommit = null
        window.imePreedit = null
        window.imeCommit = null
        imeSession.onInputSession(null)
        // The native cache keys on the NSView pointer; leaving it set would
        // let a later view allocated at the same address inherit this
        // window's text and caret.
        NativeTaoBridge.nativeSetImeDocument(window.handle, "", 0L, -1L, -1L)
        shutdownA11yScheduler()
        // Drop the transition hook before the scene goes: a late
        // willEnterFS would otherwise re-enter a torn-down host.
        NativeMetalBridge.setFullscreenPrepare(nsViewHandle, null)
        // Stop driving frames first: after this no new replay is submitted.
        frameDispatcher?.cancel()
        frameDispatcher = null
        renderLoopJob.cancel()
        textToolbar.hide()
        window.clearContentMeasurer()
        sceneBundle?.close()
        sceneBundle = null
        // Drop the TextureView handle before the context it points at dies.
        metalTextureHostCache.invalidate()
        // Close the DirectContext on its owning thread (FIFO after any in-flight
        // replay), then shut the render thread down.
        val ctx = directContext
        directContext = null
        if (ctx != null) {
            runCatching { runOnRenderThread { ctx.close() } }
        }
        renderExecutor.shutdown()
        if (attachmentHandle != 0L) {
            val h = attachmentHandle
            // Stop the CVDisplayLink (synchronous: no callback in flight after
            // this; also wakes any parked waitForVSync) before detaching.
            NativeMetalBridge.nativeStopDisplayLink(h)
            NativeMetalBridge.nativeDetach(h)
            attachmentHandle = 0L
        }
        if (nsViewHandle != 0L) {
            if (dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.isLoaded) {
                dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge
                    .nativeRevoke(nsViewHandle)
            }
            nsViewHandle = 0L
        }
    }

    /**
     * Coroutine dispatcher that funnels Compose's async work onto the macOS
     * main thread.
     *
     * Mirrors the pattern Compose Desktop uses on AWT (`MainUIDispatcher` →
     * `EventQueue.invokeLater`): we delegate to [TaoMainDispatcher], which
     * pumps queued blocks on every `Event::MainEventsCleared` tick of the
     * Tao loop. We also call `window.requestRedraw()` so the loop is woken
     * if it was idle — without it, animations driven by `withFrameNanos`
     * (whose continuations land here when `FrameRecomposer.performFrame`
     * ticks the scene's frame clock) would freeze until input arrives.
     *
     * The auto-pump matters: in the previous implementation, blocks only
     * ran during [onRedrawRequested]'s explicit drain — a chicken-and-egg
     * with redraws being what triggers them in the first place.
     */
    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        override fun dispatch(
            context: KCoroutineContext,
            block: Runnable,
        ) {
            // Only delegate to TaoMainDispatcher (auto-pumps on MAIN_EVENTS_CLEARED).
            // Do NOT call window.requestRedraw() here: every Compose snapshot
            // write goes through this dispatcher, and forcing a redraw on
            // each one floods the event loop with UserEvents that the macOS
            // throttle skips (16ms cap), starving real frames. The scene's
            // own `invalidate` callback already calls requestRedraw whenever
            // there is actually something new to draw.
            TaoMainDispatcher.dispatch(context, block)
        }

        fun enqueue(block: Runnable) {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext, block)
        }
    }
}

/*
 * `PlatformContext` for the Tao backend. Mirrors what `ComposeSceneMediator`
 * does on Compose Desktop: when Compose hovers over content with a pointer-icon
 * modifier (notably `BasicTextField` → `PointerIcon.Text`), it calls
 * [setPointerIcon] which we forward to Tao's `Window::set_cursor_icon`.
 *
 * Standard Compose icons (`PointerIcon.Default/Text/Hand/Crosshair`) are
 * singletons we recognise via `===`. Custom `PointerIcon(Cursor(...))`
 * instances wrap a `java.awt.Cursor` inside the internal `AwtCursor` class —
 * we read it back via reflection (its public-but-not-API `getCursor` method).
 */
@OptIn(InternalComposeUiApi::class)
private class TaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    /** Live px-per-dp factor of the owning scene — see [TaoPlatformContextBase.sceneScale]. */
    private val scaleProvider: () -> Float,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
    private val onInputSession: (androidx.compose.ui.platform.PlatformTextInputMethodRequest?) -> Unit,
    // #559: forwarded to Compose so `CanvasLayersComposeScene` picks the
    // alpha-aware dialog-scrim blend mode (`BlendMode.SrcAtop`) on windows
    // created with `transparent = true` — same as Compose Desktop's
    // `DesktopPlatformContext` forwarding `windowContext.isWindowTransparent`.
    override val isWindowTransparent: Boolean = false,
) : TaoPlatformContextBase() {
    override val sceneScale: Float get() = scaleProvider()

    // Compose's Popup framework reads `LocalPlatformWindowInsets.current.systemBars`
    // when `usePlatformInsets = true` (the default). The popup positioning logic
    // then operates inside `windowSize - insets`, so a `top` inset matching our
    // custom title bar prevents context menus from overflowing into it. We
    // expose a dynamic inset (lambda) so the value tracks the actual title-bar
    // height even if the user changes it at runtime.
    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform
                    .PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        NativeTaoBridge.setCursorIcon(windowHandle, mapPointerIcon(pointerIcon))
    }

    /**
     * Compose calls this when a `BasicTextField` gains focus. We use it to
     * keep Tao's IME spot in sync with the caret rectangle so macOS candidate
     * windows appear at the caret instead of the window's top-left corner.
     *
     * Mirrors `DesktopTextInputService2.startInput` in compose-multiplatform-core
     * but feeds the position through Tao's `Window::set_ime_position` rather
     * than AWT's `InputMethodRequests.getTextLocation`.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override suspend fun startInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
    ): Nothing {
        // Keep TaoView as firstResponder. Activating its NSTextInputContext
        // (and swizzling selectedRange / validAttributes / firstRect) is
        // what lets AppKit's PressAndHold accent picker engage; a hidden
        // NSTextView overlay was tried and rejected because it forced an
        // I-beam cursor for the whole window.
        NativeTaoBridge.nativeActivateInputContext(windowHandle)
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
                launch {
                    // macOS document cache (Chromium parity: the renderer
                    // pushes selection + surrounding text so the browser
                    // view can answer `selectedRange` /
                    // `attributedSubstringForProposedRange` locally). This
                    // is what lets AppKit's press-and-hold picker engage on
                    // the committed text and commit through
                    // `insertText:replacementRange:` (#611/#612).
                    androidx.compose.runtime
                        .snapshotFlow {
                            request.value()
                        }.collect { value ->
                            pushImeDocument(windowHandle, value)
                        }
                }
                awaitCancellation()
            }
        } finally {
            NativeTaoBridge.nativeSetImeDocument(windowHandle, "", 0L, -1L, -1L)
            onInputSession(null)
        }
    }

    /**
     * Pushes a bounded UTF-16 window of the field text around the selection
     * (Chromium ships ±100 chars; we ship ±[IME_DOCUMENT_WINDOW_UTF16]) plus
     * the document-absolute selection. Window edges are nudged off surrogate
     * pairs so the native `NSString` never receives a half code point.
     */
    private fun pushImeDocument(
        windowHandle: Long,
        value: androidx.compose.ui.text.input.TextFieldValue,
    ) {
        val text = value.text
        val selMin = value.selection.min
        val selMax = value.selection.max
        var start = (selMin - IME_DOCUMENT_WINDOW_UTF16).coerceAtLeast(0)
        var end = (selMax + IME_DOCUMENT_WINDOW_UTF16).coerceAtMost(text.length)
        if (start in 1 until text.length && text[start].isLowSurrogate()) start--
        if (end in 1 until text.length && text[end].isLowSurrogate()) end++
        NativeTaoBridge.nativeSetImeDocument(
            windowHandle,
            text.substring(start, end),
            start.toLong(),
            selMin.toLong(),
            selMax.toLong(),
        )
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int = icon.toTaoCursorIconCode()
}

/**
 * UTF-16 code units of committed text shipped on each side of the selection.
 * This runs on every keystroke and caret move, so it stays close to the ±100
 * Chromium ships: the only reader is `attributedSubstringForProposedRange:`,
 * which AppKit only ever asks near the caret.
 */
private const val IME_DOCUMENT_WINDOW_UTF16 = 128
