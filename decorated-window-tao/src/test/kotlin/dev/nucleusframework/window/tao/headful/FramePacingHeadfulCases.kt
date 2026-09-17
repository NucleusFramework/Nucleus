package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Headful probes for render-loop pacing: does the Tao/Metal loop sustain the
 * display's refresh rate, or is it pinned to some fixed rate?
 *
 * The macOS loop has no frame-rate constant of its own — it is paced by a
 * `CVDisplayLink` (`nativeVSyncWait`) plus `nextDrawable` blocking on the
 * layer's drawable pool, so it should follow whatever the display runs at.
 * This measures whether it actually does, because a mismatch is invisible from
 * the code: a 60 Hz result on a 120 Hz panel and a correct 60 Hz result on a
 * 60 Hz panel look identical in a log.
 *
 * The measurement drives a self-sustaining animation (a `withFrameNanos` loop
 * whose state is read in `drawBehind`, so every tick invalidates and schedules
 * the next frame) and timestamps each frame clock tick.
 *
 * Assertion policy: only a display above [HIGH_REFRESH_THRESHOLD_HZ] arms the
 * check. No CI runner has a high-refresh panel, and asserting a hard 60 fps on
 * a software-GL runner would be flaky — while on a real 90/120 Hz display a
 * loop stuck at 60 is exactly what we want to fail on. Everywhere else the
 * numbers are reported and the case passes.
 */
internal object FramePacingHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(renderLoopFollowsDisplayRefresh())

    /** Frame-clock tick timestamps, written from the composition. */
    private class FrameSink {
        private val nanos = AtomicLongArray(CAPACITY)
        private val count = AtomicLong(0)
        val recording = AtomicBoolean(false)

        fun record(t: Long) {
            if (!recording.get()) return
            val i = count.getAndIncrement()
            if (i < CAPACITY) nanos.set(i.toInt(), t)
        }

        fun reset() = count.set(0)

        /** Frames per second over the recorded window, or 0 if too few samples. */
        fun fps(): Double {
            val n = minOf(count.get(), CAPACITY.toLong()).toInt()
            if (n < MIN_SAMPLES) return 0.0
            val elapsedNanos = nanos.get(n - 1) - nanos.get(0)
            if (elapsedNanos <= 0L) return 0.0
            return (n - 1) * NANOS_PER_SECOND / elapsedNanos.toDouble()
        }

        /** Median frame interval in ms — separates a low rate from a jittery one. */
        fun medianIntervalMillis(): Double {
            val n = minOf(count.get(), CAPACITY.toLong()).toInt()
            if (n < MIN_SAMPLES) return 0.0
            val gaps = DoubleArray(n - 1) { (nanos.get(it + 1) - nanos.get(it)) / NANOS_PER_MILLI }
            gaps.sort()
            return gaps[gaps.size / 2]
        }

        fun frames(): Long = count.get()
    }

    /**
     * A frame-clock loop whose phase is read in [drawBehind]: each tick writes
     * state that the draw layer depends on, so Compose invalidates and the host
     * schedules the next frame. Without that read the clock would stall — the
     * host only ticks [androidx.compose.runtime.BroadcastFrameClock] when it
     * actually renders.
     */
    @Composable
    private fun FrameRateDriver(sink: FrameSink) {
        val phase = remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize().drawBehind {
                val x = phase.floatValue % (size.width.coerceAtLeast(1f))
                drawRect(Color.Cyan, topLeft = Offset(x, 0f), size = Size(SWATCH_PX, SWATCH_PX))
            },
        )
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { t ->
                    phase.floatValue += PHASE_STEP_PX
                    sink.record(t)
                }
            }
        }
    }

    private fun renderLoopFollowsDisplayRefresh(): TaoWindowTestCase {
        val sink = FrameSink()
        return TaoWindowTestCase(
            name = "render loop sustains the display refresh rate",
            skip = {
                if (GraphicsEnvironment.isHeadless()) "no display" else null
            },
            content = { FrameRateDriver(sink) },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                window.focus()
                // Warm up: first frames pay shader/pipeline compilation and the
                // initial layout, which would drag the average down.
                settle(WARMUP_MILLIS)

                sink.reset()
                sink.recording.set(true)
                settle(MEASURE_MILLIS)
                sink.recording.set(false)

                val fps = sink.fps()
                val median = sink.medianIntervalMillis()
                val refresh = displayRefreshHz()
                System.err.println(
                    "[probe] frames=${sink.frames()} over ${MEASURE_MILLIS}ms -> " +
                        "%.1f fps (median interval %.2f ms), display reports ${refresh}Hz"
                            .format(fps, median),
                )
                check(fps > 0.0) { "no frame clock ticks recorded — the driver never animated" }

                if (refresh <= HIGH_REFRESH_THRESHOLD_HZ) {
                    // 60 Hz panel, or a rate the platform won't report: a 60 fps
                    // result is indistinguishable from a cap, so don't judge.
                    System.err.println(
                        "[probe] display is ${refresh}Hz (<= $HIGH_REFRESH_THRESHOLD_HZ) — " +
                            "reporting only; a high-refresh display is needed to detect a cap",
                    )
                    return@TaoWindowTestCase
                }
                val floor = refresh * MIN_REFRESH_RATIO
                check(fps >= floor) {
                    "render loop sustained %.1f fps on a ${refresh}Hz display (floor %.1f fps, ".format(fps, floor) +
                        "median interval %.2f ms). The loop is paced by the CVDisplayLink in ".format(median) +
                        "nativeStartDisplayLink and by nextDrawable blocking on the drawable pool — " +
                        "check that the link targets the window's display (CVDisplayLinkSetCurrentCGDisplay) " +
                        "and that the drawable pool is not the limit"
                }
                System.err.println("[VERDICT] OK — %.1f fps against a ${refresh}Hz display".format(fps))
            },
        )
    }

    /** Refresh rate of the default screen in Hz, or 0 when the platform won't say. */
    private fun displayRefreshHz(): Int =
        runCatching {
            val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
            if (mode.refreshRate == DisplayMode.REFRESH_RATE_UNKNOWN) 0 else mode.refreshRate
        }.getOrDefault(0)

    private const val CAPACITY = 4096
    private const val MIN_SAMPLES = 10
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val WARMUP_MILLIS = 1_000L
    private const val MEASURE_MILLIS = 2_000L
    private const val SWATCH_PX = 40f
    private const val PHASE_STEP_PX = 3f
    private const val HIGH_REFRESH_THRESHOLD_HZ = 65
    private const val MIN_REFRESH_RATIO = 0.80
}
