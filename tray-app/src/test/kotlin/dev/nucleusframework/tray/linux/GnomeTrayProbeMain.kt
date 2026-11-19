package dev.nucleusframework.tray.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.app.TrayApp
import dev.nucleusframework.tray.app.TrayAppState
import dev.nucleusframework.tray.linux.GnomeShellDriver.Indicator
import dev.nucleusframework.tray.linux.GnomeShellDriver.Rect
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Child JVM of [LinuxGnomeTrayE2ETest]: a real `nucleusApplication` inside a headless GNOME Shell
 * ([GnomeShell]) whose AppIndicator extension is the panel. The panel draws the items, a Clutter
 * virtual pointer clicks them, and Mutter reports where the popups are (logical pixels): no part of
 * the panel is played by the test. Prints `CHECK ok|fail <what>` and exits with the failure count.
 *
 * Args: `<scenario>`. Env: the shell's bus, Wayland socket and XWayland display, and
 * `NUCLEUS_TEST_XFT_DPI` (what `gsd-xsettings` would set on a real session at that scale).
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
        Thread.sleep(20)
    }
    return runCatching(probe).getOrDefault(false)
}

/** GNOME's default `TrayApp` vertical offset (below the top bar). */
private const val GNOME_OFFSET = 10

/** What the `panel` scenario's composable menu reports back. */
private class PanelModel {
    val opens = AtomicInteger()
    val clicks = AtomicInteger()
    val deep = AtomicInteger()
    val disabled = AtomicInteger()
    var wrap by mutableStateOf(false)
    var renamed by mutableIntStateOf(0)
}

private class AppModel(
    val state: TrayAppState,
) {
    val menuClicks = AtomicInteger()
}

fun main(args: Array<String>) {
    val scenario = args[0]
    System.getenv("NUCLEUS_TEST_XFT_DPI")?.toIntOrNull()?.let { X11Probe.setXftDpi(it) }
    val panel = PanelModel()
    val apps =
        List(if (scenario == "multi") 2 else 1) { i ->
            AppModel(TrayAppState(initialWindowSize = DpSize((300 + 60 * i).dp, (200 + 40 * i).dp)))
        }
    Thread({
        try {
            GnomeShellDriver(System.getenv("DBUS_SESSION_BUS_ADDRESS")).use { shell ->
                val ctx = GnomeContext(shell, panel, apps)
                when (scenario) {
                    "panel" -> ctx.scenarioPanel()
                    "restart" -> ctx.scenarioRestart()
                    "trayapp" -> ctx.scenarioTrayApp()
                    "multi" -> ctx.scenarioMulti()
                    else -> check(false, "unknown scenario $scenario")
                }
            }
        } catch (t: Throwable) {
            check(false, "scenario threw $t at ${t.stackTrace.take(6).joinToString(" < ")}")
        }
        println("DONE failures=${failures.get()}")
        System.out.flush()
        Runtime.getRuntime().halt(failures.get())
    }, "gnome-probe").apply {
        isDaemon = true
        start()
    }
    nucleusApplication(enableSingleInstance = false) {
        if (scenario == "panel" || scenario == "restart") {
            Tray(
                iconContent = { Box(Modifier.fillMaxSize().background(Color(0xFF6A1B9A))) },
                tooltip = "Nucleus GNOME panel",
                onMenuOpened = { panel.opens.incrementAndGet() },
            ) {
                Item("Open_File & -") { panel.clicks.incrementAndGet() }
                Divider()
                CheckableItem("Wrap", checked = panel.wrap, onCheckedChange = { panel.wrap = it })
                Item("Disabled", isEnabled = false) { panel.disabled.incrementAndGet() }
                SubMenu("More") {
                    Item("Deep_item") { panel.deep.incrementAndGet() }
                }
                Item("Rename ${panel.renamed}") { panel.renamed++ }
            }
        } else {
            for ((i, app) in apps.withIndex()) {
                TrayApp(
                    iconContent = { Box(Modifier.fillMaxSize().background(Color(0xFF1565C0))) },
                    tooltip = "Nucleus GNOME TrayApp $i",
                    state = app.state,
                    menu = { Item("App menu $i") { app.menuClicks.incrementAndGet() } },
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E0)))
                }
            }
        }
    }
    exitProcess(failures.get())
}

