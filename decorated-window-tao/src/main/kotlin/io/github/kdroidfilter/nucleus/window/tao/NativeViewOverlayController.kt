package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneContext
import io.github.kdroidfilter.nucleus.window.tao.render.TaoNativeWireFormat
import io.github.kdroidfilter.nucleus.window.tao.render.TaoPopupHost
import io.github.kdroidfilter.nucleus.window.tao.render.dispatchSyntheticKeyTyped
import io.github.kdroidfilter.nucleus.window.tao.render.renderMetalFrame
import kotlin.coroutines.CoroutineContext
import org.jetbrains.skia.DirectContext

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
    private var overlayOffsetX: Int = 0
    private var overlayOffsetY: Int = 0
    private val scale: Float = popupHost.scale

    /**
     * `containerSize` is set so popups can extend past the overlay's
     * own rect (e.g. a context-menu popping up above its anchor when
     * the textfield sits near the overlay's top edge), but still stay
     * inside the host window. Reporting just `parentWindowSize` lets the
     * popup framework's clamping pick coords that, once shifted by
     * `overlayOffset` for the actual NSPanel placement, land outside the
     * window. Subtracting the offset from each axis (clamped at 1) is
     * the size still available below/right of the overlay's origin.
     */
    private val overlayWindowInfo: WindowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean = true
        override val containerSize: IntSize
            get() {
                val parent = popupHost.parentWindowSize
                return IntSize(
                    (parent.width - overlayOffsetX).coerceAtLeast(1),
                    (parent.height - overlayOffsetY).coerceAtLeast(1),
                )
            }
    }

    /**
     * `TaoPopupHost` adapter handed to popups originating in the overlay's
     * scene. Forwards every plumbing call to the host while contributing
     * the overlay's live position as `coordinateOffset`. `TaoPopupSceneLayer`
     * adds it to `boundsInWindow` before talking to AppKit so a popup
     * anchored at e.g. `(50, 0)` in overlay-local coords lands at the
     * correct place in the host NSWindow.
     */
    private val overlayPopupHost: TaoPopupHost = object : TaoPopupHost {
        override val parentNsView: Long get() = popupHost.parentNsView
        override val scale: Float get() = popupHost.scale
        override val parentWindowSize: IntSize get() = popupHost.parentWindowSize
        override val sceneCoroutineContext: CoroutineContext get() = popupHost.sceneCoroutineContext
        override val coordinateOffset: IntOffset
            get() = IntOffset(overlayOffsetX, overlayOffsetY)
        override fun requestRedraw() = popupHost.requestRedraw()
        override fun registerRenderer(token: Any, render: () -> Unit) =
            popupHost.registerRenderer(token, render)
        override fun unregisterRenderer(token: Any) = popupHost.unregisterRenderer(token)
        override fun registerKeyHandler(token: Any, handler: (KeyEvent) -> Boolean) =
            popupHost.registerKeyHandler(token, handler)
        override fun unregisterKeyHandler(token: Any) = popupHost.unregisterKeyHandler(token)
        override fun setCursor(iconCode: Int) = popupHost.setCursor(iconCode)
    }

    /**
     * Named inner class so GraalVM JNI reachability metadata can
     * register the implementor.
     */
    private inner class OverlayCallback : NativeTaoMacOsNativeViewBridge.OverlayEventCallback {
        override fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int) {
            val sc = scene ?: return
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
            val sc = scene ?: return
            val isShift = modifiers and TaoNativeWireFormat.MOD_SHIFT != 0
            val isCtrl = modifiers and TaoNativeWireFormat.MOD_CTRL != 0
            val isAlt = modifiers and TaoNativeWireFormat.MOD_ALT != 0
            val isMeta = modifiers and TaoNativeWireFormat.MOD_META != 0
            sc.sendKeyEvent(
                KeyEvent(
                    key = Key(nativeKeyCode = vkCode, nativeKeyLocation = 0),
                    type = if (type == TaoNativeWireFormat.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                    codePoint = codePoint,
                    isShiftPressed = isShift,
                    isCtrlPressed = isCtrl,
                    isAltPressed = isAlt,
                    isMetaPressed = isMeta,
                ),
            )
            if (type == TaoNativeWireFormat.KEY_DOWN) {
                sc.dispatchSyntheticKeyTyped(codePoint, isShift, isCtrl, isAlt, isMeta)
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
            if (sc != null && NativeTaoMacOsNativeViewBridge.nativeIsFirstResponder(overlayNsView)) {
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

    private var lastFrameX: Int = Int.MIN_VALUE
    private var lastFrameY: Int = Int.MIN_VALUE

    private fun setBoundsInternal(xPx: Int, yPx: Int, widthPxNew: Int, heightPxNew: Int) {
        val frameUnchanged = firstBoundsApplied &&
            xPx == lastFrameX && yPx == lastFrameY &&
            widthPxNew == widthPx && heightPxNew == heightPx
        if (frameUnchanged) return
        val capturedHandle = overlayNsView
        if (firstBoundsApplied) {
            // Steady state: route the AppKit setFrame through the host's
            // interop transaction so the overlay reposition commits in
            // the same CATransaction as the user's NSView frame change.
            // Without this, a parent animation moving both at once would
            // visually de-sync by one frame (overlay catches up next paint).
            host.scheduleInterop {
                NativeTaoMacOsNativeViewBridge.nativeSetOverlayFrame(
                    capturedHandle, xPx, yPx, widthPxNew, heightPxNew,
                )
            }
        } else {
            // First call: apply eagerly. `nativeAttachOverlay` (just
            // below) reads `view.bounds` to seed `layer.frame`; if we
            // defer the setFrame, the Metal layer ends up sized to the
            // parent's full bounds and the overlay renders at the wrong
            // location until the next frame catches up — which on
            // layer-hosted NSViews is never (CALayer.frame doesn't auto-
            // follow NSView.frame post layer assignment).
            NativeTaoMacOsNativeViewBridge.nativeSetOverlayFrame(
                capturedHandle, xPx, yPx, widthPxNew, heightPxNew,
            )
        }
        lastFrameX = xPx
        lastFrameY = yPx
        // Tracked live so `overlayPopupHost.coordinateOffset` returns the
        // right value when popups reposition mid-flight.
        overlayOffsetX = xPx
        overlayOffsetY = yPx
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
            val ourPlatformContext = object : PlatformContext.Empty() {
                override val windowInfo: WindowInfo get() = overlayWindowInfo
                override fun setPointerIcon(pointerIcon: PointerIcon) {
                    popupHost.setCursor(pointerIcon.toTaoCursorIconCode())
                }
            }
            // PlatformLayersComposeScene + TaoComposeSceneContext route any
            // popup mounted from inside the overlay (text-field context
            // menus, dropdowns, tooltips) through `TaoPopupSceneLayer` —
            // i.e. into a real NSPanel parented to the host NSWindow.
            // That's how those popups can extend beyond the overlay's
            // bounds, intercept clicks themselves (instead of falling
            // through to the user's native subview), and dismiss on
            // outside-click via the panel's NSEvent local monitor.
            scene = PlatformLayersComposeScene(
                density = Density(scale),
                layoutDirection = LayoutDirection.Ltr,
                size = IntSize(widthPx, heightPx),
                coroutineContext = popupHost.sceneCoroutineContext,
                composeSceneContext = TaoComposeSceneContext(
                    platformContext = ourPlatformContext,
                    popupHost = overlayPopupHost,
                ),
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
        val previous = regions[key]
        if (previous != null &&
            previous[0] == xPx && previous[1] == yPx &&
            previous[2] == widthPx && previous[3] == heightPx
        ) return
        regions[key] = intArrayOf(xPx, yPx, widthPx, heightPx)
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
        if (attachmentHandle == 0L) return
        renderMetalFrame(
            attachmentHandle = attachmentHandle,
            directContext = ctx,
            scene = sc,
            clearColor = 0x00000000,
        )
    }

    fun dispose() {
        if (overlayNsView == 0L) return
        popupHost.unregisterRenderer(rendererToken)
        popupHost.unregisterKeyHandler(rendererToken)
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        // Zero out before freeing so a render lambda still in the host's
        // snapshot iteration bails — same hazard as TaoPopupSceneLayer.close.
        val handle = attachmentHandle
        attachmentHandle = 0
        if (handle != 0L) NativeMetalBridge.nativeDetach(handle)
        NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(overlayNsView)
        overlayNsView = 0
    }

    private companion object {
        private val EmptyRegions = FloatArray(0)
    }
}
