package io.github.kdroidfilter.nucleus.window.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.kdroidfilter.nucleus.window.tao.render.TaoComposeSceneContextWindows
import io.github.kdroidfilter.nucleus.window.tao.render.TaoNativeWireFormat
import io.github.kdroidfilter.nucleus.window.tao.render.TaoPopupHostWindows
import io.github.kdroidfilter.nucleus.window.tao.render.renderGlFrame
import kotlin.coroutines.CoroutineContext
import org.jetbrains.skia.DirectContext

/**
 * Windows port of the macOS [NativeViewOverlayController]. Owns the
 * top-level overlay HWND mounted above a user-supplied child HWND, its
 * transparent WGL context (joined to the host's share group via
 * `wglCreateContextAttribsARB(.., hostHGLRC, ..)`), and the inner
 * [ComposeScene] that renders into it. Maintains a list of "interactive
 * regions" populated by `Modifier.consumeOverlayPointerEvents()`; the
 * overlay's WndProc consults the same list (via JNI) to decide whether
 * `WM_NCHITTEST` returns `HTCLIENT` or `HTTRANSPARENT`.
 *
 * Per-frame render path (cross-context sync per the plan):
 *   1. host.flushAndSubmit + nativePresent (handled by the host before
 *      this lambda runs)
 *   2. nativeMakeCurrent(overlay)
 *   3. overlayDirectContext.resetGLAll()
 *   4. renderGlFrame -> glClear(0,0,0,0) + scene.render +
 *      flushAndSubmit + nativeSwapBuffers (which does SwapBuffers + DwmFlush)
 *   5. host re-makes-current + resetGLAll (handled by the host after
 *      the popup loop)
 *
 * Phase 5 ships with [CanvasLayersComposeScene] so popups stay clipped
 * to the overlay's own bounds; Phase 7 swaps in
 * `PlatformLayersComposeScene + TaoComposeSceneContextWindows` so
 * `DropdownMenu` / `Tooltip` / context menus open as their own
 * top-level HWNDs.
 *
 * Threading: every method runs on the host HWND's UI thread.
 */
