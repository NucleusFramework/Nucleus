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
import io.github.kdroidfilter.nucleus.window.tao.NativeTaoEglBridge
import io.github.kdroidfilter.nucleus.window.tao.TaoEventCode
import io.github.kdroidfilter.nucleus.window.tao.TaoModifierMask
import io.github.kdroidfilter.nucleus.window.tao.TaoWindow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.GLAssembledInterface
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.makeGLWithInterface
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

    /**
     * SemanticsOwnerListener installed when the host carries an a11y
     * controller. Forwarded through [LinuxTaoPlatformContext] so Compose's
     * BaseComposeScene picks it up. Set once before [attach].
     */
    var semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener? = null

    private val windowInfo = LinuxTaoWindowInfo()
    private var attachmentHandle: Long = 0
    private var directContext: DirectContext? = null
    private var scene: ComposeScene? = null
    private val frameClock = BroadcastFrameClock()
    private val flushingDispatcher = FlushingMainDispatcher()

    /**
     * Coalesces `window.requestRedraw()` to one outstanding redraw per frame.
     * Multiple Compose call sites trigger redraws (the scene's `invalidate`
     * lambda, the FlushingMainDispatcher, a11y schedules, resize/scale
     * handlers); without this gate they spam Tao's `draw_tx` channel and
     * we render at the dispatch rate (>1k/sec on continuous animations).
     * Reset at the start of [onRedrawRequested].
     */
    private val redrawPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun requestRedrawCoalesced() {
        if (redrawPending.compareAndSet(false, true)) {
            window.requestRedraw()
        }
    }

    /**
     * Vsync swap thread. Owns the EGL context only during the
     * `eglSwapBuffers` call, which on Wayland blocks waiting for the
     * compositor's frame callback (and on X11 for the next refresh).
     * Running it on a *separate* thread is what makes `eglSwapInterval(1)`
     * usable — the GTK main thread keeps draining `wl_display` events
     * while the swap thread is parked on the swap, so the frame callback
     * that unblocks the swap can actually arrive. Swapping on the GTK
     * thread (the original implementation) deadlocks Mesa on Wayland.
     *
     * Pacing is intrinsic: the main thread issues a render only after
     * `swapThread.waitForIdle()` returns, which happens at the display's
     * refresh rate. No software cap, no scheduled wake — the OS does it.
     */
    private var swapThread: SwapThread? = null

    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var scale: Float = 1f

    // Coalescing: `onResized`/`onScaleFactorChanged` arrive at 60–120 Hz during
    // a user drag. Doing the X11 round-trip (XResizeWindow + rounded-shape
    // XShape rebuild) on every event is what was deadlocking the NVIDIA driver
    // on Blackwell. We just stash the latest size+scale and let the next
    // `onRedrawRequested` apply them once before drawing.
    private var lastAppliedWidthPx: Int = -1
    private var lastAppliedHeightPx: Int = -1
    private var lastAppliedScale: Float = Float.NaN

    // Cache the Skia RT/Surface across frames — recreated only when the size
    // changes. Reallocating an FBO + GL surface every frame piles up driver
    // work that contributes to the resize-time GPU lockup.
    private var cachedRt: BackendRenderTarget? = null
    private var cachedSurface: Surface? = null

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
        check(NativeTaoBridge.isLoaded && NativeTaoEglBridge.isLoaded) {
            "Tao Linux native libraries not loaded"
        }
        // (kind, display, native_window) — see NativeTaoBridge.nativeLinuxHandles.
        //   kind=1 → Xlib  (`display` = X Display*, `native_window` = XID)
        //   kind=2 → Wayland (`display` = wl_display*, `native_window` = wl_surface*)
        // GDK auto-picks the backend: native Wayland on Wayland sessions,
        // X11 on X11 sessions or when NUCLEUS_TAO_LINUX_RENDERER=x11 forces
        // GDK_BACKEND=x11 (see lib.rs).
        val handles = NativeTaoBridge.nativeLinuxHandles(window.handle)
        require(handles != null && handles.size == 3 && handles[0].toInt() != 0) {
            "Linux window handles unavailable; window not yet realised"
        }
        val kind = handles[0].toInt()
        val display = handles[1]
        val nativeWin = handles[2]
        check(kind == 1 || kind == 2) {
            "Unsupported Tao window kind=$kind"
        }

        scale = NativeTaoBridge.nativeScaleFactor(window.handle) / 1000f

        // Initial buffer / child-window size. If we already know widthPx/heightPx
        // (post-Resized) pass those; the X11 helper otherwise queries the
        // parent via XGetWindowAttributes, the Wayland helper falls back to 1×1.
        val initialW = widthPx.coerceAtLeast(0)
        val initialH = heightPx.coerceAtLeast(0)

        attachmentHandle = when (kind) {
            1 -> {
                val h = NativeTaoEglBridge.nativeAttachX11(display, nativeWin, initialW, initialH)
                require(h != 0L) { "Failed to create EGL context for XID=$nativeWin" }
                h
            }
            2 -> {
                // Wayland: physical pixels (logical × scale). Native Wayland
                // attach via a wl_subsurface child of GTK's surface — see
                // nucleus_tao_egl.c. tao reports integer scale only;
                // wp_fractional_scale_v1 binding is a future commit.
                val physW = (initialW * scale).toInt().coerceAtLeast(1)
                val physH = (initialH * scale).toInt().coerceAtLeast(1)
                val h = NativeTaoEglBridge.nativeAttachWayland(display, nativeWin, physW, physH)
                require(h != 0L) {
                    "Failed to create EGL context for wl_surface=$nativeWin — libwayland-egl missing?"
                }
                h
            }
            else -> error("unreachable")
        }

        // 1 GrDirectContext per window, paired with its own EGL context (see
        // nucleus_tao_egl.c). Skia's intended ownership model: one direct
        // context exclusively drives one GL context, no FBO 0 ambiguity, no
        // manual GL-state reset between frames.
        //
        // We hand Skia an `eglGetProcAddress`-backed proc loader through
        // `GLAssembledInterface` — same trick Skiko uses for Angle on Windows.
        val fnPtr = NativeTaoEglBridge.nativeGetProcAddrFunctionPointer()
        require(fnPtr != 0L) {
            "NativeTaoEglBridge.nativeGetProcAddrFunctionPointer returned 0 — libEGL.so.1 missing?"
        }
        val iface = GLAssembledInterface.createFromNativePointers(0L, fnPtr)
        directContext = DirectContext.makeGLWithInterface(iface)

        // The native attach binds the EGL context to *this* thread (the GTK
        // main thread). Release it so the swap thread can take it for
        // `eglSwapBuffers`. We re-bind on the main thread for every render
        // pass via [bindContextForRender].
        NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
        swapThread = SwapThread(attachmentHandle).also { it.start() }

        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        val dndManager = io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropManager(
            getRootNode = { scene!!.rootDragAndDropNode },
            outboundLauncher = ::launchLinuxOutboundDrag,
        )
        scene = CanvasLayersComposeScene(
            density = Density(scale),
            layoutDirection = LayoutDirection.Ltr,
            coroutineContext = coroutineContext + frameClock + flushingDispatcher,
            platformContext = LinuxTaoPlatformContext(
                windowHandle = window.handle,
                topInsetPx = { (titleBarHeightDpState.value * scale).toInt() },
                windowInfo = windowInfo,
                semanticsOwnerListener = semanticsOwnerListener,
                dragAndDropManager = dndManager,
            ),
            invalidate = {
                requestRedrawCoalesced()
            },
        )

        registerInboundDnD()
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun launchLinuxOutboundDrag(
        request: io.github.kdroidfilter.nucleus.window.tao.TaoDragAndDropManager.OutboundRequest,
    ): androidx.compose.ui.draganddrop.DragAndDropTransferAction? {
        if (!io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.isLoaded) return null
        if (window.handle == 0L) return null

        val allowed = request.supportedActions.fold(0) { acc, action ->
            acc or when (action) {
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_MOVE
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link ->
                    io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_LINK
                else -> 0
            }
        }.let {
            if (it == 0) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                it
            }
        }

        val files = request.files
            .takeIf { it.isNotEmpty() }
            ?.map { it.absolutePath }
            ?.toTypedArray()
        val effect = io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.nativeStartDrag(
            handle = window.handle,
            files = files,
            text = request.text,
            allowedEffects = allowed,
        )
        return when (effect) {
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Copy
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_MOVE ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Move
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_LINK ->
                androidx.compose.ui.draganddrop.DragAndDropTransferAction.Link
            else -> null
        }
    }

    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun registerInboundDnD() {
        if (!io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.isLoaded) return
        val callback = InboundDnDCallback()
        io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge
            .nativeRegister(window.handle, callback)
    }

    /**
     * Named (non-anonymous) callback class so GraalVM JNI reachability metadata
     * can register it explicitly. Anonymous classes inheriting JNI-accessible
     * interface methods aren't picked up by `GetMethodID` under native-image.
     */
    @OptIn(InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    private inner class InboundDnDCallback :
        io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.Callback {
        private fun rootNode() = scene?.rootDragAndDropNode

        // Coordinates from native are already in physical pixels (post
        // `gtk_widget_translate_coordinates` × scale_factor on the Rust side),
        // so we feed them straight into Compose's positionInRoot.
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

        override fun onDragEnter(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int {
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            val accepted = node.acceptDragAndDropTransfer(ev)
            if (accepted) {
                node.onStarted(ev)
                node.onEntered(ev)
            }
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragOver(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            hasFiles: Boolean,
        ): Int {
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            val ev = makeDragEvent(x, y, null)
            node.onMoved(ev)
            return if (node.hasEligibleDropTarget) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }
        }

        override fun onDragLeave(handle: Long) {
            val node = rootNode() ?: return
            val ev = makeDragEvent(-1, -1, null)
            node.onExited(ev)
            node.onEnded(ev)
        }

        override fun onDrop(
            handle: Long,
            x: Int,
            y: Int,
            modState: Int,
            files: Array<String>?,
        ): Int {
            val node = rootNode()
                ?: return io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            val ev = makeDropEvent(x, y, files)
            val accepted = node.onDrop(ev)
            node.onEnded(ev)
            return if (accepted) {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_COPY
            } else {
                io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.DROP_EFFECT_NONE
            }
        }
    }

    /** Current scale factor (logical→physical multiplier). */
    fun density(): Float = scale

    // Debounce a11y syncs so a burst of `onSemanticsChange` callbacks during
    // recomposition collapses into a single push at the next render tick.
    private var a11ySyncScheduled: Runnable? = null

    /**
     * Schedules [block] to run on the GTK main thread "soon" — at the next
     * redraw. Used by the SemanticsObserver to coalesce per-recomposition
     * change notifications into one snapshot push per frame, mirroring the
     * macOS / Windows behavior.
     */
    fun scheduleA11ySync(block: () -> Unit) {
        if (a11ySyncScheduled != null) return
        val r = Runnable {
            a11ySyncScheduled = null
            block()
        }
        a11ySyncScheduled = r
        flushingDispatcher.enqueue(r)
        requestRedrawCoalesced()
    }

    fun setContent(content: @Composable () -> Unit) {
        scene?.setContent(content)
    }

    fun onResized(widthPxNew: Int, heightPxNew: Int) {
        if (widthPxNew == widthPx && heightPxNew == heightPx) return
        widthPx = widthPxNew
        heightPx = heightPxNew
        // Cheap state updates run inline so external callers (`applyRoundedShape`,
        // composables reading `WindowInfo.containerSize`) see the new size
        // immediately. The X11/GLX side is deferred to `onRedrawRequested` —
        // see `applyPendingNativeResize`.
        scene?.size = IntSize(widthPx, heightPx)
        updateWindowInfoSize()
        requestRedrawCoalesced()
    }

    /**
     * Applies (or clears) the rounded-rectangle XShape on the GL surface.
     * Called on every resize and any time the maximized/fullscreen flag may
     * have changed. Mirrors `decorated-window-core/DecoratedWindowCore.kt`'s
     * `updateWindowShape()`: rectangular when the window fills the screen,
     * rounded otherwise.
     */
    fun onScaleFactorChanged(newScale: Float) {
        if (newScale == scale) return
        scale = newScale
        scene?.density = Density(scale)
        updateWindowInfoSize()
        requestRedrawCoalesced()
    }

    /**
     * Pushes the current `widthPx`/`heightPx`/`scale` to the GLX child window
     * + rounded-shape + Skia surface cache, but only if any of them has
     * changed since the last apply. Called from [onRedrawRequested] so a
     * burst of resize events collapses to one X11 round-trip per actual frame.
     */
    private fun applyPendingNativeResize() {
        if (attachmentHandle == 0L) return
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == lastAppliedWidthPx &&
            heightPx == lastAppliedHeightPx &&
            scale == lastAppliedScale
        ) {
            return
        }
        NativeTaoEglBridge.nativeResize(attachmentHandle, widthPx, heightPx, scale)
        // Drop the cached Skia surface so the next render rebuilds it at the
        // new size. Closing here (rather than lazily in `onRedrawRequested`)
        // keeps the lifetime tied to the GL context being current.
        if (widthPx != lastAppliedWidthPx || heightPx != lastAppliedHeightPx) {
            cachedSurface?.close()
            cachedSurface = null
            cachedRt?.close()
            cachedRt = null
        }
        lastAppliedWidthPx = widthPx
        lastAppliedHeightPx = heightPx
        lastAppliedScale = scale
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
        // Open the redraw gate first thing: any invalidation triggered while
        // we're in this method (state writes inside scene.render, animation
        // continuations resuming under sendFrame, observers firing during
        // sendApplyNotifications) can re-arm a redraw for the next tick.
        // Resetting *after* the early-return below would leave the gate
        // armed permanently if we skip this frame, and Compose would never
        // be able to schedule another redraw — i.e. the app would freeze.
        redrawPending.set(false)

        // Wait for the previous frame's `eglSwapBuffers` to complete on the
        // swap thread before issuing the next render. This is what gives us
        // hardware vsync without melting CPU: the swap thread parks in
        // `eglSwapBuffers` until the compositor signals it can present
        // (16.7 ms on a 60 Hz display, 6.9 ms on a 144 Hz display, etc.),
        // and only then releases the EGL context back to us.
        //
        // If the swap is still in flight after the timeout (occluded /
        // minimised window — Wayland compositors stop sending frame
        // callbacks in that state), skip this redraw. Compose's
        // invalidation machinery will naturally re-arm via
        // [requestRedrawCoalesced] when there's actual work; binding the
        // context now would race the swap thread.
        val st = swapThread
        if (st != null && !st.waitForIdle()) {
            return
        }
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

        NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
        // Coalesced size/scale change is committed here, after the GL context
        // is current — applyPendingNativeResize closes the stale Skia cache.
        applyPendingNativeResize()

        var surface = cachedSurface
        if (surface == null) {
            val rt = BackendRenderTarget.makeGL(
                width = widthPx,
                height = heightPx,
                sampleCnt = 0,
                stencilBits = 8,
                fbId = 0,
                fbFormat = FramebufferFormat.GR_GL_RGBA8,
            )
            surface = Surface.makeFromBackendRenderTarget(
                context = ctx,
                rt = rt,
                origin = SurfaceOrigin.BOTTOM_LEFT,
                colorFormat = SurfaceColorFormat.RGBA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: run {
                rt.close()
                NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
                return
            }
            cachedRt = rt
            cachedSurface = surface
        }

        // Clear to fully transparent so the carve pass below can zero out the
        // alpha at the four rounded-corner cut-outs without leaving theme
        // background pixels behind.
        surface.canvas.clear(0x00000000)
        sc.render(surface.canvas.asComposeCanvas(), now)
        if (cornerRadiusPx > 0 && !window.isMaximized && !window.isFullscreen) {
            carveRoundedCorners(surface.canvas, widthPx, heightPx, cornerRadiusPx)
        }
        // Submit GL commands to the driver from this thread, then release
        // the context so the swap thread can pick it up and call
        // `eglSwapBuffers` (which blocks for vsync).
        surface.flushAndSubmit(syncCpu = false)
        NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
        swapThread?.requestSwap()
    }

    /**
     * Alpha-blended rounded-corner clip. Paints the four corner cut-outs with
     * `BlendMode.CLEAR` (destination alpha → 0) so the compositor blends the
     * content behind those pixels — works uniformly on X11 and Wayland (no
     * XShape needed).
     *
     * The path is `full_rect XOR rounded_rect` via `EVEN_ODD` fill, which
     * yields exactly the four corner pieces; AA at the rounded edge stays in
     * the destination, only the strictly-outside pixels are zeroed.
     */
    private fun carveRoundedCorners(canvas: Canvas, w: Int, h: Int, radius: Int) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val rf = radius.toFloat()
        // Skia m144 (skiko 0.144+) made the mutating `Path.addRect` /
        // `addRRect` deprecated in favour of `PathBuilder`; build the
        // carve mask there and snapshot to an immutable Path for drawing.
        val frame = PathBuilder()
            .setFillType(PathFillMode.EVEN_ODD)
            .addRect(Rect.makeXYWH(0f, 0f, wf, hf))
            .addRRect(RRect.makeXYWH(0f, 0f, wf, hf, rf))
            .snapshot()
        val paint = Paint().apply {
            blendMode = BlendMode.CLEAR
            isAntiAlias = true
        }
        canvas.drawPath(frame, paint)
        frame.close()
        paint.close()
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
        if (io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge.isLoaded &&
            window.handle != 0L
        ) {
            io.github.kdroidfilter.nucleus.window.tao.NativeTaoLinuxDndBridge
                .nativeRevoke(window.handle)
        }
        // Stop the swap thread first. It may be parked inside
        // `eglSwapBuffers` waiting on a frame callback — joining without a
        // wakeup would hang. `shutdownAndJoin` requests shutdown before
        // signalling, and the swap thread bails out once the current swap
        // (if any) returns. After it joins, no other thread holds the EGL
        // context, so we can safely re-bind here for Skia teardown.
        swapThread?.shutdownAndJoin()
        swapThread = null

        // Re-bind THIS window's EGL context before tearing down Skia. The
        // GPU-resource releases that follow (glDeleteFramebuffers /
        // glDeleteTextures inside Surface.close + DirectContext.close) reach
        // GL through the `GrGLInterface` function pointers we resolved via
        // eglGetProcAddress; those pointers expect *some* valid context to
        // be current, and on a multi-window app (main + popup/dialog) the
        // currently-current context may belong to another window or be
        // unbound altogether — leading to a segfault deep inside the
        // driver. Making the local context current first guarantees the
        // releases land on the right resources.
        if (attachmentHandle != 0L) {
            NativeTaoEglBridge.nativeMakeCurrent(attachmentHandle)
        }
        cachedSurface?.close()
        cachedSurface = null
        cachedRt?.close()
        cachedRt = null
        scene?.close()
        scene = null
        directContext?.close()
        directContext = null
        if (attachmentHandle != 0L) {
            NativeTaoEglBridge.nativeReleaseCurrent(attachmentHandle)
            NativeTaoEglBridge.nativeDetach(attachmentHandle)
            attachmentHandle = 0L
        }
    }

    private companion object {
        private val SyntheticEventSource: java.awt.Component = javax.swing.JPanel()
    }

    /**
     * Owns the EGL context during `eglSwapBuffers`. The render thread (GTK
     * main thread) hands the context off via
     * [NativeTaoEglBridge.nativeReleaseCurrent] before signalling
     * [requestSwap]; the swap thread then re-binds via `nativeMakeCurrent`,
     * presents (blocking on the compositor's vsync), and releases the
     * context again. The render thread synchronises through
     * [waitForIdle] before its next render — that's what gives us
     * hardware-vsync pacing for free.
     *
     * The two threads never hold the context simultaneously: the render
     * thread always releases before `requestSwap`, the swap thread waits
     * on the work signal before binding, releases before signalling done.
     */
    private inner class SwapThread(
        private val handle: Long,
    ) : Thread("TaoSwapThread-${java.lang.Long.toHexString(handle)}") {
        private val lock = ReentrantLock()
        private val workCond = lock.newCondition()
        private val idleCond = lock.newCondition()
        private var swapPending = false
        private var swapping = false
        private var shutdown = false

        init { isDaemon = true }

        /** Called on the GTK main thread after `flushAndSubmit` + release. */
        fun requestSwap() {
            lock.withLock {
                swapPending = true
                workCond.signal()
            }
        }

        /**
         * Called on the GTK main thread at the start of the next render
         * cycle. Blocks until the swap thread has finished any in-flight
         * `eglSwapBuffers` and released the EGL context, or the timeout
         * elapses. Returns `true` if the context is free to bind, `false`
         * if the swap is still in flight after the timeout (the caller
         * must then *skip* binding to avoid two threads holding the EGL
         * context simultaneously — undefined behaviour on every driver).
         *
         * The timeout matters in practice: when a Wayland window is
         * occluded or minimised, the compositor stops sending frame
         * callbacks and `eglSwapBuffers` parks indefinitely. Without a
         * timeout the GTK main thread would freeze, taking input handling
         * with it. 100 ms = ~6 vsync periods at 60 Hz, comfortably above
         * any plausible normal swap latency.
         */
        fun waitForIdle(timeoutMs: Long = 100): Boolean {
            lock.withLock {
                if (!swapPending && !swapping) return true
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (swapPending || swapping) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    idleCond.awaitNanos(remaining)
                }
                return true
            }
        }

        fun shutdownAndJoin() {
            lock.withLock {
                shutdown = true
                workCond.signalAll()
            }
            // Best-effort join. If the swap thread is parked inside
            // `eglSwapBuffers` (waiting on a frame callback that GTK is
            // about to deliver), the join can take up to one vsync
            // interval. Two frames worth of headroom is plenty in
            // practice — past that, leak the thread rather than risk
            // hanging the host shutdown.
            join(50)
        }

        override fun run() {
            try {
                while (true) {
                    val doSwap = lock.withLock {
                        while (!shutdown && !swapPending) workCond.await()
                        if (shutdown) return
                        swapPending = false
                        swapping = true
                        true
                    }
                    if (doSwap) {
                        try {
                            NativeTaoEglBridge.nativeMakeCurrent(handle)
                            NativeTaoEglBridge.nativePresent(handle)
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        } finally {
                            try {
                                NativeTaoEglBridge.nativeReleaseCurrent(handle)
                            } catch (_: Throwable) {
                                // Detached underneath us; the host's
                                // detach() handles cleanup.
                            }
                            lock.withLock {
                                swapping = false
                                idleCond.signalAll()
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private inner class FlushingMainDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: KCoroutineContext, block: Runnable) {
            queue.add(block)
            requestRedrawCoalesced()
        }

        /** Same effect as `dispatch` but skips the no-op coroutine context. */
        fun enqueue(block: Runnable) {
            queue.add(block)
            requestRedrawCoalesced()
        }

        fun drain() {
            var remaining = queue.size
            while (remaining-- > 0) {
                val runnable = queue.poll() ?: break
                runnable.run()
            }
            if (!queue.isEmpty()) {
                requestRedrawCoalesced()
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
    override val semanticsOwnerListener: androidx.compose.ui.platform.PlatformContext.SemanticsOwnerListener?,
    override val dragAndDropManager: androidx.compose.ui.platform.PlatformDragAndDropManager,
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
