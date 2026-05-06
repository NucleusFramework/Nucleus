package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
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
import io.github.kdroidfilter.nucleus.window.tao.NativeMetalBridge
import io.github.kdroidfilter.nucleus.window.tao.PopupNativeBridge
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Phase 3 — `ComposeSceneLayer` implementation that backs each Compose
 * `Popup` / `DropdownMenu` / `Tooltip` with its own borderless transparent
 * `NSPanel` (child of the host `NSWindow`). Plugged into the rendering
 * pipeline via [TaoComposeSceneContext]'s `createLayer`, which is what
 * `PlatformLayersComposeScene` consults whenever the popup framework
 * mounts a new layer.
 *
 * **Three failure modes from the post-mortem are explicitly avoided:**
 *  1. *Render-driving model*: the layer registers a per-frame callback
 *     with [TaoPopupHost], so the host's `onRedrawRequested` pump fires
 *     [renderFrame] every parent frame — the inner scene is never starved.
 *  2. *Measurement chicken-and-egg*: the inner [CanvasLayersComposeScene]
 *     is constructed with `size = host.parentWindowSize` (non-zero from
 *     the start) so Compose's `RootMeasurePolicy` measures the popup
 *     content with real constraints. Without this, content size collapses
 *     to 0 and `boundsInWindow` never gets a non-trivial value.
 *  3. *CompositionLocal propagation*: [setContent] wraps user content
 *     with `CompositionLocalProvider(_compositionLocalContext) { ... }`.
 *     Compose's popup framework sets `compositionLocalContext` *before*
 *     [setContent] (`Popup.skiko.kt: rememberComposeSceneLayer`), so by
 *     the time content composes the locals snapshot is valid. This is
 *     what makes `MaterialTheme.colorScheme` etc. flow into the popup
 *     content automatically.
 *
 * Phase 3 deliberately omits:
 *  - `setOutsidePointerEventListener` — outside-click dismissal lands
 *    in Phase 4 (NSEvent local monitor on the parent window).
 *  - `setKeyEventListener` — key forwarding lands in Phase 4 too.
 *  - `scrimColor` — would need a third surface (full-window-sized
 *    overlay between main scene and popup). Not relevant for context
 *    menus / dropdowns.
 *
 * Threading: every method must run on the macOS main thread.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoPopupSceneLayer(
    private val host: TaoPopupHost,
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
    private var widthPx: Int = host.parentWindowSize.width.coerceAtLeast(1)
    private var heightPx: Int = host.parentWindowSize.height.coerceAtLeast(1)
    private val scale: Float = host.scale

    /**
     * Panel created at parent-window-size offscreen so the inner scene
     * has real layout constraints, while the user doesn't see a 1×1
     * artifact. Compose's `Popup` framework will write [boundsInWindow]
     * during the first measure pass, which repositions / resizes us.
     */
    private val panelHandle: Long = PopupNativeBridge.nativeCreatePanel(
        parentNsView = host.parentNsView,
        // Offscreen until first `boundsInWindow` setter call. The negative
        // top-left ensures the panel is allocated but invisible (NSPanel
        // honors offscreen frames; it just doesn't render outside the
        // visible screen rect).
        xPx = -OFFSCREEN_OFFSET_PX,
        yPx = -OFFSCREEN_OFFSET_PX,
        widthPx = widthPx,
        heightPx = heightPx,
    ).also {
        require(it != 0L) { "Failed to allocate popup NSPanel" }
    }

    private val attachmentHandle: Long = NativeMetalBridge.nativeAttachOverlay(
        PopupNativeBridge.nativeContentNsView(panelHandle),
    ).also {
        require(it != 0L) { "Failed to attach popup CAMetalLayer" }
    }

    private val directContext: DirectContext = DirectContext.makeMetal(
        NativeMetalBridge.nativeDevicePtr(attachmentHandle),
        NativeMetalBridge.nativeQueuePtr(attachmentHandle),
    )

    /**
     * Inner scene at parent window size — see "measurement chicken-and-
     * egg" in the class doc. The CAMetalLayer is sized to the popup's
     * actual bounds (smaller); render writes scene content (positioned
     * at 0,0 by `Popup.skiko.kt`'s `RootMeasurePolicy`) into the smaller
     * surface — content fits because the popup framework lays out at
     * `IntSize(widthPx, heightPx)` matching `boundsInWindow.size`.
     */
    /**
     * Custom WindowInfo with `isWindowFocused = true`. Compose's
     * `BasicTextField` (and other focus-aware widgets) gate the visible
     * caret + keystroke handling on this flag — `PlatformContext.Empty`'s
     * default (false) leaves text fields uneditable. We always say "yes,
     * focused" because the popup, by virtue of being on screen via Compose
     * intent, is logically focused from the app's perspective.
     */
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

    /**
     * Named inner class so GraalVM JNI reachability metadata can register
     * it explicitly. Anonymous-object subclasses of a JNI-accessed
     * interface aren't picked up by `GetMethodID` under native-image.
     */
    private inner class PopupEventCallback : PopupNativeBridge.EventCallback {
        override fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int) {
            val pointerButton = when (button) {
                1 -> PointerButton.Primary
                2 -> PointerButton.Secondary
                else -> null
            }
            val eventType = when (type) {
                EVT_PTR_DOWN -> PointerEventType.Press
                EVT_PTR_UP -> PointerEventType.Release
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
            val isShift = modifiers and MOD_SHIFT != 0
            val isCtrl = modifiers and MOD_CTRL != 0
            val isAlt = modifiers and MOD_ALT != 0
            val isMeta = modifiers and MOD_META != 0
            val ev = KeyEvent(
                key = Key(nativeKeyCode = vkCode, nativeKeyLocation = 0),
                type = if (type == EVT_KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                codePoint = codePoint,
                isShiftPressed = isShift,
                isCtrlPressed = isCtrl,
                isAltPressed = isAlt,
                isMetaPressed = isMeta,
            )
            if (onPreviewKeyEvent?.invoke(ev) == true) return
            val consumed = innerScene.sendKeyEvent(ev)
            if (type == EVT_KEY_DOWN && codePoint.isPrintableTextInput(isCtrl, isMeta)) {
                innerScene.sendKeyEvent(
                    KeyEvent(
                        key = Key(nativeKeyCode = 0, nativeKeyLocation = 0),
                        type = KeyEventType.Unknown,
                        codePoint = codePoint,
                        isShiftPressed = isShift,
                        isCtrlPressed = isCtrl,
                        isAltPressed = isAlt,
                        isMetaPressed = isMeta,
                        nativeEvent = java.awt.event.KeyEvent(
                            SyntheticEventSource,
                            java.awt.event.KeyEvent.KEY_TYPED,
                            System.currentTimeMillis(),
                            awtModifiers(isShift, isCtrl, isAlt, isMeta),
                            java.awt.event.KeyEvent.VK_UNDEFINED,
                            codePoint.toChar(),
                            java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
                        ),
                    ),
                )
            }
            if (consumed) return
            onKeyEvent?.invoke(ev)
        }
    }

    private inner class PopupOutsideListener : PopupNativeBridge.OutsideClickListener {
        override fun onOutsideClick(type: Int, button: Int) {
            val pointerButton = when (button) {
                1 -> PointerButton.Primary
                2 -> PointerButton.Secondary
                else -> PointerButton.Tertiary
            }
            onOutsidePointerEvent?.invoke(PointerEventType.Press, pointerButton)
        }
    }

    init {
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        PopupNativeBridge.nativeSetEventCallback(panelHandle, PopupEventCallback())
        host.registerRenderer(rendererToken) { renderFrame() }
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
            // Reposition the NSPanel — `value` is parent-window pixels
            // with a top-left origin (matching what Compose's popup
            // RootMeasurePolicy writes here).
            PopupNativeBridge.nativeSetFrameInWindow(
                panel = panelHandle,
                xPx = value.left,
                yPx = value.top,
                widthPx = value.width.coerceAtLeast(1),
                heightPx = value.height.coerceAtLeast(1),
            )
            // Resize the CAMetalLayer's drawable to match the popup's
            // actual size. We DON'T resize the inner scene — its size
            // stays at parent window size so layout has real constraints.
            // Only the visible draw area is constrained to `boundsInWindow`.
            val w = value.width.coerceAtLeast(1)
            val h = value.height.coerceAtLeast(1)
            if (w != widthPx || h != heightPx) {
                widthPx = w
                heightPx = h
                NativeMetalBridge.nativeResize(attachmentHandle, w, h, scale)
            }
            host.requestRedraw()
        }

    override var compositionLocalContext: CompositionLocalContext?
        get() = _compositionLocalContext
        set(value) { _compositionLocalContext = value }

    override var scrimColor: Color?
        get() = _scrimColor
        set(value) { _scrimColor = value /* TODO Phase 4: third surface */ }

    override var focusable: Boolean
        get() = _focusable
        set(value) {
            _focusable = value
            PopupNativeBridge.nativeSetFocusable(panelHandle, value)
        }

    init {
        // Apply the initial focusable state (constructor sets the field
        // but the setter is not invoked from a constructor parameter).
        PopupNativeBridge.nativeSetFocusable(panelHandle, _focusable)
    }

    override fun close() {
        host.unregisterRenderer(rendererToken)
        // Drop callbacks before tearing the panel down so any in-flight
        // AppKit event doesn't deref a half-disposed scene.
        PopupNativeBridge.nativeUninstallOutsideClickMonitor(panelHandle)
        PopupNativeBridge.nativeSetEventCallback(panelHandle, null)
        innerScene.close()
        directContext.close()
        NativeMetalBridge.nativeDetach(attachmentHandle)
        PopupNativeBridge.nativeRelease(panelHandle)
    }

    override fun setContent(content: @Composable () -> Unit) {
        innerScene.setContent {
            // Replay parent locals snapshot so MaterialTheme et al. flow
            // into the popup content. Compose's popup framework writes
            // `compositionLocalContext` *before* this `setContent`, so
            // by the time we compose, `_compositionLocalContext` is the
            // freshest.
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
            PopupNativeBridge.nativeInstallOutsideClickMonitor(panelHandle, PopupOutsideListener())
        } else {
            PopupNativeBridge.nativeUninstallOutsideClickMonitor(panelHandle)
        }
    }

    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset {
        // boundsInWindow is in parent-window pixels with a top-left origin;
        // popup-local = position - bounds.topLeft.
        return IntOffset(
            positionInWindow.x - _bounds.left,
            positionInWindow.y - _bounds.top,
        )
    }

    // ── Per-frame render — driven by host.onRedrawRequested ────────────

    private fun renderFrame() {
        if (widthPx <= 0 || heightPx <= 0) return
        val frame = NativeMetalBridge.nativeBeginFrame(attachmentHandle) ?: return
        var presented = false
        try {
            val rt = BackendRenderTarget.makeMetal(frame.widthPx, frame.heightPx, frame.texturePtr)
            val surface = Surface.makeFromBackendRenderTarget(
                context = directContext,
                rt = rt,
                origin = SurfaceOrigin.TOP_LEFT,
                colorFormat = SurfaceColorFormat.BGRA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: return
            try {
                surface.canvas.clear(0x00000000)
                innerScene.render(surface.canvas.asComposeCanvas(), System.nanoTime())
                surface.flushAndSubmit(syncCpu = false)
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
                presented = true
            } finally {
                surface.close()
                rt.close()
            }
        } finally {
            if (!presented) {
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
            }
        }
    }

    private companion object {
        // Far enough offscreen to be invisible on any reasonable monitor
        // setup, but still inside the integer range we hand to the bridge.
        private const val OFFSCREEN_OFFSET_PX: Int = 100_000
        // Wire constants — must match the C #defines in popup_panel.m.
        private const val EVT_PTR_DOWN = 1
        private const val EVT_PTR_UP = 2
        private const val EVT_KEY_DOWN = 1

        private const val MOD_SHIFT = 0x1
        private const val MOD_CTRL = 0x2
        private const val MOD_ALT = 0x4
        private const val MOD_META = 0x8

        private val SyntheticEventSource: java.awt.Component = javax.swing.JPanel()

        private fun Int.isPrintableTextInput(isCtrl: Boolean, isMeta: Boolean): Boolean =
            this >= 0x20 && this != 0x7F && !isCtrl && !isMeta

        private fun awtModifiers(isShift: Boolean, isCtrl: Boolean, isAlt: Boolean, isMeta: Boolean): Int =
            (if (isShift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
                (if (isCtrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0) or
                (if (isAlt) java.awt.event.InputEvent.ALT_DOWN_MASK else 0) or
                (if (isMeta) java.awt.event.InputEvent.META_DOWN_MASK else 0)
    }
}
