package dev.nucleusframework.lab.probes.fixtures.partialredraw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.lab.designsystem.DropdownItem
import dev.nucleusframework.lab.designsystem.LabDimens
import dev.nucleusframework.lab.designsystem.LabShapes
import dev.nucleusframework.lab.designsystem.LabTheme
import dev.nucleusframework.lab.designsystem.PopupMenu
import dev.nucleusframework.lab.designsystem.Text
import dev.nucleusframework.window.DecoratedWindowScope
import dev.nucleusframework.window.WindowAppearance
import dev.nucleusframework.window.WindowAppearanceMode
import dev.nucleusframework.window.WindowBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.logging.Logger
import kotlin.random.Random

/**
 * `torture`: random window-level chaos over content that keeps changing in small,
 * partial-friendly pieces, for the `nucleus.tao.partialRedraw.verify` oracle to check frame by
 * frame. `-Dpartial.demo.seed=N` replays a run, `-Dpartial.demo.events=a,b` restricts the
 * chaos to those events.
 *
 * `-Dpartial.demo.settleDir=<dir>` adds a `settle` event, the oracle that needs no `verify`
 * (which presents every frame and slows each one down): the content freezes, the app writes
 * `<n>-a` and waits for `<n>-a.done` while a script captures the window, then repaints in full
 * (the clear colour flipped and back), writes `<n>-b` and waits again. Both captures must be
 * identical: a difference is a stale region the partial frames left.
 */
@Composable
internal fun DecoratedWindowScope.Torture(state: WindowState) {
    val seed = remember { System.getProperty("partial.demo.seed")?.toLongOrNull() ?: System.nanoTime() }
    val only =
        remember {
            System
                .getProperty("partial.demo.events")
                ?.split(',')
                ?.map { it.trim() }
                ?.toSet()
        }
    val rng = remember { Random(seed) }
    // The clear colour starts as the scene's own surface, as with every other scene.
    val surface = LabTheme.colors.panel
    var background by remember { mutableStateOf(surface) }
    var paused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var burst by remember { mutableIntStateOf(0) }
    var event by remember { mutableStateOf("start") }
    WindowBackground(background)
    WindowAppearance(if (LabTheme.isDark) WindowAppearanceMode.Dark else WindowAppearanceMode.Light)

    LaunchedEffect(Unit) {
        val settleDir = System.getProperty("partial.demo.settleDir")?.let(::File)
        logger.info("Torture seed=$seed events=${only ?: "all"}")
        val events =
            TortureEvent.entries.filter {
                (only == null || it.id in only) &&
                    (it != TortureEvent.Settle || settleDir != null)
            }
        var count = 0
        while (isActive) {
            delay(rng.nextLong(150, 1500))
            val next = events[rng.nextInt(events.size)]
            event = "${++count} ${next.id}"
            logger.info("Torture event $event")
            val base = state.size
            when (next) {
                TortureEvent.AnimateSize -> {
                    val target = DpSize(rng.nextInt(500, 1300).dp, rng.nextInt(400, 900).dp)
                    val frames = rng.nextInt(4, 30)
                    for (i in 1..frames) {
                        withFrameNanos { }
                        val t = i.toFloat() / frames
                        state.size =
                            DpSize(
                                base.width + (target.width - base.width) * t,
                                base.height + (target.height - base.height) * t,
                            )
                    }
                }
                TortureEvent.Bounce ->
                    repeat(rng.nextInt(1, 6)) {
                        state.size = DpSize(base.width + rng.nextInt(1, 40).dp, base.height + rng.nextInt(-20, 20).dp)
                        if (rng.nextBoolean()) withFrameNanos { }
                        state.size = base
                        withFrameNanos { }
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
                TortureEvent.ClearColor -> background = Color(0xFF000000 or rng.nextLong(0xFFFFFF))
                TortureEvent.LongPause, TortureEvent.ShortPause -> {
                    paused = true
                    delay(if (next == TortureEvent.LongPause) rng.nextLong(1050, 2500) else rng.nextLong(700, 999))
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

    Column(Modifier.fillMaxSize().padding(LabDimens.gap), verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.block)) {
            Text("Torture — event $event", Isolated, style = LabTheme.typography.heading)
            Box {
                Text(
                    "menu",
                    Modifier.background(LabTheme.colors.raised, LabShapes.small).padding(horizontal = 4.dp),
                    style = LabTheme.typography.small,
                )
                PopupMenu(menuOpen, TORTURE_MENU, onDismissRequest = { menuOpen = false })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
            ChurningGrid(rng, paused, burst)
            Column(verticalArrangement = Arrangement.spacedBy(LabDimens.gap)) {
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

/** Writes [name] into [dir] and waits (bounded) for the script's `<name>.done`. */
private suspend fun handshake(
    dir: File,
    name: String,
) {
    val done = File(dir, "$name.done")
    File(dir, name).writeText(name)
    val deadline = System.nanoTime() + SETTLE_TIMEOUT_NS
    while (!done.exists() && System.nanoTime() < deadline) delay(20)
    if (!done.exists()) logger.warning("Torture settle $name: no capture")
}

private val TORTURE_MENU = listOf(DropdownItem("One") {}, DropdownItem("Two") {})

private const val SETTLE_QUIET_MS = 600L
private const val SETTLE_TIMEOUT_NS = 10_000_000_000L

private val logger: Logger = Logger.getLogger("dev.nucleusframework.lab.probes.fixtures.partialredraw.Torture")
