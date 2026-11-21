package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Rectangle
import java.awt.Robot
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A window keeps its content on screen while the focus leaves it and comes
 * back (#952).
 *
 * On X11 the EGL surface rendered into the GTK toplevel's own X window, and
 * every GTK `draw` of that window — the `:backdrop` restyle a focus change
 * queues among them — cleared it to transparent black before Compose's next
 * frame was swapped in. A compositor showed whatever lay behind the window for
 * that moment; without one (Xvfb) the cleared pixels are black. The case films
 * the window's centre while the focus goes back and forth to a second window,
 * and counts the frames whose pixels are not the window's solid colour.
 *
 * Before the fix only a toplevel whose GDK visual is one Mesa registered with
 * EGL rendered into its own XID (a real Xorg server); anywhere else EGL already
 * went through a child window. The default Xvfb is such a place: its GLX
 * extension adds the visual GDK picks. Started with `-extension GLX`, Xvfb has
 * a single 32-bit visual and reproduces the flash (72 black frames of ~18 000).
 */
internal object LinuxFocusFlashHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(focusChangesKeepTheContentOnScreen())

    private fun skipReason(): String? =
        when {
            Platform.Current != Platform.Linux -> "Linux only: the X11 EGL surface"
            System.getenv("WAYLAND_DISPLAY") != null ->
                "films through the AWT Robot, which needs a plain X server (WAYLAND_DISPLAY is set)"
            else -> robotSkipReason()
        }

    private fun focusChangesKeepTheContentOnScreen(): TaoWindowTestCase {
        val other = OtherWindow()
        return TaoWindowTestCase(
            name = "linux focus leaving and returning keeps the window content on screen (#952)",
            skip = ::skipReason,
            paintDefaultBackground = false,
            size = DpSize(CASE_W_DP.dp, CASE_H_DP.dp),
            content = { Box(Modifier.fillMaxSize().background(FILL)) },
            applicationContent = {
                DecoratedWindow(
                    onCloseRequest = {},
                    state =
                        WindowState(
                            position = WindowPosition(OTHER_X_DP.dp, OTHER_Y_DP.dp),
                            size = DpSize(OTHER_W_DP.dp, OTHER_H_DP.dp),
                        ),
                    title = "tao-headful: focus thief",
                ) {
                    val self = LocalTaoWindow.current
                    SideEffect { other.window = self }
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            val thief = other.window
            awaitUntil("second window mapped") { thief?.hasRealFramePx() == true }
            checkNotNull(thief)
            window.focus()
            awaitUntil("case window focused") { window.isFocused }
            settle()

            val rect = requireNotNull(bounds())
            val region =
                Rectangle(
                    (rect[0] + rect[2] / 2 - SAMPLE_PX / 2).toInt(),
                    (rect[1] + rect[3] / 2 - SAMPLE_PX / 2).toInt(),
                    SAMPLE_PX,
                    SAMPLE_PX,
                )
            val film = Film(region)
            // Off the loop thread: Robot captures deadlock on the Tao thread on Linux (#658).
            val before = withContext(Dispatchers.IO) { film.grab() }
            check(before == null) { "the window does not show its colour before the focus moves: $before" }

            film.start()
            var lostFocus = 0
            try {
                repeat(FOCUS_ROUNDS) {
                    thief.focus()
                    if (awaitUntilOrTimeout(FOCUS_TIMEOUT_MILLIS) { !window.isFocused }) lostFocus++
                    settle(ROUND_SETTLE_MILLIS)
                    window.focus()
                    awaitUntilOrTimeout(FOCUS_TIMEOUT_MILLIS) { window.isFocused }
                    settle(ROUND_SETTLE_MILLIS)
                }
            } finally {
                withContext(Dispatchers.IO) { film.stop() }
            }
            check(lostFocus > 0) { "the case window never lost the focus: nothing was exercised" }
            check(film.frames > MIN_FRAMES) { "only ${film.frames} frames filmed: the grabber was too slow" }
            check(film.flashes.isEmpty()) {
                "${film.flashes.size} of ${film.frames} frames over $lostFocus focus round trips " +
                    "did not show the window's colour; first: ${film.flashes.take(MAX_REPORTED)}"
            }
        }
    }

    private class OtherWindow {
        @Volatile
        var window: TaoWindow? = null
    }

    /** Grabs [region] in a loop, off the loop thread, keeping the frames that show something else. */
    private class Film(
        private val region: Rectangle,
    ) {
        private val robot = Robot()
        private val running = AtomicBoolean(false)
        private var grabber: Thread? = null

        @Volatile
        var frames = 0
            private set
        val flashes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        /** `null` when every pixel of [region] is [FILL], else a description of the first one that is not. */
        fun grab(): String? {
            val image = robot.createScreenCapture(region)
            val want = FILL.toArgb()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    if (!close(argb, want)) return "(%d,%d)=#%06x".format(x, y, argb and RGB_MASK)
                }
            }
            return null
        }

        fun start() {
            running.set(true)
            grabber =
                thread(name = "focus-flash-film") {
                    while (running.get()) {
                        grab()?.let { flashes += "frame $frames $it" }
                        frames++
                    }
                }
        }

        fun stop() {
            running.set(false)
            grabber?.join()
        }

        private fun close(
            a: Int,
            b: Int,
        ): Boolean = listOf(16, 8, 0).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= TOLERANCE }
    }

    private val FILL = Color(0xFF2E7D32)
    private const val RGB_MASK = 0xFFFFFF
    private const val TOLERANCE = 8
    private const val SAMPLE_PX = 24
    private const val FOCUS_ROUNDS = 12
    private const val FOCUS_TIMEOUT_MILLIS = 2_000L
    private const val ROUND_SETTLE_MILLIS = 250L
    private const val MIN_FRAMES = 100
    private const val MAX_REPORTED = 5
    private const val CASE_W_DP = 360
    private const val CASE_H_DP = 260
    private const val OTHER_X_DP = 700
    private const val OTHER_Y_DP = 80
    private const val OTHER_W_DP = 240
    private const val OTHER_H_DP = 160
}
