package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
import io.github.kdroidfilter.nucleus.window.tao.PopupNativeBridgeWindows
import org.jetbrains.skia.DirectContext

/**
 * Windows port of [TaoPopupSceneLayer]. Each Compose `Popup` /
 * `DropdownMenu` / `Tooltip` / context menu mounted from the host or
 * overlay scene becomes its own borderless WS_POPUP HWND owned by the
 * Tao main HWND, with a transparent WGL context joined to the host's
 * share group via `wglCreateContextAttribsARB(.., hostHGLRC, ..)`.
 *
 * Mirrors the macOS layer 1:1 — same chicken-and-egg defenses for
 * measurement (inner scene at parent window size so layout has real
 * constraints), same per-frame render driver via [TaoPopupHostWindows],
 * same composition-local context propagation through [setContent],
 * same outside-click dismissal contract via the popup bridge's
 * SetCapture-based monitor.
 *
 * Threading: every method must run on the host HWND's UI thread.
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
    private var widthPx: Int = host.parentWindowSize.width.coerceAtLeast(1)
    private var heightPx: Int = host.parentWindowSize.height.coerceAtLeast(1)
    private val scale: Float = host.scale

    private val panelHandle: Long = PopupNativeBridgeWindows.nativeCreatePanel(
        parentHwnd = host.parentHwnd,
        xPx = -OFFSCREEN_OFFSET_PX,
        yPx = -OFFSCREEN_OFFSET_PX,
        widthPx = widthPx,
        heightPx = heightPx,
    ).also {
        require(it != 0L) { "Failed to allocate popup HWND" }
    }

    /** Single-HGLRC architecture: shared with the host + every other popup. */
    private val directContext: DirectContext = host.hostDirectContext

    private val popupWindowInfo: androidx.compose.ui.platform.WindowInfo =
        object : androidx.compose.ui.platform.WindowInfo {
            override val isWindowFocused: Boolean = true
            override val containerSize: IntSize get() = IntSize(widthPx, heightPx)
        }

    private val innerScene: ComposeScene = CanvasLayersComposeScene(
        density = _density,
        layoutDirection = _layoutDirection,
        size = IntSize(widthPx, heightPx),
        coroutineContext = host.sceneCoroutineContext,
        platformContext = object : PlatformContext.Empty() {
            override val windowInfo: androidx.compose.ui.platform.WindowInfo
                get() = popupWindowInfo
        },
        invalidate = { host.requestRedraw() },
    )

    private var onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onKeyEvent: ((KeyEvent) -> Boolean)? = null
    private var onOutsidePointerEvent: ((PointerEventType, PointerButton?) -> Unit)? = null

    private inner class PopupEventCallback : PopupNativeBridgeWindows.EventCallback {
        override fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int) {
            val pointerButton = when (button) {
                TaoNativeWireFormat.BUTTON_PRIMARY -> PointerButton.Primary
                TaoNativeWireFormat.BUTTON_SECONDARY -> PointerButton.Secondary
                else -> null
            }
            val eventType = when (type) {
                TaoNativeWireFormat.PTR_DOWN -> PointerEventType.Press
                TaoNativeWireFormat.PTR_UP -> PointerEventType.Release
                else -> PointerEventType.Move
            }
            innerScene.sendPointerEvent(
                eventType = eventType,
                position = Offset(x, y),
                type = PointerType.Mouse,
                button = pointerButton,
            )
        }

        override fun onScroll(x: Float, y: Float, dx: Float, dy: Float) {
            innerScene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(x, y),
                scrollDelta = Offset(dx, dy),
                type = PointerType.Mouse,
            )
        }

        override fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int) {
            val isShift = modifiers and TaoNativeWireFormat.MOD_SHIFT != 0
            val isCtrl = modifiers and TaoNativeWireFormat.MOD_CTRL != 0
            val isAlt = modifiers and TaoNativeWireFormat.MOD_ALT != 0
            val isMeta = modifiers and TaoNativeWireFormat.MOD_META != 0
            val ev = KeyEvent(
                key = Key(nativeKeyCode = vkCode, nativeKeyLocation = 0),
                type = if (type == TaoNativeWireFormat.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                codePoint = codePoint,
                isShiftPressed = isShift,
                isCtrlPressed = isCtrl,
                isAltPressed = isAlt,
                isMetaPressed = isMeta,
            )
            if (onPreviewKeyEvent?.invoke(ev) == true) return
            val consumed = innerScene.sendKeyEvent(ev)
            if (consumed) return
            onKeyEvent?.invoke(ev)
        }
    }

    private inner class PopupOutsideListener : PopupNativeBridgeWindows.OutsideClickListener {
        override fun onOutsideClick(type: Int, button: Int) {
            val pointerButton = when (button) {
                TaoNativeWireFormat.BUTTON_PRIMARY -> PointerButton.Primary
                TaoNativeWireFormat.BUTTON_SECONDARY -> PointerButton.Secondary
                else -> PointerButton.Tertiary
            }
            onOutsidePointerEvent?.invoke(PointerEventType.Press, pointerButton)
        }
    }

    init {
        // Single-HGLRC: directContext is the host's, no makeGL needed
        // here. The popup's HDC will share the host's HGLRC via
        // wglMakeCurrent on each renderFrame.
        PopupNativeBridgeWindows.nativeSetEventCallback(panelHandle, PopupEventCallback())
        PopupNativeBridgeWindows.nativeSetFocusable(panelHandle, _focusable)
        host.registerRenderer(rendererToken) { renderFrame() }
        // Re-issue nativeSetFrameInWindow whenever the host moves on
        // screen — the popup is top-level and its screen coords don't
        // auto-track the owner.
        host.registerOwnerMoveListener(moveListenerToken) {
            if (panelHandle != 0L && _bounds != IntRect.Zero) {
                val offset = host.coordinateOffset
                PopupNativeBridgeWindows.nativeSetFrameInWindow(
                    panel = panelHandle,
                    xPx = _bounds.left + offset.x,
                    yPx = _bounds.top + offset.y,
                    widthPx = _bounds.width.coerceAtLeast(1),
                    heightPx = _bounds.height.coerceAtLeast(1),
                )
            }
        }
    }

    // ── ComposeSceneLayer surface ──────────────────────────────────────

    override var density: Density
        get() = _density
        set(value) { _density = value; innerScene.density = value }

    override var layoutDirection: LayoutDirection
        get() = _layoutDirection
        set(value) { _layoutDirection = value; innerScene.layoutDirection = value }

    override var boundsInWindow: IntRect
        get() = _bounds
        set(value) {
            _bounds = value
            val offset = host.coordinateOffset
            PopupNativeBridgeWindows.nativeSetFrameInWindow(
                panel = panelHandle,
                xPx = value.left + offset.x,
                yPx = value.top + offset.y,
                widthPx = value.width.coerceAtLeast(1),
                heightPx = value.height.coerceAtLeast(1),
            )
            val w = value.width.coerceAtLeast(1)
            val h = value.height.coerceAtLeast(1)
            if (w != widthPx || h != heightPx) {
                widthPx = w
                heightPx = h
            }
            host.requestRedraw()
        }

    override var compositionLocalContext: CompositionLocalContext?
        get() = _compositionLocalContext
        set(value) { _compositionLocalContext = value }

    override var scrimColor: Color?
        get() = _scrimColor
        set(value) { _scrimColor = value /* TODO: full-window scrim surface */ }

    override var focusable: Boolean
        get() = _focusable
        set(value) {
            _focusable = value
            PopupNativeBridgeWindows.nativeSetFocusable(panelHandle, value)
        }

    override fun close() {
        // Notify parent scenes (overlay) so they can flush focus state
        // BEFORE the popup HWND is destroyed and Compose's PlatformLayer
        // chain unwinds — at that later point the BasicTextField that
        // captured focus on right-click is in a stuck state that
        // `clearFocus(force=true)` from a focus-lost event can no longer
        // release. Calling clearFocus here, while the popup is still
        // alive, releases the capture cleanly.
        host.notifyPopupClosing()
        host.unregisterRenderer(rendererToken)
        host.unregisterOwnerMoveListener(moveListenerToken)
        PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panelHandle)
        PopupNativeBridgeWindows.nativeSetEventCallback(panelHandle, null)
        innerScene.close()
        // directContext is the host's — don't close.
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
        if (onOutsidePointerEvent != null) {
            PopupNativeBridgeWindows.nativeInstallOutsideClickMonitor(panelHandle, PopupOutsideListener())
        } else {
            PopupNativeBridgeWindows.nativeUninstallOutsideClickMonitor(panelHandle)
        }
    }

    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset {
        return IntOffset(
            positionInWindow.x - _bounds.left,
            positionInWindow.y - _bounds.top,
        )
    }

    private fun renderFrame() {
        if (widthPx <= 0 || heightPx <= 0) return
        if (!PopupNativeBridgeWindows.nativeMakeCurrent(panelHandle)) return
        // External WGL context switch — re-sync Skia's GL state cache.
        directContext.resetGLAll()
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = directContext,
            scene = innerScene,
            clearColorArgb = 0x00000000,
            present = { PopupNativeBridgeWindows.nativeSwapBuffers(panelHandle) },
        )
    }

    private companion object {
        private const val OFFSCREEN_OFFSET_PX: Int = 100_000
    }
}
