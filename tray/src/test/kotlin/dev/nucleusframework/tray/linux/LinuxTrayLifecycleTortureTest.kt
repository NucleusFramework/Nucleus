package dev.nucleusframework.tray.linux

import dev.nucleusframework.tray.internal.LinuxTrayInitializer
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.windows.MenuFuzzer
import java.lang.ref.WeakReference
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lifecycle torture for the Linux tray backend, black-box over D-Bus like [LinuxTrayTortureTest]:
 * panel restarts, throwing and re-entrant callbacks, churn and bursts across threads, many trays,
 * callbacks released, and the session bus dying under a tray. Every test ends with no native tray,
 * tray thread or bus name left behind.
 */
class LinuxTrayLifecycleTortureTest : LinuxTrayTestBase() {
    @Test
    fun `panel restart - every tray registers with the new watcher`() =
        linuxOnly {
            val ids = listOf("restart-a", "restart-b")
            ids.forEach { init(it) { Item("x") {} } }
            val services = ids.map { panel.serviceOf(it) }
            repeat(3) { round ->
                panel.stopWatcher()
                panel.registrations.clear()
                panel.startWatcher()
                for (s in services) {
                    awaitTrue("$s re-registers (round $round)") { panel.registrations.any { it.service == s } }
                }
            }
        }

    @Test
    fun `a tray started before any panel shows up when the panel starts`() =
        linuxOnly {
            panel.stopWatcher()
            try {
                panel.registrations.clear()
                init("early") { Item("x") {} }
                awaitTrue("early tray on the bus") { panel.itemNames().isNotEmpty() }
                Thread.sleep(200)
                assertTrue(panel.registrations.isEmpty())
            } finally {
                panel.startWatcher()
            }
            panel.serviceOf("early")
        }

    @Test
    fun `throwing callbacks leave the tray working`() =
        linuxOnly {
            val ok = AtomicInteger()
            init("throws", onLeftClick = { error("primary boom") }, onMenuOpened = { error("opened boom") }) {
                Item("boom") { error("item boom") }
                Item("fine") { ok.incrementAndGet() }
            }
            val service = panel.serviceOf("throws")
            val menu = awaitValue("menu") { panel.menu(service).takeIf { it.size == 2 } }
            repeat(5) {
                panel.activate(service, 0, 0)
                panel.click(service, menu[0].id)
                panel.aboutToShow(service, 0)
            }
            panel.click(service, menu[1].id)
            awaitTrue("fine still fires") { ok.get() == 1 }
            assertEquals(menu.map { it.id }, panel.menu(service).map { it.id })
        }

    @Test
    fun `a callback may update and dispose its own tray`() =
        linuxOnly {
            val counter = AtomicInteger()
            lateinit var menu: TrayMenuBuilder.() -> Unit
            menu = {
                Item("count ${counter.get()}") {
                    counter.incrementAndGet()
                    LinuxTrayInitializer.update("self", LinuxFixtures.png(), "self", null, menu, null)
                }
                Item("close") { LinuxTrayInitializer.dispose("self") }
            }
            init("self", menu = menu)
            val service = panel.serviceOf("self")
            repeat(5) { n ->
                val items =
                    awaitValue("count $n") { panel.menu(service).takeIf { it.firstOrNull()?.label == "count $n" } }
                panel.click(service, items[0].id)
            }
            val items = awaitValue("count 5") { panel.menu(service).takeIf { it.firstOrNull()?.label == "count 5" } }
            panel.click(service, items[1].id)
            awaitTrue("self-disposed tray leaves the bus") { !panel.hasOwner(service) }
            created.remove("self")
        }

