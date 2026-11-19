package dev.nucleusframework.tray.macos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.MacTrayHooks
import dev.nucleusframework.tray.app.TrayApp
import dev.nucleusframework.tray.app.TrayAppState
import dev.nucleusframework.tray.app.TrayScreenGeometry
import dev.nucleusframework.tray.app.TrayWindowDismissMode
import dev.nucleusframework.tray.internal.MacTrayInitializer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Child JVM of [MacTrayAppE2ETest]: a real `nucleusApplication` (Tao loop, AppKit main thread)
 * with one or two `TrayApp`s, driven from a side thread. Clicks go through the status item's own
 * action; the popup is read back from the window server. Prints `CHECK ok|fail <what>` lines and
 * exits with the number of failed checks.
 *
 * Args: `<scenario> <seed> [rounds]`.
 */
private val failures = AtomicInteger()

private fun check(
    ok: Boolean,
    what: String,
) {
    if (!ok) failures.incrementAndGet()
    println("CHECK ${if (ok) "ok" else "fail"} $what")
    System.out.flush()
}

private fun waitFor(
    timeoutMs: Long,
    probe: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (probe()) return true
        Thread.sleep(10)
    }
    return probe()
}

/** One `TrayApp` of the probe, with everything the fuzzer may change. */
private class App(
    val index: Int,
    size: DpSize,
) {
    val state = TrayAppState(initialWindowSize = size)
    var shown by mutableStateOf(true)

    /** Always starts with [tag]: the tray of an app is found by its tooltip. */
    var tooltip by mutableStateOf("a$index probe")
    val tag: String get() = "a$index "
    var menuLabel by mutableStateOf("Menu item $index")
    var hOffset by mutableIntStateOf(0)
    var vOffset by mutableIntStateOf(5)
    var ticking by mutableStateOf(false)
    val menuClicks = AtomicInteger()

    /** Tray id, once the status item exists. */
    @Volatile var trayId: String? = null
}

private class Harness(
    val apps: List<App>,
) {
    fun awaitTrays() {
        // The system menu bar sometimes takes many seconds to place a new item.
        val ok =
            waitFor(30_000) {
                val ids = MacTrayHooks.debugIds().sortedBy { it.removePrefix("tray-").toIntOrNull() ?: 0 }
                assign()
                ids.size == apps.count { it.shown } &&
                    apps.filter { it.shown }.all { it.trayId != null } &&
                    ids.all { id ->
                        val xy = IntArray(2)
                        MacTrayInitializer.statusItemPositionFor(id, xy)
                    }
            }
        check(
            ok,
            "every tray laid out in the menu bar: " +
                MacTrayHooks.debugIds().map { id ->
                    val xy = IntArray(2)
                    val laidOut = MacTrayInitializer.statusItemPositionFor(id, xy)
                    "$id tooltip=${MacProbe.snapshot(
                        id,
                    )?.tooltip} laidOut=$laidOut ${MacProbe.snapshot(id)?.windowFrame}"
                } + " apps=" + apps.map { "${it.index}:${it.trayId}:${it.shown}" },
        )
        assign()
    }

    /** Finds each app's tray by its tooltip (creation order changes with removal and reinsertion). */
    fun assign() {
        val byTooltip = MacTrayHooks.debugIds().associateBy { MacProbe.snapshot(it)?.tooltip.orEmpty() }
        for (app in apps) app.trayId = byTooltip.entries.firstOrNull { it.key.startsWith(app.tag) }?.value
    }

    fun click(app: App) {
        app.trayId?.let { MacTrayHooks.debugClick(it, secondary = false) }
    }

    /**
     * Windows of this process on screen. The popup panel sits at the pop-up menu level like native
     * menus do, so a popup is told apart by its size (each app owns distinct sizes).
     */
    fun popups(): List<CgWindows.Window> = CgWindows.of().filter { it.onScreen && it.alpha > 0.0 }

    fun popupOf(app: App): CgWindows.Window? {
        val size = app.state.windowSize.value
        return popups().firstOrNull {
            abs(it.bounds.width - size.width.value.toInt()) <= 2 &&
                abs(it.bounds.height - size.height.value.toInt()) <= 2
        }
    }

    /** The status item's center; AppKit may report it unplaced for a moment while the menu bar rearranges. */
    fun iconX(app: App): Int? {
        val id = app.trayId ?: return null
        val xy = IntArray(2)
        return if (waitFor(3000) { MacTrayInitializer.statusItemPositionFor(id, xy) }) xy[0] else null
    }

    /** Visible popup sized like the state says, below the menu bar and centered on its icon. */
    fun checkAnchored(
        app: App,
        label: String,
    ) {
        val popup = popupOf(app)
        check(
            popup != null,
            "$label: popup ${app.index} (${app.state.windowSize.value}) on screen, windows=${popups()}",
        )
        popup ?: return
        val box = popup.bounds
        val work = TrayScreenGeometry.workAreaLogical()
        val fits =
            app.state.windowSize.value.width.value <= work.width &&
                app.state.windowSize.value.height.value <= work.height
        if (fits) {
            check(
                box.x >= work.x - 1 &&
                    box.right <= work.x + work.width + 1 &&
                    box.y >= work.y - 1 &&
                    box.bottom <= work.y + work.height + 1,
                "$label: popup $box inside work area $work",
            )
        } else {
            check(
                box.x >= work.x - 1 && box.y >= work.y - 1,
                "$label: oversized popup $box keeps its top-left in $work",
            )
        }
        val expectedTop = work.y + app.vOffset
        if (fits && box.height + app.vOffset <= work.height) {
            check(abs(box.y - expectedTop) <= 2, "$label: popup top ${box.y} right below the menu bar ($expectedTop)")
        }
        val icon = iconX(app)
        check(icon != null, "$label: icon position")
        icon ?: return
        // The offset pushes away from the screen edge the item sits closer to.
        val leftHalf = app.trayId?.let { MacTrayInitializer.statusItemRegionFor(it) } == "top-left"
        val expectedCenter = if (leftHalf) icon + app.hOffset else icon - app.hOffset
        val centered = abs(box.centerX - expectedCenter) <= 3
        val clamped = abs(box.right - (work.x + work.width)) <= 2 || abs(box.x - work.x) <= 2
        check(centered || clamped, "$label: popup $box centered on icon x=$icon (offset ${app.hOffset}) or clamped")
    }

    fun consistent(label: String) {
        // Settle: debounce windows and the exit animation.
        Thread.sleep(1200)
        for (app in apps) {
            val visible = app.shown && app.state.isVisible.value
            val onScreen = waitFor(2000) { (popupOf(app) != null) == visible }
            check(onScreen, "$label: app ${app.index} isVisible=$visible matches the screen (${popupOf(app) != null})")
        }
    }
}

