package com.example.partialredraw

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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Partial redraw demo and end-to-end fixture (#755).
 *
 * `-Dpartial.demo.scene=`:
 *  - `blink` (default) — the issue's measurement: a busy, static window and an
 *    8×8 px box toggling colour at 25 fps, isolated in its own clipped layer;
 *  - `blink-plain` — the same box without a layer of its own: its change
 *    re-records the layer around it, which is what gets repainted;
 *  - `idle` — the busy window alone, nothing animating;
 *  - `bare` — a full-window background and a counter with no layer of the
 *    app's own around them (no Surface, no clip);
 *  - `tour` — a scripted sequence of every kind of change the damage tracker
 *    has to get right (content, placement, transform, alpha, scroll, shadow,
 *    blur, item animations, popups, explicit layers, a caret), run under
 *    `-Dnucleus.tao.partialRedraw.verify=true` by the end-to-end check;
 *  - `torture` — random window-level chaos (resizes, fullscreen, minimize,
 *    clear colour, pauses, menus) over partial-friendly churn, see [Torture].
 *
 * `-Dpartial.demo.exitAfterSeconds=N` closes the app after N seconds;
 * `-Dpartial.demo.maximized=true` opens it maximized (the power measurement).
 */
fun main(args: Array<String>) {
    val scene = System.getProperty("partial.demo.scene") ?: "blink"
    val exitAfter = System.getProperty("partial.demo.exitAfterSeconds")?.toLongOrNull()
    val maximized = System.getProperty("partial.demo.maximized") == "true"
    nucleusApplication(args) {
        val windowState =
            rememberWindowState(
                placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                size = DpSize(1100.dp, 760.dp),
            )
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Partial redraw ($scene)",
        ) {
            TitleBar { Text("Partial redraw — $scene", color = Color.White) }
            if (exitAfter != null) {
                LaunchedEffect(Unit) {
                    delay(exitAfter * 1000)
                    exitProcess(0)
                }
            }
            if (scene == "bare") {
                // No Surface, no clip, no layer of the app's own: the root's
                // own layer is all there is.
                BareToggle()
                return@DecoratedWindow
            }
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F4F6)) {
                    when (scene) {
                        "tour" -> Tour()
                        "torture" -> Torture(windowState)
                        else -> BusyWindow(blink = scene != "idle", isolated = scene != "blink-plain")
                    }
                }
            }
        }
    }
}

/** A full-window background and a counter toggling with nothing around them. */
@Composable
private fun BareToggle() {
    var on by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            on = !on
            count++
        }
    }
    Box(Modifier.fillMaxSize().background(if (on) Color(0xFF203040) else Color(0xFF402030))) {
        Text("$count", color = Color.White, fontSize = 40.sp)
    }
}

/** A dense, static UI — the kind whose full repaint the issue measured — plus the blinking box. */
@Composable
private fun BusyWindow(
    blink: Boolean,
    isolated: Boolean,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Static content, ${CARD_COUNT} cards", fontWeight = FontWeight.Bold)
            Box(Modifier.width(16.dp))
            if (blink) BlinkingBox(isolated)
        }
        StaticCards()
    }
}

@Composable
private fun BlinkingBox(isolated: Boolean) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(40)
            on = !on
        }
    }
    val modifier = if (isolated) Modifier.graphicsLayer { clip = true } else Modifier
    Box(modifier.size(8.dp).background(if (on) Color.Red else Color.Blue))
}

@Composable
private fun StaticCards() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(CARD_COUNT) { i ->
            Box(
                Modifier
                    .width(150.dp)
                    .background(Color(0xFFFFFFFF), RoundedCornerShape(6.dp))
                    .padding(6.dp),
            ) {
                Column {
                    Text("Card #$i", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text(LOREM, fontSize = 10.sp, maxLines = 3)
                }
            }
        }
    }
}

