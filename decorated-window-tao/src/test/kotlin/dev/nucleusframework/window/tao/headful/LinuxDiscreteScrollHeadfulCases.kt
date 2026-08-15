package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.TaoEventCode
import java.util.concurrent.atomic.AtomicInteger

/**
 * #533 — a discrete GTK mouse-wheel event (`direction` set, delta `(0, 0)`)
 * must scroll Compose content. A physical mouse on a modern compositor often
 * emits `GDK_SCROLL_SMOOTH` instead, so this case synthesizes the discrete
 * payload GTK 3 actually produces for a classic wheel
 * (`gdkdevice-wayland.c` / `flush_discrete_scroll_event`).
 */
internal object LinuxDiscreteScrollHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            discreteWheelDownScrollsColumn(),
            smoothScrollStillWorks(),
        )

    private fun discreteWheelDownScrollsColumn(): TaoWindowTestCase {
        val scrollPx = AtomicInteger(0)
        val scrollMax = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#533 discrete GDK_SCROLL_DOWN (zero delta) scrolls a vertical column",
            skip = { linuxOnly() },
            // The suite's default DarkGray `fillMaxSize` Box is a Column
            // sibling of [content]; leaving it on would give this column
            // 0 height and the wheel would hit an unscrollable surface.
            paintDefaultBackground = false,
            content = { ScrollableColumn(scrollPx, scrollMax) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scrollable has overflow") { scrollMax.get() > 0 }
            settle()
            movePointerOverContent()
            val injected =
                LinuxGdkScrollProbe.inject(
                    handle = window.handle,
                    direction = LinuxGdkScrollProbe.DOWN,
                    x = TARGET_X,
                    y = TARGET_Y,
                )
            check(injected) { "nativeLinuxInjectGdkScroll returned false (window not realized?)" }
            awaitUntil("scroll offset advanced after discrete GDK_SCROLL_DOWN") {
                scrollPx.get() > 0
            }
        }
    }

    private fun smoothScrollStillWorks(): TaoWindowTestCase {
        val scrollPx = AtomicInteger(0)
        val scrollMax = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#533 GDK_SCROLL_SMOOTH (populated delta) still scrolls",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = { ScrollableColumn(scrollPx, scrollMax) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scrollable has overflow") { scrollMax.get() > 0 }
            settle()
            movePointerOverContent()
            val injected =
                LinuxGdkScrollProbe.inject(
                    handle = window.handle,
                    direction = LinuxGdkScrollProbe.SMOOTH,
                    deltaYMilli = SMOOTH_DELTA_Y_MILLI,
                    x = TARGET_X,
                    y = TARGET_Y,
                )
            check(injected) { "nativeLinuxInjectGdkScroll returned false (window not realized?)" }
            awaitUntil("scroll offset advanced after GDK_SCROLL_SMOOTH") {
                scrollPx.get() > 0
            }
        }
    }

    @Composable
    private fun ScrollableColumn(
        scrollPx: AtomicInteger,
        scrollMax: AtomicInteger,
    ) {
        val state = rememberScrollState()
        scrollPx.set(state.value)
        scrollMax.set(state.maxValue)
        Column(Modifier.fillMaxSize().verticalScroll(state)) {
            repeat(ROW_COUNT) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT_DP.dp)
                        .background(if (i % 2 == 0) Color.DarkGray else Color.Gray),
                )
            }
        }
    }

    /**
     * Place Compose's last pointer over the scrollable, through the same
     * CURSOR_MOVED wire the host uses for a real mouse. GTK motion injection
     * is unreliable as a send_event on Wayland; the discrete-scroll bug is
     * in the scroll handler, not the motion path.
     */
    private fun TaoWindowTestScope.movePointerOverContent() {
        val scale = window.scaleFactor
        val xFixed = (TARGET_X * scale * CURSOR_FIXED_SCALE).toInt()
        val yFixed = (TARGET_Y * scale * CURSOR_FIXED_SCALE).toInt()
        window.dispatch(TaoEventCode.CURSOR_MOVED, xFixed, yFixed)
    }

    private fun linuxOnly(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win") || os.contains("mac") || os.contains("darwin")) {
            "Linux only — discrete GdkEventScroll injection"
        } else {
            null
        }
    }

    private const val TARGET_X = 400
    private const val TARGET_Y = 300

    /** Must match `events.rs::CURSOR_FIXED_SCALE`. */
    private const val CURSOR_FIXED_SCALE = 1024f

    /** Thousandths: 1.0 in GDK's smooth-delta convention (positive Y = down). */
    private const val SMOOTH_DELTA_Y_MILLI = 1000

    private const val ROW_COUNT = 80
    private const val ROW_HEIGHT_DP = 24
}
