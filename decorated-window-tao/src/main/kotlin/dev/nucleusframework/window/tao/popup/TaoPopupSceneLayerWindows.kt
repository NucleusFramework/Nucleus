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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.win32WheelToAwtScrollEvent
import dev.nucleusframework.window.tao.ffi.PopupNativeBridgeWindows
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.catchExceptions
import dev.nucleusframework.window.tao.scene.renderGlFrame
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Rect

/**
 * Windows popup layer backed by a transparent owned WS_POPUP HWND.
 *
 * The coordinate model mirrors Compose Desktop's AWT WindowComposeSceneLayer:
 * boundsInWindow is the logical content rect, and rendering happens in
 * parent-window coordinates. The native popup surface is kept exactly
 * at content bounds because transparent pixels around the content are not
 * reliably alpha-composited by DWM on all Windows drivers.
 *
 * ### Multi-Monitor DPI Synchronization:
 * Under Windows Per-Monitor DPI Aware v2:
 * 1. The native panel is created at the parent window's current coordinates so
 *    Windows associates the HWND with the target monitor's DPI immediately.
 * 2. `WM_DPICHANGED` on the popup HWND is ignored by the native WndProc because
 *    Compose Multiplatform layout explicitly manages physical pixel sizing via
 *    [updateNativeFrame].
 * 3. [densityState] reactively propagates dynamic monitor DPI changes into
 *    [innerScene]'s [androidx.compose.ui.platform.LocalDensity], ensuring font rasterization
 *    and container measurement stay synchronized across displays.
 */
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class TaoPopupSceneLayerWindows(
    private val host: TaoPopupHostWindows,
    initialDensity: Density,
    initialLayoutDirection: LayoutDirection,
    initialFocusable: Boolean,
    initialConsumePointerInputOutside: Boolean,
) : ComposeSceneLayer {
    private var _density = initialDensity
    private val densityState: MutableState<Density> = mutableStateOf(initialDensity)
    private var _layoutDirection = initialLayoutDirection
    private val layoutDirectionState: MutableState<LayoutDirection> = mutableStateOf(initialLayoutDirection)
    private var _focusable = initialFocusable
    private var _bounds: IntRect = IntRect.Zero
    private val scrimColorState: MutableState<Color?> = mutableStateOf(null)
    private var _compositionLocalContext: CompositionLocalContext? = null

    private val rendererToken: Any = Any()
    private val moveListenerToken: Any = Any()

    /**
     * Set in [close] before `nativeRelease` frees the panel. Guards
     * [renderFrame] against firing on a freed handle: the host drains a
     * *snapshot* of its renderer map each frame, so if rendering one
     * layer triggers a recomposition that closes another layer, that
     * layer's already-captured renderer still runs once. Without this
     * flag it would call `nativeMakeCurrent` on freed memory (a
     * use-after-free crash reading the released `PopupState`).
     */
    private var released = false

    // Work-area sized (not parent-window sized) so a popup larger than its
    // owner window lays out at full size — mirrors the macOS
    // TaoPopupSceneLayer contract. Critical for tiny owner windows (e.g. the
    // tray-popup anchor pattern) where the parent is only a few px.
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
     * The rectangle the HWND covers, in scene coordinates: [_bounds] inflated
     * by [popupDrawBounds] so shadows and the dialog appearance animation are
     * not clipped at the layout edge. The native side keeps [_bounds] as the
     * content rect, so a click in the margin is an outside click.
     */
    private var drawBounds: IntRect = IntRect(0, 0, 1, 1)

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
    private var widthPx: Int = 1
    private var heightPx: Int = 1

    /**
     * Created as a tiny offscreen HWND. The inner scene has real layout
     * constraints already, so the native surface doesn't need to start at
     * parent-window size.
     *
     * **Deferred out of the render pass**: Compose instantiates this layer
     * inside [TaoComposeSceneHostWindows.onRedrawRequested]'s `sc.render()`
     * call — i.e. mid GL-frame, while the host EGLContext is bound to the
     * host window surface and Skia is mid-record. Allocating the popup's
     * D3D11 texture + `eglCreatePbufferFromClientBuffer` +
     * `CreateTargetForHwnd` + composition swapchain there touches ANGLE's
     * D3D11 immediate context mid-frame, which intermittently fails
     * (observed when a tooltip popup is torn down and re-created by the
     * same recomposition — e.g. a theme switch on the hovered toggle).
     * Instead the native HWND is created lazily from [renderFrame], which
     * the host runs in its `popupRenderers` loop *after* `flushAndSubmit`
     * — outside the GL record pass. Setters invoked during composition
     * store their state and defer the matching native call until the panel
     * exists. A creation failure degrades to a skipped frame (logged)
     * rather than a fatal `require`.
     */
    private var panelHandle: Long = 0L

    /** Set once [ensurePanel] fails, so we don't retry every frame. */
    private var panelCreateFailed: Boolean = false

    private fun ensurePanel(): Boolean {
        if (panelHandle != 0L) return true
        if (panelCreateFailed) return false
        val offset = host.coordinateOffset
        val initX = if (_bounds != IntRect.Zero) _bounds.left + offset.x else 0
        val initY = if (_bounds != IntRect.Zero) _bounds.top + offset.y else 0
        val handle =
            PopupNativeBridgeWindows
                .nativeCreatePanel(
                    parentHwnd = host.parentHwnd,
                    xPx = initX,
                    yPx = initY,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
        if (handle == 0L) {
            panelCreateFailed = true
            layerLogger.warning(
                "nativeCreatePanel returned 0 (parentHwnd=${host.parentHwnd}); popup layer disabled",
            )
            return false
        }
        panelHandle = handle
        // Replay the deferred state set during composition (init block below
        // no longer touches the native panel — it didn't exist yet).
        PopupNativeBridgeWindows.nativeSetEventCallback(panelHandle, PopupEventCallback())
        PopupNativeBridgeWindows.nativeSetFocusable(panelHandle, _focusable)
        if (onOutsidePointerEvent != null) {
            PopupNativeBridgeWindows.nativeInstallOutsideClickMonitor(panelHandle, PopupOutsideListener())
        }
        if (_bounds != IntRect.Zero) updateNativeFrame()
        return true
    }

    private val directContext: DirectContext = host.hostDirectContext

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

                    // The popup HWND's surface is per-pixel transparent, so
                    // dialog scrims must use the alpha-aware blend — same
                    // contract as Compose Desktop's `WindowComposeSceneLayer`
                    // (#559).
                    override val isWindowTransparent: Boolean get() = true
                },
            requestFrame = { host.requestRedraw() },
        ).apply {
            // Report through the owner window's channel — see [TaoPopupHost.exceptionHandler].
            exceptionHandler = host.exceptionHandler
            // Dim this popup under the dialogs stacked above it. The canvas is
            // translated by `-drawBounds.topLeft` at this point, so the visible
            // surface is `drawBounds.topLeft` + the surface size in scene coordinates.
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

    /**
     * Every method is guarded: these are WndProc callbacks into the popup's own
     * scene, not nested inside the owner window's guarded frame pass.
     */
    private inner class PopupEventCallback : PopupNativeBridgeWindows.EventCallback {
        override fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) = host.exceptionHandler.catchExceptions {
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
            innerScene.sendPointerEvent(
                eventType = eventType,
                position = scenePosition(x, y),
                type = PointerType.Mouse,
                button = pointerButton,
            )
        }

        override fun onScroll(
            x: Float,
            y: Float,
            dx: Float,
            dy: Float,
        ) = host.exceptionHandler.catchExceptions {
            val pos = scenePosition(x, y)
            innerScene.dispatchAwtShapedScroll(pos.x, pos.y, win32WheelToAwtScrollEvent(dx, dy))
        }

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) = host.exceptionHandler.catchExceptions {
            innerScene.dispatchNativeKeyEvent(
                type = type,
                vkCode = vkCode,
                codePoint = codePoint,
                modifiers = modifiers,
                onPreviewKeyEvent = onPreviewKeyEvent,
                onKeyEvent = onKeyEvent,
            )
        }
    }

    private inner class PopupOutsideListener : PopupNativeBridgeWindows.OutsideClickListener {
        override fun onOutsideClick(
            type: Int,
            button: Int,
        ) {
            if (released) return
            val handler = onOutsidePointerEvent ?: return
            val pointerButton =
                when (button) {
                    TaoNativeWireFormat.BUTTON_PRIMARY -> PointerButton.Primary
                    TaoNativeWireFormat.BUTTON_SECONDARY -> PointerButton.Secondary
                    else -> PointerButton.Tertiary
                }
            handler(PointerEventType.Press, pointerButton)
        }
    }

    init {
        // The native panel is created lazily in renderFrame (see ensurePanel),
        // not here — the constructor runs inside sc.render() (mid GL-frame).
        // Register the per-frame renderer + owner-move listener now; both
        // defer / no-op until the panel exists.
        host.registerRenderer(rendererToken) { renderFrame() }
        host.popupScrims.register(rendererToken) { scrimColorState.value }
        host.registerOwnerMoveListener(moveListenerToken) {
            if (panelHandle != 0L && !contentBounds.isEmpty) {
                updateNativeFrame()
            }
        }
    }

    override var density: Density
        get() = _density
        set(value) {
            _density = value
            densityState.value = value
            innerScene.density = value
        }

    override var layoutDirection: LayoutDirection
        get() = _layoutDirection
        set(value) {
            _layoutDirection = value
            layoutDirectionState.value = value
            innerScene.layoutDirection = value
        }

    override var boundsInWindow: IntRect
        get() = _bounds
        set(value) {
            _bounds = value
            if (!value.isEmpty) contentBounds = value
            updateDrawBoundsFromBounds()
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
            if (panelHandle != 0L) PopupNativeBridgeWindows.nativeSetFocusable(panelHandle, value)
        }

    // Stored for the ComposeSceneLayer contract; the native popup HWND handles
    // outside-click dismissal via its own SetCapture monitor, so this flag is
    // not consulted on the render path.
    override var consumePointerInputOutside: Boolean = initialConsumePointerInputOutside

    override fun close() {
        released = true
        host.notifyPopupClosing()
        host.unregisterRenderer(rendererToken)
        host.onLayerClosed(this)
        host.popupScrims.unregister(rendererToken)
        host.unregisterOwnerMoveListener(moveListenerToken)
        PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panelHandle)
        PopupNativeBridgeWindows.nativeSetEventCallback(panelHandle, null)
        sceneBundle.close()
        PopupNativeBridgeWindows.nativeRelease(panelHandle)
    }

    override fun setContent(
        @Suppress("UNUSED_PARAMETER") parentCompositionContext: CompositionContext,
        content: @Composable () -> Unit,
    ) {
        innerScene.setContent {
            val locals = _compositionLocalContext
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(
                    LocalDensity provides densityState.value,
                    LocalLayoutDirection provides layoutDirectionState.value,
                    // Inside the replayed parent locals, and deliberately so
                    // (#569). `Popup.skiko.kt` reads `LocalWindowInfo` from
                    // *this* composition to size the box it flips and clips the
                    // popup inside; the replayed snapshot carries the owner
                    // window's WindowInfo, which would pin every popup to the
                    // window — the exact opposite of what native popup layers
                    // exist for. The scene's own `popupWindowInfo` reports the
                    // work area, so Compose lays out and flips against a
                    // screen-sized box (still rooted at the window — the
                    // origin is what [updateNativeFrame]'s clamp corrects).
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
        if (panelHandle == 0L) return // deferred to ensurePanel
        if (onOutsidePointerEvent != null) {
            PopupNativeBridgeWindows.nativeInstallOutsideClickMonitor(panelHandle, PopupOutsideListener())
        } else {
            PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panelHandle)
        }
    }

    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset = positionInWindow

    private fun renderFrame() {
        if (released) return
        if (drawBounds == IntRect.Zero) return
        if (widthPx <= 0 || heightPx <= 0) return
        if (!ensurePanel()) return
        syncSceneSize()
        if (!PopupNativeBridgeWindows.nativeMakeCurrent(panelHandle)) return
        directContext.resetGLAll()

        val frame = drawBounds
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = directContext,
            clearColorArgb = 0x00000000,
            // Per-pixel-alpha DComp surface — no LCD SurfaceProps.
            windowTransparent = true,
            present = { PopupNativeBridgeWindows.nativeSwapBuffers(panelHandle) },
        ) { canvas, nanoTime ->
            canvas.save()
            try {
                canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
                sceneBundle.render(canvas, nanoTime)
            } finally {
                canvas.restore()
            }
        }
    }

    private fun scenePosition(
        x: Float,
        y: Float,
    ): Offset = Offset(x + drawBounds.left, y + drawBounds.top)

    private fun updateDrawBoundsFromBounds(): Boolean {
        if (contentBounds.isEmpty) return false
        val nextDrawBounds = popupDrawBounds(contentBounds, _density.density)
        val changed = nextDrawBounds != drawBounds
        drawBounds = nextDrawBounds
        widthPx = drawBounds.width.coerceAtLeast(1)
        heightPx = drawBounds.height.coerceAtLeast(1)
        updateNativeFrame()
        return changed
    }

    /**
     * Pushes the popup frame to its HWND, screen-clamped (#569).
     *
     * The clamp shifts the **native frame only** — never [drawBounds] or
     * [_bounds]. Those two are the popup's *scene* coordinates: [renderFrame]
     * translates the inner scene by `-drawBounds` and [scenePosition] maps
     * HWND-local pointers back by `+drawBounds`, so shifting them would move
     * the content inside the surface by exactly as much as the surface moved
     * on screen — a visual no-op — and would desynchronize hit-testing from
     * what Compose believes. Only the `SetWindowPos` origin moves; the surface
     * content and the coordinate space Compose sees stay untouched.
     *
     * Re-clamped on every call, so the owner-move listener (see [init]) keeps
     * an open popup inside the work area while the window is dragged, and a
     * drag onto a second display re-resolves the display too.
     */
    private fun updateNativeFrame() {
        if (panelHandle == 0L) return
        if (drawBounds == IntRect.Zero || contentBounds.isEmpty) return
        val offset = host.coordinateOffset
        // The clamp is decided on the content, not the inflated surface: what
        // must stay on screen is the popup the user sees, and a shadow margin
        // hanging past the edge is what the in-scene layer does too.
        val contentInParent = contentBounds.translate(offset)
        val frameInParent = drawBounds.translate(offset)
        val geometry = host.popupScreenGeometry
        val clamp = popupScreenClampOffset(contentInParent, geometry)
        val finalX = frameInParent.left + clamp.x
        val finalY = frameInParent.top + clamp.y
        geometry?.let {
            val onScreen = it.parentContentOriginPx + clamp
            TaoPopupDiagnostics.record(
                PopupFrameRecord(
                    boundsInWindowPx = _bounds,
                    frameOnScreenPx = frameInParent.translate(onScreen),
                    contentOnScreenPx = contentInParent.translate(onScreen),
                    clampOffsetPx = clamp,
                    panelHandle = panelHandle,
                ),
            )
        }
        PopupNativeBridgeWindows.nativeSetFrameInWindow(
            panel = panelHandle,
            xPx = finalX,
            yPx = finalY,
            widthPx = drawBounds.width.coerceAtLeast(1),
            heightPx = drawBounds.height.coerceAtLeast(1),
            contentXPx = contentBounds.left - drawBounds.left,
            contentYPx = contentBounds.top - drawBounds.top,
            contentWidthPx = contentBounds.width.coerceAtLeast(1),
            contentHeightPx = contentBounds.height.coerceAtLeast(1),
        )
    }

    private companion object {
        private const val OFFSCREEN_OFFSET_PX: Int = 100_000
        private val layerLogger: java.util.logging.Logger =
            java.util.logging.Logger
                .getLogger("dev.nucleusframework.window.tao.popup")
    }
}
