@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.deco

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.TaoNativeViewHost
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.popup.TaoPopupHost
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayer
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.MetalTextureHostCache
import dev.nucleusframework.window.tao.scene.TaoComposeSceneContext
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoRecordedSurface
import dev.nucleusframework.window.tao.scene.recordSceneToPicture
import org.jetbrains.skia.DirectContext
import kotlin.coroutines.CoroutineContext

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
    private val overlayWindowInfo: WindowInfo =
        object : WindowInfo {
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
    private val overlayPopupHost: TaoPopupHost =
        object : TaoPopupHost {
            override val parentNsView: Long get() = popupHost.parentNsView
            override val scale: Float get() = popupHost.scale
            override val parentWindowSize: IntSize get() = popupHost.parentWindowSize
            override val workAreaSize: IntSize get() = popupHost.workAreaSize
            override val sceneCoroutineContext: CoroutineContext get() = popupHost.sceneCoroutineContext
            override val coordinateOffset: IntOffset
                get() = IntOffset(overlayOffsetX, overlayOffsetY)

            override fun requestRedraw() = popupHost.requestRedraw()

            override fun registerRenderer(
                token: Any,
                record: () -> TaoRecordedSurface?,
            ) = popupHost.registerRenderer(token, record)

            override fun unregisterRenderer(token: Any) = popupHost.unregisterRenderer(token)

            override fun <T> runOnRenderThread(block: () -> T): T = popupHost.runOnRenderThread(block)

            override fun registerKeyHandler(
                token: Any,
                handler: (KeyEvent) -> Boolean,
            ) = popupHost.registerKeyHandler(token, handler)

            override fun unregisterKeyHandler(token: Any) = popupHost.unregisterKeyHandler(token)

            override fun setCursor(iconCode: Int) = popupHost.setCursor(iconCode)
        }

    /**
     * Named inner class so GraalVM JNI reachability metadata can
     * register the implementor.
     */
    private inner class OverlayCallback : NativeTaoMacOsNativeViewBridge.OverlayEventCallback {
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
            sc.sendPointerEvent(
                eventType = eventType,
                position = Offset(x, y),
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

        override fun onKeyEvent(
            type: Int,
            vkCode: Int,
            codePoint: Int,
            modifiers: Int,
        ) {
            scene?.dispatchNativeKeyEvent(
                type = type,
                vkCode = vkCode,
                codePoint = codePoint,
                modifiers = modifiers,
            )
        }
    }

    private var overlayNsView: Long = 0
    private var attachmentHandle: Long = 0

    // Created on / used on / closed on the host's render thread (Skia Metal
    // DirectContext is thread-affine). See TaoComposeSceneHost's render thread.
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null

    /**
     * Set in [dispose] before GPU teardown; read on the render thread via
     * [TaoRecordedSurface.isAlive] so an overlay torn down between record and
     * replay is skipped rather than replayed against a closed context.
     */
    @Volatile
    private var disposed: Boolean = false
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
        popupHost.registerRenderer(rendererToken) { recordSurface() }
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

    fun setBounds(
        xPx: Int,
        yPx: Int,
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
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

    private fun setBoundsInternal(
        xPx: Int,
        yPx: Int,
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        val frameUnchanged =
            firstBoundsApplied &&
                xPx == lastFrameX &&
                yPx == lastFrameY &&
                widthPxNew == widthPx &&
                heightPxNew == heightPx
        if (frameUnchanged) return

        // Steady-state path: defer EVERYTHING (AppKit setFrame, Metal
        // layer resize, ComposeScene size) into a single CATransaction
        // committed by the host's interop scheduler. The three updates
        // MUST land atomically because they read each other:
        //
        //   * `nativeResize` does `att->layer.frame = att->view.bounds`,
        //     so it has to run AFTER `nativeSetOverlayFrame` updated
        //     `view.frame` (and thus `view.bounds`) — otherwise the
        //     CAMetalLayer keeps its old `.frame` and the next presented
        //     drawable shows the new-size texture clipped/positioned
        //     inside the old-size layer rect. Visually: the overlay
        //     "lags" or flickers during a window live-resize.
        //   * `scene.size` is the ComposeScene's own viewport for
        //     popup clamping / hit-test bounds; doing it in the same
        //     transaction keeps it in lock-step with the surface size.
        //
        // The bookkeeping (`lastFrame*`, `widthPx`, `heightPx`, `overlayOffset*`)
        // updates synchronously so a follow-up `setBoundsInternal` call
        // sees the latest values; the captured locals carry the new
        // values into the deferred block independent of any racing
        // bookkeeping change.
        if (firstBoundsApplied) {
            val capturedScale = scale
            // Decided NOW, against the pre-update bookkeeping: by the time the
            // deferred block runs, `widthPx`/`heightPx` have already been set
            // to the new values below, so comparing inside the block is always
            // false — the inner scene's viewport would never be updated and
            // the overlay content would keep its stale layout size (visible
            // as the content slot not resizing across a fullscreen
            // round-trip).
            val sizeChanged = widthPxNew != widthPx || heightPxNew != heightPx
            host.scheduleInterop {
                // May execute AFTER dispose(): an action queued for the next
                // interop transaction outlives the embedding (dispose cannot
                // reach into the host's transaction queue to cancel it), and
                // by then nativeDetach has freed the attachment struct and
                // nativeReleaseOverlay the NSView — captured raw handles
                // would be use-after-free (observed SIGSEGV inside
                // nativeSetOverlayFrame). Re-read the live fields instead:
                // dispose zeroes them before freeing, all on the main thread.
                if (disposed) return@scheduleInterop
                val liveOverlay = overlayNsView
                if (liveOverlay == 0L) return@scheduleInterop
                NativeTaoMacOsNativeViewBridge.nativeSetOverlayFrame(
                    liveOverlay,
                    xPx,
                    yPx,
                    widthPxNew,
                    heightPxNew,
                )
                val liveAttachment = attachmentHandle
                if (liveAttachment != 0L) {
                    NativeMetalBridge.nativeResize(
                        liveAttachment,
                        widthPxNew,
                        heightPxNew,
                        capturedScale,
                    )
                }
                if (sizeChanged) {
                    scene?.size = IntSize(widthPxNew, heightPxNew)
                }
            }
            lastFrameX = xPx
            lastFrameY = yPx
            overlayOffsetX = xPx
            overlayOffsetY = yPx
            widthPx = widthPxNew
            heightPx = heightPxNew
            popupHost.requestRedraw()
            return
        }

        // First call: apply eagerly. `nativeAttachOverlay` (below) reads
        // `view.bounds` to seed `layer.frame`; if we defer the setFrame,
        // the Metal layer ends up sized to the parent's full bounds and
        // the overlay renders at the wrong location until the next
        // frame catches up — which on layer-hosted NSViews is never
        // (CALayer.frame doesn't auto-follow NSView.frame post layer
        // assignment).
        NativeTaoMacOsNativeViewBridge.nativeSetOverlayFrame(
            overlayNsView,
            xPx,
            yPx,
            widthPxNew,
            heightPxNew,
        )
        lastFrameX = xPx
        lastFrameY = yPx
        overlayOffsetX = xPx
        overlayOffsetY = yPx
        widthPx = widthPxNew
        heightPx = heightPxNew

        firstBoundsApplied = true
        attachmentHandle = NativeMetalBridge.nativeAttachOverlay(overlayNsView)
        require(attachmentHandle != 0L) { "Failed to attach overlay CAMetalLayer" }
        directContext =
            popupHost.runOnRenderThread {
                DirectContext.makeMetal(
                    NativeMetalBridge.nativeDevicePtr(attachmentHandle),
                    NativeMetalBridge.nativeQueuePtr(attachmentHandle),
                )
            }
        val ourPlatformContext =
            object : PlatformContext.Empty() {
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
        scene =
            PlatformLayersComposeScene(
                density = Density(scale),
                layoutDirection = LayoutDirection.Ltr,
                size = IntSize(widthPx, heightPx),
                coroutineContext = popupHost.sceneCoroutineContext,
                composeSceneContext =
                    TaoComposeSceneContext(
                        platformContext = ourPlatformContext,
                    ) { density, layoutDirection, focusable, cc ->
                        TaoPopupSceneLayer(
                            host = overlayPopupHost,
                            initialDensity = density,
                            initialLayoutDirection = layoutDirection,
                            initialFocusable = focusable,
                            parentCompositionContext = cc,
                        )
                    },
                invalidate = { popupHost.requestRedraw() },
            )
        pendingContent?.let {
            scene?.setContent(it)
            pendingContent = null
        }

        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        popupHost.requestRedraw()
    }

    fun setContent(content: @Composable () -> Unit) {
        // The overlay renders through its own Skia context, so `TextureView`s in
        // the overlay slot must import onto it rather than the window scene's.
        // Resolved at composition time — the context only exists after attach().
        val wrapped: @Composable () -> Unit = {
            CompositionLocalProvider(LocalTaoMetalTextureHost provides metalTextureHost()) {
                content()
            }
        }
        val sc = scene
        if (sc != null) sc.setContent(wrapped) else pendingContent = wrapped
        popupHost.requestRedraw()
    }

    /** This overlay's handle for `TextureView`s composed inside it — see [MetalTextureHostCache]. */
    private val metalTextureHostCache = MetalTextureHostCache()

    private fun metalTextureHost(): TaoMetalTextureHost? =
        metalTextureHostCache.get(attachmentHandle, directContext) { device, ctx ->
            object : TaoMetalTextureHost {
                override val metalDevicePtr: Long = device
                override val directContext: DirectContext = ctx

                override fun <T> runOnRenderThread(block: () -> T): T = popupHost.runOnRenderThread(block)
            }
        }

    @Suppress("ComplexCondition")
    fun registerRegion(
        key: Any,
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        val previous = regions[key]
        if (previous != null &&
            previous[0] == xPx &&
            previous[1] == yPx &&
            previous[2] == widthPx &&
            previous[3] == heightPx
        ) {
            return
        }
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

    /**
     * Records the overlay scene into a [TaoRecordedSurface] on the main thread;
     * the host replays it on its render thread. Returns null to skip the frame.
     */
    private fun recordSurface(): TaoRecordedSurface? {
        if (disposed) return null
        val ctx = directContext ?: return null
        val sc = scene ?: return null
        if (widthPx == 0 || heightPx == 0) return null
        if (attachmentHandle == 0L) return null
        return TaoRecordedSurface(
            attachmentHandle = attachmentHandle,
            directContext = ctx,
            picture = recordSceneToPicture(sc, widthPx, heightPx),
            clearColor = 0x00000000,
            isAlive = { !disposed },
        )
    }

    fun dispose() {
        if (overlayNsView == 0L) return
        popupHost.unregisterRenderer(rendererToken)
        popupHost.unregisterKeyHandler(rendererToken)
        // Mark disposed before teardown so an already-recorded surface is skipped
        // at replay time (TaoRecordedSurface.isAlive).
        disposed = true
        scene?.close()
        scene = null
        // Drop the TextureView handle before the context it points at dies.
        metalTextureHostCache.invalidate()
        // Close the Skia context on its owning render thread. dispose() runs in
        // the host's main-thread record pass (Compose disposal), when the render
        // thread is idle, so this blocking hop returns immediately without racing
        // a replay; nativeDetach / nativeReleaseOverlay stay on the main thread.
        val ctx = directContext
        directContext = null
        if (ctx != null) popupHost.runOnRenderThread { ctx.close() }
        // Zero out before freeing so a recorder still in the host's snapshot
        // iteration bails — same hazard as TaoPopupSceneLayer.close.
        val handle = attachmentHandle
        attachmentHandle = 0
        if (handle != 0L) NativeMetalBridge.nativeDetach(handle)
        // Zero out BEFORE freeing, like attachmentHandle above: a queued
        // interop action re-reads this field at execution time and must see 0
        // instead of a freed NSView pointer.
        val overlayView = overlayNsView
        overlayNsView = 0
        if (overlayView != 0L) NativeTaoMacOsNativeViewBridge.nativeReleaseOverlay(overlayView)
    }

    private companion object {
        private val EmptyRegions = FloatArray(0)
    }
}
