package resizelagdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar

// scripts/macos-resize-lag-e2e.sh (#953) records this window frame by frame while it is resized and reads
// the colours back: a fixed-width rail on the left, a red stripe on the right edge and a green one on the
// bottom edge. In a frame laid out for the size on screen the rail starts at the left edge and both
// stripes touch their edges.
// RESIZE_LAG_ANIMATE=1 moves a square along the top and prints frame-interval stats every 2 s, to check
// that ordinary frames keep their pacing.
private const val WINDOW_TITLE = "ResizeLagDemo"

fun main(args: Array<String>) =
    nucleusApplication(args, enableSingleInstance = false) {
        NucleusDecoratedWindowTheme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = WINDOW_TITLE,
                state = rememberWindowState(size = DpSize(800.dp, 500.dp), position = WindowPosition(100.dp, 100.dp)),
            ) {
                TitleBar { BasicText(WINDOW_TITLE, style = TextStyle(color = Color.White)) }
                Box(Modifier.fillMaxSize().background(Color(0xFF2060C0))) {
                    Box(Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF303030)))
                    if (System.getenv("RESIZE_LAG_ANIMATE") == "1") MovingSquare()
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(6.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFFF0000)),
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color(0xFF00FF00)),
                    )
                }
            }
        }
    }

@Composable
private fun MovingSquare() {
    var x by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val intervals = ArrayList<Long>()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) intervals += now - last
                last = now
                x = (now / 4_000_000L % 400).toFloat()
                if (intervals.size >= 180) {
                    intervals.sort()
                    val ms = { i: Int -> intervals[i] / 1_000_000.0 }
                    val late = intervals.count { it > 16_000_000L }
                    println(
                        "FRAMES p50=%.2f p95=%.2f p99=%.2f max=%.2f over16ms=%d".format(
                            ms(intervals.size / 2),
                            ms(intervals.size * 95 / 100),
                            ms(intervals.size * 99 / 100),
                            ms(intervals.size - 1),
                            late,
                        ),
                    )
                    intervals.clear()
                }
            }
        }
    }
    Box(Modifier.padding(start = (220 + x).dp, top = 8.dp).size(20.dp).background(Color.White))
}
