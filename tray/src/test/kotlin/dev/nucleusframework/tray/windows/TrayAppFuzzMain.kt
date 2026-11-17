package dev.nucleusframework.tray.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.sun.jna.platform.win32.WinDef.HWND
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.app.TrayApp
import dev.nucleusframework.tray.app.TrayAppState
import dev.nucleusframework.tray.app.TrayWindowDismissMode
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random

/**
 * Child JVM of the TrayApp fuzz tests. `fuzz <seed> <rounds>`: one TrayApp, every round a seeded
 * random action, then the invariants. `multi <seed> <rounds>`: two TrayApps, each popup must stay
 * anchored to its own icon. Prints `CHECK ok|fail` lines, exits with the failure count.
 */
private val fails = AtomicInteger()
private val oks = AtomicInteger()

private fun expect(
    ok: Boolean,
    what: () -> String,
) {
    if (ok) {
        oks.incrementAndGet()
    } else {
        fails.incrementAndGet()
        println("CHECK fail ${what()}")
        System.out.flush()
    }
}

private fun until(
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

private class FuzzApp(
    val name: String,
    initialSize: DpSize,
) {
    val state = TrayAppState(initialWindowSize = initialSize)
    var shown by mutableStateOf(true)
    var color by mutableStateOf(Color(0xFF6A1B9A))
    var tooltip by mutableStateOf(name)
    var menuSize by mutableIntStateOf(1)
    var hOffset by mutableIntStateOf(0)
    var vOffset by mutableIntStateOf(0)
    var ticking by mutableStateOf(false)
    val menuClicks = AtomicInteger()

    /** The Shell identity of this app's tray, learned by clicking. */
    var trayId: String? = null
}

private val knownClasses = setOf("NucleusTrayWindow", "#32768")

/** Visible windows of this process that are popups (not trays, not menus). */
private fun popups(): List<Pair<HWND, Win32Probe.Box>> =
    Win32Probe.visibleWindows().filter { it.second !in knownClasses }.map { it.first to it.third }

private fun popupOf(
    app: FuzzApp,
    scale: Float = 1f,
): Win32Probe.Box? {
    val size = app.state.windowSize.value
    val w = (size.width.value * scale).toInt()
    val h = (size.height.value * scale).toInt()
    return popups().map { it.second }.firstOrNull { abs(it.width - w) <= 3 && abs(it.height - h) <= 3 }
}

// Must match WM_APP_OUTSIDE_CLICK in nucleus_tao_windows_popup.c: what its WH_MOUSE_LL hook posts
// to a standalone popup for a click elsewhere, delivered here without moving the real mouse.
private const val WM_APP_OUTSIDE_CLICK = 0x8000 + 0x51

private fun popupHwndOf(app: FuzzApp): HWND? {
    val size = app.state.windowSize.value
    return popups()
        .firstOrNull { (_, b) ->
            abs(b.width - size.width.value.toInt()) <= 3 && abs(b.height - size.height.value.toInt()) <= 3
        }?.first
}

private fun outsideClick(app: FuzzApp) {
    popupHwndOf(app)?.let {
        Win32Probe.post(
            com.sun.jna.Pointer
                .nativeValue(it.pointer),
            WM_APP_OUTSIDE_CLICK,
            1,
        )
    }
}

private fun identity(app: FuzzApp): Pair<Long, Int>? = app.trayId?.let { WindowsTrayInitializer.nativeIdentities()[it] }

private fun click(app: FuzzApp) {
    identity(app)?.let { (hwnd, _) ->
        Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
    }
}

/** Learns which tray belongs to [app]: the one whose click flips its state. */
private fun learnTray(
    app: FuzzApp,
    others: List<FuzzApp>,
) {
    app.trayId = null
    val taken = others.mapNotNull { it.trayId }.toSet()
    until(10_000) { WindowsTrayInitializer.nativeIdentities().keys.any { it !in taken } }
    Thread.sleep(500)
    for ((id, ident) in WindowsTrayInitializer.nativeIdentities()) {
        if (id in taken) continue
        val before = app.state.isVisible.value
        Win32Probe.post(ident.first, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
        if (until(2000) { app.state.isVisible.value != before }) {
            app.trayId = id
            Thread.sleep(400)
            // Put it back the way it was.
            Win32Probe.post(ident.first, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
            until(2000) { app.state.isVisible.value == before }
            Thread.sleep(400)
            return
        }
        Thread.sleep(400)
    }
    expect(false) { "${app.name}: no tray answers its clicks" }
}

/** The invariants every app must hold once things settle. */
@Suppress("CyclomaticComplexMethod") // one oracle per invariant, kept together
private fun verify(
    app: FuzzApp,
    label: String,
) {
    Thread.sleep(700)
    if (!app.shown) {
        expect(until(3000) { popupOf(app) == null }) { "$label ${app.name}: popup still on screen after removal" }
        return
    }
    val visible = app.state.isVisible.value
    // The popup follows the state (enter/exit animations, debounce windows).
    val matches = until(3000) { (popupOf(app) != null) == visible && app.state.isVisible.value == visible }
    expect(matches) {
        "$label ${app.name}: isVisible=$visible but popup on screen=${popupOf(app) != null} " +
            "(size ${app.state.windowSize.value}, popups=${Win32Probe.visibleWindows().map {
                it.second + " " + it.third
            }})"
    }
    if (!visible || !matches) return
    val box = popupOf(app) ?: return
    val ident = identity(app) ?: return expect(false) { "$label ${app.name}: tray gone while visible" }
    val icon = Win32Probe.shellIconRect(ident.first, ident.second) ?: return
    val work = Win32Probe.workAreaOf(icon)
    if (box.width <= work.width && box.height <= work.height) {
        expect(
            box.left >= work.left - 1 &&
                box.right <= work.right + 1 &&
                box.top >= work.top - 1 &&
                box.bottom <= work.bottom + 1,
        ) {
            "$label ${app.name}: popup $box leaves the work area $work (offsets ${app.hOffset},${app.vOffset})"
        }
    } else {
        // Larger than the screen: at least its top-left corner (title, close affordances) must show.
        expect(box.left >= work.left - 1 && box.top >= work.top - 1) {
            "$label ${app.name}: oversized popup $box hides its top-left corner (work area $work)"
        }
    }
    if (app.hOffset == 0 && app.vOffset == 0 && box.width <= work.width && box.height <= work.height) {
        val gap = if (icon.top >= work.bottom - 2) icon.top - box.bottom else box.top - icon.bottom
        val clampedV = box.top <= work.top + 1
        expect(gap in -2..12 || clampedV) { "$label ${app.name}: popup $box not against icon $icon (gap $gap)" }
        val centered = abs(box.centerX - icon.centerX) <= 4
        val clampedH = abs(box.right - work.right) <= 2 || abs(box.left - work.left) <= 2
        expect(centered || clampedH) { "$label ${app.name}: popup $box not centered on icon $icon" }
    }
}

