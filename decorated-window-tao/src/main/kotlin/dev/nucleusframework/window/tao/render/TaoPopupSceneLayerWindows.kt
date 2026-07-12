package dev.nucleusframework.window.tao.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.PopupNativeBridgeWindows
import org.jetbrains.skia.DirectContext

/**
 * Windows popup layer backed by a transparent owned WS_POPUP HWND.
 *
 * The coordinate model mirrors Compose Desktop's AWT WindowComposeSceneLayer:
 * boundsInWindow is the logical content rect, and rendering happens in
 * parent-window coordinates. The native popup surface is kept exactly
 * at content bounds because transparent pixels around the content are not
 * reliably alpha-composited by DWM on all Windows drivers.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoPopupSceneLayerWindows(
    private val host: TaoPopupHostWindows,
    initialDensity: Density,
    initialLayoutDirection: LayoutDirection,
    initialFocusable: Boolean,
    @Suppress("UNUSED_PARAMETER") parentCompositionContext: CompositionContext,
) : ComposeSceneLayer {
    private var _density = initialDensity
    private var _layoutDirection = initialLayoutDirection
    private var _focusable = initialFocusable
    private var _bounds: IntRect = IntRect.Zero
    private var _scrimColor: Color? = null
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
    private var drawBounds: IntRect = IntRect(0, 0, 1, 1)
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
        val handle =
            PopupNativeBridgeWindows
                .nativeCreatePanel(
                    parentHwnd = host.parentHwnd,
                    xPx = -OFFSCREEN_OFFSET_PX,
                    yPx = -OFFSCREEN_OFFSET_PX,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
        if (handle == 0L) {
            panelCreateFailed = true
            System.err.println(
                "Nucleus: nativeCreatePanel returned 0 (parentHwnd=${host.parentHwnd}); popup layer disabled",
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
            override val containerSize: IntSize get() = sceneLayoutSize
        }

    private val innerScene: ComposeScene =
        CanvasLayersComposeScene(
            density = _density,
            layoutDirection = _layoutDirection,
            size = sceneLayoutSize,
            coroutineContext = host.sceneCoroutineContext,
            platformContext =
                object : PlatformContext.Empty() {
                    override val windowInfo: androidx.compose.ui.platform.WindowInfo
                        get() = popupWindowInfo
                },
            invalidate = { host.requestRedraw() },
        )

    private var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onOutsidePointerEvent: ((PointerEventType, PointerButton?) -> Unit)? = null

    private inner class PopupEventCallback : PopupNativeBridgeWindows.EventCallback {
        override fun onPointerEvent(
            type: Int,
            x: Float,
            y: Float,
            button: Int,
            modifiers: Int,
        ) {
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
        ) {
            innerScene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = scenePosition(x, y),
                scrollDelta = Offset(dx, dy),
                type = PointerType.Mouse,
            )
        }

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) {
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
            val pointerButton =
                when (button) {
                    TaoNativeWireFormat.BUTTON_PRIMARY -> PointerButton.Primary
                    TaoNativeWireFormat.BUTTON_SECONDARY -> PointerButton.Secondary
                    else -> PointerButton.Tertiary
                }
            onOutsidePointerEvent?.invoke(PointerEventType.Press, pointerButton)
        }
    }

    init {
        // The native panel is created lazily in renderFrame (see ensurePanel),
        // not here — the constructor runs inside sc.render() (mid GL-frame).
        // Register the per-frame renderer + owner-move listener now; both
        // defer / no-op until the panel exists.
        host.registerRenderer(rendererToken) { renderFrame() }
        host.registerOwnerMoveListener(moveListenerToken) {
            if (panelHandle != 0L && _bounds != IntRect.Zero) {
                updateNativeFrame()
            }
        }
    }

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
            updateDrawBoundsFromBounds()
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
            if (panelHandle != 0L) PopupNativeBridgeWindows.nativeSetFocusable(panelHandle, value)
        }

    override fun close() {
        released = true
        host.notifyPopupClosing()
        host.unregisterRenderer(rendererToken)
        host.unregisterOwnerMoveListener(moveListenerToken)
        PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panelHandle)
        PopupNativeBridgeWindows.nativeSetEventCallback(panelHandle, null)
        innerScene.close()
        PopupNativeBridgeWindows.nativeRelease(panelHandle)
    }

    override fun setContent(content: @Composable () -> Unit) {
        innerScene.setContent {
            val locals = _compositionLocalContext
            if (locals != null) {
                CompositionLocalProvider(locals) { content() }
            } else {
                content()
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
        if (!PopupNativeBridgeWindows.nativeMakeCurrent(panelHandle)) return
        directContext.resetGLAll()

        val frame = drawBounds
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = directContext,
            clearColorArgb = 0x00000000,
            present = { PopupNativeBridgeWindows.nativeSwapBuffers(panelHandle) },
        ) { canvas, nanoTime ->
            canvas.save()
            try {
                canvas.translate(-frame.left.toFloat(), -frame.top.toFloat())
                innerScene.render(canvas.asComposeCanvas(), nanoTime)
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
        if (_bounds == IntRect.Zero) return false
        val nextDrawBounds =
            IntRect(
                left = _bounds.left,
                top = _bounds.top,
                right = _bounds.right,
                bottom = _bounds.bottom,
            )
        val changed = nextDrawBounds != drawBounds
        drawBounds = nextDrawBounds
        widthPx = drawBounds.width.coerceAtLeast(1)
        heightPx = drawBounds.height.coerceAtLeast(1)
        updateNativeFrame()
        return changed
    }

    private fun updateNativeFrame() {
        if (panelHandle == 0L) return
        if (drawBounds == IntRect.Zero || _bounds == IntRect.Zero) return
        val offset = host.coordinateOffset
        PopupNativeBridgeWindows.nativeSetFrameInWindow(
            panel = panelHandle,
            xPx = drawBounds.left + offset.x,
            yPx = drawBounds.top + offset.y,
            widthPx = drawBounds.width.coerceAtLeast(1),
            heightPx = drawBounds.height.coerceAtLeast(1),
            contentXPx = _bounds.left - drawBounds.left,
            contentYPx = _bounds.top - drawBounds.top,
            contentWidthPx = _bounds.width.coerceAtLeast(1),
            contentHeightPx = _bounds.height.coerceAtLeast(1),
        )
    }

    private companion object {
        private const val OFFSCREEN_OFFSET_PX: Int = 100_000
    }
}
