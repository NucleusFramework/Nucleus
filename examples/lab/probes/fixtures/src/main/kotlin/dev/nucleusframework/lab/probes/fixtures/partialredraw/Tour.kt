package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import kotlinx.coroutines.delay

private val TOUR_NAMES =
    listOf(
        "isolated blink",
        "plain blink",
        "rotation",
        "placement + translation",
        "alpha",
        "elevation",
        "blur",
        "explicit layer",
        "animated visibility",
        "dropdown menu",
        "scroll",
        "item reorder",
        "caret",
    )

/** Every kind of change, one after the other every 1.5 s, then all together. */
@Composable
internal fun Tour() {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            step++
        }
    }
    val steps = TOUR_NAMES.size
    val phase = step % steps
    val all = step >= steps
    Column(Modifier.fillMaxSize().padding(LabDimens.block), verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        Text("Tour step ${step + 1} — ${TOUR_NAMES[phase]}", style = LabTheme.typography.heading)
        Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.block)) {
            if (all || phase == 0) BlinkingBox(isolated = true)
            if (all || phase == 1) BlinkingBox(isolated = false)
            if (all || phase == 2) Rotating()
            if (all || phase == 3) Sliding()
            if (all || phase == 4) Fading()
            if (all || phase == 5) Elevating()
            if (all || phase == 6) Blurring()
            if (all || phase == 7) ExplicitLayer()
            if (all || phase == 8) Appearing()
            if (all || phase == 9) TourMenu()
        }
        Row(Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(LabDimens.block)) {
            ScrollingList(scroll = all || phase == 10)
            ReorderingList(reorder = all || phase == 11)
            Caret(focus = all || phase == 12)
        }
        StaticCards()
    }
}

@Composable
private fun Rotating() {
    val angle by rememberInfiniteTransition().animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)),
    )
    Box(Modifier.size(40.dp).graphicsLayer { rotationZ = angle }.background(LabTheme.colors.accent))
}

@Composable
private fun Sliding() {
    val x by rememberInfiniteTransition().animateFloat(0f, 60f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    val colors = LabTheme.colors
    Box(Modifier.size(100.dp, 40.dp)) {
        // Placement only, no layer of its own.
        Box(Modifier.offset { IntOffset(x.toInt(), 0) }.size(30.dp).background(colors.ok))
        // Through a layer's translation.
        Box(Modifier.graphicsLayer { translationY = x / 6 }.size(10.dp).background(colors.error))
    }
}

@Composable
private fun Fading() {
    val alpha by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Box(Modifier.size(40.dp).graphicsLayer { this.alpha = alpha }.background(LabTheme.colors.warning))
}

@Composable
private fun Elevating() {
    val elevation by rememberInfiniteTransition().animateFloat(
        0f,
        16f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
    )
    // What the Material Card it replaces drew: a shadow layer (none at 0, no clip), then a
    // clipped surface.
    val shape = LabShapes.specimen
    val shadow = with(LocalDensity.current) { elevation.dp.toPx() }
    Box(
        Modifier
            .size(60.dp, 40.dp)
            .then(if (shadow > 0f) Modifier.graphicsLayer(shadowElevation = shadow, shape = shape) else Modifier)
            .background(LabTheme.colors.raised, shape)
            .clip(shape),
    ) {
        Text("Card", Modifier.padding(6.dp))
    }
}

@Composable
private fun Blurring() {
    val radius by rememberInfiniteTransition().animateFloat(0f, 8f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Text("Blur", Modifier.blur(radius.dp), style = LabTheme.typography.title)
}

/** An explicit graphics layer drawn by the node that records it — the screenshot pattern. */
@Composable
private fun ExplicitLayer() {
    val layer = rememberGraphicsLayer()
    val colors = LabTheme.colors
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            tick++
        }
    }
    Box(
        Modifier
            .size(40.dp)
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }.background(if (tick % 2 == 0) colors.accent else colors.warning),
    )
}

@Composable
private fun Appearing() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    Box(Modifier.size(60.dp, 40.dp)) {
        AnimatedVisibility(
            visible,
        ) { Box(Modifier.size(40.dp).clip(LabShapes.specimen).background(LabTheme.colors.ok)) }
    }
}
