package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GRID = 16

/** A clipped layer of its own: what keeps a change inside it from re-recording the window. */
internal val Isolated = Modifier.graphicsLayer { clip = true }

private fun randomColor(rng: Random) = Color(0xFF000000 or rng.nextLong(0xFFFFFF))

/** A grid of small cells recoloured at random; odd cells have a layer of their own. */
@Composable
internal fun ChurningGrid(
    rng: Random,
    paused: Boolean,
    burst: Int,
) {
    val initial = LabTheme.colors.borderStrong
    val colors = remember { mutableStateListOf(*Array(GRID * GRID) { initial }) }
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
internal fun Wanderer(
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
    val colors = LabTheme.colors
    Box(Isolated.size(240.dp, 130.dp).background(colors.background)) {
        Box(Modifier.offset { IntOffset(x, y) }.size(20.dp).background(colors.ok))
        Box(
            Modifier
                .graphicsLayer {
                    translationX = (200 - x).toFloat()
                    translationY = (100 - y).toFloat()
                }.size(16.dp)
                .background(colors.error),
        )
    }
}

/** A colour written from a plain thread at ~1 kHz and read only by a draw lambda. */
@Composable
internal fun BackgroundWriter(paused: Boolean) {
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
                    // Paused or disposed: the writer ends.
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            awaitCancellation()
        } finally {
            thread.interrupt()
        }
    }
    Box(Modifier.size(40.dp).graphicsLayer { clip = true }.drawBehind { drawRect(color.value) })
}

/** Items appearing and disappearing. */
@Composable
internal fun Blinkers(
    rng: Random,
    paused: Boolean,
) {
    val visible = remember { mutableStateListOf(*Array(6) { true }) }
    val colors = LabTheme.colors
    val palette = listOf(colors.accent, colors.ok, colors.warning, colors.error, colors.selection, colors.borderStrong)
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
                if (shown) Box(Modifier.size(24.dp).background(palette[i]))
            }
        }
    }
}
