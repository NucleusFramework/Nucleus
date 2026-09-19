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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
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
import dev.nucleusframework.window.tao.scene.catchExceptions
import dev.nucleusframework.window.tao.scene.preservingEglBinding
import dev.nucleusframework.window.tao.scene.renderGlFrame
import dev.nucleusframework.window.tao.scene.withEglContextCurrent
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Rect
import org.jetbrains.skia.makeGLWithInterface
import java.util.logging.Level
import java.util.logging.Logger
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
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    private val scrimColorState: MutableState<Color?> = mutableStateOf(null)
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

    /**
     * The rectangle the popup window covers, in scene coordinates: [_bounds]
     * inflated by [popupDrawBounds] so shadows and the dialog appearance
     * animation are not clipped at the layout edge. A press in the margin is
     * an outside press — see [sendPointer].
     */
    private var drawBounds: IntRect = IntRect.Zero

    /**
     * The last non-empty [_bounds]: what the native surface is sized and placed
     * on. `Dialog.skiko.kt`'s disappearance swaps the layer's content for an
     * empty `Layout` that only replays the recorded picture, so Compose reports
     * a zero-size `boundsInWindow` at the window centre for the whole fade-out.
     * An in-scene layer does not care — it draws into the window canvas — but
     * this surface must keep covering where the dialog was, or the fade-out
     * shows as a square of margin around a point.
     */
    private var contentBounds: IntRect = IntRect.Zero

    /**
     * Whether the compositor positions this surface (`xdg_popup`) instead of us
     * (`wl_subsurface`) — see [decideCompositorPlacement]. Decided at the first
     * frame, since a window's map type cannot change afterwards.
     */
    private var compositorPlaced: Boolean? = null

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
     * Compose's box for placing this layer's content, as reported through
     * `LocalWindowInfo` inside the layer's own composition (#569).
     *
     * Two answers, because two very different things end up in a scene layer:
     *
     *  - A **popup** (`Popup`, `DropdownMenu`, context menu, tooltip, Jewel's
     *    combo-box flyout) belongs to the *display*. It gets the work area
     *    ([sceneLayoutSize]), so `Popup.skiko.kt` lays it out at full size and
     *    flips it against a screen-sized box instead of against the owner
     *    window — the point of native popup layers. That box is still rooted at
     *    the window; the origin is what the screen clamp corrects when the
     *    frame is pushed.
     *  - A **dialog** (`Dialog`, Material `AlertDialog`) belongs to its
     *    *window*: `Dialog.skiko.kt` places it at `containerSize.center`, and a
     *    window-owned dialog centred on the display would sit visibly
     *    off-centre — and drift further as the user moved the window. It gets
     *    the owner window's content size, exactly as before #569.
     *
     * `scrimColor` is the discriminator, and a sound one: only
     * `Dialog.skiko.kt` ever writes it, from
     * `DialogAppearanceController.properties` — assigned while `DialogLayout`
     * composes, *before* `layer.Content { }` and so before this is read.
     * `Popup.skiko.kt` never touches it. Held as snapshot state so a later
     * write recomposes the content that read it.
     */
    private val dialogContainerSize: IntSize
        get() =
            host.parentWindowInfo.containerSize.let {
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
            override val containerSize: IntSize
                get() = if (scrimColorState.value != null) dialogContainerSize else sceneLayoutSize
        }

    private val sceneBundle: TaoSceneBundle =
        canvasLayersSceneBundle(
            coroutineContext = host.sceneCoroutineContext,
            density = _density,
            layoutDirection = _layoutDirection,
            size = sceneLayoutSize,
            platformContext =
                object : TaoPlatformContextBase() {
                    override val sceneScale: Float get() = _density.density

                    override val windowInfo: androidx.compose.ui.platform.WindowInfo
                        get() = popupWindowInfo

                    // The popup window's surface is per-pixel transparent, so
                    // dialog scrims must use the alpha-aware blend — same
                    // contract as Compose Desktop's `WindowComposeSceneLayer`
                    // (#559).
                    override val isWindowTransparent: Boolean get() = true

                    override fun setPointerIcon(pointerIcon: PointerIcon) {
                        if (released) return
                        NativeTaoBridge.setCursorIcon(
                            popupWindow.handle,
                            pointerIcon.toTaoCursorIconCode(),
                        )
                    }
                },
            requestFrame = { host.requestRedraw() },
        ).apply {
            // Report through the owner window's channel — see [TaoPopupHost.exceptionHandler].
            exceptionHandler = host.exceptionHandler
            // Dim this popup under the dialogs stacked above it. The canvas is
            // translated by `-_bounds.topLeft` at this point, so the visible
            // surface is `_bounds.topLeft` + the surface size in scene coordinates.
            renderOverlay = { canvas ->
                host.popupScrims.paintAbove(
                    rendererToken,
                    canvas,
                    Rect.makeXYWH(
                        drawBounds.left.toFloat(),
                        drawBounds.top.toFloat(),
                        widthPx.toFloat(),
                        heightPx.toFloat(),
                    ),
                )
            }
        }

    private val innerScene: ComposeScene get() = sceneBundle.scene

    /**
     * Keeps the inner scene's size on the box the layer's content lays out in
     * (#569). A dialog's root `Layout` fills the scene's constraints, and
     * `Dialog.skiko.kt` puts its appearance animation's `GraphicsLayer` on
     * that very Layout — so the scale pivots around the *scene's* centre. In
     * the window's own scene that box is the window, whose centre is the
     * dialog's; a work-area-sized scene would make the dialog slide towards
     * the display's centre while it scales in. Popups keep the work area so a
     * tall menu can lay out at full height. Re-checked every frame: the window
     * may have been resized since.
     */
    private fun syncSceneSize() {
        val want = if (scrimColorState.value != null) dialogContainerSize else sceneLayoutSize
        if (innerScene.size != want) innerScene.size = want
    }

    private var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onOutsidePointerEvent: ((PointerEventType, PointerButton?) -> Unit)? = null

    init {
        trace { "created popup window ${popupWindow.handle} focusable=$_focusable" }
        popupWindow.onWindowReady { _, _ ->
            trace { "window ready" }
            attachGpu()
        }
        // Compositor expose (X11) / re-map: repaint through the host pump.
        popupWindow.onRedrawRequested { host.requestRedraw() }
        registerInput()
        host.registerRenderer(rendererToken) { renderFrame() }
        host.popupScrims.register(rendererToken) { scrimColorState.value }
        host.registerKeyHandler(keyHandlerToken) { dispatchKey(it) }
        host.registerOwnerMoveListener(moveListenerToken) {
            if (!contentBounds.isEmpty) updateNativeFrame()
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
        trace { "gpu attached kind=$kind ${w}x$h" }
        glTextureHostState.value =
            object : TaoGlTextureHost {
                override val directContext: DirectContext = ctx

                // Read live: 0 once the layer closed, so a late disposal can't
                // bind (nor dereference) a freed attachment.
                override fun <T> withContextCurrent(block: () -> T): T? = withEglContextCurrent(attachment, block)
            }
        // Re-push any frame set before the window was ready, and paint.
        if (!contentBounds.isEmpty) updateNativeFrame()
        // Paint now, not on the owner's next frame: this first render is what
        // measures the content and writes boundsInWindow, i.e. what shows the
        // popup at all — waiting for the owner's redraw added a frame or two to
        // every menu. The present itself still rides the owner's pump.
        renderFrame()
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
            trace { "boundsInWindow=$value" }
            _bounds = value
            if (!value.isEmpty) contentBounds = value
            updateNativeFrame()
            host.requestRedraw()
        }

    override var compositionLocalContext: CompositionLocalContext?
        get() = _compositionLocalContext
        set(value) {
            _compositionLocalContext = value
        }

    override var scrimColor: Color?
        get() = scrimColorState.value
        set(value) {
            scrimColorState.value = value
            syncSceneSize()
            // The scrim is painted by the owner window's scene and by the layers
            // below, none of which observe this state — repaint them.
            host.popupScrims.notifyChanged()
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
        trace { "close" }
        host.unregisterRenderer(rendererToken)
        host.onLayerClosed(this)
        host.popupScrims.unregister(rendererToken)
        host.unregisterKeyHandler(keyHandlerToken)
        host.unregisterOwnerMoveListener(moveListenerToken)
        host.unregisterOutsidePressListener(outsidePressToken)
        host.releaseCompositorPopup(rendererToken)
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
                CompositionLocalProvider(
                    LocalTaoGlTextureHost provides glTextureHost,
                    // Inside the replayed parent locals, and deliberately so
                    // (#569): `Popup.skiko.kt` reads `LocalWindowInfo` from
                    // *this* composition to size the box it flips and clips the
                    // popup inside. The replayed snapshot carries the owner
                    // window's WindowInfo, which would pin every popup to the
                    // window — the opposite of what native popup layers exist
                    // for. `popupWindowInfo` reports the work area, so Compose
                    // flips against a screen-sized box (still rooted at the
                    // window; the origin is what the clamp corrects).
                    LocalWindowInfo provides popupWindowInfo,
                ) {
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
     *
     * The position is clamped into the hosting display's work area
     * ([popupScreenClampOffset], #569) — Compose picked it inside a
     * work-area-sized virtual screen rooted at the window, so it can point off
     * the real display. Only the window position moves: `_bounds` stays what
     * Compose believes, and it is also the space [renderFrame] translates by
     * and [scenePosition] maps pointers back through, so the surface content
     * and hit-testing are unaffected. Re-clamped on every call, so the
     * owner-move listener keeps an open popup on screen during an X11 drag.
     * No-op on Wayland, where the host reports no screen geometry.
     */
    private fun updateNativeFrame() {
        if (contentBounds.isEmpty || released) return
        drawBounds = popupDrawBounds(contentBounds, _density.density)
        val origin = host.parentScreenOriginPx
        val offset = host.coordinateOffset
        // The clamp is decided on the content, not the inflated surface: what
        // must stay on screen is the popup the user sees, and a shadow margin
        // hanging past the edge is what the in-scene layer does too.
        val contentInParent = contentBounds.translate(offset)
        val frameInParent = drawBounds.translate(offset)
        val geometry = host.popupScreenGeometry
        val clamp = popupScreenClampOffset(contentInParent, geometry)
        val xPx = frameInParent.left + clamp.x + origin.x
        val yPx = frameInParent.top + clamp.y + origin.y
        geometry?.let {
            val onScreen = it.parentContentOriginPx + clamp
            TaoPopupDiagnostics.record(
                PopupFrameRecord(
                    boundsInWindowPx = _bounds,
                    frameOnScreenPx = frameInParent.translate(onScreen),
                    contentOnScreenPx = contentInParent.translate(onScreen),
                    clampOffsetPx = clamp,
                    panelHandle = popupWindow.handle,
                ),
            )
        }
        // Aligned to the surface scale: Compose bounds are arbitrary physical
        // pixels (odd widths come out of text measurement and half-dp padding
        // all the time), and a buffer that isn't a multiple of the announced
        // `buffer_scale` is a fatal Wayland protocol error — the compositor
        // drops the connection and the process dies (#502). It also keeps the
        // logical size below an exact integer for GTK.
        val w = alignToBufferScale(drawBounds.width, bufferScale)
        val h = alignToBufferScale(drawBounds.height, bufferScale)
        val compositorPlaced =
            compositorPlaced ?: decideCompositorPlacement(geometry).also {
                compositorPlaced = it
                TaoPopupDiagnostics.lastCompositorPlaced = it
            }
        trace {
            "push frame pos=($xPx,$yPx) size=${w}x$h shown=$shown attached=${attachment != 0L} " +
                "compositorPlaced=$compositorPlaced"
        }
        val sizeChanged = w != widthPx || h != heightPx
        if (compositorPlaced) {
            // The compositor owns the position from map on, and GDK builds the
            // `xdg_positioner` once, from the window's geometry as it stands at
            // map — so the anchor call carries the size as well, and a plain
            // move or resize afterwards would re-map the window as a
            // subsurface. A size that changes after the popup is mapped (a menu
            // whose items measure late) therefore cannot be applied in place:
            // resizing the EGL buffer alone would leave the `xdg_surface`
            // geometry at the anchored size, which is the buffer/geometry
            // disagreement of #502. Re-map instead — hide, re-anchor at the new
            // size, show — which is also what re-runs the compositor's flip for
            // the size it now has.
            if (!shown || sizeChanged) {
                if (shown) {
                    trace { "re-anchor ${widthPx}x$heightPx -> ${w}x$h" }
                    popupWindow.hide()
                    shown = false
                }
                popupWindow.anchorPopupInParent(
                    contentXDp = contentInParent.left / scale.toDouble(),
                    contentYDp = contentInParent.top / scale.toDouble(),
                    widthDp = (w / scale).toDouble(),
                    heightDp = (h / scale).toDouble(),
                    shadowLeftDp = ((contentBounds.left - drawBounds.left) / scale).roundToInt(),
                    shadowTopDp = ((contentBounds.top - drawBounds.top) / scale).roundToInt(),
                    shadowRightDp = ((drawBounds.right - contentBounds.right) / scale).roundToInt(),
                    shadowBottomDp = ((drawBounds.bottom - contentBounds.bottom) / scale).roundToInt(),
                )
                TaoPopupDiagnostics.compositorAnchorCount++
            }
        } else {
            popupWindow.setOuterPosition((xPx / scale).toDouble(), (yPx / scale).toDouble())
            popupWindow.setInnerSize((w / scale).toDouble(), (h / scale).toDouble())
        }
        if (sizeChanged) {
            widthPx = w
            heightPx = h
            if (attachment != 0L) {
                NativeTaoEglBridge.nativeResize(attachment, w, h, scale)
            }
        }
        if (!shown) {
            shown = true
            trace { "show" }
            popupWindow.show()
        }
    }

    /**
     * Whether the compositor should place this surface — an `xdg_popup` it
     * keeps on screen — rather than us. Only on native Wayland, the one
     * backend where the client cannot see the screen and so cannot clamp (X11
     * has [popupScreenClampOffset]); only for popups, since a dialog belongs
     * to its window and stays centred in it as a subsurface; and one per
     * parent, because an `xdg_popup` must be its parent's topmost popup
     * ([TaoPopupHostLinux.acquireCompositorPopup]).
     */
    private fun decideCompositorPlacement(geometry: PopupScreenGeometry?): Boolean =
        geometry == null &&
            popupWindow.parentIsNativeWayland() &&
            scrimColorState.value == null &&
            host.acquireCompositorPopup(rendererToken)

    // ── Per-frame render — driven by the host's redraw pump ───────────────

    private var presented = false

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
        syncSceneSize()
        val frame = drawBounds
        NativeTaoEglBridge.nativeMakeCurrent(attachment)
        // Private EGL context — no resetGLAll needed (unlike the Windows
        // shared-process-context path).
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = ctx,
            clearColorArgb = 0x00000000,
            // Per-pixel-alpha popup surface (no-op on Linux today, but the
            // alpha mode must be stated — see renderGlFrame).
            windowTransparent = true,
            present = {
                if (frame != IntRect.Zero) {
                    if (!presented) {
                        presented = true
                        trace { "first present frame=$frame" }
                    }
                    NativeTaoEglBridge.nativePresent(attachment)
                }
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

    // Guarded: these are Tao popup-window callbacks into this popup's own
    // scene, not nested inside the owner window's guarded frame pass.
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
            host.exceptionHandler.catchExceptions {
                if (released) return@catchExceptions
                val pos = scenePosition(lastX, lastY)
                innerScene.dispatchAwtShapedScroll(
                    x = pos.x,
                    y = pos.y,
                    event = event,
                    keyboardModifiers = taoKeyboardModifiers(host.parentWindow.modifierState),
                )
            }
        }
    }

    private var lastX = 0f
    private var lastY = 0f

    private fun sendPointer(
        eventType: PointerEventType,
        xPx: Float,
        yPx: Float,
        button: PointerButton?,
    ) = host.exceptionHandler.catchExceptions {
        if (released) return@catchExceptions
        lastX = xPx
        lastY = yPx
        val position = scenePosition(xPx, yPx)
        // The window is inflated past the layout bounds (see [drawBounds]), and
        // that margin lands on this window rather than the parent — on Windows
        // and macOS the OS routes it to the parent, because those layers hand
        // it the content rect. Here the layer has to do the routing: report the
        // outside press (Compose's dismiss-on-click-outside) and hand the event
        // to the owner window, so a click on a button beside an open menu both
        // closes the menu and presses the button.
        if (!_bounds.contains(position.round())) {
            if (eventType == PointerEventType.Press) onOutsidePointerEvent?.invoke(eventType, button)
            forwardToOwner(eventType, position, button)
            return@catchExceptions
        }
        innerScene.sendPointerEvent(
            eventType = eventType,
            position = position,
            type = PointerType.Mouse,
            keyboardModifiers = taoKeyboardModifiers(host.parentWindow.modifierState),
            button = button,
        )
    }

    /**
     * Hands the owner window an event that landed on this popup's draw margin.
     *
     * Only while the point is over the owner's content: the margin can hang off
     * the window (a menu opened at its edge), and a press over another window —
     * or another application — is not the owner's to receive. Compose would
     * simply hit-test nothing there, but forwarding it would still run the
     * dismissal twice and report a press the user never made to that window.
     */
    private fun forwardToOwner(
        eventType: PointerEventType,
        position: Offset,
        button: PointerButton?,
    ) {
        val size = host.parentWindowSize
        val inOwner =
            position.x >= 0f &&
                position.y >= 0f &&
                position.x < size.width &&
                position.y < size.height
        if (!inOwner) return
        host.forwardMarginPointer(eventType, position, button)
    }

    /** Popup-window-local physical px → inner-scene (parent-window) coords. */
    private fun scenePosition(
        x: Float,
        y: Float,
    ): Offset = Offset(x + drawBounds.left, y + drawBounds.top)

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

    private fun trace(message: () -> String) {
        if (logger.isLoggable(Level.FINE)) logger.fine("popup ${System.identityHashCode(this)}: ${message()}")
    }

    private companion object {
        private val logger: Logger = Logger.getLogger(TaoPopupSceneLayerLinux::class.java.name)

        // Wire scale — must match Rust `CURSOR_FIXED_SCALE`.
        private const val POSITION_SCALE: Float = 1024f

        // Backend kinds from NativeTaoBridge.nativeLinuxHandles — the
        // bridge returns a (kind, display, native_window) triple.
        private const val HANDLE_TRIPLE_SIZE: Int = 3
        private const val KIND_X11: Int = 1
        private const val KIND_WAYLAND: Int = 2
    }
}
