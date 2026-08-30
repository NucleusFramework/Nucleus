package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.TaoDnDDiagnostics
import dev.nucleusframework.window.tao.TaoScreenGeometry
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.dnd.TaoDragAndDropManager
import dev.nucleusframework.window.tao.dnd.TaoSceneDnD
import dev.nucleusframework.window.tao.event.appKitWheelToAwtScrollEvent
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsDndBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.MetalTextureHostCache
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.newMetalRenderExecutor
import dev.nucleusframework.window.tao.scene.recordSceneToPicture
import dev.nucleusframework.window.tao.scene.replayPictureToFrame
import org.jetbrains.skia.DirectContext
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * Standalone transparent popup surface (macOS): an ownerless, non-activating
 * `NSPanel` with a per-pixel transparent `CAMetalLayer`, driving its own Compose
 * scene. The macOS counterpart of [TaoStandalonePopupHost] (Windows): same
 * public surface so [dev.nucleusframework.window.tao.TaoStandalonePopup] can
 * route per platform without a per-platform composable.
 *
 * Differences from the Windows host:
 *  - No headless EGL bootstrap and no `WM_PAINT`; the panel is ownerless and
 *    rendering is driven purely on demand (scene invalidate → scheduleRender).
 *  - Metal, not OpenGL: the Skia [DirectContext] is created on — and only ever
 *    used on — a dedicated render thread (Skia's Metal context is thread-affine,
 *    and `[CAMetalLayer nextDrawable]` can block, so the Tao main loop stays
 *    free). Each frame is **recorded** into a Skia `Picture` on the main thread
 *    (Compose state lives there) then **replayed + presented** on the render
 *    thread, reusing [recordSceneToPicture] / [replayPictureToFrame].
 *
 * Threading: construction and every public method must run on the Tao main
 * thread (the composable wrapper guarantees this). Rendering is scheduled
 * through [TaoMainDispatcher] and paced at ~60 fps for self-invalidating
 * content (animations).
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoStandalonePopupHostMac : StandalonePopupHost {
    override val isValid: Boolean

    private var panel: Long = 0
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene
    private var disposed = false

    /**
     * Primary-monitor scale, captured once: a tray popup lives on the primary
     * monitor by definition. Live DPI changes are NOT tracked.
     */
    override val scale: Float = TaoScreenGeometry.primaryMonitorScaleFactor()

    private var widthPx: Int = 1
    private var heightPx: Int = 1

    override var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    override var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    private val flushingDispatcher = FlushingDispatcher()
    private val windowInfo = StandalonePopupWindowInfo()

    private val framePump = StandaloneFramePump { renderNow() }
    private val replayInFlight = AtomicBoolean(false)
    private var nextFrameNs = 0L
    private var visible = false

    // Declared before the init block: the init block calls runOnRenderThread to
    // create the Skia DirectContext, and Kotlin runs property initializers +
    // init blocks in declaration order — a later-declared executor would be
    // null at that point. (Skia's Metal context is thread-affine, so it is owned
    // by this single-thread executor for the lifetime of the host.)
    // Every task drains its own ObjC autorelease pool — see
    // newMetalRenderExecutor (#494).
    private val renderExecutor: ExecutorService =
        newMetalRenderExecutor("TaoStandalonePopupMetalRender")

    init {
        var valid = false
        if (!NativeMetalBridge.isLoaded || !PopupNativeBridge.isLoaded) {
            logger.warning("Standalone popup unavailable: native bridges not loaded")
        } else {
            // Ownerless panel: parentNsView = 0 → native takes the screen-coord
            // ownerless branch (no parent window, no addChildWindow).
            panel =
                PopupNativeBridge.nativeCreatePanel(
                    parentNsView = 0L,
                    xPx = HIDDEN_X_PX,
                    yPx = HIDDEN_Y_PX,
                    widthPx = 1,
                    heightPx = 1,
                )
            if (panel == 0L) {
                logger.warning("Standalone popup unavailable: ownerless panel creation failed")
            } else {
                val contentNsView = PopupNativeBridge.nativeContentNsView(panel)
                attachmentHandle = NativeMetalBridge.nativeAttachOverlay(contentNsView)
                if (attachmentHandle == 0L) {
                    logger.warning("Standalone popup unavailable: CAMetalLayer attach failed")
                    PopupNativeBridge.nativeRelease(panel)
                    panel = 0
                } else {
                    NativeMetalBridge.nativeResize(attachmentHandle, 1, 1, scale)
                    // Skia's Metal DirectContext is thread-affine: create it on
                    // the render thread that will use it for every frame's replay.
                    val devicePtr = NativeMetalBridge.nativeDevicePtr(attachmentHandle)
                    val queuePtr = NativeMetalBridge.nativeQueuePtr(attachmentHandle)
                    directContext =
                        runOnRenderThread { DirectContext.makeMetal(devicePtr, queuePtr) }
                    val dndManager =
                        TaoDragAndDropManager(
                            getRootNode = { scene!!.rootDragAndDropNode },
                        )
                    sceneBundle =
                        canvasLayersSceneBundle(
                            coroutineContext = flushingDispatcher,
                            density = Density(scale),
                            layoutDirection = GlobalLayoutDirection,
                            size = IntSize(1, 1),
                            platformContext = StandalonePopupPlatformContext(dndManager),
                            requestFrame = { scheduleRender() },
                        )
                    PopupNativeBridge.nativeSetEventCallback(panel, PanelEventCallback())
                    registerInboundDnD()
                    PopupNativeBridge.nativeOrderOut(panel) // hidden until first setVisible(true)
                    valid = true
                    logger.fine { "Standalone popup panel ready (panel=$panel, scale=$scale)" }
                }
            }
        }
        isValid = valid
    }

    override fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content = content)
        scheduleRender()
    }

    /**
     * This panel owns its Skia context and render thread, so `TextureView`s
     * inside it import onto that context rather than a window scene's.
     */
    @Composable
    override fun ProvidePanelLocals(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalTaoMetalTextureHost provides metalTextureHost()) {
            content()
        }
    }

    /** This panel's handle for `TextureView`s composed inside it — see [MetalTextureHostCache]. */
    private val metalTextureHostCache = MetalTextureHostCache()

    private fun metalTextureHost(): TaoMetalTextureHost? {
        val outer = this
        return metalTextureHostCache.get(attachmentHandle, directContext) { device, ctx ->
            object : TaoMetalTextureHost {
                override val metalDevicePtr: Long = device
                override val directContext: DirectContext = ctx

                override fun <T> runOnRenderThread(block: () -> T): T = outer.runOnRenderThread(block)
            }
        }
    }

    /** Logical (dp) screen position and size of the panel. */
    override fun setFrame(
        xDp: Float,
        yDp: Float,
        widthDp: Float,
        heightDp: Float,
    ) {
        if (!isValid) return
        val x = (xDp * scale).roundToInt()
        val y = (yDp * scale).roundToInt()
        val w = (widthDp * scale).roundToInt().coerceAtLeast(1)
        val h = (heightDp * scale).roundToInt().coerceAtLeast(1)
        PopupNativeBridge.nativeSetFrameOnScreen(
            panel = panel,
            xPx = x,
            yPx = y,
            widthPx = w,
            heightPx = h,
        )
        if (w != widthPx || h != heightPx) {
            widthPx = w
            heightPx = h
            NativeMetalBridge.nativeResize(attachmentHandle, w, h, scale)
            scene?.size = IntSize(w, h)
            windowInfo.containerSizeState = IntSize(w, h)
        }
        scheduleRender()
    }

    override fun setVisible(visible: Boolean) {
        if (!isValid || visible == this.visible) return
        this.visible = visible
        if (visible) {
            PopupNativeBridge.nativeOrderFront(panel)
            // Render + present the first frame synchronously before returning:
            // the very first real frame pays Metal pipeline compilation and
            // glyph shaping (potentially longer than the caller's enter
            // animation), so warming it up while the caller is still delaying
            // its animation start keeps the animation's early frames from
            // being swallowed by the warm-up.
            renderFrameBlocking()
            scheduleRender()
        } else {
            // Restore the arrow before ordering out so a text-field I-beam
            // doesn't linger over whatever ends up under the pointer.
            PopupNativeBridge.nativeSetPanelCursor(panel, TaoCursorIcon.DEFAULT)
            PopupNativeBridge.nativeOrderOut(panel)
        }
    }

    override fun setFocusable(focusable: Boolean) {
        if (!isValid) return
        // For a standalone panel the native side takes key focus (makeKeyWindow)
        // when focusable becomes true — see popup_panel.m's nativeSetFocusable.
        PopupNativeBridge.nativeSetFocusable(panel, focusable)
    }

    override fun setOutsideClickListener(listener: (() -> Unit)?) {
        if (!isValid) return
        if (listener != null) {
            PopupNativeBridge.nativeInstallOutsideClickMonitor(panel, PanelOutsideClickListener(listener))
        } else {
            PopupNativeBridge.nativeUninstallOutsideClickMonitor(panel)
        }
    }

    /**
     * Named inner class so GraalVM JNI reachability metadata can register the
     * implementor (same pattern as [TaoPopupSceneLayer.PopupOutsideListener]).
     */
    private class PanelOutsideClickListener(
        private val listener: () -> Unit,
    ) : PopupNativeBridge.OutsideClickListener {
        override fun onOutsideClick(
            type: Int,
            button: Int,
        ) {
            listener()
        }
    }

    override fun dispose() {
        if (!isValid || disposed) return
        disposed = true
        framePump.disposed = true
        revokeInboundDnD()
        PopupNativeBridge.nativeUninstallOutsideClickMonitor(panel)
        PopupNativeBridge.nativeSetEventCallback(panel, null)
        sceneBundle?.close()
        sceneBundle = null
        metalTextureHostCache.invalidate()
        val ctx = directContext
        directContext = null
        if (ctx != null) runCatching { runOnRenderThread { ctx.close() } }
        val handle = attachmentHandle
        attachmentHandle = 0
        if (handle != 0L) NativeMetalBridge.nativeDetach(handle)
        PopupNativeBridge.nativeRelease(panel)
        panel = 0
        renderExecutor.shutdown()
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun scheduleRender() {
        framePump.schedule()
    }

    private fun renderNow() {
        if (disposed) return
        val bundle = sceneBundle ?: return
        val ctx = directContext ?: return
        val handle = attachmentHandle
        if (handle == 0L || widthPx <= 0 || heightPx <= 0) return

        // Keep the scene's coroutine work (recomposer steps, effects) moving
        // even while hidden — only the GPU part is skipped below.
        flushingDispatcher.drain()

        // On-demand rendering: no frames while the panel is hidden. Pending
        // frame-clock awaiters stay parked; setVisible(true) renders a fresh
        // frame synchronously and re-arms the paced loop.
        if (!visible) return

        // Serialize record/replay: never record frame N+1 while frame N is
        // still on the render thread. `nextDrawable` blocks at the display
        // rate, so an unserialized 60 fps record loop slowly builds a replay
        // backlog and presents drift behind the animation timestamps.
        val retryNs =
            if (replayInFlight.get()) {
                REPLAY_RETRY_NS
            } else {
                // Pace self-invalidating content (animations) on an absolute
                // deadline so scheduling latency doesn't accumulate as drift and
                // the frame clock is fed evenly spaced timestamps.
                val now = System.nanoTime()
                if (now < nextFrameNs) nextFrameNs - now else 0L
            }
        if (retryNs > 0L) {
            pacer.schedule({ scheduleRender() }, retryNs, TimeUnit.NANOSECONDS)
            return
        }
        val now = System.nanoTime()
        val frameNs = if (now - nextFrameNs > FRAME_INTERVAL_NS) now else nextFrameNs
        nextFrameNs = frameNs + FRAME_INTERVAL_NS

        // Record on the main thread (Compose state lives here). The Picture is
        // a thread-safe snapshot — safe to hand to the render thread for replay.
        // `recordSceneToPicture` ticks the scene's frame clock with the paced
        // `frameNs` (via FrameRecomposer.performFrame) before drawing.
        flushingDispatcher.drain()
        val picture = recordSceneToPicture(bundle, widthPx, heightPx, frameNs)
        replayInFlight.set(true)
        renderExecutor.submit {
            try {
                if (!disposed && attachmentHandle != 0L) {
                    replayPictureToFrame(handle, ctx, picture, clearColor = 0x00000000)
                }
            } finally {
                picture.close()
                replayInFlight.set(false)
            }
        }
    }

    /**
     * Records + replays one frame, blocking until the present is queued. Used
     * on the show path only: guarantees a fresh frame is on screen (and the
     * Metal pipelines are compiled) before the caller starts its enter
     * animation. Runs on the Tao main thread; the blocking hop to the render
     * thread is safe because frames are serialized ([replayInFlight]) and the
     * render thread never blocks back on the main thread (see
     * `NativeMetalBridge.nativeBeginFrame` / `nativePresent`).
     */
    private fun renderFrameBlocking() {
        if (disposed) return
        val bundle = sceneBundle ?: return
        val ctx = directContext ?: return
        val handle = attachmentHandle
        if (handle == 0L || widthPx <= 0 || heightPx <= 0) return
        flushingDispatcher.drain()
        val frameNs = System.nanoTime()
        nextFrameNs = frameNs + FRAME_INTERVAL_NS
        val picture = recordSceneToPicture(bundle, widthPx, heightPx, frameNs)
        runOnRenderThread {
            try {
                replayPictureToFrame(handle, ctx, picture, clearColor = 0x00000000)
            } finally {
                picture.close()
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────

    private inner class PanelEventCallback : PopupNativeBridge.EventCallback {
        override fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) {
            val sc = scene ?: return
            val pointerButton =
                when (button) {
                    TaoNativeWireFormat.BUTTON_PRIMARY -> PointerButton.Primary
                    TaoNativeWireFormat.BUTTON_SECONDARY -> PointerButton.Secondary
                    else -> null
                }
            val eventType =
                when (type) {
                    TaoNativeWireFormat.PTR_DOWN -> PointerEventType.Press
                    TaoNativeWireFormat.PTR_UP -> PointerEventType.Release
                    else -> PointerEventType.Move
                }
            sc.sendPointerEvent(
                eventType = eventType,
                position = Offset(x, y),
                type = PointerType.Mouse,
                button = pointerButton,
            )
        }

        override fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
            precise: Boolean,
        ) {
            scene?.dispatchAwtShapedScroll(
                x,
                y,
                appKitWheelToAwtScrollEvent(dx, dy, precise, scale),
            )
        }

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) {
            scene?.dispatchNativeKeyEvent(
                type = type,
                vkCode = vkCode,
                codePoint = codePoint,
                modifiers = modifiers,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            )
        }
    }

    // ── Inbound drag-and-drop ─────────────────────────────────────────────
    //
    // Ownerless NSPanel content views never go through DecoratedWindow's
    // NSDraggingDestination install. Register here so Modifier.dragAndDropTarget
    // inside a TrayApp (e.g. a file converter) receives OS drops.

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!NativeTaoMacOsDndBridge.isLoaded) {
            TaoDnDDiagnostics.log("macOS standalone popup DnD lib not loaded — inbound disabled")
            return
        }
        val nsView = PopupNativeBridge.nativeContentNsView(panel)
        if (nsView == 0L) {
            TaoDnDDiagnostics.log("macOS standalone popup has no NSView — inbound disabled")
            return
        }
        val rc = NativeTaoMacOsDndBridge.nativeRegister(nsView = nsView, callback = InboundDnDCallback())
        TaoDnDDiagnostics.log("standalone popup nativeRegister rc=$rc")
    }

    private fun revokeInboundDnD() {
        if (!NativeTaoMacOsDndBridge.isLoaded) return
        val nsView = PopupNativeBridge.nativeContentNsView(panel)
        if (nsView == 0L) return
        NativeTaoMacOsDndBridge.nativeRevoke(nsView)
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly — same constraint as the DecoratedWindow host.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback : NativeTaoMacOsDndBridge.Callback {
        private fun node() = scene?.rootDragAndDropNode

        override fun onDragEnter(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int {
            TaoDnDDiagnostics.log("standalone popup onDragEnter x=$x y=$y hasFiles=$hasFiles")
            if (!hasFiles) return NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            return if (TaoSceneDnD.onDragEnter(node(), x, y)) {
                NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int =
            if (TaoSceneDnD.onDragOver(node(), x, y)) {
                NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragLeave(nsView: Long) {
            TaoDnDDiagnostics.log("standalone popup onDragLeave")
            TaoSceneDnD.onDragLeave(node())
        }

        override fun onDrop(
            nsView: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int {
            TaoDnDDiagnostics.log("standalone popup onDrop x=$x y=$y files=${files?.size ?: 0}")
            return if (TaoSceneDnD.onDrop(node(), x, y, files)) {
                NativeTaoMacOsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoMacOsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    // ── Platform plumbing ─────────────────────────────────────────────────

    private inner class StandalonePopupPlatformContext(
        override val dragAndDropManager: PlatformDragAndDropManager,
    ) : TaoPlatformContextBase() {
        override val sceneScale: Float get() = this@TaoStandalonePopupHostMac.scale

        override val windowInfo: WindowInfo get() = this@TaoStandalonePopupHostMac.windowInfo

        // Standalone popup surfaces are always per-pixel transparent, so
        // dialog scrims must use the alpha-aware blend (#559).
        override val isWindowTransparent: Boolean get() = true

        override fun setPointerIcon(pointerIcon: PointerIcon) {
            if (!isValid || disposed) return
            PopupNativeBridge.nativeSetPanelCursor(panel, pointerIcon.toTaoCursorIconCode())
        }
    }

    private inner class FlushingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
            scheduleRender()
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
        }
    }

    private class StandalonePopupWindowInfo : WindowInfo {
        var containerSizeState: IntSize = IntSize(1, 1)
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = containerSizeState
    }

    // ── Render thread (Skia Metal DirectContext thread-affinity) ───────────

    private fun <T> runOnRenderThread(block: () -> T): T = renderExecutor.submit(Callable { block() }).get()

    private companion object {
        val logger: java.util.logging.Logger =
            java.util.logging.Logger
                .getLogger(TaoStandalonePopupHostMac::class.java.name)

        const val HIDDEN_X_PX: Int = -32_000
        const val HIDDEN_Y_PX: Int = -32_000
        const val FRAME_INTERVAL_NS: Long = 1_000_000_000L / 60

        /** Re-check cadence while a frame's replay is still on the render thread. */
        const val REPLAY_RETRY_NS: Long = 2_000_000L

        val pacer =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TaoStandalonePopupPacer").apply { isDaemon = true }
            }
    }
}
