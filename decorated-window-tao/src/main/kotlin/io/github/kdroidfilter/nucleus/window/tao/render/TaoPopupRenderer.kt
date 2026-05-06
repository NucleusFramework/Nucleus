package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
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
import kotlin.coroutines.CoroutineContext

/**
 * Phase 2 of the Compose-popup-via-NSPanel architecture: drives one
 * [ComposeScene] into the transparent `CAMetalLayer` of a freshly
 * allocated [PopupNativeBridge] panel. Phase 2 instantiates this manually
 * (no `Popup` composable yet); Phase 3 will hand a similar object to
 * Compose's popup framework via `ComposeSceneContext.createLayer(...)`.
 *
 * Lifecycle:
 *  1. `[create]`: allocates the NSPanel (parent-window-pixel rect),
 *     attaches a transparent `CAMetalLayer` to its content view, builds
 *     a `CanvasLayersComposeScene` of the same size, registers a renderer
 *     callback with the parent host so we paint a frame whenever the
 *     parent paints one.
 *  2. `[setContent]`: mounts user composable.
 *  3. `[setBounds]`: resize / reposition (called from Compose's popup
 *     measure pipeline in Phase 3+).
 *  4. `[dispose]`: tears it all down.
 *
 * Threading: every call must run on the macOS main thread (= Tao event-
 * loop thread = Compose dispatcher thread).
 *
 * **Critical invariant**: the inner scene's `size` is set **before**
 * `setContent` so the popup's `RootMeasurePolicy` (in Compose's `Popup`
 * composable) measures with non-zero constraints — the diagnosis from
 * the post-mortem says zero-sized constraints make the policy short-
 * circuit and `boundsInWindow` never updates.
 */