private class GnomeContext(
    val shell: GnomeShellDriver,
    val panel: PanelModel,
    val apps: List<AppModel>,
) {
    /** The unique bus names of our items, in creation order, once the panel drew each one, ready to click. */
    fun items(count: Int): List<String> {
        check(waitFor(15_000) { shell.itemNames().size == count }, "$count item(s) on the bus (${shell.itemNames()})")
        val owners = shell.itemNames().map { shell.ownerOf(it) }
        check(
            waitFor(15_000) {
                owners.all { o ->
                    shell.indicator(o)?.let { it.visible && it.hasIcon && it.supportsActivation } == true
                }
            },
            "the panel draws each item (${shell.indicators()})",
        )
        return owners
    }

    fun indicator(owner: String): Indicator = shell.indicator(owner) ?: error("no indicator for $owner")

    fun openMenuByRightClick(owner: String) {
        val r = indicator(owner).rect
        shell.click(r.centerX, r.centerY, button = 3)
        check(waitFor(3_000) { shell.isMenuOpen(owner) }, "a right click opens the menu")
    }

    fun clickEntry(
        owner: String,
        path: List<Int>,
    ) {
        // The menu slides in: aim once the entry stopped moving.
        var r = shell.menuEntryRect(owner, path)
        for (attempt in 0 until 25) {
            Thread.sleep(80)
            val next = shell.menuEntryRect(owner, path)
            if (next == r) break
            r = next
        }
        shell.click(r.centerX, r.centerY)
    }

    fun popupOf(app: AppModel): Rect? {
        val size = app.state.windowSize.value
        val w = size.width.value.toInt()
        val h = size.height.value.toInt()
        return shell
            .windows()
            .firstOrNull {
                it.overrideRedirect &&
                    it.visible &&
                    abs(it.rect.width - w) <= 2 &&
                    abs(it.rect.height - h) <= 2
            }?.rect
    }

    /** Popup of [app] sized like its state (logical), under the top bar, centered on [icon] or clamped. */
    fun checkAnchored(
        app: AppModel,
        icon: Rect,
        label: String,
    ) {
        val box = popupOf(app)
        check(box != null, "$label: popup on screen, sized like the state (${shell.windows()})")
        box ?: return
        val work = shell.workArea()
        check(
            box.x >= work.x - 1 && box.right <= work.right + 1 && box.y >= work.y - 1 && box.bottom <= work.bottom + 1,
            "$label: popup $box inside the work area $work",
        )
        check(
            abs(box.y - (work.y + GNOME_OFFSET)) <= 2,
            "$label: popup $box right below the top bar ($work + $GNOME_OFFSET)",
        )
        val centered = abs(box.centerX - icon.centerX) <= 2
        val clamped = abs(box.right - work.right) <= 2 || abs(box.x - work.x) <= 2
        check(centered || clamped, "$label: popup $box centered on its icon $icon (or clamped)")
    }

    fun scenarioPanel() {
        val owner = items(1).single()
        val ind = indicator(owner)
        check(ind.title == "Nucleus GNOME panel", "the panel shows the tooltip as the title (${ind.title})")
        check(ind.iconWidth > 0, "the panel draws a ${ind.iconWidth} px icon")
        check(ind.pixmapSize >= ind.iconWidth, "from a pixmap at least that large (${ind.pixmapSize} px)")
        check(ind.menuItems > 0, "the panel built the menu (${ind.menuItems} items)")

        // No primary action: a left click opens the menu, as on every desktop.
        val opensBefore = panel.opens.get()
        shell.click(ind.rect.centerX, ind.rect.centerY)
        check(waitFor(3_000) { shell.isMenuOpen(owner) }, "a left click opens the menu (no primary action)")
        check(waitFor(2_000) { panel.opens.get() > opensBefore }, "onMenuOpened ran")
        val menu = shell.menu(owner)
        val top = menu.filter { it.path.size == 1 }
        check(
            top.map { it.label } == listOf("Open_File & -", "", "Wrap", "Disabled", "More", "Rename 0"),
            "labels shown literally, in order (${top.map { it.label }})",
        )
        if (top.size == 6) {
            check(top[1].kind.contains("Separator"), "the divider is a separator (${top[1].kind})")
            check(top[2].ornament == 0, "Wrap starts unchecked (ornament ${top[2].ornament})")
            check(!top[3].sensitive, "Disabled is insensitive")
            check(top[4].kind.contains("SubMenu"), "More is a submenu (${top[4].kind})")
        }
        val deep = menu.firstOrNull { it.path == listOf(4, 0) }
        check(deep?.label == "Deep_item", "the submenu holds Deep_item, literally (${deep?.label})")

        clickEntry(owner, listOf(0))
        check(waitFor(3_000) { panel.clicks.get() == 1 }, "clicking an item runs its callback once")
        check(waitFor(3_000) { !shell.isMenuOpen(owner) }, "the menu closes after a click")

        // Checkable: the click flips the state, the composition re-records the menu, the panel updates.
        openMenuByRightClick(owner)
        clickEntry(owner, listOf(2))
        check(waitFor(3_000) { panel.wrap }, "clicking Wrap checks it")
        // The extension defers updates while its menu is closed and applies them when it opens.
        Thread.sleep(300)
        openMenuByRightClick(owner)
        check(
            waitFor(5_000) { shell.menu(owner).first { it.path == listOf(2) }.ornament == 2 },
            "reopened, the menu shows Wrap checked",
        )
        shell.closeMenus()

        // A disabled item never fires, whatever the panel does with the click.
        openMenuByRightClick(owner)
        clickEntry(owner, listOf(3))
        Thread.sleep(500)
        check(panel.disabled.get() == 0, "a disabled item does not fire")
        shell.closeMenus()

        // Submenu: GNOME expands it inline, then the deep item fires.
        openMenuByRightClick(owner)
        clickEntry(owner, listOf(4))
        check(waitFor(3_000) { shell.isSubmenuOpen(owner, listOf(4)) }, "clicking More expands it")
        clickEntry(owner, listOf(4, 0))
        check(waitFor(3_000) { panel.deep.get() == 1 }, "the submenu item fires")
        shell.closeMenus()

        // A label driven by state follows it, while the menu is closed and while it is open.
        openMenuByRightClick(owner)
        clickEntry(owner, listOf(5))
        check(waitFor(3_000) { panel.renamed == 1 }, "Rename fires")
        Thread.sleep(300)
        openMenuByRightClick(owner)
        check(
            waitFor(5_000) { shell.menu(owner).firstOrNull { it.path == listOf(5) }?.label == "Rename 1" },
            "reopened, the menu shows the new label (${shell.menu(owner).map { it.label }})",
        )
        panel.renamed = 7
        check(
            waitFor(5_000) { shell.menu(owner).firstOrNull { it.path == listOf(5) }?.label == "Rename 7" },
            "a label changed while the menu is open updates in place",
        )
        check(shell.isMenuOpen(owner), "the open menu stays open across a label change")
        shell.closeMenus()
    }

    fun scenarioRestart() {
        val owner = items(1).single()
        shell.restartAppIndicatorHost()
        check(
            waitFor(15_000) { shell.indicator(owner)?.let { it.visible && it.hasIcon && it.menuItems > 0 } == true },
            "the item is back in the panel after the host restarted (${shell.indicators()})",
        )
        openMenuByRightClick(owner)
        clickEntry(owner, listOf(0))
        check(waitFor(3_000) { panel.clicks.get() == 1 }, "its menu still works")
    }

    fun scenarioTrayApp() {
        val app = apps.single()
        val owner = items(1).single()
        val icon = indicator(owner).rect
        println("scale=${shell.scale()} monitor=${shell.monitor()} work=${shell.workArea()} icon=$icon")

        // GNOME's AppIndicator host: a single left click opens the menu, a double click activates.
        shell.click(icon.centerX, icon.centerY)
        check(waitFor(3_000) { shell.isMenuOpen(owner) }, "a single left click opens the menu (GNOME)")
        check(popupOf(app) == null && !app.state.isVisible.value, "and not the popup")
        shell.closeMenus()
        check(waitFor(2_000) { !shell.isMenuOpen(owner) }, "menu closed")
        Thread.sleep(500)

        shell.doubleClick(icon.centerX, icon.centerY)
        check(waitFor(3_000) { popupOf(app) != null }, "a double click shows the popup")
        check(app.state.isVisible.value, "state is visible")
        Thread.sleep(400)
        checkAnchored(app, icon, "after a double click")

        shell.doubleClick(icon.centerX, icon.centerY)
        check(waitFor(3_000) { popupOf(app) == null }, "another double click hides it")
        check(!app.state.isVisible.value, "state is hidden")
        Thread.sleep(400)

        app.state.show()
        check(waitFor(3_000) { popupOf(app) != null }, "show() brings it back")
        Thread.sleep(400)
        checkAnchored(app, icon, "after show()")
        app.state.setWindowSize(420.dp, 320.dp)
        check(waitFor(3_000) { popupOf(app) != null }, "resized popup on screen")
        Thread.sleep(600)
        checkAnchored(app, icon, "after a resize")
        app.state.hide()
        check(waitFor(3_000) { popupOf(app) == null }, "hide() takes it away")

        openMenuByRightClick(owner)
        clickEntry(owner, listOf(0))
        check(waitFor(3_000) { app.menuClicks.get() == 1 }, "the TrayApp menu works")
        Thread.sleep(300)
        check(popupOf(app) == null, "the menu did not open the popup")
    }

    fun scenarioMulti() {
        val owners = items(2)
        val icons = owners.map { indicator(it).rect }
        check(abs(icons[0].centerX - icons[1].centerX) >= icons[0].width, "two icons, side by side ($icons)")
        // Each popup must come up under its own icon, not under the last one clicked.
        for ((app, icon) in apps.zip(icons)) {
            shell.doubleClick(icon.centerX, icon.centerY)
            check(waitFor(3_000) { popupOf(app) != null }, "popup under $icon shown")
            Thread.sleep(400)
            checkAnchored(app, icon, "popup of the icon at $icon")
        }
        val first = apps[0]
        if (first.state.isVisible.value) {
            first.state.setWindowSize(320.dp, 220.dp)
            Thread.sleep(800)
            checkAnchored(first, icons[0], "first popup re-anchored after the second icon was clicked")
        }
        check(apps[1].state.isVisible.value, "second popup still up")
    }
}
