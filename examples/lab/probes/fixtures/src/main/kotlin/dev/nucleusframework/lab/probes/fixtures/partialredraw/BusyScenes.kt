package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.Text
import kotlinx.coroutines.delay

internal const val CARD_COUNT = 260
private const val LOREM =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore."

/** A full-window background and a counter toggling with nothing around them. */
@Composable
internal fun BareToggle() {
    var on by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            on = !on
            count++
        }
    }
    val colors = LabTheme.colors
    Box(Modifier.fillMaxSize().background(if (on) colors.selection else colors.raised)) {
        Text("$count", style = LabTheme.typography.title)
    }
}

/** The idle window plus a main-thread loop writing the same value: no change, so no frame (#754). */
@Composable
internal fun MainTick() {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            tick = 0
        }
    }
    BusyWindow(blink = false, isolated = true)
}

/** A dense, static UI — the kind whose full repaint the issue measured — plus the blinking box. */
@Composable
internal fun BusyWindow(
    blink: Boolean,
    isolated: Boolean,
) {
    Column(Modifier.fillMaxSize().padding(LabDimens.block)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Static content, $CARD_COUNT cards", style = LabTheme.typography.heading)
            Box(Modifier.width(LabDimens.indent))
            if (blink) BlinkingBox(isolated)
        }
        StaticCards()
    }
}

/** 8×8 px at 25 fps; [isolated] gives it a clipped layer of its own. */
@Composable
internal fun BlinkingBox(isolated: Boolean) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            on = !on
        }
    }
    val modifier = if (isolated) Modifier.graphicsLayer { clip = true } else Modifier
    val colors = LabTheme.colors
    Box(modifier.size(8.dp).background(if (on) colors.error else colors.accent))
}

@Composable
internal fun StaticCards() {
    val background = LabTheme.colors.background
    val heading = LabTheme.typography.heading
    val small = LabTheme.typography.small
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(CARD_COUNT) { i ->
            Box(
                Modifier
                    .width(150.dp)
                    .background(background, LabShapes.block)
                    .padding(6.dp),
            ) {
                Column {
                    Text("Card #$i", style = heading)
                    Text(LOREM, style = small, maxLines = 3)
                }
            }
        }
    }
}
