package dev.nucleusframework.lab.probes.rendering.partialredraw

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.lab.designsystem.Tone
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Draw passes of the sample's two regions, counted off-composition from their draw lambdas. */
@Stable
internal class DamageCounters {
    val staticDraws = AtomicInteger()
    val animatedDraws = AtomicInteger()
}

/**
 * The counters as readouts, re-read twice a second in a scope of their own so the state they
 * hold never invalidates the sample being measured.
 */
@Composable
internal fun DamageReadouts(counters: DamageCounters) {
    var shownStatic by remember { mutableIntStateOf(0) }
    var shownAnimated by remember { mutableIntStateOf(0) }
    LaunchedEffect(counters) {
        while (true) {
            delay(500)
            shownStatic = counters.staticDraws.get()
            shownAnimated = counters.animatedDraws.get()
        }
    }
    Readout(
        "static panel draws",
        shownStatic.toString(),
        tone = if (shownStatic > 2) Tone.Warning else Tone.Neutral,
    )
    Readout("animated region draws", shownAnimated.toString())
}

/**
 * A static panel in its own layer beside an animated region. The static panel's draw count
 * must stay at its first value whatever the scene does: that is the layer isolation the
 * damage tracker relies on.
 */
@Composable
internal fun DamageSample(
    scene: DamageScene,
    intervalMillis: Long,
    counters: DamageCounters,
) {
    SpecimenFrame(height = 220.dp) {
        when (scene) {
            DamageScene.Idle -> Unit
            DamageScene.Blink -> Blinker(intervalMillis, counters.animatedDraws)
            DamageScene.Sweep -> Sweep(intervalMillis, counters.animatedDraws)
            DamageScene.Counter -> TickingText(counters.animatedDraws)
            DamageScene.FullFrame -> FullFrame(intervalMillis, counters.animatedDraws)
        }
        // Drawn last, so even the full-frame scene leaves it visible.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(160.dp, 80.dp)
                .graphicsLayer { clip = true }
                .drawBehind { counters.staticDraws.incrementAndGet() }
                .background(LabTheme.colors.selection, LabShapes.block)
                .padding(8.dp),
        ) {
            Text("static panel", style = LabTheme.typography.mono)
        }
    }
}

@Composable
private fun Blinker(
    intervalMillis: Long,
    draws: AtomicInteger,
) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            on = !on
        }
    }
    Box(
        Modifier
            .padding(24.dp)
            .size(48.dp)
            .graphicsLayer { clip = true }
            .drawBehind {
                draws.incrementAndGet()
                drawRect(if (on) Color(0xFF34D399) else Color(0xFF1F2937))
            },
    )
}

@Composable
private fun Sweep(
    intervalMillis: Long,
    draws: AtomicInteger,
) {
    val progress by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((intervalMillis * 6).toInt(), easing = LinearEasing)),
        label = "x",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 120.dp)
            .height(36.dp)
            .graphicsLayer { clip = true },
    ) {
        val width = maxWidth
        Box(
            Modifier
                .offset { IntOffset(((width.toPx() - 24.dp.toPx()) * progress).toInt(), 0) }
                .width(24.dp)
                .fillMaxSize()
                .drawBehind {
                    draws.incrementAndGet()
                    drawRect(Color(0xFF60A5FA))
                },
        )
    }
}

@Composable
private fun TickingText(draws: AtomicInteger) {
    var value by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            value++
        }
    }
    Box(Modifier.padding(24.dp).graphicsLayer { clip = true }.drawBehind { draws.incrementAndGet() }) {
        Text("tick %06d".format(Locale.ROOT, value), style = LabTheme.typography.mono)
    }
}

@Composable
private fun FullFrame(
    intervalMillis: Long,
    draws: AtomicInteger,
) {
    var hue by remember { mutableStateOf(0f) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            hue = (hue + 37f) % 360f
        }
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            draws.incrementAndGet()
            drawRect(Color.hsv(hue, 0.4f, 0.5f))
        },
    )
}
