package dev.nucleusframework.window.tao.popup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.event.TaoSyntheticMouseWheelEvent
import dev.nucleusframework.window.tao.event.taoKeyboardModifiers
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoEglBridge
import dev.nucleusframework.window.tao.releaseGlTextureImports
import dev.nucleusframework.window.tao.scene.LocalTaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoGlTextureHost
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.alignToBufferScale
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.preservingEglBinding
import dev.nucleusframework.window.tao.scene.renderGlFrame
import dev.nucleusframework.window.tao.scene.withEglContextCurrent
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.makeGLWithInterface
import kotlin.math.roundToInt

/**
 * Linux popup layer backed by a real Tao popup window
 * (`openWindow(popupOf = parent)`): GTK_WINDOW_POPUP, i.e. an
 * override-redirect ARGB toplevel on X11 and a `wl_subsurface` of the
 * parent on Wayland — the only client-positionable window kinds on each
 * backend. Popup content can therefore extend beyond the owner window
 * bounds on both display servers.
 *
 * The coordinate model mirrors [TaoPopupSceneLayerWindows]: `boundsInWindow`
 * is the content rect in parent-window physical pixels, the inner scene is
 * laid out at screen work-area size (see the "measurement chicken-and-egg"
 * note on [TaoPopupSceneLayer]), and rendering translates by
 * `-bounds.topLeft` into a window sized to the content, rounded up to a
 * multiple of the surface scale ([alignToBufferScale]).
 *
 * Differences from Windows/macOS driven by platform reality:
 *  - Window creation is asynchronous (Tao posts a CreateWindow user event);
 *    EGL attaches on WINDOW_READY and everything set before that
 *    (bounds, content) is applied then. Until ready the layer simply skips
 *    its render callback — the popup appears one event-loop tick later.
 *  - Rendering uses a private EGL context per attachment (the Linux
 *    convention, see `nucleus_tao_egl.c`) with swap interval 0: presents
 *    must never block the event-loop thread, and on Wayland the popup's
 *    EGL child hangs off GDK's synchronized subsurface where FIFO frame
 *    pacing is a fatal protocol error (see [TaoWindow.isPopup]).
 *  - Popup windows never own keyboard focus (override-redirect / subsurface),
 *    so key events keep arriving on the parent and are forwarded through
 *    [TaoPopupHostLinux.registerKeyHandler] — the macOS piggy-back model.
 *  - Outside-click detection has no global-hook equivalent (especially on
 *    Wayland); presses reaching the *parent* scene are outside every popup
 *    by construction and are forwarded via
 *    [TaoPopupHostLinux.registerOutsidePressListener].
 *
 * Threading: every method must run on the Tao event-loop thread.
 */