fun main(args: Array<String>) {
    val scenario = args[0]
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 1L
    val rounds = args.getOrNull(2)?.toIntOrNull() ?: 40
    val random = Random(seed)
    val apps =
        when (scenario) {
            "multi" -> listOf(App(0, DpSize(300.dp, 200.dp)), App(1, DpSize(260.dp, 340.dp)))
            else -> listOf(App(0, DpSize(300.dp, 200.dp)))
        }
    val h = Harness(apps)
    if (scenario == "visible-on-start") apps[0].state.show()

    Thread({
        try {
            run(scenario, h, random, rounds)
        } catch (t: Throwable) {
            check(false, "scenario threw $t")
        }
        println("DONE failures=${failures.get()}")
        System.out.flush()
        Runtime.getRuntime().halt(failures.get())
    }, "trayapp-probe").apply {
        isDaemon = true
        start()
    }

    nucleusApplication(enableSingleInstance = false) {
        for (app in apps) {
            if (!app.shown) continue
            TrayApp(
                iconContent = {
                    Box(
                        Modifier.fillMaxSize().background(
                            if (app.index ==
                                0
                            ) {
                                Color(0xFF6A1B9A)
                            } else {
                                Color(0xFF00838F)
                            },
                        ),
                    )
                },
                tooltip = app.tooltip,
                state = app.state,
                horizontalOffset = app.hOffset,
                verticalOffset = app.vOffset,
                menu = { Item(app.menuLabel) { app.menuClicks.incrementAndGet() } },
            ) {
                var frames by mutableIntStateOf(0)
                if (app.ticking) {
                    LaunchedEffect(Unit) { while (true) withFrameMillis { frames++ } }
                }
                Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E0))) { Text("app ${app.index} f$frames") }
            }
        }
    }
    exitProcess(failures.get())
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun run(
    scenario: String,
    h: Harness,
    random: Random,
    rounds: Int,
) {
    h.awaitTrays()
    val app = h.apps[0]
    when (scenario) {
        "click" -> {
            h.click(app)
            check(waitFor(3000) { h.popupOf(app) != null }, "click shows the popup")
            check(app.state.isVisible.value, "state is visible after the click")
            Thread.sleep(500)
            h.checkAnchored(app, "after click")
            Thread.sleep(400)
            h.click(app)
            check(waitFor(3000) { h.popupOf(app) == null }, "second click hides the popup")
            check(!app.state.isVisible.value, "state is hidden after the second click")
            Thread.sleep(500)
            h.click(app)
            check(waitFor(3000) { h.popupOf(app) != null }, "third click shows it again")
            Thread.sleep(500)
            h.checkAnchored(app, "reopened")
        }
        "visible-on-start" -> {
            check(waitFor(5000) { h.popupOf(app) != null }, "visibleOnStart shows the popup")
            Thread.sleep(600)
            h.checkAnchored(app, "visible on start")
        }
        "toggle-storm" -> {
            repeat(60) {
                h.click(app)
                Thread.sleep(random.nextLong(0, 400))
            }
            h.consistent("after 60 seeded clicks")
            if (app.state.isVisible.value) h.checkAnchored(app, "storm end")
        }
        "programmatic" -> {
            val workers =
                List(3) { w ->
                    Thread {
                        val r = Random(random.nextLong() + w)
                        repeat(80) {
                            when (r.nextInt(3)) {
                                0 -> app.state.show()
                                1 -> app.state.hide()
                                else -> app.state.toggle()
                            }
                            Thread.sleep(r.nextLong(0, 30))
                        }
                    }.apply { start() }
                }
            workers.forEach { it.join() }
            h.consistent("after concurrent show/hide/toggle")
            app.state.show()
            check(waitFor(3000) { h.popupOf(app) != null }, "show() after the storm")
            Thread.sleep(600)
            h.checkAnchored(app, "show() after the storm")
        }
        "resize" -> {
            h.click(app)
            check(waitFor(3000) { h.popupOf(app) != null }, "popup shown")
            Thread.sleep(500)
            app.state.setWindowSize(420.dp, 360.dp)
            check(waitFor(3000) { h.popupOf(app) != null }, "resized popup on screen")
            Thread.sleep(500)
            h.checkAnchored(app, "after resize")
        }
        "menu" -> {
            val id = app.trayId!!
            MacTrayHooks.debugClick(id, secondary = true)
            check(waitFor(3000) { MacProbe.snapshot(id)?.menuOpen == true }, "right click shows the menu")
            val item = MacProbe.snapshot(id)?.menu?.singleOrNull()
            check(item?.title == "Menu item 0", "menu reads back (${item?.title})")
            MacTrayHooks.debugPerformItem(id, intArrayOf(0))
            check(waitFor(3000) { app.menuClicks.get() == 1 }, "menu item callback")
            MacTrayHooks.debugCancelMenu(id)
            check(waitFor(3000) { MacProbe.snapshot(id)?.menuOpen == false }, "menu closed")
            check(h.popupOf(app) == null, "the menu did not open the popup")
        }
        "remove" -> {
            h.click(app)
            check(waitFor(3000) { h.popupOf(app) != null }, "popup shown")
            app.shown = false
            check(waitFor(5000) { h.popupOf(app) == null }, "popup gone with TrayApp")
            check(waitFor(5000) { MacTrayHooks.debugIds().isEmpty() }, "tray gone with TrayApp")
            check(waitFor(5000) { MacTrayHooks.debugStatusItemCount() == 0 }, "status item released")
            app.shown = true
            check(waitFor(5000) { MacTrayHooks.debugIds().size == 1 }, "tray back with TrayApp")
        }
        "fuzz", "multi" -> fuzz(h, random, rounds)
        else -> check(false, "unknown scenario $scenario")
    }
}

