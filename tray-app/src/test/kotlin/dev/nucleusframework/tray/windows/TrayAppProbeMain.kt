package dev.nucleusframework.tray.windows

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
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.WindowsTrayHooks
import dev.nucleusframework.tray.app.TrayApp
import dev.nucleusframework.tray.app.TrayAppState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Child JVM of [WindowsTrayAppE2ETest]: a real `nucleusApplication` with a `TrayApp`, driven from
 * a side thread through the same Win32 messages the shell sends. Prints `CHECK ok|fail <what>`
 * lines and exits with the number of failed checks.
 *
 * Args: `<scenario> <seed>`.
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

private const val POPUP_W = 300
private const val POPUP_H = 200

private class Harness(
    val state: TrayAppState,
) {
    lateinit var scope: NucleusApplicationScope
    var shown by mutableStateOf(true)
    val menuClicks = AtomicInteger()

    fun tray(): Pair<Long, Int> {
        var found: Pair<Long, Int>? = null
        waitFor(10_000) {
            WindowsTrayHooks
                .nativeIdentities()
                .values
                .singleOrNull()
                ?.also { found = it } != null
        }
        return found ?: error("no tray")
    }

    fun popup(): Triple<com.sun.jna.platform.win32.WinDef.HWND, String, Win32Probe.Box>? =
        Win32Probe.visibleWindows().firstOrNull { (_, cls, box) ->
            cls != "NucleusTrayWindow" && cls != "#32768" && box.width > 50 && box.height > 50
        }

    fun click(hwnd: Long) {
        Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
    }

    /** Visible popup sized like the state says and anchored to the tray icon. */
    fun checkAnchored(
        tray: Pair<Long, Int>,
        label: String,
        expectedDp: DpSize = state.windowSize.value,
    ) {
        val popup = popup()
        check(popup != null, "$label: popup on screen")
        popup ?: return
        val (hwnd, _, box) = popup
        val scale = Win32Probe.scaleOf(hwnd)
        val w = (expectedDp.width.value * scale).toInt()
        val h = (expectedDp.height.value * scale).toInt()
        check(
            abs(box.width - w) <= 3 && abs(box.height - h) <= 3,
            "$label: size ${box.width}x${box.height} ~ ${w}x$h (scale $scale)",
        )
        val icon = Win32Probe.shellIconRect(tray.first, tray.second)
        check(icon != null, "$label: icon rect")
        icon ?: return
        val work = Win32Probe.workAreaOf(icon)
        check(
            box.left >= work.left - 1 &&
                box.right <= work.right + 1 &&
                box.top >= work.top - 1 &&
                box.bottom <= work.bottom + 1,
            "$label: popup $box inside work area $work",
        )
        val taskbarAtBottom = icon.top >= work.bottom - 2
        val gapY = if (taskbarAtBottom) icon.top - box.bottom else box.top - icon.bottom
        check(gapY in -2..(12 * scale).toInt(), "$label: popup $box vertically next to icon $icon (gap $gapY)")
        val centered = abs(box.centerX - icon.centerX) <= (4 * scale).toInt()
        val clamped = abs(box.right - work.right) <= 2 || abs(box.left - work.left) <= 2
        check(
            centered || clamped,
            "$label: popup $box horizontally on icon $icon (centered=$centered clamped=$clamped)",
        )
    }

    fun consistent(label: String) {
        // Settle: debounce windows and the exit animation.
        Thread.sleep(1500)
        val visible = state.isVisible.value
        val onScreen = waitFor(1500) { (popup() != null) == visible }
        check(onScreen, "$label: state.isVisible=$visible matches the popup on screen (${popup() != null})")
    }
}

