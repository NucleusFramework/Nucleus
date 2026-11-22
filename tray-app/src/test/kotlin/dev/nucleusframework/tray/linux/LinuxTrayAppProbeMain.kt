package dev.nucleusframework.tray.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.LinuxTrayHooks
import dev.nucleusframework.tray.app.TrayApp
import dev.nucleusframework.tray.app.TrayAppState
import dev.nucleusframework.tray.app.TrayScreenGeometry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Child JVM of [LinuxTrayAppE2ETest]: a real `nucleusApplication` with one or two `TrayApp`s on
 * a private session bus. It plays the panel itself ([SniPanel]: the watcher the trays register
 * with, `Activate` / `Event` as a click) and reads the popups back from the X server
 * ([X11Probe]). Prints `CHECK ok|fail <what>` lines and exits with the number of failed checks.
 *
 * Args: `<scenario> <seed>`. Env: `DBUS_SESSION_BUS_ADDRESS` = the private bus.
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
        if (runCatching(probe).getOrDefault(false)) return true
        Thread.sleep(10)
    }
    return runCatching(probe).getOrDefault(false)
}

private const val POPUP_W = 300
private const val POPUP_H = 200

/** One TrayApp under test; [sizeDp] tells its popup apart from the other one's. */
private class App(
    val state: TrayAppState,
    val color: Color,
) {
    val menuClicks = AtomicInteger()
    var shown by mutableStateOf(true)
    lateinit var trayId: String
    lateinit var service: String
}

private class Harness(
    val panel: SniPanel,
    val apps: List<App>,
) {
    val scale: Float get() = TrayScreenGeometry.scale()

    fun workLogical(): X11Probe.Box {
        val wa = X11Probe.workArea() ?: error("no _NET_WORKAREA")
        val s = scale
        return X11Probe.Box(
            (wa.x / s).roundToInt(),
            (wa.y / s).roundToInt(),
            (wa.width / s).roundToInt(),
            (
                wa.height /
                    s
            ).roundToInt(),
        )
    }

    /** The on-screen popup of [app]: our window of its size (each app has its own size). */
    fun popup(app: App): X11Probe.Box? {
        val w = (app.state.windowSize.value.width.value * scale).roundToInt()
        val h = (app.state.windowSize.value.height.value * scale).roundToInt()
        return X11Probe.ownWindows().firstOrNull { abs(it.width - w) <= 3 && abs(it.height - h) <= 3 }
    }

    fun anyPopup(): X11Probe.Box? = X11Probe.ownWindows().firstOrNull { it.width > 50 && it.height > 50 }

    /** A left click on [app]'s icon at ([x], [y]) logical, as the panel reports it. */
    fun click(
        app: App,
        x: Int,
        y: Int,
    ) {
        panel.activate(app.service, x, y)
    }

    /** Popup on screen, sized like the state, inside the work area, anchored to the click. */
    fun checkAnchored(
        app: App,
        clickX: Int,
        clickY: Int,
        label: String,
    ) {
        val box = popup(app)
        check(box != null, "$label: popup on screen (own windows: ${X11Probe.ownWindows()})")
        box ?: return
        val s = scale
        val wa = X11Probe.workArea() ?: return check(false, "$label: work area")
        check(
            box.x >= wa.x - 1 && box.right <= wa.right + 1 && box.y >= wa.y - 1 && box.bottom <= wa.bottom + 1,
            "$label: popup $box inside work area $wa",
        )
        val work = workLogical()
        val top = clickY - work.y < work.y + work.height - clickY
        val edgeGap = if (top) box.y - wa.y else wa.bottom - box.bottom
        check(edgeGap in -2..2, "$label: popup $box against the ${if (top) "top" else "bottom"} of $wa (gap $edgeGap)")
        val clickPx = (clickX * s).roundToInt()
        val centered = abs(box.centerX - clickPx) <= (3 * s).roundToInt() + 1
        val clamped = abs(box.right - wa.right) <= 2 || abs(box.x - wa.x) <= 2
        check(centered || clamped, "$label: popup $box centered on the click x=$clickPx (or clamped)")
    }

    fun consistent(
        app: App,
        label: String,
    ) {
        Thread.sleep(1500)
        val visible = app.state.isVisible.value
        val onScreen = waitFor(1500) { (popup(app) != null) == visible }
        check(onScreen, "$label: state.isVisible=$visible matches the screen (${popup(app)})")
    }
}

