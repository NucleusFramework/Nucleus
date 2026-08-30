package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import dev.nucleusframework.window.tao.event.ProvideTaoWindowsScrollConfig
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.win32WheelToAwtScrollEvent
import dev.nucleusframework.window.tao.ffi.NativeTaoGlBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDndBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeWindows
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.releaseWindowsTextureImports
import dev.nucleusframework.window.tao.scene.LocalTaoWindowsTextureHost
import dev.nucleusframework.window.tao.scene.TaoComposeSceneHostWindows
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.TaoWindowsTextureHost
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.preservingAngleBinding
import dev.nucleusframework.window.tao.scene.renderGlFrame
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * Standalone transparent popup surface (Windows): a top-level, ownerless
 * `WS_POPUP` HWND with `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` and a per-pixel
 * transparent DComp-presented surface, driving its own Compose scene.
 *
 * Unlike [TaoPopupSceneLayerWindows] it has no owner window and no host render
 * loop: rendering runs on demand on the Tao main thread whenever the scene
 * invalidates (recomposition, animation frame, input). No `WM_PAINT` is
 * involved, so the panel works without any visible window in the process —
 * the backbone of tray-style popups.
 *
 * Threading: construction and every public method must run on the Tao main
 * thread (the composable wrapper guarantees this). Rendering is scheduled
 * through [TaoMainDispatcher] and paced at ~60 fps for self-invalidating
 * content (animations).
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoStandalonePopupHost : StandalonePopupHost {
    override val isValid: Boolean

    private var panel: Long = 0
    private var directContext: DirectContext? = null
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene
    private var disposed = false

    /**
     * Primary-monitor scale, captured once: a tray popup lives on the
     * primary monitor by definition. Mixed-DPI multi-monitor setups and
     * live DPI changes are NOT tracked (would need WM_DPICHANGED plumbing
     * plus per-position monitor lookup).
     */
    override val scale: Float = TaoScreenGeometry.primaryMonitorScaleFactor()

    private var widthPx: Int = 1
    private var heightPx: Int = 1

    override var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    override var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    private val flushingDispatcher = FlushingDispatcher()
    private val windowInfo = StandalonePopupWindowInfo()

    private val framePump = StandaloneFramePump { renderNow() }
    private var nextFrameNs = 0L
    private var visible = false

    /**
     * Handle for `TextureView`s composed inside this panel. Published as
     * **state**, like the Linux twin: the composition reads it, so dropping it in
     * [dispose] takes effect instead of leaving a live composition importing onto
     * a context that is about to be destroyed.
     *
     * Declared **before** [init], which publishes into it: Kotlin runs property
     * initializers and `init` blocks in declaration order, so a state declared
     * below would still be null when the panel comes up.
     */
    private val textureHostState: MutableState<TaoWindowsTextureHost?> = mutableStateOf(null)

    init {
        var valid = false
        // The whole bring-up is surface-displacing, and it runs wherever the panel
        // was composed — `TaoStandalonePopup` builds the host from `remember {}`, so
        // for a panel added to a live window that is inside the window scene's
        // `ComposeScene.render()`. Two steps leave a foreign binding current: the
        // headless EGL bootstrap (which ends bound to its immortal 1x1 pbuffer, on
        // an unshared context) and this panel's own `nativeMakeCurrent`. Either one
        // sends the remainder of that frame — frame decoration, glyph-atlas uploads,
        // `flushAndSubmit` — onto a context the host's `DirectContext` does not own,
        // which corrupts the window's GPU objects for good. So the guard wraps the
        // entire block, exactly like the Linux twin. See [preservingAngleBinding].
        preservingAngleBinding {
            if (!NativeTaoGlBridge.isLoaded || !PopupNativeBridgeWindows.isLoaded) {
                logger.warning("Standalone popup unavailable: native bridges not loaded")
            } else if (!runCatching { NativeTaoGlBridge.nativeEnsureHeadlessContext() }
                    .onFailure { logger.warning("Standalone popup unavailable: $it") }
                    .getOrDefault(false)
            ) {
                logger.warning("Standalone popup unavailable: headless EGL bootstrap failed")
            } else {
                panel =
                    PopupNativeBridgeWindows.nativeCreatePanel(
                        parentHwnd = 0L,
                        xPx = HIDDEN_X_PX,
                        yPx = HIDDEN_Y_PX,
                        widthPx = 1,
                        heightPx = 1,
                    )
                if (panel == 0L) {
                    logger.warning("Standalone popup unavailable: panel creation failed")
                } else {
                    PopupNativeBridgeWindows.nativeSetPanelVisible(panel, false)
                    directContext =
                        if (PopupNativeBridgeWindows.nativeMakeCurrent(panel)) {
                            runCatching {
                                val intf =
                                    GLAssembledInterface.createFromNativePointers(
                                        0L,
                                        NativeTaoGlBridge.nativeEglGetProcFn(),
                                    )
                                DirectContext.makeGLWithInterface(intf)
                            }.getOrNull()
                        } else {
                            null
                        }
                    if (directContext != null) {
                        // Same lazy getRootNode as the DecoratedWindow host: the
                        // scene is built with this manager in PlatformContext,
                        // then rootDragAndDropNode exists after construction.
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
                        PopupNativeBridgeWindows.nativeSetEventCallback(panel, PanelEventCallback())
                        publishTextureHost()
                        registerInboundDnD()
                        // See TaoComposeSceneHostWindows: all contexts sharing the
                        // process EGL context must resetGLAll when siblings exist.
                        TaoComposeSceneHostWindows.attachedHostCount.incrementAndGet()
                        valid = true
                        logger.fine { "Standalone popup panel ready (panel=$panel, scale=$scale)" }
                    } else {
                        logger.warning("Standalone popup unavailable: Skia DirectContext creation failed")
                        PopupNativeBridgeWindows.nativeRelease(panel)
                        panel = 0
                    }
                }
            }
        }
        isValid = valid
    }

    override fun setContent(content: @Composable () -> Unit) {
        // Initial composition dispatches coroutines (LaunchedEffects) into the
        // scene dispatcher; rendering inline from those would race the apply
        // pass still on the stack. Same guard as the input entry points below.
        framePump.nonReentrant { scene?.setContent(content = content) }
        scheduleRender()
    }

    /** This panel owns its Skia context, so `TextureView`s inside it must import onto that one. */
    @Composable
    override fun ProvidePanelLocals(content: @Composable () -> Unit) {
        // Innermost, so a TrayApp composed next to a DecoratedWindow still
        // gets the window-host wheel policy instead of Compose's default
        // (which reads scrollAmount=1 and under-scrolls 3×).
        ProvideTaoWindowsScrollConfig {
            CompositionLocalProvider(LocalTaoWindowsTextureHost provides textureHostState.value) {
                content()
            }
        }
    }

    /**
     * `hostHwnd = 0`: the panel renders through the process-wide headless ANGLE
     * context, which is exactly the fallback the native import takes when the
     * HWND lookup finds no EGL trio.
     */
    private fun publishTextureHost() {
        val ctx = directContext ?: return
        val outer = this
        textureHostState.value =
            object : TaoWindowsTextureHost {
                override val hostHwnd: Long = 0L
                override val directContext: DirectContext = ctx

                override fun requestRedraw() = outer.scheduleRender()

                override fun <T> withContextCurrent(block: () -> T): T? {
                    // Read live: 0 after dispose, which keeps a late caller off
                    // a freed panel. During the panel's own render pass the
                    // enclosing preservingAngleBinding makes the inner save a
                    // no-op and the re-make-current idempotent.
                    val panelHandle = outer.panel
                    if (panelHandle == 0L) return null
                    return preservingAngleBinding {
                        if (!PopupNativeBridgeWindows.nativeMakeCurrent(panelHandle)) {
                            null
                        } else {
                            block()
                        }
                    }
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
        // A size change rebuilds the panel's DComp swapchain and its EGL
        // pbuffer, and the native side unbinds the thread when the pbuffer it
        // destroys is the current one. This arrives from the caller's layout,
        // i.e. from inside the window scene's render pass — see
        // [preservingAngleBinding].
        preservingAngleBinding {
            PopupNativeBridgeWindows.nativeSetFrameInWindow(
                panel = panel,
                xPx = x,
                yPx = y,
                widthPx = w,
                heightPx = h,
                contentXPx = 0,
                contentYPx = 0,
                contentWidthPx = w,
                contentHeightPx = h,
            )
        }
        if (w != widthPx || h != heightPx) {
            widthPx = w
            heightPx = h
            scene?.size = IntSize(w, h)
            windowInfo.containerSizeState = IntSize(w, h)
        }
        scheduleRender()
    }

    override fun setVisible(visible: Boolean) {
        if (!isValid || visible == this.visible) return
        this.visible = visible
        PopupNativeBridgeWindows.nativeSetPanelVisible(panel, visible)
        // High-resolution timers only while animating on screen: the frame
        // pacer relies on ~1 ms scheduling accuracy for a steady 60 fps.
        PopupNativeBridgeWindows.nativeSetHighResTimer(visible)
        if (visible) scheduleRender()
    }

    override fun setFocusable(focusable: Boolean) {
        if (!isValid) return
        PopupNativeBridgeWindows.nativeSetFocusable(panel, focusable)
    }

    override fun setOutsideClickListener(listener: (() -> Unit)?) {
        if (!isValid) return
        if (listener != null) {
            PopupNativeBridgeWindows.nativeInstallOutsideClickMonitor(panel, PanelOutsideClickListener(listener))
        } else {
            PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panel)
        }
    }

    /**
     * Named inner class so GraalVM JNI reachability metadata can register
     * the implementor (same pattern as [TaoPopupSceneLayerWindows]).
     */
    private class PanelOutsideClickListener(
        private val listener: () -> Unit,
    ) : PopupNativeBridgeWindows.OutsideClickListener {
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
        if (visible) {
            visible = false
            PopupNativeBridgeWindows.nativeSetHighResTimer(false)
        }
        TaoComposeSceneHostWindows.attachedHostCount.decrementAndGet()
        revokeInboundDnD()
        PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panel)
        PopupNativeBridgeWindows.nativeSetEventCallback(panel, null)
        // Teardown binds this panel's own surface for the Skia frees below, and
        // it arrives from `DisposableEffect.onDispose` — i.e. from the caller's
        // composition, inside the window scene's render pass. Restoring the
        // binding we displace is what keeps the remainder of that frame
        // targeting the window. See [preservingAngleBinding].
        preservingAngleBinding {
            // Drop the TextureView handle before the context it points at dies.
            textureHostState.value = null
            sceneBundle?.close()
            sceneBundle = null
            // An ownerless panel binds the immortal headless EGL context, not
            // the caller's — so the Skia frees below need it made current
            // explicitly, and they can't disturb any window host's GL state.
            PopupNativeBridgeWindows.nativeMakeCurrent(panel)
            // Belt for imports a leaked composition may still hold; scene.close()
            // above released the leases of every live one.
            directContext?.let(::releaseWindowsTextureImports)
            directContext?.close()
            directContext = null
            // Destroys the panel's pbuffer, which the native side unbinds first
            // when it is the current one — inside the wrapper, so the caller's
            // binding is put back afterwards either way.
            PopupNativeBridgeWindows.nativeRelease(panel)
            panel = 0
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun scheduleRender() {
        framePump.schedule()
    }

    private fun renderNow() {
        if (disposed) return
        val ctx = directContext ?: return
        val bundle = sceneBundle ?: return
        if (widthPx <= 0 || heightPx <= 0) return

        // Keep the scene's coroutine work (recomposer steps, effects) moving
        // even while hidden — only the GPU part is skipped below.
        flushingDispatcher.drain()

        // On-demand rendering: no frames while the panel is hidden. Pending
        // frame-clock awaiters stay parked; setVisible(true) re-arms the
        // paced loop.
        if (!visible) return

        // Pace self-invalidating content (animations): DComp presents don't
        // block on vsync, so an unthrottled invalidate->render loop would
        // spin the Tao thread at 100%. Pacing runs on an ABSOLUTE deadline
        // (nextFrameNs) so scheduling latency doesn't accumulate as drift,
        // and the frame clock is fed evenly spaced timestamps — presents
        // latch at vsync, so even *timestamps*, not even render moments,
        // are what makes an animation look smooth.
        val now = System.nanoTime()
        if (now < nextFrameNs) {
            pacer.schedule(
                { scheduleRender() },
                nextFrameNs - now,
                TimeUnit.NANOSECONDS,
            )
            return
        }
        // Resynchronize after an idle gap; otherwise stay on the fixed grid.
        val frameNs = if (now - nextFrameNs > FRAME_INTERVAL_NS) now else nextFrameNs
        nextFrameNs = frameNs + FRAME_INTERVAL_NS

        // Drain queued main-thread work before the frame. The scene's frame
        // clock is ticked inside `bundle.render` (FrameRecomposer.performFrame)
        // with the paced `frameNs` timestamp, so withFrameNanos-driven
        // animations are fed evenly spaced times for smooth motion.
        flushingDispatcher.drain()

        // Surface-neutral, like the bring-up: whatever was bound before this
        // render task gets it back. Window hosts re-bind their own surface at
        // frame entry anyway, but this render also runs while a window frame is
        // merely paused on an event-loop turn.
        preservingAngleBinding {
            if (!PopupNativeBridgeWindows.nativeMakeCurrent(panel)) return@preservingAngleBinding
            // Cheap insurance: the headless context this panel binds is the
            // fallback trio every ownerless surface shares, so another one may
            // have issued GL on it since our last frame.
            ctx.resetGLAll()
            renderGlFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                directContext = ctx,
                clearColorArgb = 0x00000000,
                present = { PopupNativeBridgeWindows.nativeSwapBuffers(panel) },
            ) { canvas, _ ->
                bundle.render(canvas, frameNs)
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────

    // Every scene dispatch below runs inside framePump.nonReentrant: a drag
    // gesture can force a measure pass synchronously (scrollbar drag →
    // LazyListState.onScroll → forceRemeasure), and a coroutine dispatched
    // from within it must post the next frame instead of rendering inline.
    private inner class PanelEventCallback : PopupNativeBridgeWindows.EventCallback {
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
            framePump.nonReentrant {
                sc.sendPointerEvent(
                    eventType = eventType,
                    position = Offset(x, y),
                    type = PointerType.Mouse,
                    button = pointerButton,
                )
            }
        }

        override fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        ) {
            framePump.nonReentrant {
                scene?.dispatchAwtShapedScroll(x, y, win32WheelToAwtScrollEvent(dx, dy))
            }
        }

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) {
            framePump.nonReentrant {
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
    }

    // ── Inbound drag-and-drop ─────────────────────────────────────────────
    //
    // Tray popups are ownerless WS_POPUP HWNDs, not Tao windows, so they never
    // get Tao's RegisterDragDrop — and unlike DecoratedWindow they also skipped
    // Nucleus's IDropTarget. Modifier.dragAndDropTarget in a TrayApp (e.g. a
    // file converter) therefore never saw OS drops. Mirror the window host:
    // register on the panel HWND, dispatch through TaoSceneDnD.

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!NativeTaoWindowsDndBridge.isLoaded) {
            TaoDnDDiagnostics.log("windows standalone popup DnD lib not loaded — inbound disabled")
            return
        }
        val hwnd = PopupNativeBridgeWindows.nativeContentHwnd(panel)
        if (hwnd == 0L) {
            TaoDnDDiagnostics.log("windows standalone popup has no HWND — inbound disabled")
            return
        }
        val rc = NativeTaoWindowsDndBridge.nativeRegister(hwnd, InboundDnDCallback())
        TaoDnDDiagnostics.log("standalone popup RegisterDragDrop rc=$rc")
    }

    private fun revokeInboundDnD() {
        if (!NativeTaoWindowsDndBridge.isLoaded) return
        val hwnd = PopupNativeBridgeWindows.nativeContentHwnd(panel)
        if (hwnd == 0L) return
        NativeTaoWindowsDndBridge.nativeRevoke(hwnd)
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly — same constraint as the DecoratedWindow host.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback : NativeTaoWindowsDndBridge.Callback {
        private fun node() = scene?.rootDragAndDropNode

        override fun onDragEnter(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int {
            TaoDnDDiagnostics.log("standalone popup onDragEnter x=$x y=$y hasFiles=$hasFiles")
            if (!hasFiles) return NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            return if (TaoSceneDnD.onDragEnter(node(), x, y)) {
                NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            hasFiles: Boolean,
        ): Int =
            if (TaoSceneDnD.onDragOver(node(), x, y)) {
                NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }

        override fun onDragLeave(hwnd: Long) {
            TaoDnDDiagnostics.log("standalone popup onDragLeave")
            TaoSceneDnD.onDragLeave(node())
        }

        override fun onDrop(
            hwnd: Long,
            x: Int,
            y: Int,
            keyState: Int,
            files: Array<String>?,
        ): Int {
            TaoDnDDiagnostics.log("standalone popup onDrop x=$x y=$y files=${files?.size ?: 0}")
            return if (TaoSceneDnD.onDrop(node(), x, y, files)) {
                NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    // ── Platform plumbing ─────────────────────────────────────────────────

    private inner class StandalonePopupPlatformContext(
        override val dragAndDropManager: PlatformDragAndDropManager,
    ) : TaoPlatformContextBase() {
        override val sceneScale: Float get() = this@TaoStandalonePopupHost.scale

        override val windowInfo: WindowInfo get() = this@TaoStandalonePopupHost.windowInfo

        // Standalone popup surfaces are always per-pixel transparent, so
        // dialog scrims must use the alpha-aware blend (#559).
        override val isWindowTransparent: Boolean get() = true

        override fun setPointerIcon(pointerIcon: PointerIcon) {
            if (!isValid) return
            PopupNativeBridgeWindows.nativeSetPanelCursor(panel, mapPointerIcon(pointerIcon))
        }
    }

    private fun mapPointerIcon(icon: PointerIcon): Int {
        when {
            icon === PointerIcon.Default -> return TaoCursorIcon.DEFAULT
            icon === PointerIcon.Text -> return TaoCursorIcon.TEXT
            icon === PointerIcon.Hand -> return TaoCursorIcon.HAND
            icon === PointerIcon.Crosshair -> return TaoCursorIcon.CROSSHAIR
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

    private companion object {
        val logger: java.util.logging.Logger =
            java.util.logging.Logger
                .getLogger(TaoStandalonePopupHost::class.java.name)

        const val HIDDEN_X_PX: Int = -32_000
        const val HIDDEN_Y_PX: Int = -32_000
        const val FRAME_INTERVAL_NS: Long = 1_000_000_000L / 60

        val pacer =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TaoStandalonePopupPacer").apply { isDaemon = true }
            }
    }
}
