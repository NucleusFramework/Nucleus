package dev.nucleusframework.lab.probes.fixtures.rectstress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.system.exitProcess

private const val SPIN_NANOS_PER_NODE = 300_000L // ~0.3 ms x 120 nodes = ~36 ms tail
private const val DRAW_SPIN_NANOS = 25_000_000L // ~25 ms draw phase, past the 16 ms deadline

/**
 * The worst-case shape for the RectManagerEdtGuard: a throttled + debounced
 * [onLayoutRectChanged] entry whose rect moves every frame (a debounced trailing edge always
 * pending), a LazyColumn (mid-pass `dispatchCallbacks`), a >16 ms draw phase and a >16 ms
 * semantics-free placement tail (where an escaped EDT dispatch would fire), plus periodic
 * remounts (a fragmented RectList an escaped dispatch would corrupt while defragmenting).
 */
@Composable
internal fun StressContent() {
    var pulse by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    // Every frame: move the observed box, and remount the tail every ~20 frames.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            pulse++
            if (pulse % 20 == 0) tab = (tab + 1) % 2
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 1) The debounced entry. The throttle makes RectManager skip most inline fires and
        // defer them as a debounced trailing edge, whose deadline arms a real EDT dispatch.
        // The callback is the detector: on AWT-EventQueue it prints the proof and exits.
        Box(
            Modifier
                .offset { IntOffset(pulse % 200, 0) }
                .size(24.dp)
                .background(Color.Magenta)
                .onLayoutRectChanged(throttleMillis = 100, debounceMillis = 1) {
                    val thread = Thread.currentThread()
                    if (thread.name.startsWith("AWT-Event")) {
                        System.err.println(
                            "REPRODUCED #555: onLayoutRectChanged fired on ${thread.name} " +
                                "(RectManager delayed dispatch escaped to the EDT)",
                        )
                        Thread.dumpStack()
                        exitProcess(RectStressFixture.EXIT_ESCAPED)
                    }
                },
        )

        // 1b) Slow draw phase (>16 ms): a dispatch armed at the end of measure elapses while
        // the scene thread is still recording, so the EDT would run dispatchCallbacks concurrently.
        Box(
            Modifier
                .size(4.dp)
                .drawBehind { spin(DRAW_SPIN_NANOS) },
        )

        // 2) Per-node subcompose measures: mid-pass dispatchCallbacks re-arming the EDT dispatch.
        LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
            items(30) { row ->
                Row {
                    repeat(6) { col ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(if ((row + col + pulse / 60) % 2 == 0) Color.DarkGray else Color.Gray),
                        )
                    }
                }
            }
        }

        // 3) Long semantics-free tail, remounted periodically to keep the RectList fragmented.
        when (tab) {
            0 -> SlowTail(Color.Red)
            else -> SlowTail(Color.Blue)
        }
    }
}

@Composable
private fun SlowTail(color: Color) {
    Column(Modifier.fillMaxSize()) {
        repeat(12) { row ->
            Row {
                repeat(10) { col ->
                    Box(
                        Modifier
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    spin(SPIN_NANOS_PER_NODE)
                                    placeable.place(0, 0)
                                }
                            }.size(10.dp)
                            .background(if ((row + col) % 2 == 0) color else Color.LightGray),
                    )
                }
            }
        }
    }
}

/** Busy-waits: the slowness must be on this thread, not a suspension. */
private fun spin(nanos: Long) {
    val end = System.nanoTime() + nanos
    while (System.nanoTime() < end) Thread.onSpinWait()
}
