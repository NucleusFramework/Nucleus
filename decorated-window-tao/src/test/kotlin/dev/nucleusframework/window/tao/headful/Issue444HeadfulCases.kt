package dev.nucleusframework.window.tao.headful

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.scene.TaoWaylandFrameDiagnostics
import kotlinx.coroutines.delay

/**
 * #444 — on native Wayland the content detaches from the window while an edge
 * is dragged.
 *
 * The defect these cases gate is the geometric one: Skia is handed a render
 * target wrapping the default framebuffer (`fbId = 0`) at a size of *our*
 * choosing, while the buffer behind that framebuffer is only reallocated
 * inside `eglSwapBuffers`. Under `SurfaceOrigin.BOTTOM_LEFT` a paint that
 * overstates the height by N lands N rows off the top of the real drawable,
 * leaving a band of clear colour along one edge — the flicker the issue's
 * frame-by-frame analysis measured on ~41 % of frames.
 *
 * The measurement does not depend on the eye, on the compositor's frame clock
 * or on a GPU: [TaoWaylandFrameDiagnostics] records, per render pass, the size
 * Skia was told against `wl_egl_window_get_attached_size` — libwayland-egl's
 * own record of the buffer the compositor holds. Any frame where the two
 * disagree is the defect.
 *
 * [resizeStormKeepsPaintOnTheBuffer] drives the size from the client rather
 * than through a pointer grab: the configure / ack / reallocate pipeline is the
 * same one a dragged edge exercises, only the cadence differs, so the defect
 * shows without depending on input injection reaching the compositor.
 */