/**
 * Seeded random actions on one or two `TrayApp`s, then the invariants: the state matches the
 * screen, a visible popup is anchored to its own icon, and the status items match the apps.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun fuzz(
    h: Harness,
    random: Random,
    rounds: Int,
) {
    val sizes = listOf(1 to 1, 120 to 80, 300 to 200, 420 to 360, 900 to 700, 5000 to 5000)
    repeat(rounds) { round ->
        repeat(random.nextInt(1, 6)) {
            val app = h.apps.random(random)
            when (random.nextInt(12)) {
                0, 1 -> h.click(app)
                2 -> app.state.show()
                3 -> app.state.hide()
                4 -> app.state.toggle()
                5 -> {
                    // Each app keeps sizes of its own, so a popup always says whose it is.
                    val (w, hh) = sizes.random(random)
                    app.state.setWindowSize((w + app.index * 10).dp, (hh + app.index * 10).dp)
                }
                6 -> {
                    app.hOffset = random.nextInt(-600, 600)
                    app.vOffset = random.nextInt(0, 60)
                }
                7 -> app.tooltip = app.tag + "t$round-${random.nextInt(100)}"
                8 -> app.menuLabel = "m$round-${random.nextInt(100)}"
                9 -> app.ticking = !app.ticking
                10 ->
                    app.state.setDismissMode(
                        if (random.nextBoolean()) TrayWindowDismissMode.AUTO else TrayWindowDismissMode.MANUAL,
                    )
                else -> {
                    val workers =
                        List(3) { w ->
                            Thread {
                                val r = Random(random.nextLong() + w)
                                repeat(10) {
                                    when (r.nextInt(3)) {
                                        0 -> app.state.show()
                                        1 -> app.state.hide()
                                        else -> app.state.toggle()
                                    }
                                }
                            }.apply { start() }
                        }
                    workers.forEach { it.join() }
                }
            }
            Thread.sleep(random.nextLong(0, 120))
        }
        if (round % 5 == 4) {
            // Removal from and reinsertion into the composition.
            val app = h.apps.random(random)
            app.shown = false
            check(
                waitFor(5000) { MacTrayHooks.debugIds().size == h.apps.count { it.shown } },
                "round $round: tray removed",
            )
            app.shown = true
            check(waitFor(5000) { MacTrayHooks.debugIds().size == h.apps.size }, "round $round: tray reinserted")
            h.awaitTrays()
        }
        h.consistent("round $round")
        for (app in h.apps) {
            if (app.state.isVisible.value) h.checkAnchored(app, "round $round app ${app.index}")
        }
        check(MacTrayHooks.debugStatusItemCount() == h.apps.size, "round $round: one status item per app")
    }
}
