package com.example.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.WindowBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.logging.Logger
import kotlin.random.Random

/**
 * `torture`: random window-level chaos over content that keeps changing in
 * small, partial-friendly pieces, for the `nucleus.tao.partialRedraw.verify`
 * oracle to check frame by frame. `-Dpartial.demo.seed=N` replays a run,
 * `-Dpartial.demo.events=a,b` restricts the chaos to those events.
 *
 * `-Dpartial.demo.settleDir=<dir>` adds a `settle` event, the oracle that
 * needs no `verify` (which presents every frame and slows each one down):
 * the content freezes, the app writes `<n>-a` and waits for `<n>-a.done`
 * while a script captures the window, then repaints in full (the clear
 * colour flipped and back), writes `<n>-b` and waits again. Both captures
 * must be identical: a difference is a stale region the partial frames left.
 */
@Composable
internal fun DecoratedWindowScope.Torture(state: WindowState) {
    val seed = System.getProperty("partial.demo.seed")?.toLongOrNull() ?: System.nanoTime()
    val only = System.getProperty("partial.demo.events")?.split(',')?.map { it.trim() }?.toSet()
    val rng = remember { Random(seed) }
    var background by remember { mutableStateOf(Color(0xFFF4F4F6)) }
    var paused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var burst by remember { mutableIntStateOf(0) }
    var event by remember { mutableStateOf("start") }
    WindowBackground(background)

    LaunchedEffect(Unit) {
        tortureLogger.info("Torture seed=$seed events=${only ?: "all"}")
        val events =
            TortureEvent.entries.filter {
                (only == null || it.id in only) && (it != TortureEvent.Settle || settleDir != null)
            }
        var count = 0
        while (isActive) {
            delay(rng.nextLong(150, 1500))
            val next = events[rng.nextInt(events.size)]
            event = "${++count} ${next.id}"
            tortureLogger.info("Torture event $event")
            val base = state.size
            when (next) {
                TortureEvent.AnimateSize -> {
                    val target = randomSize(rng)
                    val start = state.size
                    val frames = rng.nextInt(4, 30)
                    for (i in 1..frames) {
                        withFrameNanos { }
                        val t = i.toFloat() / frames
                        state.size =
                            DpSize(
                                start.width + (target.width - start.width) * t,
                                start.height + (target.height - start.height) * t,
                            )
                    }
                }
                TortureEvent.Bounce -> {
                    repeat(rng.nextInt(1, 6)) {
                        state.size = DpSize(base.width + rng.nextInt(1, 40).dp, base.height + rng.nextInt(-20, 20).dp)
                        if (rng.nextBoolean()) withFrameNanos { }
                        state.size = base
                        withFrameNanos { }
                    }
                }
                TortureEvent.Maximize -> {
                    state.placement = WindowPlacement.Maximized
                    delay(rng.nextLong(100, 1500))
                    state.placement = WindowPlacement.Floating
                }
                TortureEvent.Fullscreen -> {
                    state.placement = WindowPlacement.Fullscreen
                    delay(rng.nextLong(1500, 3000))
                    state.placement = WindowPlacement.Floating
                    delay(1500)
                }
                TortureEvent.Minimize -> {
                    state.isMinimized = true
                    delay(rng.nextLong(200, 1500))
                    state.isMinimized = false
                }
                TortureEvent.ClearColor -> {
                    background = Color(0xFF000000 or rng.nextLong(0xFFFFFF))
                }
                TortureEvent.LongPause -> {
                    paused = true
                    delay(rng.nextLong(1050, 2500))
                    paused = false
                }
                TortureEvent.ShortPause -> {
                    paused = true
                    delay(rng.nextLong(700, 999))
                    paused = false
                }
                TortureEvent.Burst -> burst++
                TortureEvent.Settle -> {
                    paused = true
                    menuOpen = false
                    delay(SETTLE_QUIET_MS)
                    handshake(settleDir!!, "$count-a")
                    val kept = background
                    background = Color(kept.toArgb().inv() or 0xFF000000.toInt())
                    repeat(3) { withFrameNanos { } }
                    background = kept
                    delay(SETTLE_QUIET_MS)
                    handshake(settleDir, "$count-b")
                    paused = false
                }
                TortureEvent.Menu -> {
                    menuOpen = true
                    delay(rng.nextLong(50, 800))
                    menuOpen = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Torture — event $event", Modifier.then(Isolated), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Box {
                Text("menu", Modifier.background(Color(0xFFE0E0E0)).padding(horizontal = 4.dp), fontSize = 12.sp)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("One") }, onClick = {})
                    DropdownMenuItem(text = { Text("Two") }, onClick = {})
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChurningGrid(rng, paused, burst)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Wanderer(rng, paused)
                BackgroundWriter(paused)
                Blinkers(rng, paused)
            }
        }
    }
}

