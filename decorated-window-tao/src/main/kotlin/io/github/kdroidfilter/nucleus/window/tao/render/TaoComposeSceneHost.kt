package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext as KCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon
import io.github.kdroidfilter.nucleus.window.tao.TaoEventCode
import io.github.kdroidfilter.nucleus.window.tao.TaoModifierMask
import java.awt.Cursor
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.tao.MacOSStyle
import io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoMainDispatcher
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import io.github.kdroidfilter.nucleus.window.tao.shouldApplyLargeCornerRadius
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
internal class TaoComposeSceneHost(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    private val macOSStyle: MacOSStyle = MacOSStyle.Auto,
) {
    // Backed by `MutableState` so the `TitleBar` composable's SideEffect
    // (`heightHolder.value = ...`) updates the value reactively and our
    // PlatformContext re-reads it via the `topInsetPx` lambda. Compose's
    // Popup framework subtracts this top inset from the available area, so
    // context menus and dropdowns can't overflow into the title bar zone.
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

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
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    // Mirrors `PlatformWindowContext.desktop.kt` — Compose's `Popup` framework
    // reads `LocalWindowInfo.current.containerSize` to know how large the host
    // window is, which is the basis for the popup positioning math (see
    // `ContextMenuPopupPositionProvider.calculatePosition` and
    // `Popup.skiko.kt:positionWithInsets`). Without a real value the popup
    // collapses the available area to zero and consistently places itself
    // above the click → the "inverted" feel reported by users.
    private val windowInfo = TaoWindowInfo()
    private var attachmentHandle: Long = 0
    private var nsViewHandle: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val frameClock = BroadcastFrameClock()
    // Dispatcher that funnels Compose's async work (notably MouseWheel scroll
    // dispatching, which uses the scene's coroutineContext) onto the render
    // thread. Without it, Compose attempts measure/layout from a worker
    // coroutine and throws "performMeasureAndLayout called during measure
    // layout".
    private val flushingDispatcher = FlushingMainDispatcher()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // Frame pacing is delegated to the CAMetalLayer's `displaySyncEnabled`
    // (default YES): `nextDrawable` blocks for vsync, naturally capping the
    // loop at the display refresh rate. Mirrors Windows/Linux where Tao
    // backends rely on `wglSwapIntervalEXT(1)` / GLX swap interval. A software
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

        val handle = NativeMetalBridge.nativeAttach(nsView)
        require(handle != 0L) { "Failed to attach CAMetalLayer to NSView" }
        attachmentHandle = handle

        val devicePtr = NativeMetalBridge.nativeDevicePtr(handle)
        val queuePtr = NativeMetalBridge.nativeQueuePtr(handle)
        directContext = DirectContext.makeMetal(devicePtr, queuePtr)

        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f

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
        val dndManager = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropManager(
            getRootNode = { scene!!.rootDragAndDropNode },
            outboundLauncher = ::launchMacOsOutboundDrag,
        )
        scene = CanvasLayersComposeScene(
            density = Density(scale),
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = coroutineContext + frameClock + flushingDispatcher,
            platformContext = TaoPlatformContext(
                windowHandle = window.handle,
                topInsetPx = { (titleBarHeightDpState.value * scale).toInt() },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
            ),
            invalidate = {
                window.requestRedraw()
            },
        )

        registerInboundDnD()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun launchMacOsOutboundDrag(
        request: io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        if (!io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.isLoaded) return null
        if (nsViewHandle == 0L) return null

        val allowed = request.supportedActions.fold(0) { acc, action ->
            acc or when (action) {
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_MOVE
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_LINK
                else -> 0
            }
        }.let { if (it == 0) {
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
        } else it }

        val files = request.files.takeIf { it.isNotEmpty() }
            ?.map { it.absolutePath }?.toTypedArray()
        val effect = io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.nativeStartDrag(
            nsView = nsViewHandle,
            files = files,
            text = request.text,
            allowedEffects = allowed,
        )
        return when (effect) {
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_MOVE ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_LINK ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link
            else -> null
        }
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.isLoaded) {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "macOS DnD lib not loaded — inbound disabled",
            )
            return
        }
        val callback = InboundDnDCallback()
        val rc = io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.nativeRegister(
            nsView = nsViewHandle,
            callback = callback,
        )
        io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log("registerForDraggedTypes rc=$rc")
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback :
        io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.Callback {

        private fun rootNode() = scene?.rootDragAndDropNode

        private fun makeDragEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropPayload(
                files = files?.toList() ?: emptyList(),
            )
            val transferable = io.github.kdroidfilter.nucleus.window.tao.TaoFilesTransferable(
                files = payload.files.map { java.io.File(it) },
            )
            val native = io.github.kdroidfilter.nucleus.window.tao.TaoSyntheticDragEvent(
                cursorLocn = java.awt.Point(xPx, yPx),
                dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                backingTransferable = transferable,
                payload = payload,
            )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl = androidx.compose.ui.geometry.Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        private fun makeDropEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropPayload(
                files = files?.toList() ?: emptyList(),
            )
            val transferable = io.github.kdroidfilter.nucleus.window.tao.TaoFilesTransferable(
                files = payload.files.map { java.io.File(it) },
            )
            val native = io.github.kdroidfilter.nucleus.window.tao.TaoSyntheticDropEvent(
                cursorLocn = java.awt.Point(xPx, yPx),
                dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                backingTransferable = transferable,
                payload = payload,
            )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl = androidx.compose.ui.geometry.Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        override fun onDragEnter(nsView: Long, x: Int, y: Int, modState: Int, hasFiles: Boolean): Int {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "onDragEnter x=$x y=$y hasFiles=$hasFiles",
            )
            if (!hasFiles) {
                return io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            val accepted = node.acceptDragAndDropTransfer(ev)
            if (accepted) {
                node.onStarted(ev)
                node.onEntered(ev)
            }
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(nsView: Long, x: Int, y: Int, modState: Int, hasFiles: Boolean): Int {
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            node.onMoved(ev)
            return if (node.hasEligibleDropTarget) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragLeave(nsView: Long) {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log("onDragLeave")
            val node = rootNode() ?: return
            val ev = makeDragEvent(-1, -1, null)
            node.onExited(ev)
            node.onEnded(ev)
        }

        override fun onDrop(nsView: Long, x: Int, y: Int, modState: Int, files: Array<String>?): Int {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "onDrop x=$x y=$y files=${files?.size ?: 0}",
            )
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            val ev = makeDropEvent(x, y, files)
            val accepted = node.onDrop(ev)
            node.onEnded(ev)
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content)
    }

    fun onResized(widthPxNew: Int, heightPxNew: Int) {
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        window.requestRedraw()
    }

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

    // Debounce a11y syncs so a burst of `onSemanticsChange` callbacks during
    // recomposition collapses into a single push at the next render tick.
    private var a11ySyncScheduled: Runnable? = null

    /**
     * Schedules [block] to run on the macOS main thread "soon" — at the next
     * redraw. Used by the SemanticsObserver to coalesce per-recomposition
     * change notifications into one snapshot push per frame.
     */
    fun scheduleA11ySync(block: () -> Unit) {
        if (a11ySyncScheduled != null) return
        val r = Runnable {
            a11ySyncScheduled = null
            block()
        }
        a11ySyncScheduled = r
        flushingDispatcher.enqueue(r)
        window.requestRedraw()
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

    fun onRedrawRequested() {
        val ctx = directContext ?: return
        val sc = scene ?: return

        val frame = NativeMetalBridge.nativeBeginFrame(attachmentHandle) ?: return

        var presented = false
        try {
            val rt = BackendRenderTarget.makeMetal(frame.widthPx, frame.heightPx, frame.texturePtr)
            val surface = Surface.makeFromBackendRenderTarget(
                context = ctx,
                rt = rt,
                origin = SurfaceOrigin.TOP_LEFT,
                colorFormat = SurfaceColorFormat.BGRA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: return
            try {
                // CAMetalLayer drawables are not auto-cleared between frames; an
                // uninitialised texture on Apple Silicon shows up as undefined
                // memory (often magenta-ish). Clear to opaque white so any
                // Compose region without an explicit background looks like a
                // standard AWT/Compose-Desktop window.
                surface.canvas.clear(0xFFFFFFFF.toInt())
                val nanoTime = System.nanoTime()
                sc.render(surface.canvas.asComposeCanvas(), nanoTime)
                surface.flushAndSubmit(syncCpu = false)
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
                presented = true
                // Drain Compose's async work (sendFrame continuations,
                // recomposer steps) **synchronously** here so their state
                // writes happen now and trigger `invalidate` → next
                // requestRedraw inside the same Tao loop iteration. Without
                // this, the work would sit in TaoMainDispatcher.pending until
                // the loop happens to wake again — which on macOS only
                // reliably occurs on input events. This mirrors Skiko's
                // FrameDispatcher pattern: render → drain pending coroutine
                // work → next frame.
                TaoMainDispatcher.pump()
            } finally {
                surface.close()
                rt.close()
            }
        } finally {
            if (!presented) {
                // Drawable was retained in beginFrame; release via present to balance.
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
            }
        }
    }

    /** [aFixed] / [bFixed] are physical pixels × 1024 (see `CURSOR_FIXED_SCALE`). */
    fun onPointerMove(aFixed: Int, bFixed: Int) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(xPx, yPx),
            type = PointerType.Mouse,
        )
    }

    fun onPointerExited() {
        scene?.sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
        )
    }

    fun onPointerButton(buttonCode: Int, pressed: Boolean) {
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            button = mapButton(buttonCode),
        )
    }

    /**
     * [dxAwt]/[dyAwt] are pre-shaped to match AWT `MouseWheelEvent.preciseWheelRotation`
     * (see [TaoWindow.onPointerScroll]). Compose's `MacOSCocoaConfig` will then
     * apply `× 10dp.toPx() × -scrollAmount` to convert into pixel scroll.
     */
    fun onPointerScroll(dxAwt: Float, dyAwt: Float) {
        scene?.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(lastPointerX, lastPointerY),
            scrollDelta = Offset(dxAwt, dyAwt),
            type = PointerType.Mouse,
        )
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
        val isCtrl = (modifiers and TaoModifierMask.CONTROL) != 0
        val isMeta = (modifiers and TaoModifierMask.META) != 0
        val isAlt = (modifiers and TaoModifierMask.ALT) != 0
        val isShift = (modifiers and TaoModifierMask.SHIFT) != 0
        val composeEvent = when (type) {
            TaoEventCode.KEY_DOWN, TaoEventCode.KEY_UP -> {
                KeyEvent(
                    key = Key(nativeKeyCode = vkCode, nativeKeyLocation = keyLocation),
                    type = if (type == TaoEventCode.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                    codePoint = codePoint,
                    isCtrlPressed = isCtrl,
                    isMetaPressed = isMeta,
                    isAltPressed = isAlt,
                    isShiftPressed = isShift,
                )
            }
            TaoEventCode.KEY_TYPED -> {
                val ch = codePoint.toChar()
                val awtModifiers = (if (isShift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
                    (if (isCtrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0) or
                    (if (isAlt) java.awt.event.InputEvent.ALT_DOWN_MASK else 0) or
                    (if (isMeta) java.awt.event.InputEvent.META_DOWN_MASK else 0)
                val awtEvent = java.awt.event.KeyEvent(
                    SyntheticEventSource,
                    java.awt.event.KeyEvent.KEY_TYPED,
                    System.currentTimeMillis(),
                    awtModifiers,
                    java.awt.event.KeyEvent.VK_UNDEFINED,
                    ch,
                    java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
                )
                KeyEvent(
                    key = Key(nativeKeyCode = 0, nativeKeyLocation = keyLocation),
                    type = KeyEventType.Unknown,
                    codePoint = codePoint,
                    isCtrlPressed = isCtrl,
                    isMetaPressed = isMeta,
                    isAltPressed = isAlt,
                    isShiftPressed = isShift,
                    nativeEvent = awtEvent,
                )
            }
            else -> return false
        }
        if (previewKeyHandler?.invoke(composeEvent) == true) return true
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    private companion object {
        // Dummy AWT Component used as `source` for synthetic `KEY_TYPED` events.
        // `java.awt.event.KeyEvent` requires a non-null Component; we never
        // dispatch the event back to AWT so a never-shown panel is enough.
        private val SyntheticEventSource: java.awt.Component = javax.swing.JPanel()
    }

    fun detach() {
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeMetalBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
        if (nsViewHandle != 0L) {
            if (io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.isLoaded) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoMacOsDndBridge.nativeRevoke(nsViewHandle)
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
        override fun dispatch(context: KCoroutineContext, block: Runnable) {
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

    private fun mapButton(code: Int): androidx.compose.ui.input.pointer.PointerButton {
        // Compose's PointerButton has Primary/Secondary/Tertiary plus generic indices.
        return when (code) {
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.LEFT ->
                androidx.compose.ui.input.pointer.PointerButton.Primary
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.RIGHT ->
                androidx.compose.ui.input.pointer.PointerButton.Secondary
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.MIDDLE ->
                androidx.compose.ui.input.pointer.PointerButton.Tertiary
            else -> androidx.compose.ui.input.pointer.PointerButton.Primary
        }
    }
}

/**
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
/**
 * Mutable [androidx.compose.ui.platform.WindowInfo] backed by snapshot state.
 * Mirrors the upstream `WindowInfoImpl` (which is `internal` to compose-ui).
 *
 * `containerSize` is read by `Popup.skiko.kt` (via `LocalWindowInfo.current`)
 * to compute the available area for popup positioning. Must be updated by the
 * host on every resize / scale-factor change, otherwise popup positioning
 * collapses to a zero-sized window and menus consistently flip above the click.
 */
internal class TaoWindowInfo : androidx.compose.ui.platform.WindowInfo {
    override var isWindowFocused: Boolean by androidx.compose.runtime.mutableStateOf(true)
    override var containerSize: androidx.compose.ui.unit.IntSize
        by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    override var containerDpSize: androidx.compose.ui.unit.DpSize
        by androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.DpSize.Zero)
}

@OptIn(InternalComposeUiApi::class)
private class TaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
) : androidx.compose.ui.platform.PlatformContext.Empty() {
    // Compose's Popup framework reads `LocalPlatformWindowInsets.current.systemBars`
    // when `usePlatformInsets = true` (the default). The popup positioning logic
    // then operates inside `windowSize - insets`, so a `top` inset matching our
    // custom title bar prevents context menus from overflowing into it. We
    // expose a dynamic inset (lambda) so the value tracks the actual title-bar
    // height even if the user changes it at runtime.
    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform.PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        NativeTaoBridge.nativeSetCursorIcon(windowHandle, mapPointerIcon(pointerIcon))
    }

    /**
     * Compose calls this when a `BasicTextField` gains focus. We use it to
     * keep Tao's IME spot in sync with the caret rectangle so the macOS
     * press-and-hold accent picker (and any future IME candidate window)
     * appears at the caret instead of the window's top-left corner.
     *
     * Mirrors `DesktopTextInputService2.startInput` in compose-multiplatform-core
     * but feeds the position through Tao's `Window::set_ime_position` rather
     * than AWT's `InputMethodRequests.getTextLocation`.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override suspend fun startInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
    ): Nothing {
        // AppKit's press-and-hold logic in `_NSKeyBindingManager` engages
        // only when the firstResponder's class lineage includes NSTextView.
        // We installed a transparent NSTextView overlay at attach time; here
        // we make it the firstResponder so AppKit dispatches keys through it
        // (engaging the marked-text phase + accent picker) while every
        // NSTextInputClient call is forwarded back to TaoView's existing
        // pipeline. Activate the inputContext too — required so AppKit
        // routes through this context's NSKeyBindingManager instance.
        NativeTaoBridge.nativeActivateInputContext(windowHandle)
        NativeTaoBridge.nativeFocusTextOverlay(true)
        try {
            coroutineScope {
                launch {
                    androidx.compose.runtime.snapshotFlow {
                        request.focusedRectInRoot()
                    }.collect { rect ->
                        if (rect != null) {
                            // `focusedRectInRoot` is in root-pixel space; pass
                            // it as a real-sized rect — AppKit's press-and-hold
                            // logic gates on `firstRectForCharacterRange:`
                            // returning non-zero size.
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
            // Compose cancelled the input session (TextField lost focus). Hand
            // firstResponder back to TaoView so non-text key events resume.
            NativeTaoBridge.nativeFocusTextOverlay(false)
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
