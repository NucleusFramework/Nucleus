package io.github.kdroidfilter.nucleus.window.tao.render

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoGlBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDecoBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoEventCode
import io.github.kdroidfilter.nucleus.window.tao.TaoModifierMask
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import kotlin.coroutines.CoroutineContext as KCoroutineContext

/**
 * Windows variant of [TaoComposeSceneHost]. Drives a Compose scene onto the
 * Tao-owned HWND via the WGL helper, with custom title-bar decoration applied
 * by [NativeTaoWindowsDecoBridge].
 *
 * Threading: every public method runs on the thread that owns the Tao event
 * loop (Windows imposes no main-thread constraint, but the GL context is bound
 * to whatever thread called `nativeAttach`, so all rendering must stay on it).
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneHostWindows(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) {
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    /** App-level pre-dispatch hook. See [TaoComposeSceneHost.previewKeyHandler]. */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /** App-level post-dispatch hook. See [TaoComposeSceneHost.keyHandler]. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Wired through [WindowsTaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    private val windowInfo = WindowsTaoWindowInfo()
    private var attachmentHandle: Long = 0
    private var hwnd: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val frameClock = BroadcastFrameClock()
    private val flushingDispatcher = FlushingMainDispatcher()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // Frame pacing is delegated to VSync — `wglSwapIntervalEXT(1)` makes
    // SwapBuffers block until the next display refresh, which keeps Compose
    // animations (smooth scroll, etc.) aligned on the display cadence.
    // No software throttle here: the Tao event loop wakes us via invalidate
    // → requestRedraw, and SwapBuffers caps the loop at 60Hz naturally.

    fun attach() {
        check(NativeTaoBridge.isLoaded && NativeTaoGlBridge.isLoaded && NativeTaoWindowsDecoBridge.isLoaded) {
            "Tao Windows native libraries not loaded"
        }
        hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        require(hwnd != 0L) { "HWND unavailable; window not yet realised" }

        // Install custom decoration (WndProc subclass + DwmExtendFrameIntoClientArea).
        // Title-bar height is set later — the value the TitleBar composable publishes
        // via SideEffect arrives after first composition.
        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f
        val initialTitleBarPx = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(28)
        NativeTaoWindowsDecoBridge.nativeInstallDecoration(hwnd, initialTitleBarPx)

        val handle = NativeTaoGlBridge.nativeAttach(hwnd)
        require(handle != 0L) { "Failed to create WGL context for HWND" }
        attachmentHandle = handle

        directContext = DirectContext.makeGL()

        val dndManager = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropManager(
            getRootNode = { scene!!.rootDragAndDropNode },
        )
        scene = CanvasLayersComposeScene(
            density = Density(scale),
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = coroutineContext + frameClock + flushingDispatcher,
            platformContext = WindowsTaoPlatformContext(
                windowHandle = window.handle,
                topInsetPx = { (titleBarHeightDpState.value * scale).toInt() },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
            ),
            invalidate = {
                window.requestRedraw()
            },
        )

        registerInboundDnD()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "windows DnD lib not loaded — inbound disabled"
            )
            return
        }
        val callback = InboundDnDCallback()
        val rc = io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.nativeRegister(hwnd, callback)
        io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log("RegisterDragDrop rc=$rc")
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback :
        io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.Callback {

        private fun rootNode() = scene?.rootDragAndDropNode

        private fun makeDragEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropPayload(
                files = files?.toList() ?: emptyList(),
            )
            val transferable = io.github.kdroidfilter.nucleus.window.tao.TaoFilesTransferable(
                files = payload.files.map { java.io.File(it) },
            )
            val native = io.github.kdroidfilter.nucleus.window.tao.TaoSyntheticDragEvent(
                cursorLocn = java.awt.Point(xPx, yPx),
                dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                backingTransferable = transferable,
                payload = payload,
            )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl = androidx.compose.ui.geometry.Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        private fun makeDropEvent(
            xPx: Int,
            yPx: Int,
            files: Array<String>?,
        ): androidx.compose.ui.draganddrop.DragAndDropEvent {
            val payload = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropPayload(
                files = files?.toList() ?: emptyList(),
            )
            val transferable = io.github.kdroidfilter.nucleus.window.tao.TaoFilesTransferable(
                files = payload.files.map { java.io.File(it) },
            )
            val native = io.github.kdroidfilter.nucleus.window.tao.TaoSyntheticDropEvent(
                cursorLocn = java.awt.Point(xPx, yPx),
                dropAction = java.awt.dnd.DnDConstants.ACTION_COPY,
                backingTransferable = transferable,
                payload = payload,
            )
            return androidx.compose.ui.draganddrop.DragAndDropEvent(
                action = androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy,
                nativeEvent = native,
                positionInRootImpl = androidx.compose.ui.geometry.Offset(xPx.toFloat(), yPx.toFloat()),
            )
        }

        override fun onDragEnter(hwnd: Long, x: Int, y: Int, keyState: Int, hasFiles: Boolean): Int {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "onDragEnter x=$x y=$y hasFiles=$hasFiles",
            )
            if (!hasFiles) {
                return io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            val accepted = node.acceptDragAndDropTransfer(ev)
            if (accepted) {
                node.onStarted(ev)
                node.onEntered(ev)
            }
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(hwnd: Long, x: Int, y: Int, keyState: Int, hasFiles: Boolean): Int {
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            node.onMoved(ev)
            return if (node.hasEligibleDropTarget) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragLeave(hwnd: Long) {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log("onDragLeave")
            val node = rootNode() ?: return
            val ev = makeDragEvent(-1, -1, null)
            node.onExited(ev)
            node.onEnded(ev)
        }

        override fun onDrop(hwnd: Long, x: Int, y: Int, keyState: Int, files: Array<String>?): Int {
            io.github.kdroidfilter.nucleus.window.tao.TaoDnDDiagnostics.log(
                "onDrop x=$x y=$y files=${files?.size ?: 0}",
            )
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            val ev = makeDropEvent(x, y, files)
            val accepted = node.onDrop(ev)
            node.onEnded(ev)
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content)
    }

    fun onResized(widthPxNew: Int, heightPxNew: Int) {
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        NativeTaoGlBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        // Re-publish title-bar height in physical pixels so the deco WndProc
        // keeps its hit-test caption zone in sync after a DPI change.
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(
            hwnd,
            (titleBarHeightDpState.value * scale).toInt(),
        )
        updateWindowInfoSize()
        window.requestRedraw()
    }

    fun onFocusChanged(focused: Boolean) {
        windowInfo.isWindowFocused = focused
    }

    private fun updateWindowInfoSize() {
        windowInfo.containerSize = IntSize(widthPx, heightPx)
        if (scale > 0f) {
            val dpW = (widthPx / scale)
            val dpH = (heightPx / scale)
            windowInfo.containerDpSize = DpSize(dpW.dp, dpH.dp)
        }
    }

    fun onRedrawRequested() {
        val ctx = directContext ?: return
        val sc = scene ?: return

        if (widthPx <= 0 || heightPx <= 0) return
        val now = System.nanoTime()

        // ── Frame clock ordering ──────────────────────────────────────────
        // Tick the frame clock BEFORE rendering and drain twice. Without this
        // the smooth-scroll animation (and any other `withFrameNanos`-driven
        // animation) lags one frame behind: `sendFrame` resumes the awaiting
        // continuations which then mutate state, but if we render first the
        // composition reads the *previous* frame's state. JNI / Skiko's
        // default loop ticks before render, so to match that feel we mirror
        // the order here.
        flushingDispatcher.drain()
        frameClock.sendFrame(now)
        flushingDispatcher.drain()

        // Make sure the WGL context is current on this thread (defensive — it
        // already was since `attach`, but other tools/tests can clear it).
        NativeTaoGlBridge.nativeMakeCurrent(attachmentHandle)

        // Wrap the default framebuffer (id 0). Skia's GL backend uses
        // BOTTOM_LEFT origin with the GL convention; SurfaceOrigin handles the
        // flip so Compose draws right-side up.
        val rt = BackendRenderTarget.makeGL(
            width = widthPx,
            height = heightPx,
            sampleCnt = 0,
            stencilBits = 8,
            fbId = 0,
            fbFormat = FramebufferFormat.GR_GL_RGBA8,
        )
        val surface = Surface.makeFromBackendRenderTarget(
            context = ctx,
            rt = rt,
            origin = SurfaceOrigin.BOTTOM_LEFT,
            colorFormat = SurfaceColorFormat.RGBA_8888,
            colorSpace = ColorSpace.sRGB,
        ) ?: run { rt.close(); return }

        try {
            surface.canvas.clear(0xFFFFFFFF.toInt())
            sc.render(surface.canvas.asComposeCanvas(), now)
            surface.flushAndSubmit(syncCpu = false)
            NativeTaoGlBridge.nativePresent(attachmentHandle)
        } finally {
            surface.close()
            rt.close()
        }
    }

    fun onPointerMove(aFixed: Int, bFixed: Int) {
        val xPx = aFixed / 1024f
        val yPx = bFixed / 1024f
        lastPointerX = xPx
        lastPointerY = yPx
        scene?.sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(xPx, yPx),
            type = PointerType.Mouse,
        )
    }

    fun onPointerExited() {
        scene?.sendPointerEvent(
            eventType = PointerEventType.Exit,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
        )
    }

    fun onPointerButton(buttonCode: Int, pressed: Boolean) {
        scene?.sendPointerEvent(
            eventType = if (pressed) PointerEventType.Press else PointerEventType.Release,
            position = Offset(lastPointerX, lastPointerY),
            type = PointerType.Mouse,
            button = mapButton(buttonCode),
        )
    }

    fun onPointerScroll(dxAwt: Float, dyAwt: Float) {
        scene?.sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset(lastPointerX, lastPointerY),
            scrollDelta = Offset(dxAwt, dyAwt),
            type = PointerType.Mouse,
        )
    }

    fun onKeyEvent(
        type: Int,
        vkCode: Int,
        keyLocation: Int,
        modifiers: Int,
        codePoint: Int,
    ): Boolean {
        val sc = scene ?: return false
        val isCtrl = (modifiers and TaoModifierMask.CONTROL) != 0
        val isMeta = (modifiers and TaoModifierMask.META) != 0
        val isAlt = (modifiers and TaoModifierMask.ALT) != 0
        val isShift = (modifiers and TaoModifierMask.SHIFT) != 0
        val composeEvent = when (type) {
            TaoEventCode.KEY_DOWN, TaoEventCode.KEY_UP -> {
                KeyEvent(
                    key = Key(nativeKeyCode = vkCode, nativeKeyLocation = keyLocation),
                    type = if (type == TaoEventCode.KEY_DOWN) KeyEventType.KeyDown else KeyEventType.KeyUp,
                    codePoint = codePoint,
                    isCtrlPressed = isCtrl,
                    isMetaPressed = isMeta,
                    isAltPressed = isAlt,
                    isShiftPressed = isShift,
                )
            }
            TaoEventCode.KEY_TYPED -> {
                val ch = codePoint.toChar()
                val awtModifiers = (if (isShift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
                    (if (isCtrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0) or
                    (if (isAlt) java.awt.event.InputEvent.ALT_DOWN_MASK else 0) or
                    (if (isMeta) java.awt.event.InputEvent.META_DOWN_MASK else 0)
                val awtEvent = java.awt.event.KeyEvent(
                    SyntheticEventSource,
                    java.awt.event.KeyEvent.KEY_TYPED,
                    System.currentTimeMillis(),
                    awtModifiers,
                    java.awt.event.KeyEvent.VK_UNDEFINED,
                    ch,
                    java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN,
                )
                KeyEvent(
                    key = Key(nativeKeyCode = 0, nativeKeyLocation = keyLocation),
                    type = KeyEventType.Unknown,
                    codePoint = codePoint,
                    isCtrlPressed = isCtrl,
                    isMetaPressed = isMeta,
                    isAltPressed = isAlt,
                    isShiftPressed = isShift,
                    nativeEvent = awtEvent,
                )
            }
            else -> return false
        }
        if (previewKeyHandler?.invoke(composeEvent) == true) return true
        if (sc.sendKeyEvent(composeEvent)) return true
        return keyHandler?.invoke(composeEvent) == true
    }

    /** Push the latest title-bar height (in dp) down to the deco WndProc so
     *  the caption hit-test zone matches the Compose layout. */
    fun syncTitleBarHeight() {
        if (hwnd == 0L) return
        val px = (titleBarHeightDpState.value * scale).toInt().coerceAtLeast(0)
        NativeTaoWindowsDecoBridge.nativeSetTitleBarHeight(hwnd, px)
    }

    fun setTitleBarBackgroundColor(argb: Int) {
        if (hwnd != 0L) NativeTaoWindowsDecoBridge.nativeSetBackgroundColor(hwnd, argb)
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    // Debounce a11y syncs so a burst of `onSemanticsChange` callbacks during
    // recomposition collapses into a single push at the next render tick.
    private var a11ySyncScheduled: Runnable? = null

    /**
     * Schedules [block] to run on the render thread "soon" — at the next
     * redraw. Used by the SemanticsObserver to coalesce per-recomposition
     * change notifications into one snapshot push per frame.
     */
    fun scheduleA11ySync(block: () -> Unit) {
        if (a11ySyncScheduled != null) return
        val r = Runnable {
            a11ySyncScheduled = null
            block()
        }
        a11ySyncScheduled = r
        flushingDispatcher.enqueue(r)
        window.requestRedraw()
    }

    fun detach() {
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeTaoGlBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
        if (hwnd != 0L) {
            if (io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.isLoaded) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoWindowsDndBridge.nativeRevoke(hwnd)
            }
            NativeTaoWindowsDecoBridge.nativeUninstallDecoration(hwnd)
            hwnd = 0L
        }
    }

    private companion object {
        private val SyntheticEventSource: java.awt.Component = javax.swing.JPanel()
    }

    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: KCoroutineContext, block: Runnable) {
            queue.add(block)
            window.requestRedraw()
        }

        fun enqueue(block: Runnable) {
            queue.add(block)
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
        }
    }

    private fun mapButton(code: Int): androidx.compose.ui.input.pointer.PointerButton {
        return when (code) {
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.LEFT ->
                androidx.compose.ui.input.pointer.PointerButton.Primary
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.RIGHT ->
                androidx.compose.ui.input.pointer.PointerButton.Secondary
            io.github.kdroidfilter.nucleus.window.tao.TaoMouseButton.MIDDLE ->
                androidx.compose.ui.input.pointer.PointerButton.Tertiary
            else -> androidx.compose.ui.input.pointer.PointerButton.Primary
        }
    }
}