private enum class TortureEvent(
    val id: String,
) {
    AnimateSize("animate-size"),
    Bounce("bounce"),
    Maximize("maximize"),
    Fullscreen("fullscreen"),
    Minimize("minimize"),
    ClearColor("clear-color"),
    LongPause("long-pause"),
    ShortPause("short-pause"),
    Burst("burst"),
    Menu("menu"),
    Settle("settle"),
}

private val settleDir = System.getProperty("partial.demo.settleDir")?.let(::File)

/** Writes [name] into [dir] and waits (bounded) for the script's `<name>.done`. */
private suspend fun handshake(
    dir: File,
    name: String,
) {
    val done = File(dir, "$name.done")
    File(dir, name).writeText(name)
    val deadline = System.nanoTime() + SETTLE_TIMEOUT_NS
    while (!done.exists() && System.nanoTime() < deadline) delay(20)
    if (!done.exists()) tortureLogger.warning("Torture settle $name: no capture")
}

private const val SETTLE_QUIET_MS = 600L
private const val SETTLE_TIMEOUT_NS = 10_000_000_000L

private fun randomSize(rng: Random) = DpSize(rng.nextInt(500, 1300).dp, rng.nextInt(400, 900).dp)

/** A grid of small cells, each in its own clipped layer, recoloured at random. */
@Composable
private fun ChurningGrid(
    rng: Random,
    paused: Boolean,
    burst: Int,
) {
    val colors = remember { mutableStateListOf(*Array(GRID * GRID) { Color.Gray }) }
    LaunchedEffect(paused) {
        while (!paused) {
            delay(rng.nextLong(1, 40))
            repeat(rng.nextInt(1, 4)) { colors[rng.nextInt(colors.size)] = randomColor(rng) }
        }
    }
    LaunchedEffect(burst) {
        if (burst > 0) for (i in colors.indices) colors[i] = randomColor(rng)
    }
    Column(Isolated, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until GRID) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until GRID) {
                    val index = row * GRID + col
                    // Odd cells get a layer of their own, even ones re-record their parent.
                    val layer = if (index % 2 == 1) Modifier.graphicsLayer { clip = true } else Modifier
                    Box(layer.size(14.dp).background(colors[index]))
                }
            }
        }
    }
}

/** A box walking through placement (no layer) and a layer walking through its translation. */
@Composable
private fun Wanderer(
    rng: Random,
    paused: Boolean,
) {
    var x by remember { mutableIntStateOf(0) }
    var y by remember { mutableIntStateOf(0) }
    LaunchedEffect(paused) {
        while (!paused) {
            withFrameNanos { }
            x = (x + rng.nextInt(-6, 7)).coerceIn(0, 200)
            y = (y + rng.nextInt(-6, 7)).coerceIn(0, 100)
        }
    }
    Box(Isolated.size(240.dp, 130.dp).background(Color.White)) {
        Box(Modifier.offset { IntOffset(x, y) }.size(20.dp).background(Color(0xFF009688)))
        Box(
            Modifier
                .graphicsLayer {
                    translationX = (200 - x).toFloat()
                    translationY = (100 - y).toFloat()
                }.size(16.dp)
                .background(Color(0xFFE91E63)),
        )
    }
}

/** A colour written from a plain thread at ~1 kHz and read only by a draw lambda. */
@Composable
private fun BackgroundWriter(paused: Boolean) {
    val color = remember { mutableStateOf(Color.Black) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val thread =
            Thread {
                var i = 0
                try {
                    while (true) {
                        color.value = Color(0xFF000000 or ((i++ * 0x0A0B0C).toLong() and 0xFFFFFF))
                        Thread.sleep(1)
                    }
                } catch (_: InterruptedException) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            thread.interrupt()
        }
    }
    Box(Modifier.size(40.dp).graphicsLayer { clip = true }.drawBehind { drawRect(color.value) })
}

/** Items appearing and disappearing. */
@Composable
private fun Blinkers(
    rng: Random,
    paused: Boolean,
) {
    val visible = remember { mutableStateListOf(*Array(6) { true }) }
    LaunchedEffect(paused) {
        while (!paused) {
            delay(rng.nextLong(10, 120))
            val i = rng.nextInt(visible.size)
            visible[i] = !visible[i]
        }
    }
    Row(Isolated, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEachIndexed { i, shown ->
            Box(Modifier.size(24.dp)) {
                if (shown) Box(Modifier.size(24.dp).background(Color(0xFF3F51B5 + i * 0x101010)))
            }
        }
    }
}

private fun randomColor(rng: Random) = Color(0xFF000000 or rng.nextLong(0xFFFFFF))

private const val GRID = 16

/** A clipped layer of its own: what keeps a change inside it from re-recording the window. */
private val Isolated = Modifier.graphicsLayer { clip = true }

private val tortureLogger: Logger = Logger.getLogger("com.example.partialredraw.Torture")