@OptIn(InternalComposeUiApi::class)
class TaoPopupRenderer(
    private val host: TaoPopupHost,
) {
    private var panelHandle: Long = 0
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val rendererToken: Any = Any()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = host.scale

    private inner class EventCallback : PopupNativeBridge.EventCallback {
        override fun onPointerEvent(type: Int, x: Float, y: Float, button: Int, modifiers: Int) {
            val eventType = when (type) {
                EVT_PTR_DOWN -> PointerEventType.Press
                EVT_PTR_UP -> PointerEventType.Release
                else -> PointerEventType.Move
            }
            scene?.sendPointerEvent(
                eventType = eventType,
                position = Offset(x, y),
                type = PointerType.Mouse,
                button = when (button) {
                    1 -> androidx.compose.ui.input.pointer.PointerButton.Primary
                    2 -> androidx.compose.ui.input.pointer.PointerButton.Secondary
                    else -> null
                },
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

        override fun onKeyEvent(type: Int, vkCode: Int, codePoint: Int, modifiers: Int) {
            val sc = scene ?: return
            val isShift = modifiers and MOD_SHIFT != 0
            val isCtrl = modifiers and MOD_CTRL != 0
            val isAlt = modifiers and MOD_ALT != 0
            val isMeta = modifiers and MOD_META != 0
            val keyEvent = KeyEvent(
                key = Key(nativeKeyCode = vkCode, nativeKeyLocation = 0),
                type = if (type == EVT_KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                codePoint = codePoint,
                isShiftPressed = isShift,
                isCtrlPressed = isCtrl,
                isAltPressed = isAlt,
                isMetaPressed = isMeta,
            )
            sc.sendKeyEvent(keyEvent)

            if (type == EVT_KEY_DOWN && codePoint.isPrintableTextInput(isCtrl, isMeta)) {
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
                            awtModifiers(isShift, isCtrl, isAlt, isMeta),
                            java.awt.event.KeyEvent.VK_UNDEFINED,
                            codePoint.toChar(),
                            java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
                        ),
                    ),
                )
            }
        }
    }

    /** Allocates panel + Metal pipeline + scene. Must be called before any other method. */
    fun create(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) {
        require(panelHandle == 0L) { "TaoPopupRenderer already created" }
        check(NativeMetalBridge.isLoaded) { "NativeMetalBridge dylib not loaded" }
        check(PopupNativeBridge.isLoaded) { "PopupNativeBridge dylib not loaded" }

        panelHandle = PopupNativeBridge.nativeCreatePanel(
            parentNsView = host.parentNsView,
            xPx = xPx,
            yPx = yPx,
            widthPx = widthPx,
            heightPx = heightPx,
        )
        require(panelHandle != 0L) { "Failed to create popup NSPanel" }

        val contentNsView = PopupNativeBridge.nativeContentNsView(panelHandle)
        require(contentNsView != 0L) { "Popup panel has no content NSView" }

        attachmentHandle = NativeMetalBridge.nativeAttachOverlay(contentNsView)
        require(attachmentHandle != 0L) { "Failed to attach popup CAMetalLayer" }

        directContext = DirectContext.makeMetal(
            NativeMetalBridge.nativeDevicePtr(attachmentHandle),
            NativeMetalBridge.nativeQueuePtr(attachmentHandle),
        )

        this.widthPx = widthPx
        this.heightPx = heightPx
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)

        // Custom WindowInfo with isWindowFocused=true — see equivalent
        // comment in TaoPopupSceneLayer.popupWindowInfo. Without it,
        // Compose `BasicTextField` doesn't blink its caret and won't
        // accept keystrokes inside this popup.
        val capturedWidth = widthPx
        val capturedHeight = heightPx
        val popupWindowInfo: androidx.compose.ui.platform.WindowInfo =
            object : androidx.compose.ui.platform.WindowInfo {
                override val isWindowFocused: Boolean = true
                override val containerSize: IntSize
                    get() = IntSize(this@TaoPopupRenderer.widthPx, this@TaoPopupRenderer.heightPx)
            }
        scene = CanvasLayersComposeScene(
            density = Density(scale),
            layoutDirection = LayoutDirection.Ltr,
            size = IntSize(capturedWidth, capturedHeight),
            coroutineContext = host.sceneCoroutineContext,
            platformContext = object : PlatformContext.Empty() {
                override val windowInfo: androidx.compose.ui.platform.WindowInfo
                    get() = popupWindowInfo
            },
            invalidate = { host.requestRedraw() },
        )
        PopupNativeBridge.nativeSetEventCallback(panelHandle, EventCallback())

        host.registerRenderer(rendererToken) { renderFrame() }
    }

    /** Mounts the popup content. Triggers a redraw. */
    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content)
        host.requestRedraw()
    }

    /**
     * Repositions / resizes the panel + inner scene. [xPx] and [yPx] are
     * parent-window pixels with a top-left origin (matching Compose's
     * `boundsInWindow`).
     */
    fun setBounds(xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) {
        if (panelHandle == 0L) return
        PopupNativeBridge.nativeSetFrameInWindow(
            panel = panelHandle,
            xPx = xPx,
            yPx = yPx,
            widthPx = widthPx,
            heightPx = heightPx,
        )
        if (widthPx == this.widthPx && heightPx == this.heightPx) return
        this.widthPx = widthPx
        this.heightPx = heightPx
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        host.requestRedraw()
    }

    /**
     * When `true`, AppKit routes pointer events past this panel to
     * whatever sits underneath it (typically a native subview like a
     * `WKWebView`). Used for paint-only "watermark"-style popups.
     * Default: `false` (the panel intercepts events normally).
     */
    fun setIgnoresMouseEvents(ignore: Boolean) {
        if (panelHandle == 0L) return
        PopupNativeBridge.nativeSetIgnoresMouseEvents(panelHandle, ignore)
    }

    /**
     * Toggles whether the panel can become the macOS *key* window. With
     * `becomesKeyOnlyIfNeeded = YES` (set in `popup_panel.m`), AppKit
     * grants key status only when a control inside the popup actually
     * needs keyboard focus (e.g. a `BasicTextField`).
     */
    fun setFocusable(focusable: Boolean) {
        if (panelHandle == 0L) return
        PopupNativeBridge.nativeSetFocusable(panelHandle, focusable)
    }

    /**
     * Enables region-based hit-testing on the underlying NSPanel. When
     * enabled, only points inside a rect registered via
     * [setInteractiveRegions] are intercepted; transparent areas pass
     * pointer events through to the host window (and thence to its
     * subviews — e.g. the `WKWebView` mounted via `NativeView`). Used
     * by `NativeView`'s overlay slot. Default `false`.
     */
    fun setRegionHitTestEnabled(enable: Boolean) {
        if (panelHandle == 0L) return
        PopupNativeBridge.nativeSetRegionHitTestEnabled(panelHandle, enable)
    }

    /**
     * Replaces the interactive-region list. Each rect is in **physical
     * pixels** with a top-left origin, panel-local. Effective only
     * while [setRegionHitTestEnabled] is true.
     */
    fun setInteractiveRegions(rectsPx: FloatArray, count: Int) {
        if (panelHandle == 0L) return
        PopupNativeBridge.nativeSetInteractiveRegions(panelHandle, rectsPx, count)
    }

    /** Tears down the panel + Metal pipeline + scene. Idempotent. */
    fun dispose() {
        if (panelHandle == 0L) return
        host.unregisterRenderer(rendererToken)
        PopupNativeBridge.nativeSetEventCallback(panelHandle, null)
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeMetalBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0
        }
        PopupNativeBridge.nativeRelease(panelHandle)
        panelHandle = 0
    }

    /** Called from the parent host's redraw cycle once per frame. */
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
            if (!presented) {
                NativeMetalBridge.nativePresent(attachmentHandle, frame.drawablePtr)
            }
        }
    }

    private companion object {
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

/**
 * Plumbing the popup renderer needs from its host scene. Provided by
 * `TaoComposeSceneHost` via [LocalTaoPopupHost].
 *
 * Threading: every call must run on the macOS main thread.
 */
interface TaoPopupHost {
    /** NSView pointer of the host window's content view. */
    val parentNsView: Long

    /** Backing-scale factor (logical→physical multiplier). */
    val scale: Float

    /**
     * Host window's content size in **physical pixels** at the time the
     * popup is created. Used as the inner scene's initial constraints so
     * Compose's `RootMeasurePolicy` (in `Popup.skiko.kt`) can measure the
     * popup content with non-zero constraints — the diagnostic from the
     * post-mortem says zero-sized constraints make the policy short-
     * circuit and `boundsInWindow` never updates.
     */
    val parentWindowSize: IntSize

    /** Coroutine context to feed the inner scene (parent context + frame clock + flushing dispatcher). */
    val sceneCoroutineContext: CoroutineContext

    /** Triggers a parent redraw; the parent's pump iterates registered popup renderers. */
    fun requestRedraw()

    /** Registers a popup-render callback called once per parent frame. */
    fun registerRenderer(token: Any, render: () -> Unit)

    /** Unregisters a previously registered callback. */
    fun unregisterRenderer(token: Any)

    /**
     * Registers a key handler called from `TaoComposeSceneHost.onKeyEvent`
     * **before** the main scene's key dispatch. Returning `true`
     * consumes the event (main scene won't see it). Used by overlay
     * scenes that need to swallow keystrokes when their NSView is the
     * first responder — Tao's macOS event pipeline intercepts keys
     * before they reach the AppKit responder chain, so overlays can't
     * receive `keyDown:` natively and must piggy-back on the host
     * forwarding instead.
     */
    fun registerKeyHandler(
        token: Any,
        handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
    )

    fun unregisterKeyHandler(token: Any)

    /**
     * Forwards a Compose `PointerIcon` change from an overlay scene to
     * the host window's cursor. [iconCode] is one of the
     * `TaoCursorIcon` constants (same encoding as
     * `NativeTaoBridge.nativeSetCursorIcon`). Tao's cursor is window-
     * scoped, so the main scene and overlay scene share it; whichever
     * scene last called this wins. In practice that's fine because
     * Compose invokes `setPointerIcon` reactively whenever pointer
     * hover state crosses an icon boundary.
     */
    fun setCursor(iconCode: Int)
}

/** CompositionLocal exposing the popup host to descendants of `DecoratedWindow`'s content. */
val LocalTaoPopupHost: androidx.compose.runtime.ProvidableCompositionLocal<TaoPopupHost?> =
    androidx.compose.runtime.compositionLocalOf { null }