@OptIn(InternalComposeUiApi::class)
internal class NativeViewOverlayControllerWindows(
    @Suppress("UNUSED_PARAMETER") private val host: TaoNativeViewHost,
    private val popupHost: TaoPopupHostWindows,
) {
    private val rendererToken: Any = Any()
    private val keyHandlerToken: Any = Any()
    private val moveListenerToken: Any = Any()
    private val focusLostListenerToken: Any = Any()
    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var overlayOffsetX: Int = 0
    private var overlayOffsetY: Int = 0
    private val scale: Float = popupHost.scale

    /**
     * Wrapper around the host's [TaoPopupHostWindows] that adds the
     * overlay's live position as `coordinateOffset`. Popups originating
     * in the overlay's scene see their `boundsInWindow` in overlay-local
     * coords; the layer adds the offset before talking to the popup
     * bridge so the popup HWND lands at the correct screen position.
     */
    private val overlayPopupHost: TaoPopupHostWindows = object : TaoPopupHostWindows {
        override val parentHwnd: Long get() = popupHost.parentHwnd
        override val scale: Float get() = popupHost.scale
        override val parentWindowSize: IntSize get() = popupHost.parentWindowSize
        override val sceneCoroutineContext: CoroutineContext get() = popupHost.sceneCoroutineContext
        override val hostDirectContext: DirectContext get() = popupHost.hostDirectContext
        override val coordinateOffset: IntOffset
            get() = IntOffset(overlayOffsetX, overlayOffsetY)
        override fun requestRedraw() = popupHost.requestRedraw()
        override fun registerRenderer(token: Any, render: () -> Unit) =
            popupHost.registerRenderer(token, render)
        override fun unregisterRenderer(token: Any) = popupHost.unregisterRenderer(token)
        override fun registerKeyHandler(token: Any, handler: (KeyEvent) -> Boolean) =
            popupHost.registerKeyHandler(token, handler)
        override fun unregisterKeyHandler(token: Any) = popupHost.unregisterKeyHandler(token)
        override fun registerOwnerMoveListener(token: Any, onMoved: () -> Unit) =
            popupHost.registerOwnerMoveListener(token, onMoved)
        override fun unregisterOwnerMoveListener(token: Any) =
            popupHost.unregisterOwnerMoveListener(token)
        override fun registerOwnerFocusLostListener(token: Any, onLost: () -> Unit) =
            popupHost.registerOwnerFocusLostListener(token, onLost)
        override fun unregisterOwnerFocusLostListener(token: Any) =
            popupHost.unregisterOwnerFocusLostListener(token)
        override fun registerOwnerFocusGainedListener(token: Any, onGained: () -> Unit) =
            popupHost.registerOwnerFocusGainedListener(token, onGained)
        override fun unregisterOwnerFocusGainedListener(token: Any) =
            popupHost.unregisterOwnerFocusGainedListener(token)
        override fun notifyPopupClosing() = popupHost.notifyPopupClosing()
        override fun registerPopupClosingListener(token: Any, onClosing: () -> Unit) =
            popupHost.registerPopupClosingListener(token, onClosing)
        override fun unregisterPopupClosingListener(token: Any) =
            popupHost.unregisterPopupClosingListener(token)
    }
    // (overlayPopupHost still exposes both register/unregister focus-gained
    // for downstream popups that may want them; this controller no longer
    // uses them — see the OverlayCallback Press path below.)

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

    private inner class OverlayCallback : NativeTaoWindowsOverlayBridge.OverlayEventCallback {
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
    }

    private var overlayHandle: Long = 0
    /**
     * Single-HGLRC architecture: all overlays + popups + the host
     * share the host's `DirectContext`. The overlay controller doesn't
     * create its own — `popupHost.hostDirectContext` is the source of
     * truth.
     */
    private val directContext: DirectContext = popupHost.hostDirectContext
    private var scene: ComposeScene? = null
    private val regions: MutableMap<Any, IntArray> = LinkedHashMap()
    private var pendingContent: (@Composable () -> Unit)? = null
    private var firstBoundsApplied = false
    private var pendingBounds: IntArray? = null

    /**
     * Per-region manual cursor override (consumeOverlayPointerEvents `cursor` param).
     * Tracked per-key so nested or overlapping regions can each push/pop cursors
     * without stomping each other; the most recently pushed value wins.
     */
    private val manualCursorByKey: LinkedHashMap<Any, PointerIcon> = LinkedHashMap()
    private val manualCursor: PointerIcon?
        get() = if (manualCursorByKey.isEmpty()) null else manualCursorByKey.values.last()

    private fun applyCursor(icon: PointerIcon) {
        val code = when (icon) {
            PointerIcon.Text -> NativeTaoWindowsOverlayBridge.CURSOR_TEXT
            PointerIcon.Hand -> NativeTaoWindowsOverlayBridge.CURSOR_HAND
            PointerIcon.Crosshair -> NativeTaoWindowsOverlayBridge.CURSOR_CROSSHAIR
            else -> NativeTaoWindowsOverlayBridge.CURSOR_DEFAULT
        }
        if (overlayHandle != 0L) NativeTaoWindowsOverlayBridge.nativeSetCursor(overlayHandle, code)
    }

    fun pushManualCursor(key: Any, icon: PointerIcon) {
        manualCursorByKey.remove(key)
        manualCursorByKey[key] = icon
        applyCursor(icon)
    }

    fun popManualCursor(key: Any) {
        if (manualCursorByKey.remove(key) == null) return
        applyCursor(manualCursor ?: PointerIcon.Default)
    }

    fun attach() {
        if (overlayHandle != 0L) return
        overlayHandle = NativeTaoWindowsOverlayBridge.nativeCreateOverlay(popupHost.parentHwnd)
        require(overlayHandle != 0L) { "Failed to create overlay HWND" }
        NativeTaoWindowsOverlayBridge.nativeSetOverlayCallback(overlayHandle, OverlayCallback())
        popupHost.registerRenderer(rendererToken) { renderFrame() }
        popupHost.registerKeyHandler(keyHandlerToken) { event ->
            val sc = scene ?: return@registerKeyHandler false
            sc.sendKeyEvent(event)
        }
        // Re-issue nativeSetOverlayFrame whenever the host moves on
        // screen — the overlay is a top-level WS_POPUP whose screen
        // coords don't auto-track the owner.
        popupHost.registerOwnerMoveListener(moveListenerToken) {
            if (overlayHandle != 0L && firstBoundsApplied) {
                NativeTaoWindowsOverlayBridge.nativeSetOverlayFrame(
                    overlayHandle, lastFrameX, lastFrameY, widthPx, heightPx,
                )
            }
        }
        // When the host loses keyboard focus (e.g., user clicked the
        // WebView, which grabs Win32 focus), drop the overlay scene's
        // Compose focus so the URL TextField's highlight border / caret
        // stop drawing. focusManager.releaseFocus() walks the tree and
        // clears the active focused node.
        // When the host loses keyboard focus (e.g., user clicked the
        // WebView, which grabs Win32 focus), drop the overlay scene's
        // Compose focus so the URL TextField's highlight border / caret
        // stop drawing. focusManager.releaseFocus() walks the tree and
        // clears the active focused node.
        popupHost.registerOwnerFocusLostListener(focusLostListenerToken) {
            scene?.focusManager?.releaseFocus()
            popupHost.requestRedraw()
        }
        pendingBounds?.let { setBoundsInternal(it[0], it[1], it[2], it[3]) }
        pendingBounds = null
    }

    fun setBounds(xPx: Int, yPx: Int, widthPxNew: Int, heightPxNew: Int) {
        if (overlayHandle == 0L) {
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

        NativeTaoWindowsOverlayBridge.nativeSetOverlayFrame(
            overlayHandle, xPx, yPx, widthPxNew, heightPxNew,
        )

        lastFrameX = xPx
        lastFrameY = yPx
        overlayOffsetX = xPx
        overlayOffsetY = yPx
        if (widthPxNew == widthPx && heightPxNew == heightPx && firstBoundsApplied) return
        widthPx = widthPxNew
        heightPx = heightPxNew

        if (!firstBoundsApplied) {
            firstBoundsApplied = true

            // Single-HGLRC: the overlay HDC uses the host's HGLRC. We
            // don't call DirectContext.makeGL() — `directContext` is the
            // host's, already initialized. Each renderFrame() switches
            // the host HGLRC to draw into the overlay's HDC and calls
            // resetGLAll on the shared directContext to re-sync Skia's
            // GL state cache for the new framebuffer.

            val ourPlatformContext = object : PlatformContext.Empty() {
                override val windowInfo: WindowInfo get() = overlayWindowInfo
                override fun setPointerIcon(pointerIcon: PointerIcon) {
                    // While a manual cursor override is active (consumeOverlayPointerEvents
                    // with a `cursor` hint), ignore Compose's resolution. BasicTextField
                    // applies its own pointerHoverIcon(Default, overrideDescendants=true)
                    // on the click area surrounding the text glyphs, which would otherwise
                    // overwrite the I-beam mid-hover.
                    if (manualCursor != null) return
                    applyCursor(pointerIcon)
                }
            }
            // PlatformLayersComposeScene + TaoComposeSceneContextWindows
            // routes popups originating in this overlay scene
            // (text-field context menus, dropdowns, tooltips) through
            // TaoPopupSceneLayerWindows — i.e. into a real top-level
            // HWND owned by the host main HWND. They can extend beyond
            // the overlay's bounds, intercept their own clicks, and
            // dismiss on outside-click via the popup's SetCapture monitor.
            scene = PlatformLayersComposeScene(
                density = Density(scale),
                layoutDirection = LayoutDirection.Ltr,
                size = IntSize(widthPx, heightPx),
                coroutineContext = popupHost.sceneCoroutineContext,
                composeSceneContext = TaoComposeSceneContextWindows(
                    platformContext = ourPlatformContext,
                    popupHost = overlayPopupHost,
                ),
                invalidate = { popupHost.requestRedraw() },
            )
            pendingContent?.let { scene?.setContent(it); pendingContent = null }
        } else {
            scene?.size = IntSize(widthPx, heightPx)
        }
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
        if (overlayHandle == 0L) return
        val count = regions.size
        if (count == 0) {
            NativeTaoWindowsOverlayBridge.nativeSetOverlayRegions(overlayHandle, EmptyRegions, 0)
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
        NativeTaoWindowsOverlayBridge.nativeSetOverlayRegions(overlayHandle, flat, count)
    }

    private fun renderFrame() {
        val sc = scene ?: return
        if (widthPx == 0 || heightPx == 0) return
        if (overlayHandle == 0L) return
        if (!NativeTaoWindowsOverlayBridge.nativeMakeCurrent(overlayHandle)) return
        // Switched the host's HGLRC to draw into the overlay's HDC —
        // FBO 0 now refers to the overlay's back-buffer. resetGLAll
        // tells Skia "external code touched GL state" so it re-fetches
        // the current framebuffer binding before issuing draws.
        directContext.resetGLAll()
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = directContext,
            scene = sc,
            // Premultiplied transparent black: alpha=0, RGB=0. DWM honors
            // the alpha channel via the empty-blur-region trick armed at
            // overlay creation.
            clearColorArgb = 0x00000000,
            present = { NativeTaoWindowsOverlayBridge.nativeSwapBuffers(overlayHandle) },
        )
    }

    fun dispose() {
        if (overlayHandle == 0L) return
        popupHost.unregisterRenderer(rendererToken)
        popupHost.unregisterKeyHandler(keyHandlerToken)
        popupHost.unregisterOwnerMoveListener(moveListenerToken)
        popupHost.unregisterOwnerFocusLostListener(focusLostListenerToken)
        scene?.close()
        scene = null
        manualCursorByKey.clear()
        // directContext is the host's — don't close.
        NativeTaoWindowsOverlayBridge.nativeReleaseOverlay(overlayHandle)
        overlayHandle = 0
    }

    private companion object {
        private val EmptyRegions = FloatArray(0)
    }
}

/**
 * Composition local exposing the Windows overlay controller (if any) to
 * descendants of [NativeView]'s `content` slot. Used by
 * [Modifier.consumeOverlayPointerEvents] to register itself as an
 * interactive region.
 */
internal val LocalNativeViewOverlayControllerWindows =
    compositionLocalOf<NativeViewOverlayControllerWindows?> { null }
