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
import io.github.kdroidfilter.nucleus.core.runtime.LinuxDesktopEnvironment
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoBridge
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoGlxBridge
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
 * Linux variant of [TaoComposeSceneHost]. Drives a Compose scene onto the
 * Tao-owned GTK window via the EGL helper. Works on both X11 and Wayland — the
 * helper picks the right `EGLNativeWindowType` (Xlib XID vs `wl_egl_window`)
 * from the (kind, display, native_window) triple resolved at attach time.
 *
 * Threading: every public method runs on the thread that owns the Tao event
 * loop. EGL contexts are per-thread, so all rendering must stay there.
 *
 * Tao on Linux always paints native window decorations through GTK; we
 * therefore leave `decorations = true` on this backend (see [DecoratedWindow]).
 * The user's [TitleBar] composable still works as a sub-bar inside the content
 * area — same shape as the macOS path before custom-chrome was added.
 */
@OptIn(InternalComposeUiApi::class)
internal class TaoComposeSceneHostLinux(
    private val window: TaoWindow,
    private val coroutineContext: CoroutineContext = EmptyCoroutineContext,
) {
    val titleBarHeightDpState: androidx.compose.runtime.MutableState<Float> =
        androidx.compose.runtime.mutableStateOf(0f)

    /** App-level pre-dispatch hook. See [TaoComposeSceneHost.previewKeyHandler]. */
    var previewKeyHandler: ((KeyEvent) -> Boolean)? = null

    /** App-level post-dispatch hook. See [TaoComposeSceneHost.keyHandler]. */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    private val windowInfo = LinuxTaoWindowInfo()
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val frameClock = BroadcastFrameClock()
    private val flushingDispatcher = FlushingMainDispatcher()

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    private var lastPointerX: Float = 0f
    private var lastPointerY: Float = 0f

    // Corner-radius mirrors `decorated-window-core/DecoratedWindowCore.kt`'s
    // `RoundRectangle2D.Float(0, 0, w, h, gnomeCornerArc, gnomeCornerArc)` —
    // RoundRectangle2D's `arcw`/`arch` arguments are the full arc *width*
    // (= 2 × radius), not the radius itself. So `gnomeCornerArc = 24f` paints
    // a 12 px radius, and `kdeCornerArc = 10f` paints a 5 px radius. Values
    // are physical pixels regardless of scale, matching the AWT path.
    private val cornerRadiusPx: Int = when (LinuxDesktopEnvironment.Current) {
        LinuxDesktopEnvironment.Gnome -> 12
        LinuxDesktopEnvironment.KDE -> 5
        else -> 0
    }

    fun attach() {
        check(NativeTaoBridge.isLoaded && NativeTaoGlxBridge.isLoaded) {
            "Tao Linux native libraries not loaded"
        }
        // (kind, display, native_window) — see NativeTaoBridge.nativeLinuxHandles.
        // GLX only — kind must be 1 (Xlib). Wayland sessions are forced to
        // X11 via GDK_BACKEND in `taoApplication`, so we never see kind=2 here.
        val handles = NativeTaoBridge.nativeLinuxHandles(window.handle)
        require(handles != null && handles.size == 3 && handles[0].toInt() != 0) {
            "Linux window handles unavailable; window not yet realised"
        }
        check(handles[0].toInt() == 1) {
            "Tao window is not on X11 (kind=${handles[0]}). " +
                "Set GDK_BACKEND=x11 — Skiko's GL backend requires GLX."
        }
        val display = handles[1]
        val xid = handles[2]

        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f

        // Initial GL child-window size if the GTK visual isn't GL-compatible.
        // If we already know widthPx/heightPx (post-Resized), pass those; the
        // helper otherwise queries the parent via XGetWindowAttributes.
        val initialW = widthPx.coerceAtLeast(0)
        val initialH = heightPx.coerceAtLeast(0)

        val handle = NativeTaoGlxBridge.nativeAttach(display, xid, initialW, initialH)
        require(handle != 0L) { "Failed to create GLX context for XID=$xid" }
        attachmentHandle = handle

        directContext = DirectContext.makeGL()

        scene = CanvasLayersComposeScene(
            density = Density(scale),
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = coroutineContext + frameClock + flushingDispatcher,
            platformContext = LinuxTaoPlatformContext(
                windowHandle = window.handle,
                topInsetPx = { (titleBarHeightDpState.value * scale).toInt() },
                windowInfo = windowInfo,
            ),
            invalidate = {
                window.requestRedraw()
            },
        )
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content)
    }

    fun onResized(widthPxNew: Int, heightPxNew: Int) {
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        if (attachmentHandle != 0L) {
            NativeTaoGlxBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
            applyRoundedShape()
        }
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        window.requestRedraw()
    }

    /**
     * Applies (or clears) the rounded-rectangle XShape on the GL surface.
     * Called on every resize and any time the maximized/fullscreen flag may
     * have changed. Mirrors `decorated-window-core/DecoratedWindowCore.kt`'s
     * `updateWindowShape()`: rectangular when the window fills the screen,
     * rounded otherwise.
     */
    fun applyRoundedShape() {
        if (attachmentHandle == 0L || cornerRadiusPx <= 0) return
        if (widthPx <= 0 || heightPx <= 0) return
        val isMaxOrFull = window.isMaximized || window.isFullscreen
        val radius = if (isMaxOrFull) 0 else cornerRadiusPx
        NativeTaoGlxBridge.nativeSetRoundedShape(attachmentHandle, widthPx, heightPx, radius)
    }

    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        if (attachmentHandle != 0L) {
            NativeTaoGlxBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
            applyRoundedShape()
        }
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

        // Same frame-clock ordering as the Windows path: tick before render so
        // `withFrameNanos`-driven animations apply on the current frame instead
        // of lagging by one.
        flushingDispatcher.drain()
        frameClock.sendFrame(now)
        flushingDispatcher.drain()

        NativeTaoGlxBridge.nativeMakeCurrent(attachmentHandle)

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
            NativeTaoGlxBridge.nativePresent(attachmentHandle)
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
        // ⚠️ Don't dispatch PointerEventType.Exit here on Linux.
        //
        // tao's GTK backend turns every `leave-notify` GDK event into a
        // CursorLeft event — including the "virtual" leaves GTK fires every
        // time the pointer crosses an internal sub-widget boundary, even
        // though the pointer is still over the same logical window. Forwarding
        // those as Exit invalidates Compose's hover state, so Compose
        // re-Enters on the next Move and we get oscillating PointerIcon
        // updates whose visible effect is "the I-beam only flashes for one
        // pixel as you cross widget seams".
        //
        // Compose's hit-test on Move is enough to track hover state cleanly;
        // when the pointer truly leaves the OS window, no further Move events
        // are sent and the hover modifier naturally stays inactive.
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

    fun detach() {
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeTaoGlxBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
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

internal class LinuxTaoWindowInfo : androidx.compose.ui.platform.WindowInfo {
    override var isWindowFocused: Boolean by androidx.compose.runtime.mutableStateOf(true)
    override var containerSize: IntSize by androidx.compose.runtime.mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by androidx.compose.runtime.mutableStateOf(DpSize.Zero)
}

@OptIn(InternalComposeUiApi::class)
private class LinuxTaoPlatformContext(
    private val windowHandle: Long,
    private val topInsetPx: () -> Int,
    override val windowInfo: androidx.compose.ui.platform.WindowInfo,
) : androidx.compose.ui.platform.PlatformContext.Empty() {

    override val windowInsets: androidx.compose.ui.platform.PlatformWindowInsets =
        object : androidx.compose.ui.platform.PlatformWindowInsets {
            override val systemBars: androidx.compose.ui.platform.PlatformInsets =
                androidx.compose.ui.platform.PlatformInsets(getTop = topInsetPx)
            override val captionBar: androidx.compose.ui.platform.PlatformInsets get() = systemBars
        }

    override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) {
        // The Rust side maps the code to a freedesktop cursor name and goes
        // through `gdk_window_set_device_cursor` for every master pointer of
        // the seat — required because GTK 3 manages cursors via XInput 2's
        // per-device table, which masks legacy `XDefineCursor`.
        NativeTaoBridge.nativeSetCursorIcon(windowHandle, mapPointerIcon(pointerIcon))
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
