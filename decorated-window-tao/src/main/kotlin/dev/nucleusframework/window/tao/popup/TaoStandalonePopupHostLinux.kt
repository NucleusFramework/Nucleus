package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.GlobalLayoutDirection
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeLinux
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.releaseGlTextureImports
import dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.preservingEglBinding
import dev.nucleusframework.window.tao.scene.renderGlFrame
import dev.nucleusframework.window.tao.scene.withEglContextCurrent
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt

/**
 * Standalone transparent popup surface (Linux): a top-level, ownerless,
 * override-redirect ARGB32 X11 window driving its own Compose scene — the
 * Linux counterpart of [TaoStandalonePopupHost] (Windows) and
 * [TaoStandalonePopupHostMac]. Works on X11 sessions and, through XWayland,
 * on Wayland sessions too (even while the app itself is a native Wayland
 * client — the panel is an independent X client on its own connection).
 *
 * Differences from the Windows host:
 *  - No headless bootstrap and no shared process context: the Linux EGL
 *    convention is one private context per attachment
 *    (`NativeTaoEglBridge.nativeAttachX11`), so there are no sibling
 *    surfaces to `resetGLAll` against.
 *  - `eglSwapInterval(0)`: presents must never block the Tao main thread
 *    on the compositor's vsync; frame pacing is our own 60 fps pacer,
 *    same as the Windows DComp path.
 *
 * Threading: construction and every public method must run on the Tao main
 * thread (the composable wrapper guarantees this) — it owns both the X
 * command connection and the panel's EGL context. Input callbacks arrive
 * on the panel's X event thread and hop to the main thread before touching
 * the scene.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoStandalonePopupHostLinux : StandalonePopupHost {
    override val isValid: Boolean

    private var panel: Long = 0
    private var attachment: Long = 0
    private var directContext: DirectContext? = null
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene
    private var disposed = false

    /**
     * `Xft.dpi`-derived: the panel lives in the X11 coordinate space, which
     * under XWayland is logical — GDK's Wayland monitor scale would mis-size
     * it. Captured once; live DPI changes are NOT tracked.
     */
    override val scale: Float

    private var widthPx: Int = 1
    private var heightPx: Int = 1

    override var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    override var onKeyEvent: ((KeyEvent) -> Boolean)? = null

    private val flushingDispatcher = FlushingDispatcher()
    private val windowInfo = StandalonePopupWindowInfo()

    private val renderPending = AtomicBoolean(false)
    private var nextFrameNs = 0L
    private var visible = false

    /**
     * Handle for `TextureView`s composed inside this panel. Published as **state**,
     * like the window scene and popup layers: the composition reads it, so
     * dropping it in [dispose] takes effect instead of leaving a live composition
     * importing onto a context that is about to be destroyed.
     *
     * Declared **before** [init], which publishes into it: Kotlin runs property
     * initializers and `init` blocks in declaration order, so a state declared
     * below would still be null when the panel comes up.
     */
    private val glTextureHostState: MutableState<TaoGlTextureHost?> = mutableStateOf(null)

    init {
        var valid = false
        var panelScale = 1f
        // The bring-up below leaves the panel's EGL context current, and it runs
        // wherever the panel was composed — `TaoStandalonePopup` builds the host
        // from `remember {}`, so for a panel added to a live window that is inside
        // the window scene's render pass. Put back whatever binding we displace,
        // or the remainder of that frame draws into this 1x1 panel context; see
        // [preservingEglBinding].
        preservingEglBinding {
            if (!PopupNativeBridgeLinux.isLoaded || !NativeTaoEglBridge.isLoaded) {
                logger.warning("Standalone popup unavailable: native bridges not loaded")
            } else if (!PopupNativeBridgeLinux.nativeIsAvailable()) {
                logger.warning("Standalone popup unavailable: no X server (DISPLAY unset, no XWayland?)")
            } else {
                panelScale = PopupNativeBridgeLinux.nativeScale()
                panel =
                    PopupNativeBridgeLinux.nativeCreatePanel(
                        xPx = HIDDEN_X_PX,
                        yPx = HIDDEN_Y_PX,
                        widthPx = 1,
                        heightPx = 1,
                    )
                valid = attachGpu(panelScale)
            }
        }
        scale = panelScale
        isValid = valid
    }

    /**
     * EGL attach + Skia context + scene, once the X11 panel exists. Returns
     * whether the panel came up; releases everything it allocated when it did
     * not. Runs inside [preservingEglBinding] — the attach leaves this panel's
     * context current on the calling thread.
     */
    private fun attachGpu(panelScale: Float): Boolean {
        if (panel == 0L) {
            logger.warning("Standalone popup unavailable: panel creation failed (no ARGB visual?)")
            return false
        }
        attachment =
            NativeTaoEglBridge.nativeAttachX11(
                displayPtr = PopupNativeBridgeLinux.nativeDisplayPtr(),
                xid = PopupNativeBridgeLinux.nativeWindowXid(panel),
                widthPx = 1,
                heightPx = 1,
            )
        if (attachment == 0L) {
            logger.warning("Standalone popup unavailable: EGL attach failed")
            PopupNativeBridgeLinux.nativeRelease(panel)
            panel = 0
            return false
        }
        // attachX11 left the context current with swap interval 1;
        // never block the Tao main thread on the compositor's vsync.
        NativeTaoEglBridge.nativeSetSwapInterval(attachment, 0)
        directContext =
            runCatching {
                val intf =
                    GLAssembledInterface.createFromNativePointers(
                        0L,
                        NativeTaoEglBridge.nativeGetProcAddrFunctionPointer(),
                    )
                DirectContext.makeGLWithInterface(intf)
            }.getOrNull()
        if (directContext == null) {
            logger.warning("Standalone popup unavailable: Skia DirectContext creation failed")
            NativeTaoEglBridge.nativeDetach(attachment)
            attachment = 0
            PopupNativeBridgeLinux.nativeRelease(panel)
            panel = 0
            return false
        }
        sceneBundle =
            canvasLayersSceneBundle(
                coroutineContext = flushingDispatcher,
                density = Density(panelScale),
                layoutDirection = GlobalLayoutDirection,
                size = IntSize(1, 1),
                platformContext = StandalonePopupPlatformContext(),
                requestFrame = { scheduleRender() },
            )
        PopupNativeBridgeLinux.nativeSetEventCallback(panel, PanelEventCallback())
        publishGlTextureHost()
        logger.fine { "Standalone popup panel ready (panel=$panel, scale=$panelScale)" }
        return true
    }

    override fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content = content)
        scheduleRender()
    }

    /**
     * This panel owns its EGL and Skia contexts, so `TextureView`s inside it
     * import onto those rather than a window scene's.
     */
    @Composable
    override fun ProvidePanelLocals(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalTaoGlTextureHost provides glTextureHostState.value) {
            content()
        }
    }

    private fun publishGlTextureHost() {
        val ctx = directContext ?: return
        if (attachment == 0L) return
        glTextureHostState.value =
            object : TaoGlTextureHost {
                override val directContext: DirectContext = ctx

                // Read live: 0 once the panel disposed, so a late disposal can't
                // bind (nor dereference) a freed attachment.
                override fun <T> withContextCurrent(block: () -> T): T? = withEglContextCurrent(attachment, block)
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
        PopupNativeBridgeLinux.nativeSetFrameOnScreen(
            panel = panel,
            xPx = x,
            yPx = y,
            widthPx = w,
            heightPx = h,
        )
        if (w != widthPx || h != heightPx) {
            widthPx = w
            heightPx = h
            // X11 EGL surfaces follow the window; this refreshes the cached size.
            NativeTaoEglBridge.nativeResize(attachment, w, h, scale)
            scene?.size = IntSize(w, h)
            windowInfo.containerSizeState = IntSize(w, h)
        }
        scheduleRender()
    }

    override fun setVisible(visible: Boolean) {
        if (!isValid || visible == this.visible) return
        this.visible = visible
        if (visible) {
            PopupNativeBridgeLinux.nativeSetPanelVisible(panel, true)
            scheduleRender()
        } else {
            // Restore the arrow before unmapping so a text-field I-beam
            // doesn't linger over whatever ends up under the pointer.
            PopupNativeBridgeLinux.nativeSetPanelCursor(panel, TaoCursorIcon.DEFAULT)
            PopupNativeBridgeLinux.nativeSetPanelVisible(panel, false)
        }
    }

    override fun setFocusable(focusable: Boolean) {
        if (!isValid) return
        PopupNativeBridgeLinux.nativeSetFocusable(panel, focusable)
    }

    override fun setOutsideClickListener(listener: (() -> Unit)?) {
        if (!isValid) return
        if (listener != null) {
            PopupNativeBridgeLinux.nativeInstallOutsideClickMonitor(panel, PanelOutsideClickListener(listener))
        } else {
            PopupNativeBridgeLinux.nativeUninstallOutsideClickMonitor(panel)
        }
    }

    /**
     * Named inner class so GraalVM JNI reachability metadata can register
     * the implementor (same pattern as the Windows/macOS hosts).
     */
    private class PanelOutsideClickListener(
        private val listener: () -> Unit,
    ) : PopupNativeBridgeLinux.OutsideClickListener {
        override fun onOutsideClick(
            type: Int,
            button: Int,
        ) {
            // Arrives on the panel's X event thread.
            TaoMainDispatcher.dispatch(EmptyCoroutineContext) { listener() }
        }
    }

    override fun dispose() {
        if (!isValid || disposed) return
        disposed = true
        PopupNativeBridgeLinux.nativeUninstallOutsideClickMonitor(panel)
        PopupNativeBridgeLinux.nativeSetEventCallback(panel, null)
        // Teardown binds this panel's context for the Skia frees below and then
        // destroys it, and it arrives from `DisposableEffect.onDispose` — i.e.
        // from the caller's composition, inside the window scene's render pass.
        // Restoring the binding we displace is what keeps the remainder of that
        // frame (glyph-atlas uploads, flushAndSubmit) from running with a
        // destroyed context: it would fail silently and leave the window unable
        // to raster new text until something rebuilt its surface.
        preservingEglBinding {
            // Drop the TextureView handle before the context it points at dies.
            glTextureHostState.value = null
            sceneBundle?.close()
            sceneBundle = null
            NativeTaoEglBridge.nativeMakeCurrent(attachment)
            // Belt for imports a leaked composition may still hold; scene.close()
            // above released the leases of every live one.
            directContext?.let(::releaseGlTextureImports)
            directContext?.close()
            directContext = null
            NativeTaoEglBridge.nativeDetach(attachment)
            attachment = 0
        }
        PopupNativeBridgeLinux.nativeRelease(panel)
        panel = 0
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun scheduleRender() {
        if (disposed) return
        if (!renderPending.compareAndSet(false, true)) return
        TaoMainDispatcher.dispatch(EmptyCoroutineContext) { renderNow() }
    }

    private fun renderNow() {
        renderPending.set(false)
        if (disposed) return
        val ctx = directContext ?: return
        val bundle = sceneBundle ?: return
        if (widthPx <= 0 || heightPx <= 0) return

        // Keep the scene's coroutine work (recomposer steps, effects) moving
        // even while hidden — only the GPU part is skipped below.
        flushingDispatcher.drain()
        if (!visible) return

        // Pace self-invalidating content (animations): swap interval is 0,
        // so an unthrottled invalidate->render loop would spin the Tao
        // thread at 100%. Pacing runs on an ABSOLUTE deadline (nextFrameNs)
        // so scheduling latency doesn't accumulate as drift, and the frame
        // clock is fed evenly spaced timestamps.
        val now = System.nanoTime()
        if (now < nextFrameNs) {
            if (renderPending.compareAndSet(false, true)) {
                pacer.schedule(
                    { TaoMainDispatcher.dispatch(EmptyCoroutineContext) { renderNow() } },
                    nextFrameNs - now,
                    TimeUnit.NANOSECONDS,
                )
            }
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

        // Context-neutral, like the bring-up: whatever bound the thread's context
        // before this render task gets it back.
        preservingEglBinding {
            NativeTaoEglBridge.nativeMakeCurrent(attachment)
            // The attachment owns a private EGL context that only this host's
            // Skia DirectContext ever touches — no resetGLAll needed (unlike the
            // Windows shared-process-context path).
            renderGlFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                directContext = ctx,
                clearColorArgb = 0x00000000,
                present = { NativeTaoEglBridge.nativePresent(attachment) },
            ) { canvas, _ ->
                bundle.render(canvas, frameNs)
            }
        }
    }

    // ── Input ─────────────────────────────────────────────────────────────

    /**
     * Arrives on the panel's X event thread; every scene interaction hops
     * to the Tao main thread (the scene is not thread-safe).
     */
    private inner class PanelEventCallback : PopupNativeBridgeLinux.EventCallback {
        override fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext) {
                val sc = scene ?: return@dispatch
                if (disposed) return@dispatch
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
        }

        override fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        ) {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext) {
                if (disposed) return@dispatch
                scene?.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = Offset(x, y),
                    scrollDelta = Offset(dx, dy),
                    type = PointerType.Mouse,
                )
            }
        }

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) {
            TaoMainDispatcher.dispatch(EmptyCoroutineContext) {
                if (disposed) return@dispatch
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

    // ── Platform plumbing ─────────────────────────────────────────────────

    private inner class StandalonePopupPlatformContext : PlatformContext.Empty() {
        override val windowInfo: WindowInfo get() = this@TaoStandalonePopupHostLinux.windowInfo

        override fun setPointerIcon(pointerIcon: PointerIcon) {
            if (!isValid || disposed) return
            PopupNativeBridgeLinux.nativeSetPanelCursor(panel, pointerIcon.toTaoCursorIconCode())
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

    private companion object {
        val logger: java.util.logging.Logger =
            java.util.logging.Logger
                .getLogger(TaoStandalonePopupHostLinux::class.java.name)

        const val HIDDEN_X_PX: Int = -32_000
        const val HIDDEN_Y_PX: Int = -32_000
        const val FRAME_INTERVAL_NS: Long = 1_000_000_000L / 60

        val pacer =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TaoStandalonePopupPacer").apply { isDaemon = true }
            }
    }
}
