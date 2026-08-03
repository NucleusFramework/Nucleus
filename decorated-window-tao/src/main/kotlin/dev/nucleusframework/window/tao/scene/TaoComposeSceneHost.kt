@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.scene

import androidx.compose.runtime.BroadcastFrameClock
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
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.MacOSStyle
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoModifierMask
import dev.nucleusframework.window.tao.TaoNativeViewHost
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoTrackpadGesture
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.event.TaoSyntheticMouseWheelEvent
import dev.nucleusframework.window.tao.event.taoKeyEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.event.taoTypedKeyEvent
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDecoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.initialMacOsScaleFactor
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
import org.jetbrains.skia.DirectContext
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
@OptIn(InternalComposeUiApi::class)
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
     * used by `decorated-window-jni`'s `onPreviewKeyEvent`.
     */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * App-level post-dispatch hook. Fires only when the scene did not consume
     * the event. Returning `true` marks it as handled. Mirrors
     * `decorated-window-jni`'s `onKeyEvent`.
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
    private var scene: ComposeScene? = null

    /** Parent locals bridged via [setSceneCompositionLocalContext]; applied to the scene once created. */
    private var pendingCompositionLocalContext: androidx.compose.runtime.CompositionLocalContext? = null
    private val frameClock = BroadcastFrameClock()

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

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

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
    // emits a Release at the last known position to close out the stale
    // interaction, then emits the new Press. A Release received while not
    // pressed is dropped (Compose would otherwise crash inside the input
    // processor on a Release for an unknown pointer).
    private var isPressed: Boolean = false

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
        // thread that will use it for every frame's GPU encode + present.
        directContext = runOnRenderThread { DirectContext.makeMetal(devicePtr, queuePtr) }

        scale = initialMacOsScaleFactor(window)

        // Present the fullscreen layout before AppKit snapshots the window for
        // its transition animation. Runs re-entrantly on the AppKit main
        // thread from inside `windowWillEnterFullScreen:` — see
        // NativeMetalBridge.onFullscreenPrepare and #327.
        NativeMetalBridge.setFullscreenPrepare(nsViewHandle) { targetW, targetH ->
            prepareFullscreenFrame(targetW, targetH)
        }

        // CRITICAL: provide our own MonotonicFrameClock (BroadcastFrameClock)
        // in the scene's coroutineContext. Without one, Compose's recomposer
        // can't tell when a frame has finished and re-fires `invalidate` after
        // every render — causing a continuous render loop that saturates the
        // main thread. We tick the clock manually at the end of each
        // onRedrawRequested.
        // The DnD manager needs lazy access to the scene's rootDragAndDropNode,
        // but the scene cannot be constructed before we hand it the
        // PlatformContext that owns the manager. Resolve on each call.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val dndManager =
            dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager(
                getRootNode = { scene!!.rootDragAndDropNode },
                outboundLauncher = ::launchMacOsOutboundDrag,
            )

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
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
                textToolbar = textToolbar,
            )

        val hostPopupHost = if (nativePopupLayers) popupHost() else null
        scene =
            if (hostPopupHost != null) {
                // Opt-in path (e.g. tray popups): every Popup becomes a native
                // NSPanel owned by this window, so popup content can extend
                // beyond — and float independently of — the window bounds.
                PlatformLayersComposeScene(
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    size = IntSize(widthPx, heightPx),
                    coroutineContext = coroutineContext + frameClock + flushingDispatcher,
                    composeSceneContext =
                        TaoComposeSceneContext(
                            platformContext = taoPlatformContext,
                        ) { density, layoutDirection, focusable, cc ->
                            TaoPopupSceneLayer(
                                host = hostPopupHost,
                                initialDensity = density,
                                initialLayoutDirection = layoutDirection,
                                initialFocusable = focusable,
                                parentCompositionContext = cc,
                            )
                        },
                    invalidate = {
                        frameDispatcher?.scheduleFrame()
                    },
                ).apply { compositionLocalContext = pendingCompositionLocalContext }
            } else {
                // Match Windows and Linux for the main host scene: Compose
                // Popup / DropdownMenu / Tooltip content stays in the same
                // Metal render target instead of becoming a native NSPanel.
                // NativeView overlay scenes still opt into TaoComposeSceneContext
                // when their popups must float above an embedded AppKit view.
                CanvasLayersComposeScene(
                    density = Density(scale),
                    layoutDirection = GlobalLayoutDirection,
                    size = IntSize(widthPx, heightPx),
                    coroutineContext = coroutineContext + frameClock + flushingDispatcher,
                    platformContext = taoPlatformContext,
                    invalidate = {
                        // Schedule a frame on the render loop (coalesced); it renders
                        // then waits for the next vsync. See startRenderLoop.
                        frameDispatcher?.scheduleFrame()
                    },
                ).apply { compositionLocalContext = pendingCompositionLocalContext }
            }

        registerInboundDnD()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun launchMacOsOutboundDrag(
        request: dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        if (!dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge.isLoaded) return null
        if (nsViewHandle == 0L) return null
        return dev.nucleusframework.window.tao.dnd.TaoSceneDnD.launchOutboundDrag(
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
        private fun node() = scene?.rootDragAndDropNode

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

    fun setContent(content: @Composable () -> Unit) {
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

    // Tao's macOS pipeline intercepts keys before AppKit's responder
    // chain, so an overlay NSView can't receive `keyDown:` natively. The
    // host's `onKeyEvent` consults these handlers first; returning `true`
    // consumes the event before the main scene sees it.
    private val popupKeyHandlers: MutableMap<Any, (KeyEvent) -> Boolean> = LinkedHashMap()

    fun nativeViewHost(): TaoNativeViewHost? {
        if (nsViewHandle == 0L) return null
        if (!NativeTaoMacOsNativeViewBridge.isLoaded) return null
        val outer = this
        return object : TaoNativeViewHost {
            override fun attach(childHandle: Long) {
                // Eager. NativeView.kt's DisposableEffect relies on the
                // ordering `host.attach() -> overlay.attach()` so the
                // overlay's `nativeCreateOverlay` lands ABOVE the user's
                // subview in the parent's subview list (NSView z-order
                // = order of addition for siblings positioned with
                // NSWindowAbove relativeTo:nil). Deferring this would
                // re-order the adds and bury the overlay behind the
                // WKWebView. The visual-sync win we want is for
                // *reposition*, not for mount, so subview list mutation
                // stays eager.
                if (outer.interopAttachCount == 0) {
                    outer.transaction.isInteropActive = true
                }
                outer.interopAttachCount++
                NativeTaoMacOsNativeViewBridge.nativeAddSubview(outer.nsViewHandle, childHandle)
            }

            override fun detach(childHandle: Long) {
                NativeTaoMacOsNativeViewBridge.nativeRemoveSubview(childHandle)
                outer.interopAttachCount--
                if (outer.interopAttachCount == 0) {
                    outer.transaction.isInteropActive = false
                }
            }

            override fun setFrame(
                handle: Long,
                xPx: Int,
                yPx: Int,
                widthPx: Int,
                heightPx: Int,
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

            override fun scheduleInterop(action: () -> Unit) {
                outer.scheduleInteropAction(action)
            }
        }
    }

    /**
     * Enqueues an AppKit mutation to be drained inside the next frame's
     * transaction. Accessible to the overlay controller so its own
     * `nativeSetOverlayFrame` calls share the same atomic CATransaction
     * as the user's subview frame change.
     */
    internal fun scheduleInteropAction(action: TaoInteropAction) {
        transaction.add(action)
        window.requestRedraw()
    }

    fun popupHost(): TaoPopupHost? {
        if (nsViewHandle == 0L) return null
        val outer = this
        return object : TaoPopupHost {
            override val parentNsView: Long get() = outer.nsViewHandle
            override val scale: Float get() = outer.scale
            override val parentWindowSize: IntSize get() = IntSize(outer.widthPx, outer.heightPx)
            override val workAreaSize: IntSize get() {
                val packed = NativeMetalBridge.nativeOwnerWorkAreaSize(outer.nsViewHandle)
                if (packed == 0L) return parentWindowSize
                val w = (packed ushr 32).toInt()
                val h = (packed and 0xFFFFFFFFL).toInt()
                return if (w > 0 && h > 0) IntSize(w, h) else parentWindowSize
            }
            override val sceneCoroutineContext: CoroutineContext
                get() = outer.coroutineContext + outer.frameClock + outer.flushingDispatcher

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
                NativeTaoBridge.nativeSetCursorIcon(outer.window.handle, iconCode)
            }
        }
    }

    /** Stable per-window [TaoMetalTextureHost]; created lazily after [attach]. */
    private var metalTextureHostCache: TaoMetalTextureHost? = null

    /**
     * Window scene's handle for the `TextureView` composable: the Metal device
     * this window renders with, its Skia context, and its render thread. Null
     * until [attach] has created them. Cached so composition sees a stable
     * instance (the import registry keys on it).
     */
    fun metalTextureHost(): TaoMetalTextureHost? {
        metalTextureHostCache?.let { return it }
        val ctx = directContext ?: return null
        if (attachmentHandle == 0L) return null
        val device = NativeMetalBridge.nativeDevicePtr(attachmentHandle)
        if (device == 0L) return null
        val outer = this
        val created =
            object : TaoMetalTextureHost {
                override val metalDevicePtr: Long = device
                override val directContext: DirectContext = ctx

                override fun <T> runOnRenderThread(block: () -> T): T = outer.runOnRenderThread(block)
            }
        metalTextureHostCache = created
        return created
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
        lastPointerX = xPx
        lastPointerY = yPx
        hasReceivedCursorMove = true
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
            // interaction so Compose hit-tests this Press fresh. See the
            // comment on `isPressed` for the rationale.
            scene?.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(lastPointerX, lastPointerY),
                type = PointerType.Mouse,
                keyboardModifiers = currentKeyboardModifiers,
                button = composeButton,
            )
        } else if (!pressed && !isPressed) {
            // Stray Release without a matching Press — drop it.
            return
        }
        isPressed = pressed
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            keyboardModifiers = currentKeyboardModifiers,
            button = composeButton,
        )
    }

    /**
     * [event] is pre-shaped to match AWT `MouseWheelEvent.preciseWheelRotation`
     * and carries a synthetic native event so Compose's desktop scroll config
     * can read `scrollAmount` and precise-wheel metadata like the AWT backend.
     */
    fun onPointerScroll(event: TaoPointerScrollEvent) {
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
    private val renderExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "TaoMetalRender").apply { isDaemon = true }
        }
    private val renderDispatcher = renderExecutor.asCoroutineDispatcher()

    /**
     * Runs [block] on the render thread and blocks until it returns. Used for
     * `DirectContext` create/use/close that must respect Skia's Metal context
     * thread-affinity. Safe to call from the main thread during composition /
     * disposal / lifecycle — at those points the render thread is idle (see the
     * lifetime invariant above), so it never deadlocks against a replay.
     */
    fun <T> runOnRenderThread(block: () -> T): T = renderExecutor.submit(Callable { block() }).get()

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
        val scope =
            kotlinx.coroutines.CoroutineScope(coroutineContext + TaoMainDispatcher + renderLoopJob)
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
        val sc = scene ?: return
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
        val mainPicture = recordSceneToPicture(sc, widthPx, heightPx)
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
        val sc = scene ?: return
        val ctx = directContext ?: return
        if (attachmentHandle == 0L || widthPx <= 0 || heightPx <= 0) return
        val mainClear = if (glassBackgroundState.value) 0 else clearColorArgbState.value
        val mainPicture = recordSceneToPicture(sc, widthPx, heightPx)
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
        shutdownA11yScheduler()
        // Drop the transition hook before the scene goes: a late
        // willEnterFS would otherwise re-enter a torn-down host.
        NativeMetalBridge.setFullscreenPrepare(nsViewHandle, null)
        // Stop driving frames first: after this no new replay is submitted.
        frameDispatcher?.cancel()
        frameDispatcher = null
        renderLoopJob.cancel()
        textToolbar.hide()
        scene?.close()
        scene = null
        // Drop the TextureView handle before the context it points at dies.
        metalTextureHostCache = null
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
     * (whose continuations land here when `frameClock.sendFrame` fires
     * inside `BaseComposeScene.recompose`) would freeze until input arrives.
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
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
    override val textToolbar: androidx.compose.ui.platform.TextToolbar,
) : PlatformContext.Empty() {
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
        NativeTaoBridge.nativeSetCursorIcon(windowHandle, mapPointerIcon(pointerIcon))
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
        // Keep TaoView as firstResponder, matching Compose AWT's single
        // component model. Making a hidden NSTextView firstResponder causes
        // AppKit to push an I-beam cursor for the whole window before the
        // first pointer interaction on macOS.
        NativeTaoBridge.nativeActivateInputContext(windowHandle)
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
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int {
        when {
            icon === androidx.compose.ui.input.pointer.PointerIcon.Default -> return TaoCursorIcon.DEFAULT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Text -> return TaoCursorIcon.TEXT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Hand -> return TaoCursorIcon.HAND
            icon === androidx.compose.ui.input.pointer.PointerIcon.Crosshair -> return TaoCursorIcon.CROSSHAIR
        }
        return runCatching {
            val cursor = icon.javaClass.getMethod("getCursor").invoke(icon) as? java.awt.Cursor
            when (cursor?.type) {
                java.awt.Cursor.TEXT_CURSOR -> TaoCursorIcon.TEXT
                java.awt.Cursor.HAND_CURSOR -> TaoCursorIcon.HAND
                java.awt.Cursor.CROSSHAIR_CURSOR -> TaoCursorIcon.CROSSHAIR
                java.awt.Cursor.WAIT_CURSOR -> TaoCursorIcon.WAIT
                java.awt.Cursor.MOVE_CURSOR -> TaoCursorIcon.MOVE
                java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR -> TaoCursorIcon.EW_RESIZE
                java.awt.Cursor.N_RESIZE_CURSOR, java.awt.Cursor.S_RESIZE_CURSOR -> TaoCursorIcon.NS_RESIZE
                java.awt.Cursor.NE_RESIZE_CURSOR, java.awt.Cursor.SW_RESIZE_CURSOR -> TaoCursorIcon.NESW_RESIZE
                java.awt.Cursor.NW_RESIZE_CURSOR, java.awt.Cursor.SE_RESIZE_CURSOR -> TaoCursorIcon.NWSE_RESIZE
                else -> TaoCursorIcon.DEFAULT
            }
        }.getOrDefault(TaoCursorIcon.DEFAULT)
    }
}
