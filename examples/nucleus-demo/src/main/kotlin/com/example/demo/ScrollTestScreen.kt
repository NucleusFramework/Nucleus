package com.example.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Diagnostic screen that measures scroll "impact" so the Tao backend can be
 * compared against a browser reference (see tools/scroll-test.html).
 *
 * Two quantities are captured per physical gesture (a burst of scroll events
 * separated from the next burst by [IDLE_MS] of silence):
 *  - raw scrollDelta (the `Offset(dxAwt, dyAwt)` the backend feeds Compose —
 *    this is exactly what the native WM_MOUSEWHEEL patch changes), and
 *  - pixels actually scrolled (the change in [androidx.compose.foundation.ScrollState.value],
 *    i.e. after Compose's WindowsWinUIConfig `height/20` scaling).
 */
private const val IDLE_MS = 180L
private const val ROWS = 600
private const val MAX_LOG = 14

private class ScrollMeter {
    var inGesture = false
    var startValuePx = 0
    var startTimeMs = 0L
    var lastTimeMs = 0L
    var events = 0
    var rawSumY = 0f
    var rawSumX = 0f
    var maxRawAbsY = 0f
    var lastRawY = 0f

    // Monotonic frame-clock tick counter (incremented in the withFrameNanos
    // loop) and its value at gesture start. The difference over the gesture
    // window measures render FPS — the metric the scroll-cadence fix changes.
    var frameCount = 0
    var startFrameCount = 0
}

private data class GestureStat(
    val index: Int,
    val events: Int,
    val rawSumY: Float,
    val pxScrolled: Int,
    val durationMs: Long,
    val maxRawAbsY: Float,
    val fps: Int,
)

@Composable
fun ScrollTestScreen() {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val meter = remember { ScrollMeter() }
    val gestures = remember { mutableStateListOf<GestureStat>() }
    var counter by remember { mutableStateOf(0) }
    var liveRawY by remember { mutableStateOf(0f) }
    var liveValue by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0) }

    // Live render FPS = frame-clock ticks per second. This is the metric the
    // scroll-cadence fix changes (the smooth-scroll tween ticks once per frame):
    // it should hold ~display refresh while scrolling, not drop to the
    // wheel-event rate (~20). Measured over a ~500ms rolling window.
    LaunchedEffect(Unit) {
        var frames = 0
        var windowStartNs = 0L
        while (true) {
            withFrameNanos { ns ->
                frames++
                meter.frameCount++
                if (windowStartNs == 0L) windowStartNs = ns
                val elapsed = ns - windowStartNs
                if (elapsed >= 500_000_000L) {
                    fps = (frames * 1_000_000_000.0 / elapsed).roundToInt()
                    frames = 0
                    windowStartNs = ns
                }
            }
        }
    }

    // Finalize a gesture once the scroll events go quiet for IDLE_MS. Both this
    // ticker and the pointer handler run on the UI dispatcher, so the shared
    // ScrollMeter needs no extra synchronization.
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            liveValue = scrollState.value
            val now = System.nanoTime() / 1_000_000
            if (meter.inGesture && now - meter.lastTimeMs >= IDLE_MS) {
                val px = scrollState.value - meter.startValuePx
                // Render FPS over the whole gesture window (start → finalize, i.e.
                // including the post-input animation tail) = frames rendered ÷
                // wall-clock. This is what the cadence fix should lift toward the
                // display refresh; ~20 means the tween only ticks at wheel rate.
                val windowMs = (now - meter.startTimeMs).coerceAtLeast(1)
                val gestureFrames = meter.frameCount - meter.startFrameCount
                gestures.add(
                    0,
                    GestureStat(
                        index = ++counter,
                        events = meter.events,
                        rawSumY = meter.rawSumY,
                        pxScrolled = px,
                        durationMs = (meter.lastTimeMs - meter.startTimeMs).coerceAtLeast(0),
                        maxRawAbsY = meter.maxRawAbsY,
                        fps = (gestureFrames * 1000L / windowMs).toInt(),
                    ),
                )
                if (gestures.size > MAX_LOG) gestures.removeAt(gestures.lastIndex)
                meter.inGesture = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatsPanel(
                gestures = gestures,
                liveRawY = liveRawY,
                value = liveValue,
                maxValue = scrollState.maxValue,
                fps = fps,
                onReset = {
                    gestures.clear()
                    counter = 0
                },
                onCopy = { clipboard.setText(AnnotatedString(buildClipboardText(gestures))) },
            )
            HorizontalDivider()
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        // Initial pass: observe before the scrollable
                                        // consumes it. We never consume — scrolling
                                        // must still happen normally.
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        // Wheel notches arrive as Scroll (AWT wheel units);
                                        // on the Tao backend a trackpad gesture arrives as
                                        // Pan with a pixel offset — 10 dp per wheel unit
                                        // (Compose's MacOSCocoaConfig factor), so both are
                                        // logged in the same unit.
                                        val change = event.changes.first()
                                        val d =
                                            when (event.type) {
                                                PointerEventType.Scroll -> change.scrollDelta
                                                PointerEventType.PanMove -> change.panOffset / (10f * density)
                                                else -> continue
                                            }
                                        val now = System.nanoTime() / 1_000_000
                                        if (!meter.inGesture || now - meter.lastTimeMs > IDLE_MS) {
                                            meter.inGesture = true
                                            meter.startValuePx = scrollState.value
                                            meter.startTimeMs = now
                                            meter.startFrameCount = meter.frameCount
                                            meter.events = 0
                                            meter.rawSumY = 0f
                                            meter.rawSumX = 0f
                                            meter.maxRawAbsY = 0f
                                        }
                                        meter.events++
                                        meter.rawSumY += d.y
                                        meter.rawSumX += d.x
                                        meter.maxRawAbsY = max(meter.maxRawAbsY, abs(d.y))
                                        meter.lastTimeMs = now
                                        meter.lastRawY = d.y
                                        liveRawY = d.y
                                    }
                                }
                            },
                ) {
                    repeat(ROWS) { i ->
                        Text(
                            text = "Row %04d — scroll here with the trackpad and the mouse wheel".format(i),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (i % 2 == 0) {
                                            MaterialTheme.colorScheme.surface
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ).padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(
    gestures: List<GestureStat>,
    liveRawY: Float,
    value: Int,
    maxValue: Int,
    fps: Int,
    onReset: () -> Unit,
    onCopy: () -> Unit,
) {
    val avgPx = if (gestures.isEmpty()) 0f else gestures.map { it.pxScrolled }.average().toFloat()
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scroll Impact Meter", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(16.dp))
            Button(onClick = onCopy, enabled = gestures.isNotEmpty()) { Text("Copy (TSV)") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onReset) { Text("Reset") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "live rawΔy = %+.3f   |   offset = %d / %d   |   avg px/gesture = %.0f   |   render = %d fps"
                .format(liveRawY, value, maxValue, avgPx, fps),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            HeaderCell("#", 40)
            HeaderCell("events", 70)
            HeaderCell("Σ rawΔy", 90)
            HeaderCell("px scrolled", 110)
            HeaderCell("ms", 60)
            HeaderCell("max |rawΔy|", 110)
            HeaderCell("px / event", 90)
            HeaderCell("fps", 60)
        }
        gestures.forEach { g ->
            Row {
                Cell("${g.index}", 40)
                Cell("${g.events}", 70)
                Cell("%+.2f".format(g.rawSumY), 90)
                Cell("%+d".format(g.pxScrolled), 110)
                Cell("${g.durationMs}", 60)
                Cell("%.2f".format(g.maxRawAbsY), 110)
                Cell(if (g.events == 0) "-" else "%+.1f".format(g.pxScrolled.toFloat() / g.events), 90)
                Cell("${g.fps}", 60)
            }
        }
    }
}

/** Builds a tab-separated dump of the gesture log, ready to paste into a sheet. */
private fun buildClipboardText(gestures: List<GestureStat>): String {
    val avgPx = if (gestures.isEmpty()) 0f else gestures.map { it.pxScrolled }.average().toFloat()
    val os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
    val l = java.util.Locale.ROOT
    val sb = StringBuilder()
    sb.append("# Scroll Test — Compose (Tao backend) — $os\n")
    sb.append("# gestures=${gestures.size}\tavgPxPerGesture=%.0f\n".format(l, avgPx))
    sb.append("idx\tevents\trawSumY\tpxScrolled\tms\tmaxRawAbsY\tpxPerEvent\tfps\n")
    // Oldest first for natural reading; the on-screen list is newest first.
    gestures.asReversed().forEach { g ->
        val pxPerEvent = if (g.events == 0) 0f else g.pxScrolled.toFloat() / g.events
        sb.append(
            "%d\t%d\t%.2f\t%d\t%d\t%.2f\t%.2f\t%d\n"
                .format(l, g.index, g.events, g.rawSumY, g.pxScrolled, g.durationMs, g.maxRawAbsY, pxPerEvent, g.fps),
        )
    }
    return sb.toString()
}

@Composable
private fun HeaderCell(
    text: String,
    width: Int,
) {
    Text(
        text,
        modifier = Modifier.width(width.dp),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Cell(
    text: String,
    width: Int,
) {
    Text(
        text,
        modifier = Modifier.width(width.dp),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}
