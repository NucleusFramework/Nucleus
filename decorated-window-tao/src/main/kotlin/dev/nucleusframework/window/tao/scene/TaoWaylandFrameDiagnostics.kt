package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.unit.IntSize
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-frame record of the three sizes a Wayland resize keeps disagreeing about
 * (#444), the seam the headful suite asserts the contract through.
 *
 * On Wayland the buffer behind the default framebuffer is not the one we last
 * asked for: `wl_egl_window_resize` only records a *pending* size, and the
 * reallocation happens inside the next `eglSwapBuffers`. Skia's render target
 * wraps that framebuffer (`fbId = 0`) with a size of our choosing, so if the
 * two disagree under [org.jetbrains.skia.SurfaceOrigin.BOTTOM_LEFT] the frame
 * lands off the top of the real drawable by the difference — a band of clear
 * colour along one edge, which is what the issue sees flicker during a drag.
 *
 * [attachedPx] is the authoritative answer (`wl_egl_window_get_attached_size`,
 * libwayland-egl's own record of the buffer the compositor holds); [paintPx]
 * is what Skia was told. Any frame where they disagree is the defect, whether
 * or not the eye caught it.
 *
 * Off by default: [recording] is flipped on by a test around the gesture it
 * measures. Plain writes on the frame path, not snapshot state — same shape as
 * [TaoPresentDiagnostics].
 */
internal object TaoWaylandFrameDiagnostics {
    /** One render pass: what the window measured, what Skia painted, what the buffer was. */
    internal data class Frame(
        val nanos: Long,
        val windowPx: IntSize,
        val paintPx: IntSize,
        /** `wl_egl_window_get_attached_size`, or [IntSize.Zero] on X11 / before the first swap. */
        val attachedPx: IntSize,
        /**
         * `eglQuerySurface(EGL_WIDTH/EGL_HEIGHT)` sampled **before** the frame is
         * drawn: the size of the back buffer the GL commands are about to land
         * in. This is the size the render target must agree with.
         */
        val queriedPx: IntSize,
        /**
         * The same query sampled **after** the frame was flushed, which says
         * whether the pending `wl_egl_window_resize` was applied mid-frame —
         * if it were, agreeing with [queriedPx] up front would not be enough.
         */
        val queriedAfterPx: IntSize,
        /** The size last handed to `wl_egl_window_resize`. */
        val requestedPx: IntSize,
    ) {
        /** Rows by which the painted frame overshoots the buffer it lands in. */
        val heightDelta: Int get() = paintPx.height - queriedPx.height

        /** Columns by which the painted frame overshoots the buffer it lands in. */
        val widthDelta: Int get() = paintPx.width - queriedPx.width

        /** Whether the buffer was reallocated between the start and the end of this frame. */
        val reallocatedMidFrame: Boolean get() = queriedAfterPx != queriedPx
    }

    @Volatile
    private var recording = false

    private val frames = CopyOnWriteArrayList<Frame>()

    /** Starts a fresh recording; any frames from an earlier one are dropped. */
    fun start() {
        frames.clear()
        renderPasses = 0
        skipped = 0
        recording = true
    }

    /** Stops recording and returns everything captured since [start]. */
    fun stop(): List<Frame> {
        recording = false
        return frames.toList()
    }

    /** Render passes that reached the probe since [start]. */
    @Volatile
    var renderPasses: Int = 0
        private set

    /**
     * Render passes dropped since [start] because a swap was still in flight.
     *
     * A Wayland surface the compositor treats as occluded stops receiving frame
     * callbacks, so `eglSwapBuffers` never returns and every pass lands here:
     * the window renders nothing at all. Without this number a case that
     * measured nothing is indistinguishable from a window that never resized,
     * and both look like a pass.
     */
    @Volatile
    var skipped: Int = 0
        private set

    fun noteSkipped() {
        skipped++
    }

    fun record(frame: () -> Frame) {
        renderPasses++
        if (!recording) return
        frames += frame()
    }

    /** Whether a recording is armed — lets the frame path skip the closing sample too. */
    val isRecording: Boolean get() = recording

    /** Replaces the last recorded frame, once its closing sample is known. */
    fun completeLast(update: (Frame) -> Frame) {
        if (!recording) return
        val index = frames.lastIndex
        if (index >= 0) frames[index] = update(frames[index])
    }
}
