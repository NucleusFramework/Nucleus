package com.example.rectstress

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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar

/**
 * Sentinel demo for the RectManager EDT escape fixed in #551 / PR #554 and
 * tracked upstream (RectManager's `postDelayed` runs on skiko's hardcoded
 * Swing EDT dispatcher).
 *
 * It deliberately assembles the worst-case shape for the RectManagerEdtGuard:
 * a throttled+debounced [onLayoutRectChanged] entry whose rect moves every
 * frame (keeps a debounced trailing edge permanently pending), a LazyColumn
 * (mid-pass `dispatchCallbacks`), a >16ms draw phase and a >16ms
 * semantics-free placement tail (windows where an escaped EDT dispatch would
 * fire), plus periodic remounts (keeps the RectList fragmented so an escaped
 * dispatch would defragment concurrently and corrupt it).
 *
 * The rect callback doubles as the detector: RectManager must only ever
 * invoke it on the scene thread. If a delayed dispatch escapes to the EDT,
 * the app prints the proof and exits with code 55. On the guarded backend
 * this must never trigger: the pinned deadline cannot be lowered outside the
 * guard's post-frame hook (`triggerDebounced` early-returns on a future
 * deadline, and the mid-pass lowering branch in
 * `ThrottledCallbacks.fireWithUpdatedRect`/`fire` is an upstream no-op that
 * writes the old deadline back). On an unguarded backend (upstream Compose
 * 1.12 driven off the EDT) the same shape crashes with
 * `IllegalArgumentException: LayoutNode not found in RectList`.
 */
fun main(args: Array<String>) =
    nucleusApplication(args) {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            title = "RectManager stress (#555)",
        ) {
            TitleBar { BasicText("RectManager stress (#555)") }
            StressContent()
        }
    }

private const val SPIN_NANOS_PER_NODE = 300_000L // ~0.3ms x 120 nodes = ~36ms tail
private const val DRAW_SPIN_NANOS = 25_000_000L // ~25ms draw phase, past the 16ms deadline

@Suppress("FunctionNaming")
@Composable
private fun StressContent() {
    var pulse by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(0) }

    // Advance every frame: moves the observed box (keeps the debounced entry
    // firing and lowering the deadline) and remounts the tail every ~20
    // frames (keeps the RectList fragmented so the EDT dispatch defragments).
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            pulse++
            if (pulse % 20 == 0) tab = (tab + 1) % 2
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 1) The debounced entry: rect changes every frame, but the throttle
        // makes RectManager SKIP most inline fires and defer them as a
        // debounced trailing edge - that pending deadline is what lowers the
        // guard's pinned deadline and arms a real (fireable) EDT dispatch.
        // The callback itself is the detector - RectManager must only ever
        // invoke it on the scene thread. If the residual EDT dispatch fires,
        // the trailing edge runs on AWT-EventQueue-0: print the proof and
        // kill the app.
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
                        Thread.getAllStackTraces()
                        kotlin.system.exitProcess(55)
                    }
                },
        )

        // 1b) Slow draw phase (>16ms): the dispatch armed at measure end at
        // the entry's real deadline elapses while the scene thread is still
        // recording - the EDT then runs dispatchCallbacks concurrently.
        Box(
            Modifier
                .size(4.dp)
                .drawBehind {
                    val end = System.nanoTime() + DRAW_SPIN_NANOS
                    while (System.nanoTime() < end) {
                        Thread.onSpinWait()
                    }
                },
        )

        // 2) Per-node subcompose measures: mid-pass dispatchCallbacks that
        // re-arm the EDT dispatch at the entry's real deadline.
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

        // 3) Long semantics-free tail: >16ms of placements after the re-arm,
        // remounted periodically to keep the RectList fragmented.
        when (tab) {
            0 -> SlowTail(Color.Red)
            else -> SlowTail(Color.Blue)
        }
    }
}

@Suppress("FunctionNaming")
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
                                    val end = System.nanoTime() + SPIN_NANOS_PER_NODE
                                    while (System.nanoTime() < end) {
                                        Thread.onSpinWait()
                                    }
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