internal class WindowsTaoWindowInfo : androidx.compose.ui.platform.WindowInfo {
    override var isWindowFocused: Boolean by androidx.compose.runtime.mutableStateOf(true)
    override var containerSize: IntSize by androidx.compose.runtime.mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by androidx.compose.runtime.mutableStateOf(DpSize.Zero)
}

@OptIn(InternalComposeUiApi::class)
private class WindowsTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
) : androidx.compose.ui.platform.PlatformContext.Empty() {

    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform.PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        NativeTaoBridge.nativeSetCursorIcon(
            windowHandle,
            mapPointerIcon(pointerIcon),
        )
    }

    private fun mapPointerIcon(icon: androidx.compose.ui.input.pointer.PointerIcon): Int {
        when {
            icon === androidx.compose.ui.input.pointer.PointerIcon.Default ->
                return io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.DEFAULT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Text ->
                return io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.TEXT
            icon === androidx.compose.ui.input.pointer.PointerIcon.Hand ->
                return io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.HAND
            icon === androidx.compose.ui.input.pointer.PointerIcon.Crosshair ->
                return io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.CROSSHAIR
        }
        return runCatching {
            val cursor = icon.javaClass.getMethod("getCursor").invoke(icon) as? java.awt.Cursor
            when (cursor?.type) {
                java.awt.Cursor.TEXT_CURSOR -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.TEXT
                java.awt.Cursor.HAND_CURSOR -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.HAND
                java.awt.Cursor.CROSSHAIR_CURSOR -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.CROSSHAIR
                java.awt.Cursor.WAIT_CURSOR -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.WAIT
                java.awt.Cursor.MOVE_CURSOR -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.MOVE
                java.awt.Cursor.E_RESIZE_CURSOR, java.awt.Cursor.W_RESIZE_CURSOR ->
                    io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.EW_RESIZE
                java.awt.Cursor.N_RESIZE_CURSOR, java.awt.Cursor.S_RESIZE_CURSOR ->
                    io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.NS_RESIZE
                java.awt.Cursor.NE_RESIZE_CURSOR, java.awt.Cursor.SW_RESIZE_CURSOR ->
                    io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.NESW_RESIZE
                java.awt.Cursor.NW_RESIZE_CURSOR, java.awt.Cursor.SE_RESIZE_CURSOR ->
                    io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.NWSE_RESIZE
                else -> io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.DEFAULT
            }
        }.getOrDefault(io.github.kdroidfilter.nucleus.window.tao.TaoCursorIcon.DEFAULT)
    }
}