fun main(args: Array<String>) {
    val scenario = args[0]
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 1L
    val random = Random(seed)
    val address = System.getenv("DBUS_SESSION_BUS_ADDRESS") ?: error("no private bus")
    val panel = SniPanel(address)
    val count = if (scenario == "multi") 2 else 1
    val apps =
        List(count) { i ->
            App(
                TrayAppState(
                    initialWindowSize = DpSize((POPUP_W + 60 * i).dp, (POPUP_H + 40 * i).dp),
                    initiallyVisible = scenario == "visible-on-start",
                ),
                if (i == 0) Color(0xFFFFF3E0) else Color(0xFFE3F2FD),
            )
        }
    val h = Harness(panel, apps)

    Thread({
        try {
            run(scenario, h, random)
        } catch (t: Throwable) {
            check(false, "scenario threw $t at ${t.stackTrace.take(6).joinToString(" < ")}")
        }
        println("DONE failures=${failures.get()}")
        System.out.flush()
        Runtime.getRuntime().halt(failures.get())
    }, "trayapp-probe").apply {
        isDaemon = true
        start()
    }

    nucleusApplication(enableSingleInstance = false) {
        for ((i, app) in apps.withIndex()) {
            if (app.shown) {
                TrayApp(
                    iconContent = { Box(Modifier.fillMaxSize().background(Color(0xFF6A1B9A))) },
                    tooltip = "TrayApp probe $i",
                    state = app.state,
                    menu = { Item("Menu item $i") { app.menuClicks.incrementAndGet() } },
                ) {
                    Box(Modifier.fillMaxSize().background(app.color))
                }
            }
        }
    }
    exitProcess(failures.get())
}

private fun run(
    scenario: String,
    h: Harness,
    random: Random,
) {
    check(X11Probe.isAvailable, "X server reachable")
    // Bind each app to its tray: trays come up in composition order (tray-1, tray-2, ...).
    check(waitFor(15_000) { LinuxTrayHooks.debugIds().size == h.apps.size }, "trays created")
    val ids = LinuxTrayHooks.debugIds().sortedBy { it.substringAfterLast('-').toIntOrNull() ?: 0 }
    for ((app, id) in h.apps.zip(ids)) {
        app.trayId = id
        app.service = h.panel.serviceOf(id, timeoutMs = 10_000)
    }
    check(
        h.apps
            .map { it.service }
            .toSet()
            .size == h.apps.size,
        "each TrayApp has its own item on the bus",
    )
    val app = h.apps.first()
    val work = h.workLogical()
    val topX = work.x + work.width - 40
    val topY = work.y + 5
    Thread.sleep(500)

    val scenarioContext = ScenarioContext(h, app, work, topX, topY, random)
    when (scenario) {
        "click" -> scenarioContext.scenarioClick()
        "visible-on-start" -> scenarioContext.scenarioVisibleOnStart()
        "toggle-storm" -> scenarioContext.scenarioToggleStorm()
        "programmatic" -> scenarioContext.scenarioProgrammatic()
        "resize" -> scenarioContext.scenarioResize()
        "menu" -> scenarioContext.scenarioMenu()
        "remove" -> scenarioContext.scenarioRemove()
        "multi" -> scenarioContext.scenarioMulti()
        else -> check(false, "unknown scenario $scenario")
    }
}

/** What every scenario shares: the harness, the first app, the work area and a top-panel click point. */
private class ScenarioContext(
    val h: Harness,
    val app: App,
    val work: X11Probe.Box,
    val topX: Int,
    val topY: Int,
    val random: Random,
)

private fun ScenarioContext.scenarioClick() {
    h.click(app, topX, topY)
    check(waitFor(3000) { h.popup(app) != null }, "click shows the popup")
    check(app.state.isVisible.value, "state is visible after the click")
    Thread.sleep(600)
    h.checkAnchored(app, topX, topY, "after click (top panel)")
    h.click(app, topX, topY)
    check(waitFor(3000) { h.popup(app) == null }, "second click hides the popup")
    check(!app.state.isVisible.value, "state is hidden after the second click")
    Thread.sleep(400)
    // A bottom panel this time, near the left edge: the popup must clamp, not overflow.
    val bx = work.x + 10
    val by = work.y + work.height - 5
    // Clamped against the left edge: it used to wait out the 3 s anchor timeout.
    val t0 = System.nanoTime()
    h.click(app, bx, by)
    val shown = waitFor(1500) { h.popup(app) != null }
    check(shown, "third click shows it again within 1.5 s (${(System.nanoTime() - t0) / 1_000_000} ms)")
    Thread.sleep(600)
    h.checkAnchored(app, bx, by, "reopened (bottom panel, left edge)")
}

