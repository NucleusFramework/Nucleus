package dev.nucleusframework.lab.probes.input.scroll

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.time.monotonicMillis
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.TargetArea
import dev.nucleusframework.lab.designsystem.Text
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val ROWS = 600
private const val STRIP_CELLS = 120

/** The measured scroll container: observes every event on the Initial pass, never consumes. */
@Composable
internal fun ScrollMeterArea(vm: ScrollViewModel) {
    val scrollState = rememberScrollState()

    // Frame-clock ticks: render fps over a rolling ~500 ms window, plus per-gesture frames.
    LaunchedEffect(vm) {
        var frames = 0
        var windowStart = 0L
        while (true) {
            withFrameNanos { ns ->
                frames++
                vm.onFrame()
                if (windowStart == 0L) windowStart = ns
                val elapsed = ns - windowStart
                if (elapsed >= 500_000_000L) {
                    vm.onFps((frames * 1_000_000_000.0 / elapsed).roundToInt())
                    frames = 0
                    windowStart = ns
                }
            }
        }
    }
    LaunchedEffect(vm) {
        while (true) {
            delay(40)
            vm.onTick(monotonicMillis(), scrollState.value, scrollState.maxValue)
        }
    }

    TargetArea(Modifier.height(320.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .pointerInput(vm) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            val kind =
                                when (event.type) {
                                    PointerEventType.Scroll -> ScrollKind.Scroll
                                    PointerEventType.PanStart -> ScrollKind.PanStart
                                    PointerEventType.PanMove -> ScrollKind.PanMove
                                    PointerEventType.PanEnd -> ScrollKind.PanEnd
                                    PointerEventType.ScaleStart -> ScrollKind.ScaleStart
                                    PointerEventType.ScaleChange -> ScrollKind.ScaleChange
                                    PointerEventType.ScaleEnd -> ScrollKind.ScaleEnd
                                    else -> continue
                                }
                            vm.onSample(
                                ScrollSample(
                                    kind = kind,
                                    nowMs = monotonicMillis(),
                                    delta = if (kind == ScrollKind.PanMove) change.panOffset else change.scrollDelta,
                                    scaleFactor = change.scaleFactor,
                                    scrollValuePx = scrollState.value,
                                    density = density,
                                ),
                            )
                        }
                    }
                },
        ) {
            repeat(ROWS) { i ->
                Text(
                    "Row %04d — scroll here with the trackpad and the mouse wheel".format(i),
                    style = LabTheme.typography.small,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 0) LabTheme.colors.panel else LabTheme.colors.raised)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Vertical and horizontal strips whose offsets tell the sign of each axis. */
@Composable
internal fun SignStrips() {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    Readout("Vertical offset", "${vertical.value} px")
    TargetArea(Modifier.height(140.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(vertical)) {
            repeat(STRIP_CELLS) { i ->
                Text(
                    "Row %03d".format(i),
                    style = LabTheme.typography.small,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 0) LabTheme.colors.raised else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
    Readout("Horizontal offset", "${horizontal.value} px")
    TargetArea(Modifier.height(44.dp)) {
        Row(Modifier.fillMaxHeight().horizontalScroll(horizontal)) {
            repeat(STRIP_CELLS) { i ->
                Box(
                    Modifier
                        .width(48.dp)
                        .fillMaxHeight()
                        .background(if (i % 2 == 0) LabTheme.colors.raised else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { Text("%02d".format(i), style = LabTheme.typography.small) }
            }
        }
    }
}