/** Every kind of change, one after the other, then all together. */
@Composable
private fun Tour() {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            step++
        }
    }
    val phase = step % TOUR_STEPS
    val all = step >= TOUR_STEPS
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tour step ${step + 1} — ${TOUR_NAMES[phase]}", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (all || phase == 0) BlinkingBox(isolated = true)
            if (all || phase == 1) BlinkingBox(isolated = false)
            if (all || phase == 2) Rotating()
            if (all || phase == 3) Sliding()
            if (all || phase == 4) Fading()
            if (all || phase == 5) Elevating()
            if (all || phase == 6) Blurring()
            if (all || phase == 7) ExplicitLayer()
            if (all || phase == 8) Appearing()
            if (all || phase == 9) Menu()
        }
        Row(Modifier.height(260.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    Box(Modifier.size(40.dp).graphicsLayer { rotationZ = angle }.background(Color(0xFF3F51B5)))
}

@Composable
private fun Sliding() {
    val x by rememberInfiniteTransition().animateFloat(0f, 60f, infiniteRepeatable(tween(700), RepeatMode.Reverse))
    Box(Modifier.size(100.dp, 40.dp)) {
        // Placement only, no layer of its own.
        Box(Modifier.offset { IntOffset(x.toInt(), 0) }.size(30.dp).background(Color(0xFF009688)))
        // Through a layer's translation.
        Box(Modifier.graphicsLayer { translationY = x / 6 }.size(10.dp).background(Color(0xFFE91E63)))
    }
}

@Composable
private fun Fading() {
    val alpha by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Box(Modifier.size(40.dp).graphicsLayer { this.alpha = alpha }.background(Color(0xFFFF9800)))
}

@Composable
private fun Elevating() {
    val elevation by rememberInfiniteTransition().animateFloat(
        0f,
        16f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
    )
    Card(
        Modifier.size(60.dp, 40.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp),
    ) { Text("Card", Modifier.padding(6.dp)) }
}

@Composable
private fun Blurring() {
    val radius by rememberInfiniteTransition().animateFloat(0f, 8f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Text("Blur", Modifier.blur(radius.dp), fontSize = 20.sp)
}

/** An explicit graphics layer drawn by the node that records it — the screenshot pattern. */
@Composable
private fun ExplicitLayer() {
    val layer = rememberGraphicsLayer()
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
            }.background(if (tick % 2 == 0) Color(0xFF795548) else Color(0xFFCDDC39)),
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
        ) { Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF8BC34A))) }
    }
}

@Composable
private fun Menu() {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            open = !open
        }
    }
    Box {
        Text("Menu", Modifier.background(Color(0xFFE0E0E0)).padding(6.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("First") }, onClick = {})
            DropdownMenuItem(text = { Text("Second") }, onClick = {})
        }
    }
}

@Composable
private fun ScrollingList(scroll: Boolean) {
    val state = rememberLazyListState()
    LaunchedEffect(scroll) {
        while (scroll) {
            delay(16)
            if (!state.canScrollForward) state.scrollToItem(0) else state.scrollBy(3f)
        }
    }
    LazyColumn(Modifier.width(200.dp).fillMaxSize().background(Color.White), state = state) {
        items(200) { i -> Text("Row $i", Modifier.padding(4.dp)) }
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollBy(px: Float) {
    dispatchRawDelta(px)
}

@Composable
private fun ReorderingList(reorder: Boolean) {
    val items = remember { mutableStateListOf(*Array(8) { it }) }
    LaunchedEffect(reorder) {
        while (reorder) {
            delay(400)
            items.add(items.removeAt(0))
        }
    }
    LazyColumn(Modifier.width(160.dp).fillMaxSize().background(Color(0xFFFAFAFA))) {
        items(items, key = { it }) { item ->
            Text(
                "Item $item",
                Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(4.dp)
                    .background(Color(0xFFBBDEFB))
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun Caret(focus: Boolean) {
    var text by remember { mutableStateOf("Caret") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focus) {
        if (focus) {
            focusRequester.requestFocus()
            repeat(5) {
                delay(250)
                text += "!"
            }
        }
    }
    OutlinedTextField(text, { text = it }, Modifier.width(220.dp).focusRequester(focusRequester))
}

private const val CARD_COUNT = 260
private const val TOUR_STEPS = 13
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
private const val LOREM =
    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore."
