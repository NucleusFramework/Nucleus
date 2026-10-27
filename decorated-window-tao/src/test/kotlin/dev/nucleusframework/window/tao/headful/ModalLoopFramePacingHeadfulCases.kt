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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * #484: while the user drags a window — title bar (the OS modal move loop) or
 * border (the modal size loop) — a frame-clock animation must keep ticking at
 * the display's rate, not free-run.
 *
 * The modal loop presents at swap interval 0 (#477), so nothing used to pace
 * a frame the animation itself asked for: render → `withFrameNanos` resumes →
 * invalidate → redraw → render, as fast as the modal pump turns (~1300 fps
 * observed, over 2000 on a 90 Hz display here). The window here hosts a plain Compose animation — no
 * `TextureView`, no `TaoGpuRenderContext`, which #489 already kept on VSync.
 *
 * The drag is real input (AWT Robot, i.e. `SendInput`) so DefWindowProc runs
 * its own modal loop, exactly as under the user's mouse.
 */
internal object ModalLoopFramePacingHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            animationPacedDuringDrag(DragKind.Move),
            animationPacedDuringDrag(DragKind.Resize),
        )

    private enum class DragKind(
        val label: String,
    ) {
        Move("title bar dragged (modal move loop)"),
        Resize("border dragged (modal size loop)"),
    }

    /**
     * Counts, while [recording], the frame clock's advances — the frames an
     * animation moves on — and every `withFrameNanos` callback, which also
     * includes the frames that re-use the previous timestamp.
     */
    private class TickCounter {
        val recording = AtomicBoolean(false)
        private val advances = AtomicLong(0)
        private val callbacks = AtomicLong(0)
        private var lastNanos = 0L

        fun tick(nanos: Long) {
            if (!recording.get()) return
            callbacks.incrementAndGet()
            if (nanos != lastNanos) advances.incrementAndGet()
            lastNanos = nanos
        }

        fun reset() {
            advances.set(0)
            callbacks.set(0)
        }

        fun advances(): Long = advances.get()

        fun callbacks(): Long = callbacks.get()
    }

    private fun animationPacedDuringDrag(kind: DragKind): TaoWindowTestCase {
        val counter = TickCounter()
        return TaoWindowTestCase(
            name = "windows frame-clock animation stays display-paced while the ${kind.label}",
            skip = {
                when {
                    Platform.Current != Platform.Windows -> "Windows only: the OS modal size/move loop"
                    GraphicsEnvironment.isHeadless() -> "no display"
                    else -> null
                }
            },
            // The default background would stack above the title bar.
            paintDefaultBackground = false,
            size = DpSize(WIDTH_DP.dp, HEIGHT_DP.dp),
            content = animatedContent(counter),
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            // Nothing else may cover the caption or the grip the robot presses.
            window.setAlwaysOnTop(true)
            window.focus()
            settle(WARMUP_MILLIS)
            val drag = drag(kind, counter)
            settle()
            checkNotNull(drag) { HeadfulRobot.unavailableReason ?: "the drag could not be injected" }
            assertPaced(kind, counter, drag)
        }
    }

    /**
     * A title bar over a self-sustaining animation: its phase is read in draw,
     * so every tick asks for the next frame.
     */
    private fun animatedContent(counter: TickCounter): @Composable TaoDecoratedWindowScope.() -> Unit =
        {
            TitleBar()
            val phase = remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val x = phase.floatValue % size.width.coerceAtLeast(1f)
                    drawRect(Color.Cyan, topLeft = Offset(x, 0f), size = Size(SWATCH_PX, SWATCH_PX))
                },
            )
            LaunchedEffect(Unit) {
                while (true) {
                    withFrameNanos { nanos ->
                        phase.floatValue += PHASE_STEP_PX
                        counter.tick(nanos)
                    }
                }
            }
        }

    /** What the drag measured: its length, and the geometries the window went through. */
    private class Drag(
        val nanos: Long,
        val geometries: Int,
    )

    /**
     * Presses the caption (a third of the way in, clear of the caption
     * buttons) or the bottom-right resize grip, and wiggles the pointer back
     * and forth for [MEASURE_MILLIS] while [counter] records. Null when the
     * robot is unavailable.
     */
    private suspend fun TaoWindowTestScope.drag(
        kind: DragKind,
        counter: TickCounter,
    ): Drag? {
        val bounds = requireNotNull(bounds())
        val awtScale =
            GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX

        fun awt(px: Long) = (px / awtScale).roundToInt()

        val (fromX, fromY) =
            when (kind) {
                DragKind.Move -> bounds[0] + bounds[2] / 3 to bounds[1] + (TITLE_GRAB_DP * window.scaleFactor).toLong()
                DragKind.Resize -> bounds[0] + bounds[2] - CORNER_INSET_PX to bounds[1] + bounds[3] - CORNER_INSET_PX
            }
        val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
        return HeadfulRobot.inject(timeoutMillis = DRAG_TIMEOUT_MILLIS) { robot ->
            robot.mouseMove(awt(fromX), awt(fromY))
            Thread.sleep(PAUSE_MILLIS)
            HeadfulRobot.notePress()
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(PAUSE_MILLIS)
            // Read off the robot thread: proof the press landed on the caption
            // or the grip and the OS loop ran.
            val geometries = HashSet<Long>()
            counter.reset()
            counter.recording.set(true)
            val start = System.nanoTime()
            var step = 0
            while (System.nanoTime() - start < MEASURE_MILLIS * NANOS_PER_MILLI) {
                // Back and forth, so the window stays on screen.
                val swing = step % (2 * SWING_STEPS)
                val offset = if (swing < SWING_STEPS) swing else 2 * SWING_STEPS - swing
                robot.mouseMove(awt(fromX + offset * STEP_PX), awt(fromY + offset * STEP_PX / 2))
                NativeTaoWindowsDecoBridge.nativeGetWindowRect(hwnd)?.let { rect ->
                    geometries += if (kind == DragKind.Move) rect[0] else rect[2]
                }
                step++
                Thread.sleep(STEP_MILLIS)
            }
            val nanos = System.nanoTime() - start
            counter.recording.set(false)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            HeadfulRobot.noteRelease()
            Drag(nanos, geometries.size)
        }
    }

    private fun assertPaced(
        kind: DragKind,
        counter: TickCounter,
        drag: Drag,
    ) {
        val ticks = counter.advances()
        val fps = ticks * NANOS_PER_SECOND / drag.nanos
        val refresh = displayRefreshHz()
        println(
            "[modal-loop-pacing] ${kind.name}: $ticks clock advances (${counter.callbacks()} callbacks), " +
                "${drag.geometries} geometries " +
                "in ${drag.nanos / NANOS_PER_MILLI} ms -> %.1f fps, display ${refresh}Hz".format(fps),
        )
        check(drag.geometries >= MIN_GEOMETRIES) {
            "${kind.label}: the window went through ${drag.geometries} geometries — the drag never entered the OS loop"
        }
        check(ticks > 0) { "${kind.label}: the animation never ticked during the drag" }
        val ceiling = (if (refresh > 0) refresh else FALLBACK_REFRESH_HZ) * MAX_REFRESH_RATIO
        check(fps <= ceiling) {
            "${kind.label}: the frame clock free-ran at %.1f fps on a ${refresh}Hz display (ceiling %.1f fps) — "
                .format(fps, ceiling) +
                "nothing paces an animation frame inside the OS modal loop (#484)"
        }
        // A software-rendered CI runner cannot be held to the display's rate.
        if (refresh > 0 && System.getenv("CI") == null) {
            val floor = refresh * MIN_REFRESH_RATIO
            check(fps >= floor) {
                "${kind.label}: the frame clock ran at %.1f fps on a ${refresh}Hz display (floor %.1f fps) — "
                    .format(fps, floor) +
                    "a frame held inside the OS modal loop wakes too late (#484)"
            }
        }
    }

    /** Refresh rate of the default screen in Hz, or 0 when the platform won't say. */
    private fun displayRefreshHz(): Int =
        runCatching {
            val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
            if (mode.refreshRate == DisplayMode.REFRESH_RATE_UNKNOWN) 0 else mode.refreshRate
        }.getOrDefault(0)

    private const val WIDTH_DP = 480
    private const val HEIGHT_DP = 320
    private const val TITLE_GRAB_DP = 12
    private const val CORNER_INSET_PX = 4
    private const val SWATCH_PX = 40f
    private const val PHASE_STEP_PX = 3f
    private const val SWING_STEPS = 40
    private const val STEP_PX = 4
    private const val STEP_MILLIS = 4L
    private const val PAUSE_MILLIS = 150L
    private const val WARMUP_MILLIS = 800L
    private const val MEASURE_MILLIS = 2_000L
    private const val DRAG_TIMEOUT_MILLIS = 15_000L
    private const val MIN_GEOMETRIES = 10
    private const val FALLBACK_REFRESH_HZ = 60
    private const val MAX_REFRESH_RATIO = 1.5
    private const val MIN_REFRESH_RATIO = 0.75
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val NANOS_PER_MILLI = 1_000_000L
}
