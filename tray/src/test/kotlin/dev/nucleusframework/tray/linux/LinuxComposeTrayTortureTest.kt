package dev.nucleusframework.tray.linux

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
import dev.nucleusframework.tray.Tray
import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.menu.ComposableTrayMenuScope
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The `Tray` composables on Linux, driven through a real composition (the counterpart of
 * `WindowsComposeTrayTortureTest`): state changes, recompositions and removal must reach the
 * StatusNotifierItem on the bus, and callbacks must see the latest state. [SniPanel] is the panel.
 */
class LinuxComposeTrayTortureTest : LinuxTrayTestBase() {
    private var scene: ImageComposeScene? = null

    @AfterTest
    fun closeScene() {
        scene?.close()
        scene = null
        if (LinuxTestBus.isAvailable) {
            awaitTrue("trays of the closed composition gone", 10_000) { LinuxTrayInitializer.debugIds().isEmpty() }
        }
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
            if (runCatching(probe).getOrDefault(false)) return
            scene?.let { frame() }
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    /** The bus name of the composition's only tray. */
    private fun service(): String {
        await("one tray") { LinuxTrayInitializer.debugIds().size == 1 }
        return panel.serviceOf(LinuxTrayInitializer.debugIds().single(), timeoutMs = 10_000)
    }

    private val icon: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color(0xFF1565C0))) }

    @Test
    fun `menu labels follow state through recompositions`() =
        linuxOnly {
            var count by mutableIntStateOf(0)
            compose {
                Tray(iconContent = icon, tooltip = "count") {
                    Item("Count $count") {}
                }
            }
            val service = service()
            repeat(40) {
                count++
                frame()
            }
            await("menu shows the last count") { panel.menu(service).singleOrNull()?.label == "Count 40" }
        }

    @Test
    fun `tooltip and icon follow state`() =
        linuxOnly {
            var tip by mutableStateOf("first")
            var color by mutableStateOf(Color(0xFF1565C0))
            compose {
                Tray(iconContent = { Box(Modifier.fillMaxSize().background(color)) }, tooltip = tip) {
                    Item("x") {}
                }
            }
            val service = service()
            await("first tooltip") { panel.tooltipText(service) == "first" }
            val before =
                panel
                    .iconPixmaps(service)
                    .maxBy { it.first }
                    .third
                    .copyOf()
            tip = "second"
            color = Color(0xFFC62828)
            frame()
            await("tooltip follows state") { panel.tooltipText(service) == "second" }
            await("icon follows state") {
                !panel
                    .iconPixmaps(service)
                    .maxBy { it.first }
                    .third
                    .contentEquals(before)
            }
        }

    @Test
    fun `a menu click right after a state change sees the new state (composable menu)`() =
        linuxOnly {
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
    fun `a menu click right after a state change sees the new state (builder menu)`() =
        linuxOnly {
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
        val service = service()
        await("menu up") { panel.menu(service).isNotEmpty() }
        setCount(7)
        frame()
        // A user clicking right away: no time for an icon re-render, only for the menu.
        Thread.sleep(150)
        val act = panel.menu(service).single()
        panel.click(service, act.id)
        await("click delivered") { seen.get() != -1 }
        assertEquals(7, seen.get(), "the click ran a lambda captured before the state changed")
    }

    @Test
    fun `onMenuOpened sees the latest state`() =
        linuxOnly {
            var count by mutableIntStateOf(0)
            val seen = AtomicInteger(-1)
            compose {
                val captured = count
                Tray(iconContent = icon, tooltip = "opened", onMenuOpened = { seen.set(captured) }) {
                    Item("x") {}
                }
            }
            val service = service()
            await("menu up") { panel.menu(service).isNotEmpty() }
            count = 3
            frame()
            // Past the 300 ms opening debounce of any earlier opening.
            Thread.sleep(400)
            seen.set(-1)
            panel.aboutToShow(service, 0)
            await("menu-opened delivered") { seen.get() != -1 }
            assertEquals(3, seen.get(), "onMenuOpened ran a lambda captured before the state changed")
        }

    @Test
    fun `a checkable item driven by state round-trips`() =
        linuxOnly {
            var checked by mutableStateOf(false)
            compose {
                Tray(iconContent = icon, tooltip = "check") {
                    CheckableItem("Wrap", checked = checked, onCheckedChange = { checked = it })
                }
            }
            val service = service()
            await("unchecked") { panel.menu(service).single().toggleState == 0 }
            repeat(6) { round ->
                panel.click(service, panel.menu(service).single().id)
                val want = if (round % 2 == 0) 1 else 0
                await("state flipped to ${want == 1}") { checked == (want == 1) }
                await("the item reads back ${want == 1}") { panel.menu(service).single().toggleState == want }
            }
        }

    @Test
    fun `two menus with the same flattened text are told apart`() =
        linuxOnly {
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
            val service = service()
            await("single item") { panel.menu(service).map { it.label } == listOf("x:true:nullI:y") }
            split = true
            frame()
            await("two items after the switch") { panel.menu(service).map { it.label } == listOf("x", "y") }
        }

    @Test
    fun `adding and removing the tray from composition leaves nothing behind`() =
        linuxOnly {
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
            await("no tray once removed", 10_000) {
                LinuxTrayInitializer.debugIds().isEmpty() && LinuxTrayInitializer.debugLiveNativeTrays() == 0
            }
            await("no item left on the bus", 10_000) { panel.itemNames().isEmpty() }
            shown = true
            frame()
            await("exactly one tray once shown again") {
                LinuxTrayInitializer.debugIds().size == 1 && panel.itemNames().size == 1
            }
        }
}