@OptIn(InternalComposeUiApi::class)
@Suppress("TooManyFunctions")
internal class TaoPopupSceneLayerLinux(
    private val host: TaoPopupHostLinux,
    initialDensity: Density,
    initialLayoutDirection: LayoutDirection,
    initialFocusable: Boolean,
    initialConsumePointerInputOutside: Boolean,
) : ComposeSceneLayer {
    private var _density = initialDensity
    private var _layoutDirection = initialLayoutDirection
    private var _focusable = initialFocusable
    private var _bounds: IntRect = IntRect.Zero
    private var _scrimColor: Color? = null
    private var _compositionLocalContext: CompositionLocalContext? = null

    private val rendererToken: Any = Any()
    private val moveListenerToken: Any = Any()
    private val keyHandlerToken: Any = Any()
    private val outsidePressToken: Any = Any()

    /**
     * Set in [close] before the popup window is destroyed. Guards the
     * render callback (the host drains a snapshot of its renderer map, so
     * a layer closed by a sibling's recomposition can still see one late
     * call) and the async WINDOW_READY attach.
     */
    private var released = false

    /** EGL attachment ready — flips on WINDOW_READY once the GPU side is up. */
    private var attachment: Long = 0
    private var directContext: DirectContext? = null
    private var shown = false

    /**
     * Handle for `TextureView`s composed inside this popup, published once EGL
     * and Skia are up ([attachGpu]) and dropped in [close] before the context
     * dies. Recomposition follows because the inner scene reads it as state.
     */
    private val glTextureHostState: MutableState<TaoGlTextureHost?> = mutableStateOf(null)

    private val glTextureHost: TaoGlTextureHost?
        get() = glTextureHostState.value

    private val scale: Float = if (host.scale > 0f) host.scale else 1f

    /**
     * Integer surface scale announced to the compositor
     * (`wl_surface.set_buffer_scale`) — GTK3 only ever reports integer scales.
     * Every physical size we hand to the native surface goes through
     * [alignToBufferScale] with it: an unaligned buffer is a fatal Wayland
     * protocol error (#502).
     */
    private val bufferScale: Int = scale.roundToInt().coerceAtLeast(1)

    // Work-area sized (not parent-window sized) so a popup larger than its
    // owner window lays out at full size — same contract as macOS/Windows.
    private val sceneLayoutSize: IntSize =
        host.workAreaSize.let {
            IntSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
        }

    /**
     * Physical size of the popup's native surface and render target. Always
     * a multiple of [bufferScale]; the content occupies its top-left and the
     * ≤ `bufferScale - 1` px edge stays transparent.
     */
    private var widthPx: Int = bufferScale
    private var heightPx: Int = bufferScale

    /**
     * The popup's Tao window. Created hidden at one logical pixel; the real
     * frame is pushed by the first `boundsInWindow` write and the window is
     * shown then. `popupOf` makes it override-redirect on X11 and a
     * `wl_subsurface` on Wayland.
     */
    private val popupWindow: TaoWindow =
        TaoApplication.openWindow(
            title = "",
            width = 1.0,
            height = 1.0,
            decorations = false,
            resizable = false,
            visible = false,
            popupOf = host.parentWindow,
        )

    private val popupWindowInfo: androidx.compose.ui.platform.WindowInfo =
        object : androidx.compose.ui.platform.WindowInfo {
            override val isWindowFocused: Boolean = true
            override val containerSize: IntSize get() = sceneLayoutSize
        }

    private val sceneBundle: TaoSceneBundle =
        canvasLayersSceneBundle(
            coroutineContext = host.sceneCoroutineContext,
            density = _density,
            layoutDirection = _layoutDirection,
            size = sceneLayoutSize,
            platformContext =
                object : TaoPlatformContextBase() {
                    override val windowInfo: androidx.compose.ui.platform.WindowInfo
                        get() = popupWindowInfo

                    // The popup window's surface is per-pixel transparent, so
                    // dialog scrims must use the alpha-aware blend — same
                    // contract as Compose Desktop's `WindowComposeSceneLayer`
                    // (#559).
                    override val isWindowTransparent: Boolean get() = true

                    override fun setPointerIcon(pointerIcon: PointerIcon) {
                        if (released) return
                        NativeTaoBridge.nativeSetCursorIcon(
                            popupWindow.handle,
                            pointerIcon.toTaoCursorIconCode(),
                        )
                    }
                },
            requestFrame = { host.requestRedraw() },
        )

    private val innerScene: ComposeScene get() = sceneBundle.scene

    private var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onOutsidePointerEvent: ((PointerEventType, PointerButton?) -> Unit)? = null

    init {
        popupWindow.onWindowReady { _, _ -> attachGpu() }
        // Compositor expose (X11) / re-map: repaint through the host pump.
        popupWindow.onRedrawRequested { host.requestRedraw() }
        registerInput()
        host.registerRenderer(rendererToken) { renderFrame() }
        host.registerKeyHandler(keyHandlerToken) { dispatchKey(it) }
        host.registerOwnerMoveListener(moveListenerToken) {
            if (_bounds != IntRect.Zero) updateNativeFrame()
        }
    }

    /**
     * EGL + Skia bring-up, deferred to WINDOW_READY (window creation is a
     * posted user event). Mirrors [TaoComposeSceneHostLinux.attachGpu] with
     * the popup-specific swap interval 0 on both backends.
     */
    private fun attachGpu() {
        if (released) return
        if (!NativeTaoEglBridge.isLoaded) return
        // `nativeAttach*` leaves the fresh context current and Skia's bring-up
        // needs it, so — like the teardown in [close] — hand back whatever
        // binding this displaces instead of merely unbinding.
        preservingEglBinding { attachGpuBound() }
    }

    private fun attachGpuBound() {
        val handles = NativeTaoBridge.nativeLinuxHandles(popupWindow.handle) ?: return
        if (handles.size != HANDLE_TRIPLE_SIZE || handles[0].toInt() == 0) return
        val kind = handles[0].toInt()
        val display = handles[1]
        val nativeWin = handles[2]
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val handle =
            when (kind) {
                KIND_X11 ->
                    NativeTaoEglBridge
                        .nativeAttachX11(display, nativeWin, w, h)
                        .also { if (it != 0L) NativeTaoEglBridge.nativeSetSwapInterval(it, 0) }
                KIND_WAYLAND ->
                    NativeTaoEglBridge.nativeAttachWayland(
                        display,
                        nativeWin,
                        w,
                        h,
                        bufferScale,
                        0,
                    )
                else -> 0L
            }
        if (handle == 0L) return
        val fnPtr = NativeTaoEglBridge.nativeGetProcAddrFunctionPointer()
        val ctx =
            runCatching {
                val iface = GLAssembledInterface.createFromNativePointers(0L, fnPtr)
                DirectContext.makeGLWithInterface(iface)
            }.getOrNull()
        if (ctx == null) {
            NativeTaoEglBridge.nativeDetach(handle)
            return
        }
        attachment = handle
        directContext = ctx
        glTextureHostState.value =
            object : TaoGlTextureHost {
                override val directContext: DirectContext = ctx

                // Read live: 0 once the layer closed, so a late disposal can't
                // bind (nor dereference) a freed attachment.
                override fun <T> withContextCurrent(block: () -> T): T? = withEglContextCurrent(attachment, block)
            }
        // Re-push any frame set before the window was ready, and paint.
        if (_bounds != IntRect.Zero) updateNativeFrame()
        host.requestRedraw()
    }

    // ── ComposeSceneLayer surface ──────────────────────────────────────

    override var density: Density
        get() = _density
        set(value) {
            _density = value
            innerScene.density = value
        }

    override var layoutDirection: LayoutDirection
        get() = _layoutDirection
        set(value) {
            _layoutDirection = value
            innerScene.layoutDirection = value
        }

    override var boundsInWindow: IntRect
        get() = _bounds
        set(value) {
            _bounds = value
            updateNativeFrame()
            host.requestRedraw()
        }

    override var compositionLocalContext: CompositionLocalContext?
        get() = _compositionLocalContext
        set(value) {
            _compositionLocalContext = value
        }

    override var scrimColor: Color?
        get() = _scrimColor
        set(value) {
            _scrimColor = value
        }

    override var focusable: Boolean
        get() = _focusable
        set(value) {
            _focusable = value
        }

    // Stored for the ComposeSceneLayer contract; outside-press dismissal is
    // handled via the parent scene's forwarded press listener, so this flag is
    // not consulted on the render path.
    override var consumePointerInputOutside: Boolean = initialConsumePointerInputOutside

    override fun close() {
        if (released) return
        released = true
        host.unregisterRenderer(rendererToken)
        host.unregisterKeyHandler(keyHandlerToken)
        host.unregisterOwnerMoveListener(moveListenerToken)
        host.unregisterOutsidePressListener(outsidePressToken)
        // Drop the TextureView handle before the context it points at dies: a
        // late composition must not import onto a closed context.
        glTextureHostState.value = null
        sceneBundle.close()
        if (attachment != 0L) {
            // A layer closes when Compose drops it — from the owner's
            // composition, i.e. inside the window scene's render pass. Binding
            // this layer's context and then unbinding it would leave the rest of
            // that frame (glyph-atlas uploads, flushAndSubmit) with no context at
            // all, silently, for good: the window keeps painting but stops
            // rastering anything new until something rebuilds its surface. Put
            // the owner's binding back — see [preservingEglBinding].
            preservingEglBinding {
                // The DirectContext must die on its own (thread-bound) EGL
                // context — same protocol as the standalone popup host.
                NativeTaoEglBridge.nativeMakeCurrent(attachment)
                // Belt for imports a leaked composition may still hold; the leases
                // of every live one were released by innerScene.close() above.
                directContext?.let(::releaseGlTextureImports)
                directContext?.close()
                directContext = null
                NativeTaoEglBridge.nativeDetach(attachment)
                attachment = 0
            }
        }
        popupWindow.requestClose()
    }

    override fun setContent(
        @Suppress("UNUSED_PARAMETER") parentCompositionContext: CompositionContext,
        content: @Composable () -> Unit,
    ) {
        innerScene.setContent {
            val locals = _compositionLocalContext
            // Our texture host goes *inside* the replayed locals: those carry
            // the window scene's host, which would otherwise shadow ours — and
            // this popup window renders through its own EGL + Skia context, so
            // a TextureView here must import onto that one.
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(LocalTaoGlTextureHost provides glTextureHost) {
                    content()
                }
            }
            if (locals != null) {
                CompositionLocalProvider(locals) { body() }
            } else {
                body()
            }
        }
        host.requestRedraw()
    }

    override fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
        onKeyEvent: ((KeyEvent) -> Boolean)?,
    ) {
        this.onPreviewKeyEvent = onPreviewKeyEvent
        this.onKeyEvent = onKeyEvent
    }

    override fun setOutsidePointerEventListener(
        onOutsidePointerEvent: ((eventType: PointerEventType, button: PointerButton?) -> Unit)?,
    ) {
        this.onOutsidePointerEvent = onOutsidePointerEvent
        if (onOutsidePointerEvent != null) {
            host.registerOutsidePressListener(outsidePressToken) { button ->
                this.onOutsidePointerEvent?.invoke(PointerEventType.Press, button)
            }
        } else {
            host.unregisterOutsidePressListener(outsidePressToken)
        }
    }

    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset = positionInWindow

    // ── Native frame ───────────────────────────────────────────────────

    /**
     * Pushes `boundsInWindow` to the popup window. GTK positions in
     * *logical* pixels: X11 popups in root coordinates (parent screen
     * origin + window-relative bounds), Wayland subsurfaces relative to
     * the parent **content** area — [TaoWindow.setOuterPosition] adds the
     * CSD content origin for `popupOf` windows, so we pass content-space
     * coords here ([TaoPopupHostLinux.parentScreenOriginPx] is zero on
     * Wayland).
     */
    private fun updateNativeFrame() {
        if (_bounds == IntRect.Zero || released) return
        val origin = host.parentScreenOriginPx
        val offset = host.coordinateOffset
        val xPx = _bounds.left + offset.x + origin.x
        val yPx = _bounds.top + offset.y + origin.y
        // Aligned to the surface scale: Compose bounds are arbitrary physical
        // pixels (odd widths come out of text measurement and half-dp padding
        // all the time), and a buffer that isn't a multiple of the announced
        // `buffer_scale` is a fatal Wayland protocol error — the compositor
        // drops the connection and the process dies (#502). It also keeps the
        // logical size below an exact integer for GTK.
        val w = alignToBufferScale(_bounds.width, bufferScale)
        val h = alignToBufferScale(_bounds.height, bufferScale)
        popupWindow.setOuterPosition((xPx / scale).toDouble(), (yPx / scale).toDouble())
        popupWindow.setInnerSize((w / scale).toDouble(), (h / scale).toDouble())
        if (w != widthPx || h != heightPx) {
            widthPx = w
            heightPx = h
            if (attachment != 0L) {
                NativeTaoEglBridge.nativeResize(attachment, w, h, scale)
            }
        }
        if (!shown) {
            shown = true
            popupWindow.show()
        }
    }

    // ── Per-frame render — driven by the host's redraw pump ───────────────

    private fun renderFrame() {
        if (released || attachment == 0L) return
        if (widthPx <= 0 || heightPx <= 0) return
        val ctx = directContext ?: return
        // Render even while `boundsInWindow` is still Zero (surface is 1×1
        // and the window unmapped): the first `innerScene.render` is what
        // drives Compose's measure pass, and that measure is what writes
        // `boundsInWindow` in the first place — skipping it would deadlock
        // the popup at zero bounds forever. Same bootstrap as the Windows
        // layer's 1×1 initial drawBounds. The present is skipped until the
        // frame is real; nothing is on screen yet anyway.
        val frame = _bounds
        NativeTaoEglBridge.nativeMakeCurrent(attachment)
        // Private EGL context — no resetGLAll needed (unlike the Windows
        // shared-process-context path).
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = ctx,
            clearColorArgb = 0x00000000,
            present = {
                if (frame != IntRect.Zero) NativeTaoEglBridge.nativePresent(attachment)
            },
        ) { canvas, nanoTime ->
            canvas.save()
            try {
                canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
                sceneBundle.render(canvas, nanoTime)
            } finally {
                canvas.restore()
            }
        }
        NativeTaoEglBridge.nativeReleaseCurrent(attachment)
    }

    // ── Input — the popup window receives its own pointer events ──────────

    private fun registerInput() {
        popupWindow.onPointerMoved { xFixed, yFixed ->
            sendPointer(PointerEventType.Move, xFixed / POSITION_SCALE, yFixed / POSITION_SCALE, null)
        }
        popupWindow.onPointerButton { code, pressed ->
            sendPointer(
                if (pressed) PointerEventType.Press else PointerEventType.Release,
                lastX,
                lastY,
                mapButton(code),
            )
        }
        popupWindow.onPointerScroll { event ->
            if (released) return@onPointerScroll
            val modifiers = taoKeyboardModifiers(host.parentWindow.modifierState)
            innerScene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = scenePosition(lastX, lastY),
                scrollDelta = Offset(event.dxAwt, event.dyAwt),
                type = PointerType.Mouse,
                keyboardModifiers = modifiers,
                nativeEvent =
                    TaoSyntheticMouseWheelEvent.create(
                        event = event,
                        x = lastX,
                        y = lastY,
                        keyboardModifiers = modifiers,
                    ),
            )
        }
    }

    private var lastX = 0f
    private var lastY = 0f

    private fun sendPointer(
        eventType: PointerEventType,
        xPx: Float,
        yPx: Float,
        button: PointerButton?,
    ) {
        if (released) return
        lastX = xPx
        lastY = yPx
        innerScene.sendPointerEvent(
            eventType = eventType,
            position = scenePosition(xPx, yPx),
            type = PointerType.Mouse,
            keyboardModifiers = taoKeyboardModifiers(host.parentWindow.modifierState),
            button = button,
        )
    }

    /** Popup-window-local physical px → inner-scene (parent-window) coords. */
    private fun scenePosition(
        x: Float,
        y: Float,
    ): Offset = Offset(x + _bounds.left, y + _bounds.top)

    private fun mapButton(code: Int): PointerButton =
        when (code) {
            TaoMouseButton.RIGHT -> PointerButton.Secondary
            TaoMouseButton.MIDDLE -> PointerButton.Tertiary
            else -> PointerButton.Primary
        }

    /**
     * Key events forwarded by the host (the parent window keeps keyboard
     * focus — see the class doc). Preview/scene/post ordering mirrors
     * `ComposeScene.dispatchNativeKeyEvent`; the scene only sees keys when
     * the popup is focusable, so tooltips never swallow typing.
     */
    private fun dispatchKey(event: KeyEvent): Boolean {
        if (released) return false
        if (onPreviewKeyEvent?.invoke(event) == true) return true
        if (_focusable && innerScene.sendKeyEvent(event)) return true
        return onKeyEvent?.invoke(event) == true
    }

    private companion object {
        // Wire scale — must match Rust `CURSOR_FIXED_SCALE`.
        private const val POSITION_SCALE: Float = 1024f

        // Backend kinds from NativeTaoBridge.nativeLinuxHandles — the
        // bridge returns a (kind, display, native_window) triple.
        private const val HANDLE_TRIPLE_SIZE: Int = 3
        private const val KIND_X11: Int = 1
        private const val KIND_WAYLAND: Int = 2
    }
}