private fun ScenarioContext.scenarioVisibleOnStart() {
    check(waitFor(5000) { h.popup(app) != null }, "visibleOnStart shows the popup")
    Thread.sleep(800)
    val box = h.popup(app)
    val wa = X11Probe.workArea()
    check(
        box != null &&
            wa != null &&
            box.x >= wa.x - 1 &&
            box.right <= wa.right + 1 &&
            box.y >= wa.y - 1 &&
            box.bottom <= wa.bottom + 1,
        "visible on start: popup $box inside work area $wa (no click yet)",
    )
}

private fun ScenarioContext.scenarioToggleStorm() {
    repeat(60) {
        h.click(app, topX, topY)
        Thread.sleep(random.nextLong(0, 400))
    }
    h.consistent(app, "after 60 seeded clicks")
    if (app.state.isVisible.value) h.checkAnchored(app, topX, topY, "storm end")
}

private fun ScenarioContext.scenarioProgrammatic() {
    h.click(app, topX, topY)
    waitFor(3000) { h.popup(app) != null }
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
    h.consistent(app, "after concurrent show/hide/toggle")
    app.state.show()
    check(waitFor(3000) { h.popup(app) != null }, "show() after the storm")
    Thread.sleep(800)
    h.checkAnchored(app, topX, topY, "show() after the storm")
}

private fun ScenarioContext.scenarioResize() {
    h.click(app, topX, topY)
    check(waitFor(3000) { h.popup(app) != null }, "popup shown")
    Thread.sleep(600)
    app.state.setWindowSize(420.dp, 360.dp)
    check(waitFor(3000) { h.popup(app) != null }, "resized popup on screen")
    Thread.sleep(800)
    h.checkAnchored(app, topX, topY, "after resize")
}

private fun ScenarioContext.scenarioMenu() {
    val menu = h.panel.menu(app.service)
    check(menu.singleOrNull()?.label == "Menu item 0", "menu reads back (${menu.map { it.label }})")
    menu.singleOrNull()?.let { h.panel.click(app.service, it.id) }
    check(waitFor(3000) { app.menuClicks.get() == 1 }, "menu item callback")
    Thread.sleep(300)
    check(h.popup(app) == null, "the menu did not open the popup")
    check(
        h.panel.sni(app.service, "ItemIsMenu") == false,
        "a TrayApp icon is not menu-only (left click toggles)",
    )
}

private fun ScenarioContext.scenarioRemove() {
    h.click(app, topX, topY)
    check(waitFor(3000) { h.popup(app) != null }, "popup shown")
    app.shown = false
    check(waitFor(5000) { h.popup(app) == null }, "popup gone with TrayApp")
    check(waitFor(5000) { !h.panel.hasOwner(app.service) }, "tray gone from the bus with TrayApp")
}

private fun ScenarioContext.scenarioMulti() {
    // Two icons side by side on a top panel: each popup must come up under its own icon,
    // not under whichever icon was clicked last.
    val (a, b) = h.apps
    val ax = work.x + work.width / 2 - 200
    val bx = work.x + work.width / 2 + 200
    h.click(a, ax, topY)
    check(waitFor(3000) { h.popup(a) != null }, "first popup shown")
    Thread.sleep(600)
    h.click(b, bx, topY)
    check(waitFor(3000) { h.popup(b) != null }, "second popup shown")
    Thread.sleep(600)
    h.checkAnchored(b, bx, topY, "second app")
    // Re-anchoring the first one now (a resize) must use its own icon's click.
    if (a.state.isVisible.value) {
        a.state.setWindowSize(320.dp, 220.dp)
        Thread.sleep(900)
        h.checkAnchored(a, ax, topY, "first app re-anchored after the other icon was clicked")
    } else {
        a.state.show()
        check(waitFor(3000) { h.popup(a) != null }, "first popup shown again")
        Thread.sleep(800)
        h.checkAnchored(a, ax, topY, "first app shown after the other icon was clicked")
    }
}