    @Test
    fun `rapid updates from many threads converge on the last one`() =
        linuxOnly {
            init("rapid") { Item("start") {} }
            val service = panel.serviceOf("rapid")
            val threads = 6
            val perThread = 150
            val barrier = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)
            val futures =
                (0 until threads).map { t ->
                    pool.submit {
                        barrier.await()
                        repeat(perThread) { i ->
                            LinuxTrayInitializer.update(
                                "rapid",
                                LinuxFixtures.png(),
                                "t$t-$i",
                                null,
                                { Item("t$t-$i") {} },
                                null,
                            )
                        }
                    }
                }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            // Every thread is done; this one is the last change, and the one the host must end on.
            LinuxTrayInitializer.update("rapid", LinuxFixtures.png(), "final", null, { Item("final") {} }, null)
            awaitMenu(service, listOf(Shape("final", false, false, false, false, false, null, emptyList())))
            awaitTrue("final tooltip") { panel.tooltipText(service) == "final" }
        }

    @Test
    fun `lifecycle churn across threads leaves no tray, thread or bus name behind`() =
        linuxOnly {
            val seed = seed()
            println("[torture] churn seed=$seed")
            val threads = 6
            val pool = Executors.newFixedThreadPool(threads)
            val barrier = CyclicBarrier(threads)
            val futures =
                (0 until threads).map { t ->
                    pool.submit {
                        val random = Random(seed + t)
                        barrier.await()
                        repeat(40) {
                            val id = "churn-${random.nextInt(4)}"
                            created += id
                            when (random.nextInt(4)) {
                                0 -> LinuxTrayInitializer.dispose(id)
                                else -> {
                                    val nodes = MenuFuzzer.menu(random, 2)
                                    LinuxTrayInitializer.update(
                                        id,
                                        LinuxFixtures.png(),
                                        id,
                                        null,
                                        { MenuFuzzer.emit(this, nodes) },
                                        null,
                                    )
                                }
                            }
                        }
                    }
                }
            futures.forEach { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown()
            created.forEach { LinuxTrayInitializer.dispose(it) }
            created.clear()
            awaitTrue("no native tray left", 10_000) { LinuxTrayInitializer.debugLiveNativeTrays() == 0 }
            awaitTrue("no item name left on the bus") { panel.itemNames().isEmpty() }
            awaitTrue(
                "no tray thread left",
            ) { Thread.getAllStackTraces().keys.none { it.name.startsWith("NucleusTray-") } }
        }

    @Test
    fun `create and dispose a hundred times`() =
        linuxOnly {
            repeat(100) { i ->
                init("cd") { Item("i$i") {} }
                if (i % 10 == 0) panel.serviceOf("cd")
                LinuxTrayInitializer.dispose("cd")
            }
            created.clear()
            awaitTrue("no native tray left", 10_000) { LinuxTrayInitializer.debugLiveNativeTrays() == 0 }
            awaitTrue("no item name left") { panel.itemNames().isEmpty() }
        }

    @Test
    fun `thirty trays at once`() =
        linuxOnly {
            val ids = (0 until 30).map { "many-$it" }
            ids.forEach { id -> init(id) { Item(id) {} } }
            val services = ids.map { panel.serviceOf(it, timeoutMs = 15_000) }
            assertEquals(30, services.toSet().size)
            ids.zip(services).forEach { (id, s) ->
                awaitMenu(s, listOf(Shape(id, false, false, false, false, false, null, emptyList())))
            }
        }

    @Test
    fun `callbacks are released with their menu and their tray`() =
        linuxOnly {
            fun weakCallbacks(): List<WeakReference<Any>> {
                val refs = ArrayList<WeakReference<Any>>()
                val payloads = List(3) { Any() }
                payloads.forEach { refs += WeakReference(it) }
                init(
                    "release",
                    onLeftClick = { payloads[0].hashCode() },
                    onMenuOpened = { payloads[1].hashCode() },
                ) { Item("x") { payloads[2].hashCode() } }
                return refs
            }
            val refs = weakCallbacks()
            panel.serviceOf("release")
            // A new menu replaces the old callbacks.
            LinuxTrayInitializer.update("release", LinuxFixtures.png(), "release", null, { Item("y") {} }, null)
            awaitMenu(
                panel.serviceOf("release"),
                listOf(Shape("y", false, false, false, false, false, null, emptyList())),
            )
            awaitTrue("replaced callbacks collected") {
                System.gc()
                refs.all { it.get() == null }
            }
            LinuxTrayInitializer.dispose("release")
            created.remove("release")
        }

    @Test
    fun `the session bus dying under a tray`() =
        linuxOnly {
            // A second private bus: kill it under a live tray, then keep using and dispose the tray.
            val doomed = PrivateBus.start()
            ProcessEnv.set("DBUS_SESSION_BUS_ADDRESS", doomed.address)
            try {
                val doomedPanel = SniPanel(doomed.address)
                try {
                    init("doomed") { Item("x") {} }
                    doomedPanel.serviceOf("doomed")
                } finally {
                    doomedPanel.close()
                }
            } finally {
                ProcessEnv.set("DBUS_SESSION_BUS_ADDRESS", LinuxTestBus.address)
            }
            doomed.close()
            repeat(
                20,
            ) { LinuxTrayInitializer.update("doomed", LinuxFixtures.png(), "d$it", null, { Item("x$it") {} }, null) }
            LinuxTrayInitializer.dispose("doomed")
            created.remove("doomed")
            awaitTrue("tray closes without its bus", 10_000) { LinuxTrayInitializer.debugLiveNativeTrays() == 0 }
        }
}