private val sizes =
    listOf(
        DpSize(300.dp, 200.dp),
        DpSize(1.dp, 1.dp),
        DpSize(64.dp, 64.dp),
        DpSize(420.dp, 360.dp),
        DpSize(900.dp, 900.dp),
        DpSize(5000.dp, 200.dp),
        DpSize(200.dp, 3000.dp),
        DpSize(301.dp, 199.dp),
    )

@Suppress("CyclomaticComplexMethod", "LongMethod") // the action table of the fuzzer
private fun fuzz(
    apps: List<FuzzApp>,
    random: Random,
    rounds: Int,
) {
    apps.forEachIndexed { i, app -> learnTray(app, apps.take(i)) }
    val (gdi0, user0) = Win32Probe.guiResources()
    repeat(rounds) { round ->
        val app = apps.random(random)
        val action = random.nextInt(15)
        val label = "round $round action $action"
        when (action) {
            0, 1 -> click(app)
            2 -> app.state.show()
            3 -> app.state.hide()
            4 -> app.state.toggle()
            5 -> {
                // Concurrent burst from three threads, on every app.
                List(3) { t ->
                    Thread {
                        val r = Random(random.nextLong() + t)
                        repeat(15) {
                            val a = apps.random(r)
                            when (r.nextInt(4)) {
                                0 -> a.state.show()
                                1 -> a.state.hide()
                                2 -> a.state.toggle()
                                else -> click(a)
                            }
                            Thread.sleep(r.nextLong(0, 40))
                        }
                    }.apply { start() }
                }.forEach { it.join() }
            }
            6 -> {
                // Each app owns distinct sizes, so a popup on screen always says whose it is.
                val size = sizes.random(random)
                app.state.setWindowSize(if (app.name == "A") size else DpSize(size.width + 7.dp, size.height + 7.dp))
            }
            7 -> {
                app.hOffset = if (random.nextBoolean()) 0 else random.nextInt(-600, 600)
                app.vOffset = if (random.nextBoolean()) 0 else random.nextInt(-600, 600)
            }
            8 -> app.color = Color(random.nextInt() or 0xFF000000.toInt())
            9 -> app.tooltip = MenuFuzzer.label(random)
            10 -> app.menuSize = random.nextInt(0, 6)
            11 -> {
                app.shown = false
                verify(app, "$label (removed)")
                app.shown = true
                learnTray(app, apps - app)
            }
            12 ->
                app.state.setDismissMode(
                    if (random.nextBoolean()) TrayWindowDismissMode.AUTO else TrayWindowDismissMode.MANUAL,
                )
            13 -> app.ticking = !app.ticking
            else -> outsideClick(app)
        }
        Thread.sleep(random.nextLong(0, 250))
        if (round % 3 == 2 || action == 6 || action == 7) apps.forEach { verify(it, label) }
    }
    apps.forEach {
        it.ticking = false
        it.state.hide()
    }
    apps.forEach { verify(it, "final") }
    Thread.sleep(1500)
    val (gdi1, user1) = Win32Probe.guiResources()
    expect(gdi1 - gdi0 < 60) { "GDI objects $gdi0 -> $gdi1 over $rounds rounds" }
    expect(user1 - user0 < 30) { "USER objects $user0 -> $user1 over $rounds rounds" }
    val trayThreads = Thread.getAllStackTraces().keys.count { it.name.startsWith("NucleusTray-") && it.isAlive }
    expect(trayThreads == apps.size) { "$trayThreads tray threads alive for ${apps.size} TrayApps" }
}