internal object Issue444HeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(resizeStormKeepsPaintOnTheBuffer())

    private const val BASE_W = 900.0
    private const val BASE_H = 700.0

    private const val TOGGLES = 16
    private const val TOGGLE_MILLIS = 180L
    private const val MIN_FRAMES = 8

    private fun worstReport(worst: TaoWaylandFrameDiagnostics.Frame?): String =
        worst?.let {
            " (worst dw=${it.widthDelta} dh=${it.heightDelta}, paint=${it.paintPx}, " +
                "attached=${it.attachedPx}, window=${it.windowPx})"
        } ?: ""

    private fun skipUnlessNativeWayland(): String? =
        when {
            Platform.Current != Platform.Linux -> "#444 is a Wayland defect"
            System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ->
                "needs a native Wayland session (WAYLAND_DISPLAY unset)"
            System.getenv("NUCLEUS_TAO_LINUX_RENDERER") == "x11" ->
                "renderer forced to XWayland, where the defect does not exist"
            System.getenv("GDK_BACKEND") == "x11" -> "GDK forced to x11"
            else -> null
        }

    private fun resizeStormKeepsPaintOnTheBuffer() =
        TaoWindowTestCase(
            name = "#444 a resize storm paints every frame at its own buffer size",
            size = DpSize(BASE_W.dp, BASE_H.dp),
            timeoutMillis = 90_000,
            skip = ::skipUnlessNativeWayland,
        ) {
            awaitUntil("window mapped") { bounds() != null }
            // A Wayland surface the compositor considers occluded stops getting
            // frame callbacks, the swap never completes and every render pass is
            // skipped — the case would then measure nothing while looking like a
            // pass. Keep the window in front for the duration of the gesture.
            window.setAlwaysOnTop(true)
            window.focus()
            settle()

            // Compositor-driven size changes rather than `setInnerSize`. Not
            // because a client resize never works — it does on Mutter 50.1,
            // where #576 drives 40 distinct sizes through it — but because it
            // is advisory: it is a request the compositor is free to drop, and
            // a session that drops it would leave this case measuring frames
            // from a window that never changed size. A maximize is the
            // compositor's own state change, so the configure always follows,
            // and a dragged edge is compositor-driven too, so this is also the
            // closer shape to the gesture the issue is about.
            val sizesSeen = linkedSetOf<List<Long>>()
            window.onResized { w, h -> sizesSeen += listOf(w.toLong(), h.toLong()) }

            TaoWaylandFrameDiagnostics.start()
            repeat(TOGGLES) { i ->
                window.setMaximized(i % 2 == 0)
                delay(TOGGLE_MILLIS)
            }
            window.setMaximized(false)
            settle()
            window.setAlwaysOnTop(false)
            val skipped = TaoWaylandFrameDiagnostics.skipped
            val frames = TaoWaylandFrameDiagnostics.stop()
            // A run that resized nothing measured nothing, and every "no frame
            // was painted at the wrong size" check below would hold trivially.
            check(sizesSeen.size >= 2) {
                "the window never changed size (${sizesSeen.size} distinct sizes seen) — " +
                    "nothing was measured, so the result says nothing about #444"
            }
            assertPaintMatchedBuffer(frames, "maximize/restore storm", sizesSeen.size, skipped)
        }

    /**
     * Fails when any recorded frame was painted at a size the buffer behind the
     * framebuffer did not have. Prints the distribution either way — a run that
     * passes because nothing resized is a run that measured nothing, which the
     * frame-count floor catches.
     */
    private fun assertPaintMatchedBuffer(
        frames: List<TaoWaylandFrameDiagnostics.Frame>,
        gesture: String,
        distinctSizes: Int,
        skippedPasses: Int,
    ) {
        // `attachedPx` is the buffer already committed, so it lags by design;
        // the size that matters is `queriedPx`, the back buffer this frame's GL
        // commands land in.
        val measurable = frames.filter { it.queriedPx.height > 0 }
        val mismatched = measurable.filter { it.heightDelta != 0 || it.widthDelta != 0 }
        val worst = mismatched.maxByOrNull { maxOf(kotlin.math.abs(it.heightDelta), kotlin.math.abs(it.widthDelta)) }
        // Defect (1) of the issue, reported but not gated here: the buffer the
        // compositor actually holds while it shows the window at its new size.
        // Painting at the right size does not make the buffer arrive with the
        // frame — that is a commit-ordering problem between GTK's toplevel and
        // our sub-surface, not a render-target one.
        val behindTheWindow = frames.count { it.attachedPx.height > 0 && it.attachedPx != it.windowPx }
        System.err.println(
            "[#444] $gesture: $distinctSizes distinct window sizes, ${frames.size} frames, " +
                "${measurable.size} with a known buffer, " +
                "$behindTheWindow with a committed buffer that did not match the window (defect 1), " +
                "${mismatched.size} painted at the wrong size, " +
                "${measurable.count { it.reallocatedMidFrame }} reallocated mid-frame" +
                worstReport(worst),
        )
        mismatched.take(MIN_FRAMES).forEach {
            System.err.println(
                "[#444]   window=${it.windowPx} paint=${it.paintPx} attached=${it.attachedPx} " +
                    "queried=${it.queriedPx}->${it.queriedAfterPx} requested=${it.requestedPx} " +
                    "dw=${it.widthDelta} dh=${it.heightDelta}",
            )
        }
        // #444 on non-Mesa drivers: the drawable can be reallocated *during* the
        // frame, which makes the size queried up front a stale basis for the
        // render target — the very premise the fix rests on. Dump those frames:
        // if `queriedAfter` equals `requested`, the buffer reached the size we
        // asked for mid-frame, and painting at the pre-frame size was wrong by
        // exactly one step, in the opposite direction to the original defect.
        val realloc = measurable.filter { it.reallocatedMidFrame }
        if (realloc.isNotEmpty()) {
            System.err.println("[#444] ${realloc.size} frames reallocated mid-frame:")
            realloc.take(MIN_FRAMES).forEach {
                System.err.println(
                    "[#444]   REALLOC window=${it.windowPx} paint=${it.paintPx} " +
                        "queried=${it.queriedPx}->${it.queriedAfterPx} " +
                        "requested=${it.requestedPx} attached=${it.attachedPx}",
                )
            }
        }
        check(measurable.size >= MIN_FRAMES) {
            "only ${measurable.size} frames with a known buffer size were recorded during the $gesture — " +
                "nothing was measured (frames=${frames.size}, $skippedPasses passes skipped on a swap still " +
                "in flight). A window the compositor treats as occluded never gets its frame callbacks, so it " +
                "renders nothing at all; run the suite against a nested compositor, e.g. " +
                "`mutter --headless --virtual-monitor 1920x1080 --wayland-display=nested` with WAYLAND_DISPLAY set"
        }
        check(mismatched.isEmpty()) {
            "${mismatched.size} of ${measurable.size} frames were painted at a size the buffer did not have; " +
                "under SurfaceOrigin.BOTTOM_LEFT each one lands off the real drawable by that difference"
        }
        // Painting at the buffer's size is only right if the buffer catches the
        // window up: a render that followed a drawable that never converged
        // would satisfy the check above with content permanently a step small.
        val last = measurable.last()
        check(last.paintPx == last.windowPx) {
            "the gesture settled with the frame still painted at ${last.paintPx} for a ${last.windowPx} window — " +
                "the drawable never caught the window up"
        }
    }
}