fun main(args: Array<String>) {
    val scenario = args[0]
    val seed = args.getOrNull(1)?.toLongOrNull() ?: 1L
    val random = Random(seed)
    val state =
        TrayAppState(
            initialWindowSize = DpSize(POPUP_W.dp, POPUP_H.dp),
            initiallyVisible = scenario == "visible-on-start",
        )
    val h = Harness(state)

    Thread({
        try {
            run(scenario, h, random)
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
        h.scope = this
        if (h.shown) {
            TrayApp(
                iconContent = { Box(Modifier.fillMaxSize().background(Color(0xFF6A1B9A))) },
                tooltip = "TrayApp probe",
                state = state,
                menu = { Item("Menu item") { h.menuClicks.incrementAndGet() } },
            ) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E0)))
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
    val tray = h.tray()
    val (hwnd, _) = tray
    waitFor(5000) { Win32Probe.shellIconRect(tray.first, tray.second) != null }
    // Let the shell settle the icon slot and the tray thread capture its position.
    Thread.sleep(800)
    when (scenario) {
        "click" -> {
            h.click(hwnd)
            check(waitFor(3000) { h.popup() != null }, "click shows the popup")
            check(h.state.isVisible.value, "state is visible after the click")
            Thread.sleep(600)
            h.checkAnchored(tray, "after click")
            h.click(hwnd)
            check(waitFor(3000) { h.popup() == null }, "second click hides the popup")
            check(!h.state.isVisible.value, "state is hidden after the second click")
            Thread.sleep(400)
            h.click(hwnd)
            check(waitFor(3000) { h.popup() != null }, "third click shows it again")
            Thread.sleep(600)
            h.checkAnchored(tray, "reopened")
        }
        "visible-on-start" -> {
            check(waitFor(5000) { h.popup() != null }, "visibleOnStart shows the popup")
            Thread.sleep(800)
            h.checkAnchored(tray, "visible on start")
        }
        "toggle-storm" -> {
            repeat(60) {
                h.click(hwnd)
                Thread.sleep(random.nextLong(0, 400))
            }
            h.consistent("after 60 seeded clicks")
            if (h.state.isVisible.value) h.checkAnchored(tray, "storm end") else h.click(hwnd)
        }
        "programmatic" -> {
            val workers =
                List(3) { w ->
                    Thread {
                        val r = Random(random.nextLong() + w)
                        repeat(80) {
                            when (r.nextInt(3)) {
                                0 -> h.state.show()
                                1 -> h.state.hide()
                                else -> h.state.toggle()
                            }
                            Thread.sleep(r.nextLong(0, 30))
                        }
                    }.apply { start() }
                }
            workers.forEach { it.join() }
            h.consistent("after concurrent show/hide/toggle")
            h.state.show()
            check(waitFor(3000) { h.popup() != null }, "show() after the storm")
            Thread.sleep(800)
            h.checkAnchored(tray, "show() after the storm")
        }
        "resize" -> {
            h.click(hwnd)
            check(waitFor(3000) { h.popup() != null }, "popup shown")
            Thread.sleep(600)
            h.state.setWindowSize(420.dp, 360.dp)
            Thread.sleep(800)
            h.checkAnchored(tray, "after resize", DpSize(420.dp, 360.dp))
        }
        "menu" -> {
            Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong())
            check(waitFor(3000) { Win32Probe.openPopupMenu() != null }, "right click shows the menu")
            val item = Win32Probe.openPopupMenu()?.let { Win32Probe.readMenu(it.second).singleOrNull() }
            Win32Probe.post(hwnd, Win32Probe.WM_CANCELMODE)
            check(item?.text == "Menu item", "menu reads back (${item?.text})")
            waitFor(2000) { Win32Probe.openPopupMenu() == null }
            item?.let { Win32Probe.post(hwnd, Win32Probe.WM_COMMAND, it.id.toLong()) }
            check(waitFor(3000) { h.menuClicks.get() == 1 }, "menu item callback")
            check(h.popup() == null, "the menu did not open the popup")
        }
        "remove" -> {
            h.click(hwnd)
            check(waitFor(3000) { h.popup() != null }, "popup shown")
            h.shown = false
            check(waitFor(5000) { h.popup() == null }, "popup gone with TrayApp")
            check(waitFor(5000) { Win32Probe.windowsOfClass("NucleusTrayWindow").isEmpty() }, "tray gone with TrayApp")
        }
        else -> check(false, "unknown scenario $scenario")
    }
}