/** Outside-click dismissal: AUTO hides, MANUAL keeps, too-early clicks and the icon re-click guard. */
private fun dismiss(app: FuzzApp) {
    learnTray(app, emptyList())

    fun shownByClick(): Boolean {
        Thread.sleep(500)
        click(app)
        return until(3000) { popupOf(app) != null }
    }
    expect(shownByClick()) { "dismiss: click shows the popup" }
    Thread.sleep(500)
    outsideClick(app)
    expect(until(3000) { popupOf(app) == null && !app.state.isVisible.value }) { "dismiss: AUTO outside click hides" }

    expect(shownByClick()) { "dismiss: reopened" }
    outsideClick(app) // within minVisibleDuration of the show: a leftover of the opening gesture
    Thread.sleep(800)
    expect(
        popupOf(app) != null && app.state.isVisible.value,
    ) { "dismiss: an outside click right after show is ignored" }

    // A click on the icon while open: the hook sees it first (outside the popup), then the icon.
    Thread.sleep(300)
    outsideClick(app)
    Thread.sleep(30)
    click(app)
    Thread.sleep(800)
    expect(
        popupOf(app) == null && !app.state.isVisible.value,
    ) { "dismiss: icon click while open closes and stays closed" }

    app.state.setDismissMode(TrayWindowDismissMode.MANUAL)
    expect(shownByClick()) { "dismiss: reopened (MANUAL)" }
    Thread.sleep(500)
    outsideClick(app)
    Thread.sleep(800)
    expect(popupOf(app) != null && app.state.isVisible.value) { "dismiss: MANUAL ignores outside clicks" }
    app.state.hide()
    expect(until(3000) { popupOf(app) == null }) { "dismiss: hide() still works in MANUAL" }
}

fun main(args: Array<String>) {
    val scenario = args[0]
    val seed = args[1].toLong()
    val rounds = args[2].toInt()
    val apps =
        when (scenario) {
            "multi" -> listOf(FuzzApp("A", DpSize(300.dp, 200.dp)), FuzzApp("B", DpSize(250.dp, 350.dp)))
            else -> listOf(FuzzApp("A", DpSize(300.dp, 200.dp)))
        }

    Thread({
        try {
            if (scenario == "dismiss") dismiss(apps.single()) else fuzz(apps, Random(seed), rounds)
        } catch (t: Throwable) {
            expect(false) { "fuzz threw ${t.stackTraceToString()}" }
        }
        println("CHECK ok ${oks.get()} invariants held")
        println("DONE failures=${fails.get()}")
        System.out.flush()
        Runtime.getRuntime().halt(minOf(fails.get(), 100))
    }, "trayapp-fuzz").apply {
        isDaemon = true
        start()
    }

    nucleusApplication(enableSingleInstance = false) {
        for (app in apps) {
            if (!app.shown) continue
            TrayApp(
                iconContent = { Box(Modifier.fillMaxSize().background(app.color)) },
                tooltip = app.tooltip,
                state = app.state,
                horizontalOffset = app.hOffset,
                verticalOffset = app.vOffset,
                menu = { repeat(app.menuSize) { Item("${app.name} $it") { app.menuClicks.incrementAndGet() } } },
            ) {
                var frames by mutableIntStateOf(0)
                if (app.ticking) {
                    LaunchedEffect(Unit) { while (true) withFrameNanos { frames++ } }
                }
                Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E0))) { BasicText("${app.name} $frames") }
            }
        }
    }
}
