@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.deco

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.TaoNativeViewHost
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsOverlayBridge
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.popup.TaoPopupHostWindows
import dev.nucleusframework.window.tao.popup.TaoPopupSceneLayerWindows
import dev.nucleusframework.window.tao.scene.TaoComposeSceneContext
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.platformLayersSceneBundle
import dev.nucleusframework.window.tao.scene.renderGlFrame
import org.jetbrains.skia.DirectContext
import kotlin.coroutines.CoroutineContext

/**
 * Windows port of the macOS [NativeViewOverlayController]. Owns the
 * top-level overlay HWND mounted above a user-supplied child HWND, its
 * DComp/EGL render surface (the host's EGLContext bound to a
 * d3d-texture pbuffer, presented via a composition swapchain — see
 * nucleus_tao_windows_overlay_dcomp.cpp), and the inner [ComposeScene]
 * that renders into it. Maintains a list of "interactive regions"
 * populated by `Modifier.consumeOverlayPointerEvents()`; the overlay's
 * WndProc consults the same list (via JNI) to decide whether
 * `WM_NCHITTEST` returns `HTCLIENT` or `HTTRANSPARENT`.
 *
 * Per-frame render path (cross-surface sync):
 *   1. host renders + flushAndSubmit (handled by the host before this
 *      lambda runs; the host present happens after the popup loop)
 *   2. nativeMakeCurrent(overlay) — binds the pbuffer surface
 *   3. directContext.resetGLAll()
 *   4. renderGlFrame -> clear(transparent) + scene.render +
 *      flushAndSubmit + nativeSwapBuffers (CopyResource + Present +
 *      Commit)
 *   5. host re-binds its window surface + resetGLAll on its next frame
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
    private val popupClosingToken: Any = Any()

    /**
     * Captured from the overlay scene's composition via [LocalFocusManager].
     * `clearFocus(force = true)` (standard FocusManager) breaks a
     * `BasicTextField`'s "Captured" focus state — the scene-level
     * `releaseFocus()` only clears Active/ActiveParent, leaving an
     * actively-edited TextField stuck. Set in [setContent] inside a
     * `SideEffect`, nulled in [dispose].
     */
    private var capturedFocusManager: FocusManager? = null

    /**
     * Tracks which mouse buttons Compose's overlay scene currently sees as
     * pressed. Press/Release pairs can desynchronise across popup HWND
     * boundaries:
     *  - Right-click that opens a context menu: `WM_RBUTTONDOWN` reaches
     *    the overlay (Press dispatched), but `WM_RBUTTONUP` arrives after
     *    the popup HWND has captured the mouse and goes there instead —
     *    Compose never sees the Release.
     *  - Left-click that dismisses the menu: the popup eats `WM_LBUTTONDOWN`
     *    (outside-click handler), then `ReleaseCapture()`; the trailing
     *    `WM_LBUTTONUP` lands on the overlay → Compose sees a Release with
     *    no matching Press.
     * Both desyncs leave Compose's gesture pipeline in a state where a
     * subsequent Press on a `BasicTextField` no longer triggers focus.
     * We drop orphan Releases here and synthesize Releases for dangling
     * Presses on `popupClosing`.
     */
    private val pressedButtons = mutableSetOf<PointerButton>()
    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f
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
    @Suppress("TooManyFunctions")
    private val overlayPopupHost: TaoPopupHostWindows =
        object : TaoPopupHostWindows {
            override val parentHwnd: Long get() = popupHost.parentHwnd
            override val scale: Float get() = popupHost.scale
            override val parentWindowSize: IntSize get() = popupHost.parentWindowSize
            override val workAreaSize: IntSize get() = popupHost.workAreaSize
            override val sceneCoroutineContext: CoroutineContext get() = popupHost.sceneCoroutineContext
            override val hostDirectContext: DirectContext get() = popupHost.hostDirectContext
            override val coordinateOffset: IntOffset
                get() = IntOffset(overlayOffsetX, overlayOffsetY)

            override fun requestRedraw() = popupHost.requestRedraw()

            override fun registerRenderer(
                token: Any,
                render: () -> Unit,
            ) = popupHost.registerRenderer(token, render)

            override fun unregisterRenderer(token: Any) = popupHost.unregisterRenderer(token)

            override fun registerKeyHandler(
                token: Any,
                handler: (KeyEvent) -> Boolean,
            ) = popupHost.registerKeyHandler(token, handler)

            override fun unregisterKeyHandler(token: Any) = popupHost.unregisterKeyHandler(token)

            override fun registerOwnerMoveListener(
                token: Any,
                onMoved: () -> Unit,
            ) = popupHost.registerOwnerMoveListener(token, onMoved)

            override fun unregisterOwnerMoveListener(token: Any) = popupHost.unregisterOwnerMoveListener(token)

            override fun registerOwnerFocusLostListener(
                token: Any,
                onLost: () -> Unit,
            ) = popupHost.registerOwnerFocusLostListener(token, onLost)

            override fun unregisterOwnerFocusLostListener(token: Any) =
                popupHost.unregisterOwnerFocusLostListener(token)

            override fun registerOwnerFocusGainedListener(
                token: Any,
                onGained: () -> Unit,
            ) = popupHost.registerOwnerFocusGainedListener(token, onGained)

            override fun unregisterOwnerFocusGainedListener(token: Any) =
                popupHost.unregisterOwnerFocusGainedListener(token)

            override fun notifyPopupClosing() = popupHost.notifyPopupClosing()

            override fun registerPopupClosingListener(
                token: Any,
                onClosing: () -> Unit,
            ) = popupHost.registerPopupClosingListener(token, onClosing)

            override fun unregisterPopupClosingListener(token: Any) = popupHost.unregisterPopupClosingListener(token)
        }
    // (overlayPopupHost still exposes both register/unregister focus-gained
    // for downstream popups that may want them; this controller no longer
    // uses them — see the OverlayCallback Press path below.)

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

    private inner class OverlayCallback : NativeTaoWindowsOverlayBridge.OverlayEventCallback {
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
            lastPointerX = x
            lastPointerY = y
            val eventType =
                when (type) {
                    TaoNativeWireFormat.PTR_DOWN -> {
                        if (pointerButton != null) pressedButtons += pointerButton
                        PointerEventType.Press
                    }
                    TaoNativeWireFormat.PTR_UP -> {
                        if (pointerButton != null && pointerButton !in pressedButtons) {
                            // Orphan Release — typically the trailing
                            // WM_LBUTTONUP after a popup-dismiss outside-click,
                            // whose Press was eaten by the popup HWND. Letting
                            // it through corrupts Compose's gesture detector
                            // and breaks subsequent focus-on-tap on
                            // BasicTextFields in the overlay scene.
                            return
                        }
                        if (pointerButton != null) pressedButtons -= pointerButton
                        PointerEventType.Release
                    }
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
    }

    private var overlayHandle: Long = 0

    /**
     * Single-context architecture: all overlays + popups + the host
     * share the host's `DirectContext` (one EGLContext, one surface per
     * HWND). The overlay controller doesn't create its own —
     * `popupHost.hostDirectContext` is the source of truth.
     */
    private val directContext: DirectContext = popupHost.hostDirectContext
    private var sceneBundle: TaoSceneBundle? = null
    private val scene: ComposeScene? get() = sceneBundle?.scene
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
        val code =
            when (icon) {
                PointerIcon.Text -> NativeTaoWindowsOverlayBridge.CURSOR_TEXT
                PointerIcon.Hand -> NativeTaoWindowsOverlayBridge.CURSOR_HAND
                PointerIcon.Crosshair -> NativeTaoWindowsOverlayBridge.CURSOR_CROSSHAIR
                else -> NativeTaoWindowsOverlayBridge.CURSOR_DEFAULT
            }
        if (overlayHandle != 0L) NativeTaoWindowsOverlayBridge.nativeSetCursor(overlayHandle, code)
    }

    fun pushManualCursor(
        key: Any,
        icon: PointerIcon,
    ) {
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
        if (overlayHandle == 0L) {
            // DComp/EGL chain creation failed (overlay_dcomp.cpp) — e.g.
            // DirectComposition unavailable. Degrade gracefully: the
            // embedded child HWND keeps working, only the Compose `content`
            // slot is dropped. The controller stays inert — every other
            // entry point already no-ops on overlayHandle == 0.
            warnOverlayUnavailableOnce()
            return
        }
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
                    overlayHandle,
                    lastFrameX,
                    lastFrameY,
                    widthPx,
                    heightPx,
                )
            }
        }
        // When the host loses keyboard focus (e.g., user clicked the
        // WebView, which grabs Win32 focus), drop the overlay scene's
        // Compose focus so the URL TextField's highlight border / caret
        // stop drawing. We prefer `clearFocus(force = true)` over the
        // scene-level `releaseFocus()` because a BasicTextField in
        // active editing transitions to "Captured" focus state — only
        // the standard FocusManager's `force = true` path clears that.
        // Without it, a subsequent click on the same field doesn't
        // re-focus it (the node is still Captured but visually stale).
        popupHost.registerOwnerFocusLostListener(focusLostListenerToken) {
            capturedFocusManager?.clearFocus(force = true)
                ?: scene?.focusManager?.releaseFocus()
            popupHost.requestRedraw()
        }
        // Same problem at popup teardown: the right-click that opened
        // the context menu put the underlying BasicTextField into
        // Captured focus. `TaoPopupSceneLayerWindows.close()` calls
        // `notifyPopupClosing()` BEFORE destroying the popup HWND
        // precisely so we can break that Captured state while the
        // popup is still alive. Without this listener, the TextField
        // stays Captured after the menu dismisses and the next
        // click-on-URL → click-WebView → click-URL sequence leaves
        // the URL field unfocusable.
        popupHost.registerPopupClosingListener(popupClosingToken) {
            // Drain dangling Presses: when the right-click that opened the
            // popup was dispatched to Compose, the matching WM_*BUTTONUP
            // arrived after the popup HWND took capture and never reached
            // us. Without a synthetic Release, Compose stays in a "button
            // still down" state and the next click on a BasicTextField
            // doesn't trigger focus.
            val sc = scene
            if (sc != null && pressedButtons.isNotEmpty()) {
                for (btn in pressedButtons.toList()) {
                    sc.sendPointerEvent(
                        eventType = PointerEventType.Release,
                        position = Offset(lastPointerX, lastPointerY),
                        type = PointerType.Mouse,
                        button = btn,
                    )
                }
                pressedButtons.clear()
            }
            capturedFocusManager?.clearFocus(force = true)
            popupHost.requestRedraw()
        }
        pendingBounds?.let { setBoundsInternal(it[0], it[1], it[2], it[3]) }
        pendingBounds = null
    }

    fun setBounds(
        xPx: Int,
        yPx: Int,
        widthPxNew: Int,
        heightPxNew: Int,
    ) {
        if (overlayHandle == 0L) {
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

        NativeTaoWindowsOverlayBridge.nativeSetOverlayFrame(
            overlayHandle,
            xPx,
            yPx,
            widthPxNew,
            heightPxNew,
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

            // Single-context: the overlay renders through the host's
            // EGLContext. We don't create a DirectContext —
            // `directContext` is the host's, already initialized. Each
            // renderFrame() binds the overlay's pbuffer surface and calls
            // resetGLAll on the shared directContext to re-sync Skia's
            // GL state cache for the new framebuffer.

            val ourPlatformContext =
                object : PlatformContext.Empty() {
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
            sceneBundle =
                platformLayersSceneBundle(
                    coroutineContext = popupHost.sceneCoroutineContext,
                    density = Density(scale),
                    layoutDirection = LayoutDirection.Ltr,
                    size = IntSize(widthPx, heightPx),
                    composeSceneContext =
                        TaoComposeSceneContext(
                            platformContext = ourPlatformContext,
                        ) { density, layoutDirection, focusable, consumeOutside ->
                            TaoPopupSceneLayerWindows(
                                host = overlayPopupHost,
                                initialDensity = density,
                                initialLayoutDirection = layoutDirection,
                                initialFocusable = focusable,
                                initialConsumePointerInputOutside = consumeOutside,
                            )
                        },
                    requestFrame = { popupHost.requestRedraw() },
                )
            pendingContent?.let {
                scene?.setContent(content = it)
                pendingContent = null
            }
        } else {
            scene?.size = IntSize(widthPx, heightPx)
        }
        popupHost.requestRedraw()
    }

    fun setContent(content: @Composable () -> Unit) {
        // Wrap so we can capture the standard FocusManager from the
        // composition. `clearFocus(force = true)` against this manager
        // is the only reliable way to break a `BasicTextField`'s
        // Captured focus state — the scene-level `releaseFocus()` is
        // not enough.
        val wrapped: @Composable () -> Unit = {
            val fm = LocalFocusManager.current
            SideEffect { capturedFocusManager = fm }
            content()
        }
        val sc = scene
        if (sc != null) sc.setContent(content = wrapped) else pendingContent = wrapped
        popupHost.requestRedraw()
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
        val bundle = sceneBundle ?: return
        if (widthPx == 0 || heightPx == 0) return
        if (overlayHandle == 0L) return
        if (!NativeTaoWindowsOverlayBridge.nativeMakeCurrent(overlayHandle)) return
        // Bound the overlay's pbuffer surface on the shared EGLContext —
        // FBO 0 now refers to the overlay's texture. resetGLAll tells
        // Skia "external code touched GL state" so it re-fetches the
        // current framebuffer binding before issuing draws.
        directContext.resetGLAll()
        renderGlFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            directContext = directContext,
            bundle = bundle,
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
        popupHost.unregisterPopupClosingListener(popupClosingToken)
        sceneBundle?.close()
        sceneBundle = null
        capturedFocusManager = null
        pressedButtons.clear()
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

private val overlayUnavailableWarned =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

private val overlayLogger: java.util.logging.Logger =
    java.util.logging.Logger
        .getLogger("dev.nucleusframework.window.tao.nativeView")

private fun warnOverlayUnavailableOnce() {
    if (!overlayUnavailableWarned.compareAndSet(false, true)) return
    overlayLogger.warning(
        "Compose overlay unavailable: the DirectComposition overlay chain " +
            "could not be created on this system. The embedded native view " +
            "still works; the overlay `content` slot is ignored.",
    )
}
