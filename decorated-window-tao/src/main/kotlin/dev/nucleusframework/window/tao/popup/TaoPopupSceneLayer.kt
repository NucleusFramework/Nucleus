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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeSceneLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.nucleusframework.window.tao.TaoCursorIcon
import dev.nucleusframework.window.tao.event.appKitWheelToAwtScrollEvent
import dev.nucleusframework.window.tao.event.dispatchAwtShapedScroll
import dev.nucleusframework.window.tao.event.dispatchNativeKeyEvent
import dev.nucleusframework.window.tao.event.toTaoCursorIconCode
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.PopupNativeBridge
import dev.nucleusframework.window.tao.ffi.TaoNativeWireFormat
import dev.nucleusframework.window.tao.scene.LocalTaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoMetalTextureHost
import dev.nucleusframework.window.tao.scene.TaoPlatformContextBase
import dev.nucleusframework.window.tao.scene.TaoRecordedSurface
import dev.nucleusframework.window.tao.scene.TaoSceneBundle
import dev.nucleusframework.window.tao.scene.canvasLayersSceneBundle
import dev.nucleusframework.window.tao.scene.catchExceptions
import dev.nucleusframework.window.tao.scene.recordSceneToPicture
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Rect

/**
 * `ComposeSceneLayer` implementation used by macOS overlay scenes to back
 * Compose `Popup` / `DropdownMenu` / `Tooltip` content with a borderless
 * transparent `NSPanel` child of the host `NSWindow`. The main window scene
 * intentionally does not use this path; its popups are rendered in the same
 * Compose target as the rest of the UI.
 *
 * **Three failure modes from the post-mortem are explicitly avoided:**
 *  1. *Render-driving model*: the layer registers a per-frame callback
 *     with [TaoPopupHost], so the host's `onRedrawRequested` pump fires
 *     [renderFrame] every parent frame — the inner scene is never starved.
 *  2. *Measurement chicken-and-egg*: the inner [CanvasLayersComposeScene]
 *     is constructed with `size = host.workAreaSize` (non-zero from the
 *     start) so Compose's `RootMeasurePolicy` measures the popup content
 *     with real constraints. Without this, content size collapses to 0
 *     and `boundsInWindow` never gets a non-trivial value. The screen
 *     work area (not the owner window's size) is used so a tall menu
 *     in a small floating window can lay out at full height instead of
 *     being artificially scrolled.
 *  3. *CompositionLocal propagation*: [setContent] wraps user content
 *     with `CompositionLocalProvider(_compositionLocalContext) { ... }`.
 *     Compose's popup framework sets `compositionLocalContext` *before*
 *     [setContent] (`Popup.skiko.kt: rememberComposeSceneLayer`), so by
 *     the time content composes the locals snapshot is valid. This is
 *     what makes `MaterialTheme.colorScheme` etc. flow into the popup
 *     content automatically.
 *
 * The frame pushed to the panel is clamped into the hosting display's work
 * area ([popupScreenClampOffset], #569) every time `boundsInWindow` changes.
 * Unlike the Windows and Linux layers there is no re-clamp on owner move: the
 * panel is an AppKit child window and rides along with the NSWindow, so a
 * window dragged past a screen edge with a popup already open takes it along
 * — the same thing AppKit's own menus avoid by closing on window move.
 *
 * `scrimColor` is not painted here: a dialog's scrim covers what lies *under*
 * the layer, so the owner window's scene paints every layer's scrim and each
 * layer paints the ones of the layers above it — see [PopupScrimRegistry].
 *
 * Threading: every method must run on the macOS main thread.
 */
@OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class TaoPopupSceneLayer(
    private val host: TaoPopupHost,
    initialDensity: Density,
    initialLayoutDirection: LayoutDirection,
    initialFocusable: Boolean,
    initialConsumePointerInputOutside: Boolean,
) : ComposeSceneLayer {
    private var _density = initialDensity
    private var _layoutDirection = initialLayoutDirection
    private var _focusable = initialFocusable
    private var _bounds: IntRect = IntRect.Zero
    private val scrimColorState: MutableState<Color?> = mutableStateOf(null)
    private var _compositionLocalContext: CompositionLocalContext? = null

    private val rendererToken: Any = Any()
    private var widthPx: Int = host.parentWindowSize.width.coerceAtLeast(1)
    private var heightPx: Int = host.parentWindowSize.height.coerceAtLeast(1)
    private val scale: Float = host.scale

    /**
     * Upper bound for the inner scene's layout constraints. The owner
     * window's size would clip popup content that legitimately extends
     * beyond it (a tall `DropdownMenu` in a small floating window) —
     * fed the screen work area instead so Compose lays out at full
     * height + the `Popup.PositionProvider` flips/clips at the screen
     * edge. Read once; not reactive (the popup is rebuilt on owner-move).
     */
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
     * The rectangle the panel covers, in scene coordinates: [_bounds] inflated
     * by [popupDrawBounds] so shadows and the dialog appearance animation are
     * not clipped at the layout edge. The panel's interactive region stays
     * [_bounds], so a click in the margin falls through to the parent window
     * and reaches the outside-click monitor like any other outside click.
     */
    private var drawBounds: IntRect = IntRect.Zero

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

    /**
     * Panel created at parent-window-size offscreen so the inner scene
     * has real layout constraints, while the user doesn't see a 1×1
     * artifact. Compose's `Popup` framework will write [boundsInWindow]
     * during the first measure pass, which repositions / resizes us.
     */
    private val panelHandle: Long =
        PopupNativeBridge
            .nativeCreatePanel(
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

    private var attachmentHandle: Long =
        NativeMetalBridge
            .nativeAttachOverlay(
                PopupNativeBridge.nativeContentNsView(panelHandle),
            ).also {
                require(it != 0L) { "Failed to attach popup CAMetalLayer" }
            }

    // Created on (and only ever used / closed on) the host's render thread —
    // Skia's Metal DirectContext is thread-affine. Safe to build here (blocking)
    // because popup construction runs inside the host's main-thread record pass,
    // when the render thread is idle.
    private val directContext: DirectContext =
        host.runOnRenderThread {
            DirectContext.makeMetal(
                NativeMetalBridge.nativeDevicePtr(attachmentHandle),
                NativeMetalBridge.nativeQueuePtr(attachmentHandle),
            )
        }

    /**
     * Set in [close] (main thread) before the surface's GPU resources are torn
     * down. Read on the render thread in [TaoRecordedSurface.isAlive] so a popup
     * dismissed between record and replay is skipped rather than replayed against
     * a closed [directContext] / freed attachment.
     */
    @Volatile
    private var disposed: Boolean = false

    /**
     * Handle for `TextureView`s composed inside this popup. The panel renders
     * through its own [directContext], so it must not inherit the window
     * scene's — a GPU image belongs to exactly one Skia context.
     */
    private var metalTextureHost: TaoMetalTextureHost? =
        object : TaoMetalTextureHost {
            override val metalDevicePtr: Long = NativeMetalBridge.nativeDevicePtr(attachmentHandle)
            override val directContext: DirectContext = this@TaoPopupSceneLayer.directContext

            override fun <T> runOnRenderThread(block: () -> T): T = host.runOnRenderThread(block)
        }

    /**
     * Inner scene at screen work-area size — see "measurement chicken-
     * and-egg" in the class doc. The CAMetalLayer is sized to [drawBounds]
     * (smaller); the scene is laid out in window coordinates
     * ([calculateLocalPosition] is the identity) and replayed into the
     * surface translated by `-drawBounds.topLeft`, the same model as the
     * Windows and Linux layers.
     *
     * Custom WindowInfo with `isWindowFocused = true`. Compose's
     * `BasicTextField` (and other focus-aware widgets) gate the visible
     * caret + keystroke handling on this flag — `PlatformContext.Empty`'s
     * default (false) leaves text fields uneditable. We always say "yes,
     * focused" because the popup, by virtue of being on screen via Compose
     * intent, is logically focused from the app's perspective.
     *
     * `containerSize` MUST be the work-area-sized [sceneLayoutSize], not the
     * owner window (or popup) size: `Popup.skiko.kt` composes its measure
     * policy inside this layer's scene and, with `clippingEnabled` (the
     * default), clamps the popup position into `LocalWindowInfo.containerSize`
     * (`clipPosition`). Reporting the owner window size here pins every popup
     * inside the window — the whole point of native popup layers is to escape
     * it. Same contract as [TaoPopupSceneLayerWindows]'s popupWindowInfo.
     */
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

                    // The panel's surface is per-pixel transparent, so dialog
                    // scrims must use the alpha-aware blend — same contract as
                    // Compose Desktop's `WindowComposeSceneLayer` (#559).
                    override val isWindowTransparent: Boolean get() = true

                    override fun setPointerIcon(pointerIcon: PointerIcon) {
                        host.setCursor(pointerIcon.toTaoCursorIconCode())
                    }
                },
            requestFrame = { host.requestRedraw() },
        ).apply {
            // Report through the owner window's channel — see [TaoPopupHost.exceptionHandler].
            exceptionHandler = host.exceptionHandler
            // Dim this popup under the dialogs stacked above it. The scene draws
            // at the panel's own top-left, so the visible surface is the origin
            // plus the drawable size.
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
     * Named inner class so GraalVM JNI reachability metadata can register
     * it explicitly. Anonymous-object subclasses of a JNI-accessed
     * interface aren't picked up by `GetMethodID` under native-image.
     *
     * Every method is guarded: these are AppKit callbacks into the panel's own
     * scene, not nested inside the owner window's guarded frame pass.
     */
    private inner class PopupEventCallback : PopupNativeBridge.EventCallback {
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
            precise: Boolean,
        ) = host.exceptionHandler.catchExceptions {
            val pos = scenePosition(x, y)
            innerScene.dispatchAwtShapedScroll(
                pos.x,
                pos.y,
                appKitWheelToAwtScrollEvent(dx, dy, precise, scale),
            )
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

    private inner class PopupOutsideListener : PopupNativeBridge.OutsideClickListener {
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
        NativeMetalBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        PopupNativeBridge.nativeSetEventCallback(panelHandle, PopupEventCallback())
        PopupNativeBridge.nativeSetRegionHitTestEnabled(panelHandle, true)
        host.registerRenderer(rendererToken) { recordSurface() }
        host.popupScrims.register(rendererToken) { scrimColorState.value }
    }

    // ── ComposeSceneLayer surface ──────────────────────────────────────

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
            if (!value.isEmpty) contentBounds = value
            updateNativeFrame()
            host.requestRedraw()
        }

    /**
     * Pushes the panel frame — [drawBounds], screen-clamped (#569).
     *
     * `boundsInWindow` is in the parent scene's coordinate system (top-left
     * origin). For host-window-rooted scenes [TaoPopupHost.coordinateOffset]
     * is zero; for `NativeView`'s overlay scene it is the overlay's own
     * position within the host NSWindow.
     *
     * The clamp is decided on the content, not the inflated surface: what must
     * stay on screen is the popup the user sees, and a shadow margin hanging
     * past the edge is what the in-scene layer does too. Only the panel's frame
     * moves — [_bounds] and [drawBounds] stay what Compose believes, which is
     * what the scene draws in and what [scenePosition] maps pointers back to.
     */
    private fun updateNativeFrame() {
        if (contentBounds.isEmpty || disposed) return
        drawBounds = popupDrawBounds(contentBounds, _density.density)
        val offset = host.coordinateOffset
        val contentInParent = contentBounds.translate(offset)
        val frameInParent = drawBounds.translate(offset)
        val geometry = host.popupScreenGeometry
        val clamp = popupScreenClampOffset(contentInParent, geometry)
        val w = drawBounds.width.coerceAtLeast(1)
        val h = drawBounds.height.coerceAtLeast(1)
        PopupNativeBridge.nativeSetFrameInWindow(
            panel = panelHandle,
            xPx = frameInParent.left + clamp.x,
            yPx = frameInParent.top + clamp.y,
            widthPx = w,
            heightPx = h,
        )
        // Only the content answers hit-tests; the inflated margin falls through
        // to the parent window — where the outside-click monitor picks it up.
        PopupNativeBridge.nativeSetInteractiveRegions(
            panelHandle,
            floatArrayOf(
                (contentBounds.left - drawBounds.left).toFloat(),
                (contentBounds.top - drawBounds.top).toFloat(),
                contentBounds.width.toFloat(),
                contentBounds.height.toFloat(),
            ),
            1,
        )
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
        // Resize the CAMetalLayer's drawable to match the surface. We DON'T
        // resize the inner scene — its size stays at work-area size so layout
        // has real constraints. Only the visible draw area follows [drawBounds].
        if (w != widthPx || h != heightPx) {
            widthPx = w
            heightPx = h
            NativeMetalBridge.nativeResize(attachmentHandle, w, h, scale)
        }
    }

    /** Panel-local physical px → inner-scene (parent-window) coordinates. */
    private fun scenePosition(
        x: Float,
        y: Float,
    ): Offset = Offset(x + drawBounds.left, y + drawBounds.top)

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
            PopupNativeBridge.nativeSetFocusable(panelHandle, value)
        }

    // Stored for the ComposeSceneLayer contract; the native popup panel handles
    // outside-click dismissal via its own NSEvent monitor, so this flag is not
    // consulted on the render path.
    override var consumePointerInputOutside: Boolean = initialConsumePointerInputOutside

    init {
        // Apply the initial focusable state (constructor sets the field
        // but the setter is not invoked from a constructor parameter).
        PopupNativeBridge.nativeSetFocusable(panelHandle, _focusable)
    }

    override fun close() {
        host.unregisterRenderer(rendererToken)
        host.onLayerClosed(this)
        host.popupScrims.unregister(rendererToken)
        // Mark disposed before any teardown so a surface already recorded this
        // frame is skipped at replay time (TaoRecordedSurface.isAlive).
        disposed = true
        // Drop callbacks before tearing the panel down so any in-flight
        // AppKit event doesn't deref a half-disposed scene.
        PopupNativeBridge.nativeUninstallOutsideClickMonitor(panelHandle)
        PopupNativeBridge.nativeSetEventCallback(panelHandle, null)
        host.setCursor(TaoCursorIcon.DEFAULT)
        sceneBundle.close()
        // Close the Skia context on its owning render thread. close() runs in
        // the host's main-thread record pass (Compose disposal), when the render
        // thread is idle, so this blocking hop returns immediately and can't race
        // an in-flight replay. nativeDetach / nativeRelease stay on the main
        // thread for the same reason — no replay is using this attachment now.
        // Drop the TextureView handle before the context it points at dies:
        // a late composition must not import onto a closed context.
        metalTextureHost = null
        host.runOnRenderThread { directContext.close() }
        // Zero out before freeing the C struct so any pending recorder still in
        // the host's snapshot iteration bails instead of dereferencing freed
        // memory. Compose can dispose a sibling popup (or this very popup) as a
        // side effect of an earlier popup's `innerScene.render`.
        val handle = attachmentHandle
        attachmentHandle = 0
        NativeMetalBridge.nativeDetach(handle)
        PopupNativeBridge.nativeRelease(panelHandle)
    }

    override fun setContent(
        @Suppress("UNUSED_PARAMETER") parentCompositionContext: CompositionContext,
        content: @Composable () -> Unit,
    ) {
        innerScene.setContent {
            // Replay parent locals snapshot so MaterialTheme et al. flow
            // into the popup content. Compose's popup framework writes
            // `compositionLocalContext` *before* this `setContent`, so
            // by the time we compose, `_compositionLocalContext` is the
            // freshest.
            val locals = _compositionLocalContext
            // Our texture host goes *inside* the replayed locals: those carry
            // the window scene's host, which would otherwise shadow ours.
            val body: @Composable () -> Unit = {
                CompositionLocalProvider(
                    LocalTaoMetalTextureHost provides metalTextureHost,
                    // Inside the replayed parent locals, and deliberately so
                    // (#569): `Popup.skiko.kt` reads `LocalWindowInfo` from
                    // *this* composition to size the box it flips and clips the
                    // popup inside. The replayed snapshot carries the owner
                    // window's WindowInfo, which would pin every popup to the
                    // window — the opposite of what native popup layers exist
                    // for. `popupWindowInfo` reports the work area, so Compose
                    // flips against a screen-sized box (still rooted at the
                    // window; the origin is what the clamp corrects).
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
        if (onOutsidePointerEvent != null) {
            PopupNativeBridge.nativeInstallOutsideClickMonitor(panelHandle, PopupOutsideListener())
        } else {
            PopupNativeBridge.nativeUninstallOutsideClickMonitor(panelHandle)
        }
    }

    // The scene is laid out in parent-window coordinates and translated at
    // replay time (see [recordSurface]), so the popup-local position is the
    // window position itself — same contract as the Windows and Linux layers.
    override fun calculateLocalPosition(positionInWindow: IntOffset): IntOffset = positionInWindow

    // ── Per-frame record — driven by host's record pass (main thread) ──────

    /**
     * Records the popup's inner scene into a [TaoRecordedSurface] on the main
     * thread; the host replays it on its render thread after the main scene.
     * Returns null to skip the frame (disposed / zero-size).
     */
    private fun recordSurface(): TaoRecordedSurface? {
        if (disposed) return null
        if (widthPx <= 0 || heightPx <= 0) return null
        if (attachmentHandle == 0L) return null
        syncSceneSize()
        // The scene is recorded in window coordinates and replayed translated
        // into the surface, which is rooted at [drawBounds].
        return TaoRecordedSurface(
            attachmentHandle = attachmentHandle,
            directContext = directContext,
            picture = recordSceneToPicture(sceneBundle, widthPx, heightPx, cullRect = popupPictureCullRect(drawBounds)),
            clearColor = 0x00000000,
            isAlive = { !disposed },
            pictureOffset = IntOffset(-drawBounds.left, -drawBounds.top),
        )
    }

    private companion object {
        // Far enough offscreen to be invisible on any reasonable monitor
        // setup, but still inside the integer range we hand to the bridge.
        private const val OFFSCREEN_OFFSET_PX: Int = 100_000
    }
}
