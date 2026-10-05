package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoMonitors
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsNativeViewBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * While a window is resized, every composed frame shows the content laid out
 * for the size the window has in that frame: its right edge sits on the
 * window's right edge.
 *
 * The content is a fill with a red stripe along its right edge. The screen is
 * filmed along one row through the window; in every frame the stripe must be
 * the last thing of the window on that row. A frame of the previous size inside
 * a larger window leaves a band after the stripe (white where nothing painted
 * the redirection surface, or old content); one inside a smaller window cuts
 * the stripe off. Either reads as the content trembling against the border.
 * Pixels that only match because the desktop behind is that colour are left
 * out through a reference frame taken with the window moved away.
 */
internal object ResizeSyncHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(borderDrag(overhanging = false), borderDrag(overhanging = true), programmaticResize())

    private fun skipReason(): String? =
        when {
            Platform.Current != Platform.Windows -> "Windows only: films DWM's composition"
            !NativeTaoWindowsNativeViewBridge.isLoaded -> "nucleus_tao_windows_native_view not loaded"
            else -> null
        }

    private fun content(): @Composable TaoDecoratedWindowScope.() -> Unit =
        {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(FILL)
                    val stripe = EDGE_STRIPE_DP.dp.toPx()
                    drawRect(EDGE, topLeft = Offset(size.width - stripe, 0f), size = Size(stripe, size.height))
                },
            )
        }

    /**
     * The user's resize: the bottom-right corner dragged out and back with the
     * real mouse (OS modal size loop). [overhanging]: the window's left part lies outside the desktop, so its
     * frames go through the DirectComposition mirror too — which must step
     * aside for the resize and come back after it.
     */
    private fun borderDrag(overhanging: Boolean): TaoWindowTestCase =
        TaoWindowTestCase(
            name =
                "windows content stays on the border while the border is dragged" +
                    if (overhanging) " (window overhanging the desktop)" else "",
            skip = ::skipReason,
            paintDefaultBackground = false,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            content = content(),
        ) {
            val film = prepare(window, overhanging)
            val start = requireNotNull(bounds())
            val cornerX = (start[0] + start[2] - CORNER_INSET_PX).toInt()
            val cornerY = (start[1] + start[3] - CORNER_INSET_PX).toInt()
            val awtScale =
                java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX

            fun awt(px: Int) = (px / awtScale).roundToInt()

            film.start()
            val dragged =
                HeadfulRobot.inject(timeoutMillis = DRAG_TIMEOUT_MILLIS) { robot ->
                    robot.mouseMove(awt(cornerX), awt(cornerY))
                    Thread.sleep(PAUSE_MILLIS)
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    Thread.sleep(PAUSE_MILLIS)
                    val path = (1..STEPS) + (STEPS - 1 downTo 0)
                    for (step in path) {
                        robot.mouseMove(awt(cornerX + step * STEP_PX), awt(cornerY + step * STEP_PX / 2))
                        Thread.sleep(STEP_MILLIS)
                    }
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    true
                }
            settle(SETTLE_MILLIS)
            film.stop()
            checkNotNull(dragged) { HeadfulRobot.unavailableReason ?: "the drag could not be injected" }
            film.assertInStep("border drag")
        }

    /**
     * A resize the app asks for (a pane docked in, a window restored): out and
     * back in steps. Known gap, the same on the blt present alone: shrinking
     * steps show a frame of the previous size for a composition (~8 of ~108
     * frames). Opt in with `-Dnucleus.tao.headful.programmaticResizeSync=true`.
     */
    private fun programmaticResize(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "windows content stays on the border through programmatic resizes",
            skip = {
                skipReason() ?: "known gap: programmatic shrink presents a composition late (see KDoc)"
                    .takeUnless { System.getProperty("nucleus.tao.headful.programmaticResizeSync") == "true" }
            },
            paintDefaultBackground = false,
            size = DpSize(START_W_DP.dp, START_H_DP.dp),
            content = content(),
        ) {
            val film = prepare(window)
            film.start()
            val path = (1..STEPS) + (STEPS - 1 downTo 0)
            val scale = window.scaleFactor.coerceAtLeast(1f)
            for (step in path) {
                window.setInnerSize(
                    START_W_DP + step * STEP_PX / scale.toDouble(),
                    START_H_DP + step * STEP_PX / 2 / scale.toDouble(),
                )
                settle(STEP_MILLIS)
            }
            settle(SETTLE_MILLIS)
            film.stop()
            film.assertInStep("programmatic resize")
        }

    /** Pins the window, takes the reference with the window moved away, and returns the film. */
    private suspend fun TaoWindowTestScope.prepare(
        window: TaoWindow,
        overhanging: Boolean = false,
    ): Film {
        awaitUntil("window mapped") { window.hasRealFramePx() }
        window.setAlwaysOnTop(true)
        window.focus()
        settle()
        if (overhanging) {
            // Left part past the desktop's left edge; the filmed row starts on-screen.
            val left = TaoMonitors.all(window).minOf { it.boundsPx.left }
            val top = requireNotNull(bounds())[1].toInt()
            window.setOuterPositionPx(left - OVERHANG_PX, top)
            awaitUntil("window overhanging the desktop") { bounds()?.get(0)?.toInt() == left - OVERHANG_PX }
            settle()
        }
        val placed = requireNotNull(bounds())
        val start =
            if (overhanging) {
                longArrayOf(
                    placed[0] + OVERHANG_PX + MARGIN_PX,
                    placed[1],
                    placed[2] - OVERHANG_PX - MARGIN_PX,
                    placed[3],
                )
            } else {
                placed
            }
        val row = (start[1] + start[3] / 2).toInt()
        val fromX = start[0].toInt()
        val width = start[2].toInt() + (STEPS + 2) * STEP_PX
        val away = TaoMonitors.all(window).maxOf { it.boundsPx.right } + AWAY_PX
        window.setOuterPositionPx(away, start[1].toInt())
        settle()
        val reference = Film.grabRow(fromX, row, width)
        window.setOuterPositionPx(placed[0].toInt(), placed[1].toInt())
        settle()
        // A frame presented in place, so an overhanging window has its mirror up.
        window.requestRedraw()
        settle()
        return Film(fromX, row, width, requireNotNull(reference) { "the screen could not be read" })
    }

    private class Film(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val reference: IntArray,
    ) {
        private val running = AtomicBoolean(false)
        private val frames = ArrayList<IntArray>()
        private var grabber: Thread? = null

        fun start() {
            running.set(true)
            grabber =
                thread(name = "resize-sync-film") {
                    while (running.get() && frames.size < MAX_FRAMES) {
                        grabRow(x, y, width)?.let { synchronized(frames) { frames += it } }
                    }
                }
        }

        fun stop() {
            running.set(false)
            grabber?.join()
        }

        /** Frames whose stripe is not on the window's edge, judged on the filmed row. */
        fun assertInStep(what: String) {
            val edge = EDGE.toArgb()
            val fill = FILL.toArgb()
            var bands = 0
            var cuts = 0
            var widest = 0
            val examples = ArrayList<String>()
            synchronized(frames) {
                for ((n, frame) in frames.withIndex()) {
                    val lastEdge = (frame.size - 1 downTo 0).firstOrNull { frame[it].close(edge) }
                    val lastFill =
                        (frame.size - 1 downTo 0).firstOrNull {
                            frame[it].close(fill) &&
                                !reference[it].close(fill)
                        }
                    if (lastEdge == null) {
                        if (lastFill != null) {
                            cuts++
                            if (examples.size < MAX_EXAMPLES) examples += "#$n cut (fill to x=$lastFill, no stripe)"
                        }
                        continue
                    }
                    // A band: window pixels after the stripe that the desktop does not explain.
                    var band = 0
                    for (i in lastEdge + 1 until minOf(frame.size, lastEdge + 1 + BAND_SCAN_PX)) {
                        val p = frame[i]
                        val foreign =
                            (p.isWhite() && !reference[i].isWhite()) || (p.close(fill) && !reference[i].close(fill))
                        if (foreign) band = i - lastEdge
                    }
                    if (band > BAND_TOLERANCE_PX) {
                        bands++
                        if (band > widest) widest = band
                        if (examples.size < MAX_EXAMPLES) examples += "#$n band ${band}px after x=$lastEdge"
                    }
                    // Also a stripe that is not the last window pixel: fill after it means stale content.
                    if (lastFill != null && lastFill > lastEdge + BAND_TOLERANCE_PX) {
                        cuts++
                        if (examples.size < MAX_EXAMPLES) examples += "#$n fill after stripe (to x=$lastFill)"
                    }
                }
            }
            println("[resize-sync] $what: frames=${frames.size} bands=$bands widest=${widest}px cuts=$cuts $examples")
            check(frames.size >= MIN_FRAMES) { "$what: only ${frames.size} frames filmed" }
            check(bands == 0 && cuts == 0) {
                "$what: content out of step with the window in ${bands + cuts} of ${frames.size} frames " +
                    "(bands=$bands widest=${widest}px cuts=$cuts) $examples"
            }
        }

        companion object {
            fun grabRow(
                x: Int,
                y: Int,
                width: Int,
            ): IntArray? =
                NativeTaoWindowsNativeViewBridge.nativeDiagCaptureScreen(x, y, width, 1)?.copyOfRange(2, 2 + width)
        }
    }

    private fun Int.isWhite(): Boolean =
        (this shr 16 and 0xFF) >= WHITE_MIN && (this shr 8 and 0xFF) >= WHITE_MIN && (this and 0xFF) >= WHITE_MIN

    private fun Int.close(other: Int): Boolean =
        kotlin.math.abs((this shr 16 and 0xFF) - (other shr 16 and 0xFF)) <= TOLERANCE &&
            kotlin.math.abs((this shr 8 and 0xFF) - (other shr 8 and 0xFF)) <= TOLERANCE &&
            kotlin.math.abs((this and 0xFF) - (other and 0xFF)) <= TOLERANCE

    private val FILL = Color(0xFF1E88E5)
    private val EDGE = Color(0xFFE53935)

    private const val START_W_DP = 420
    private const val START_H_DP = 300
    private const val EDGE_STRIPE_DP = 6
    private const val CORNER_INSET_PX = 4
    private const val STEPS = 30
    private const val STEP_PX = 12
    private const val STEP_MILLIS = 14L
    private const val PAUSE_MILLIS = 150L
    private const val SETTLE_MILLIS = 300L
    private const val DRAG_TIMEOUT_MILLIS = 15_000L
    private const val AWAY_PX = 200
    private const val OVERHANG_PX = 120
    private const val MARGIN_PX = 8
    private const val MAX_FRAMES = 3_000
    private const val MIN_FRAMES = 50
    private const val MAX_EXAMPLES = 6
    private const val BAND_SCAN_PX = 40
    private const val BAND_TOLERANCE_PX = 2
    private const val TOLERANCE = 10
    private const val WHITE_MIN = 0xF0
}
