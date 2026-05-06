package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.window.tao.render.TaoPopupHost
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Owns the sibling overlay NSView used by [NativeView]'s `content` slot,
 * its CAMetalLayer attachment, and the inner ComposeScene that renders
 * into it. Maintains a list of "interactive regions" populated by
 * `Modifier.consumeOverlayPointerEvents()`; the native overlay's
 * `hitTest:` consults this list to decide whether to intercept a click
 * (returns `self`) or let it fall through to the user's native subview
 * (returns `nil`).
 *
 * Threading: every method runs on the macOS main thread.
 */
@OptIn(InternalComposeUiApi::class)
internal class NativeViewOverlayController(
    private val host: TaoNativeViewHost,
    private val popupHost: TaoPopupHost,
) {
    private val rendererToken: Any = Any()
    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private val scale: Float = popupHost.scale

    private val popupWindowInfo: WindowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean = true
        override val containerSize: IntSize get() = IntSize(widthPx, heightPx)
    }

    /**
     * Named inner class so GraalVM JNI reachability metadata can
     * register the implementor.
     */
    private inner class OverlayCallback : NativeTaoMacOsNativeViewBridge.OverlayEventCallback {
        override fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int) {
            println("[NativeViewOverlay] onPointerEvent type=$type x=$x y=$y button=$button (scene=${scene != null})")
            val sc = scene ?: return
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
            sc.sendPointerEvent(
                eventType = eventType,
                position = Offset(x, y),
                type = PointerType.Mouse,
                button = pointerButton,
            )
        }

        override fun onScroll(x: Float, y: Float, dx: Float, dy: Float) {
            scene?.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(x, y),
                scrollDelta = Offset(dx, dy),
                type = PointerType.Mouse,
            )
        }

        override fun onResignFirstResponder() {
            // The overlay NSView lost first-responder status (user clicked
            // on the underlying native subview or on the host's main view
            // outside our interactive regions). Release Compose focus so a
            // previously focused `BasicTextField` visually deselects, and
            // reset the cursor — Compose only re-issues `setPointerIcon`
            // on the next hover transition, so without this the cursor
            // would stay stuck on whatever icon the field requested.
            scene?.focusManager?.releaseFocus()
            popupHost.setCursor(TaoCursorIcon.DEFAULT)
            popupHost.requestRedraw()
        }

        override fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int) {
            println("[NativeViewOverlay] onKeyEvent type=$type vk=$vkCode cp=$codePoint (scene=${scene != null})")
            val sc = scene ?: return
            val isShift = modifiers and 0x1 != 0
            val isCtrl = modifiers and 0x2 != 0
            val isAlt = modifiers and 0x4 != 0
            val isMeta = modifiers and 0x8 != 0
            sc.sendKeyEvent(
                KeyEvent(
                    key = Key(nativeKeyCode = vkCode, nativeKeyLocation = 0),
                    type = if (type == EVT_KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                    codePoint = codePoint,
                    isShiftPressed = isShift,
                    isCtrlPressed = isCtrl,
                    isAltPressed = isAlt,
                    isMetaPressed = isMeta,
                ),
            )
            // Compose desktop's `BasicTextField` only inserts a character
            // when it receives a synthetic `KEY_TYPED` AWT event piggy-
            // backed inside a Compose `KeyEvent` — that's the gate
            // `KeyEvent.isTypedEvent` looks for. Without this, KeyDown
            // alone moves focus / fires onKeyEvent but never types text.
            // Same trick `TaoComposeSceneHost.onKeyEvent` and
            // `TaoPopupRenderer.EventCallback.onKeyEvent` use.
            if (type == EVT_KEY_DOWN && codePoint.isPrintableTextInput(isCtrl, isMeta)) {
                val awtModifiers =
                    (if (isShift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
                        (if (isCtrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0) or
                        (if (isAlt) java.awt.event.InputEvent.ALT_DOWN_MASK else 0) or
                        (if (isMeta) java.awt.event.InputEvent.META_DOWN_MASK else 0)
                sc.sendKeyEvent(
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
                            awtModifiers,
                            java.awt.event.KeyEvent.VK_UNDEFINED,
                            codePoint.toChar(),
                            java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
                        ),
                    ),
                )
            }
        }
    }

    private var overlayNsView: Long = 0
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val regions: MutableMap<Any, IntArray> = LinkedHashMap()
    private var pendingContent: (@Composable () -> Unit)? = null
    private var firstBoundsApplied = false

    /**
     * Creates the overlay NSView and adds it as the topmost subview of
     * the host. **Must be called *after* the user's native subview
     * (e.g. WKWebView) has been attached** — otherwise AppKit's subview
     * order leaves the user's view on top of the overlay and the
     * watermark is hidden.
     */
    fun attach() {
        if (overlayNsView != 0L) return
        overlayNsView = NativeTaoMacOsNativeViewBridge.nativeCreateOverlay(popupHost.parentNsView)
        require(overlayNsView != 0L) { "Failed to create overlay NSView" }
        NativeTaoMacOsNativeViewBridge.nativeSetOverlayCallback(overlayNsView, OverlayCallback())
        popupHost.registerRenderer(rendererToken) { renderFrame() }
        // Tao intercepts key events at the application level (winit-
        // style NSEvent monitor), so they never reach our overlay's
        // `keyDown:` even though we're the first responder. Piggy-back
        // on the host's onKeyEvent path: when our overlay is the first
        // responder, consume the event ourselves and dispatch to the
        // overlay scene.
        popupHost.registerKeyHandler(rendererToken) { event ->
            val sc = scene
            val isFirst = NativeTaoMacOsNativeViewBridge.nativeIsFirstResponder(overlayNsView)
            println("[NativeViewOverlay] popupKeyHandler key=${event.key} type=${event.type} firstResponder=$isFirst sceneReady=${sc != null}")
            if (sc != null && isFirst) {
                sc.sendKeyEvent(event)
                true
            } else {
                false
            }
        }
        // If content / bounds were pushed before attach, replay them.
        pendingBounds?.let { setBoundsInternal(it[0], it[1], it[2], it[3]) }
        pendingBounds = null
    }

    private var pendingBounds: IntArray? = null

    fun setBounds(xPx: Int, yPx: Int, widthPxNew: Int, heightPxNew: Int) {
        if (overlayNsView == 0L) {
            // attach() hasn't run yet (race with first layout pass) —
            // stash the bounds so they get applied as soon as attach()
            // is called.
            pendingBounds = intArrayOf(xPx, yPx, widthPxNew, heightPxNew)
            return
        }
        setBoundsInternal(xPx, yPx, widthPxNew, heightPxNew)
    }

    private fun setBoundsInternal(xPx: Int, yPx: Int, widthPxNew: Int, heightPxNew: Int) {
        NativeTaoMacOsNativeViewBridge.nativeSetOverlayFrame(
            overlayNsView, xPx, yPx, widthPxNew, heightPxNew,
        )
        if (widthPxNew == widthPx && heightPxNew == heightPx && firstBoundsApplied) return
        widthPx = widthPxNew
        heightPx = heightPxNew

        if (!firstBoundsApplied) {
            firstBoundsApplied = true
            attachmentHandle = NativeMetalBridge.nativeAttachOverlay(overlayNsView)
            require(attachmentHandle != 0L) { "Failed to attach overlay CAMetalLayer" }
            directContext = DirectContext.makeMetal(
                NativeMetalBridge.nativeDevicePtr(attachmentHandle),
                NativeMetalBridge.nativeQueuePtr(attachmentHandle),
            )
            scene = CanvasLayersComposeScene(
                density = Density(scale),
                layoutDirection = LayoutDirection.Ltr,
                size = IntSize(widthPx, heightPx),
                coroutineContext = popupHost.sceneCoroutineContext,
                platformContext = object : PlatformContext.Empty() {
                    override val windowInfo: WindowInfo get() = popupWindowInfo
                    override fun setPointerIcon(pointerIcon: PointerIcon) {
                        popupHost.setCursor(mapPointerIconToTao(pointerIcon))
                    }
                },
                invalidate = { popupHost.requestRedraw() },
            )
            pendingContent?.let { scene?.setContent(it); pendingContent = null }
        } else {
            scene?.size = IntSize(widthPx, heightPx)
        }

        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        popupHost.requestRedraw()
    }

    fun setContent(content: @Composable () -> Unit) {
        val sc = scene
        if (sc != null) sc.setContent(content) else pendingContent = content
        popupHost.requestRedraw()
    }

    fun registerRegion(key: Any, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) {
        val rect = intArrayOf(xPx, yPx, widthPx, heightPx)
        val previous = regions[key]
        if (previous != null && previous.contentEquals(rect)) return
        regions[key] = rect
        println("[NativeViewOverlay] registerRegion ($xPx,$yPx,$widthPx,$heightPx) total=${regions.size}")
        flushRegions()
    }

    fun unregisterRegion(key: Any) {
        if (regions.remove(key) != null) flushRegions()
    }

    private fun flushRegions() {
        if (overlayNsView == 0L) return
        val count = regions.size
        if (count == 0) {
            NativeTaoMacOsNativeViewBridge.nativeSetOverlayRegions(overlayNsView, EmptyRegions, 0)
            return
        }
        val flat = FloatArray(count * 4)
        var i = 0
        for (r in regions.values) {
            flat[i] = r[0].toFloat()
            flat[i + 1] = r[1].toFloat()
            flat[i + 2] = r[2].toFloat()
            flat[i + 3] = r[3].toFloat()
            i += 4
        }
        NativeTaoMacOsNativeViewBridge.nativeSetOverlayRegions(overlayNsView, flat, count)
    }

    private fun renderFrame() {
        val ctx = directContext ?: return
        val sc = scene ?: return
        if (widthPx == 0 || heightPx == 0) return
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
                surface.canvas.clear(0x00000000)
                sc.render(surface.canvas.asComposeCanvas(), System.nanoTime())
                surface.flushAndSubmit(syncCpu = false)
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
                presented = true
            } finally {
                surface.close()
                rt.close()
            }
        } finally {
            if (!presented) NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
        }
    }

    fun dispose() {
        if (overlayNsView == 0L) return
        popupHost.unregisterRenderer(rendererToken)
        popupHost.unregisterKeyHandler(rendererToken)
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeMetalBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0
        }
        NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(overlayNsView)
        overlayNsView = 0
    }

    private companion object {
        private val EmptyRegions = FloatArray(0)
        private const val EVT_PTR_DOWN = 1
        private const val EVT_PTR_UP = 2
        private const val EVT_KEY_DOWN = 1

        // Dummy AWT Component used as `source` for the synthetic
        // `KEY_TYPED` events we feed Compose. `java.awt.event.KeyEvent`
        // requires a non-null Component; we never dispatch back to AWT.
        private val SyntheticEventSource: java.awt.Component = javax.swing.JPanel()

        /** Heuristic — same logic as TaoComposeSceneHost. */
        private fun Int.isPrintableTextInput(isCtrl: Boolean, isMeta: Boolean): Boolean {
            if (isCtrl || isMeta) return false
            // ASCII control range / unmapped; Cmd / Ctrl combos handled
            // separately by Compose's shortcut path.
            return this >= 0x20 && this != 0x7F
        }

        /**
         * Mirrors `TaoPlatformContext.mapPointerIcon` — kept private to
         * avoid leaking a `PointerIcon` → `Int` dependency through the
         * `TaoPopupHost` interface (which only sees the wire-format
         * integer code).
         */
        private fun mapPointerIconToTao(icon: PointerIcon): Int {
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
                    java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR ->
                        TaoCursorIcon.EW_RESIZE
                    java.awt.Cursor.N_RESIZE_CURSOR, java.awt.Cursor.S_RESIZE_CURSOR ->
                        TaoCursorIcon.NS_RESIZE
                    java.awt.Cursor.NE_RESIZE_CURSOR, java.awt.Cursor.SW_RESIZE_CURSOR ->
                        TaoCursorIcon.NESW_RESIZE
                    java.awt.Cursor.NW_RESIZE_CURSOR, java.awt.Cursor.SE_RESIZE_CURSOR ->
                        TaoCursorIcon.NWSE_RESIZE
                    else -> TaoCursorIcon.DEFAULT
                }
            }.getOrDefault(TaoCursorIcon.DEFAULT)
        }
    }
}
