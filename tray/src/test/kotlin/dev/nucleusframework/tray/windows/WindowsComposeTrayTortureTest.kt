package dev.nucleusframework.tray.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.menu.ComposableTrayMenuScope
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The `Tray` composables, driven through a real composition: state changes, recompositions and
 * removal must reach the notification area, and callbacks must see the latest state.
 */
class WindowsComposeTrayTortureTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private var scene: ImageComposeScene? = null

    @AfterTest
    fun cleanup() {
        scene?.close()
        scene = null
        await("trays of the closed composition gone") { trayWindows().isEmpty() }
    }

    private fun compose(content: @Composable () -> Unit) {
        scene = ImageComposeScene(8, 8, content = content)
        frame()
    }

    /**
     * Two passes, like two vsyncs of a running app: a composable menu lambda re-records into its
     * scope's snapshot list, and the `Tray` reading that list recomposes on the following pass.
     */
    private fun frame() {
        repeat(2) {
            Snapshot.sendApplyNotifications()
            scene!!.render()
        }
    }

    private fun await(
        what: String,
        timeoutMs: Long = 6000,
        probe: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe()) return
            scene?.let { frame() }
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun trayWindows() = Win32Probe.windowsOfClass(TrayFixtures.windowClass())

    private fun trayHwnd(): Long {
        await("tray window") { trayWindows().size == 1 }
        return Pointer.nativeValue(trayWindows().single().pointer)
    }

    private fun menu(hwnd: Long): List<Win32Probe.Item> {
        Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong())
        val deadline = System.nanoTime() + 400_000_000
        while (System.nanoTime() < deadline) {
            Win32Probe.openPopupMenu()?.let { (_, hmenu) ->
                val items = Win32Probe.readMenu(hmenu)
                Win32Probe.post(hwnd, Win32Probe.WM_CANCELMODE)
                await("popup closed") { Win32Probe.openPopupMenu() == null }
                return items
            }
            Thread.sleep(10)
        }
        return emptyList()
    }

    private val icon: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color(0xFF1565C0))) }

    @Test
    fun `menu labels follow state through recompositions`() {
        if (!isWindows) return
        var count by mutableIntStateOf(0)
        compose {
            Tray(iconContent = icon, tooltip = "count") {
                Item("Count $count") {}
            }
        }
        val hwnd = trayHwnd()
        repeat(40) {
            count++
            frame()
        }
        await("menu shows the last count") { menu(hwnd).singleOrNull()?.text == "Count 40" }
    }

    @Test
    fun `a menu click right after a state change sees the new state (composable menu)`() {
        if (!isWindows) return
        var count by mutableIntStateOf(0)
        val seen = AtomicInteger(-1)
        compose {
            // A value captured at composition time (not the State object): what most apps pass.
            val captured = count
            val menu: @Composable ComposableTrayMenuScope.() -> Unit = {
                // Same structure every time: only the captured value changes.
                Item("Act") { seen.set(captured) }
            }
            Tray(iconContent = icon, tooltip = "stale", menuContent = menu)
        }
        assertClickSeesLatest({ count = it }, seen)
    }

    @Test
    fun `a menu click right after a state change sees the new state (builder menu)`() {
        if (!isWindows) return
        var count by mutableIntStateOf(0)
        val seen = AtomicInteger(-1)
        compose {
            val captured = count
            val menu: TrayMenuBuilder.() -> Unit = { Item("Act") { seen.set(captured) } }
            Tray(iconContent = icon, tooltip = "stale", menuContent = menu)
        }
        assertClickSeesLatest({ count = it }, seen)
    }

    private fun assertClickSeesLatest(
        setCount: (Int) -> Unit,
        seen: AtomicInteger,
    ) {
        val hwnd = trayHwnd()
        await("menu up") { menu(hwnd).isNotEmpty() }
        setCount(7)
        frame()
        // A user clicking right away: no time for an icon re-render, only for the menu.
        Thread.sleep(150)
        val act = menu(hwnd).single()
        Win32Probe.post(hwnd, Win32Probe.WM_COMMAND, act.id.toLong())
        await("click delivered") { seen.get() != -1 }
        assertEquals(7, seen.get(), "the click ran a lambda captured before the state changed")
    }

    @Test
    fun `onMenuOpened sees the latest state`() {
        if (!isWindows) return
        var count by mutableIntStateOf(0)
        val seen = AtomicInteger(-1)
        compose {
            val captured = count
            Tray(iconContent = icon, tooltip = "opened", onMenuOpened = { seen.set(captured) }) {
                Item("x") {}
            }
        }
        val hwnd = trayHwnd()
        await("menu up") { menu(hwnd).isNotEmpty() }
        count = 3
        frame()
        Thread.sleep(300)
        seen.set(-1)
        menu(hwnd)
        await("menu-opened delivered") { seen.get() != -1 }
        assertEquals(3, seen.get(), "onMenuOpened ran a lambda captured before the state changed")
    }

    @Test
    fun `two menus with the same flattened text are told apart`() {
        if (!isWindows) return
        var split by mutableStateOf(false)
        compose {
            Tray(iconContent = icon, tooltip = "fingerprint") {
                if (split) {
                    Item("x") {}
                    Item("y") {}
                } else {
                    // Flattens to the same text as the two items above in a naive fingerprint.
                    Item("x:true:nullI:y") {}
                }
            }
        }
        val hwnd = trayHwnd()
        await("single item") { menu(hwnd).map { it.text } == listOf("x:true:nullI:y") }
        split = true
        frame()
        await("two items after the switch") { menu(hwnd).map { it.text } == listOf("x", "y") }
    }

    @Test
    fun `adding and removing the tray from composition leaves nothing behind`() {
        if (!isWindows) return
        var shown by mutableStateOf(true)
        compose {
            if (shown) Tray(iconContent = icon, tooltip = "toggle") { Item("x") {} }
        }
        repeat(30) {
            shown = !shown
            frame()
            if (it % 5 == 0) Thread.sleep(20)
        }
        shown = false
        frame()
        await("no tray once removed") { trayWindows().isEmpty() }
        shown = true
        frame()
        await("exactly one tray once shown again") { trayWindows().size == 1 }
    }
}
