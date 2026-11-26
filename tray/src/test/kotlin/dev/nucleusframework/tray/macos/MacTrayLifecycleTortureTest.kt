package dev.nucleusframework.tray.macos

import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.macos.MacProbe.await
import dev.nucleusframework.tray.macos.MacProbe.awaitItem
import dev.nucleusframework.tray.macos.MacProbe.awaitValue
import dev.nucleusframework.tray.macos.MacProbe.snapshot
import dev.nucleusframework.tray.windows.MenuFuzzer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Lifecycle tortures of the macOS backend: open menus, disposal, many trays, JVM exit. */
class MacTrayLifecycleTortureTest {
    private companion object {
        const val TRACKING = "NSEventTrackingRunLoopMode"
    }

    private val created = ConcurrentLinkedQueue<String>()
    private val iterations = System.getProperty("nucleus.tray.torture.iterations")?.toIntOrNull() ?: 60

    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    @AfterTest
    fun cleanup() {
        created.forEach { runCatching { MacTrayInitializer.dispose(it) } }
        created.clear()
        // Every test starts from, and must leave, a menu bar without our items.
        if (MacProbe.isMac) MacProbe.awaitStatusItems(0, "status items of the test released")
    }

    private fun init(
        id: String,
        onLeftClick: (() -> Unit)? = null,
        menu: dev.nucleusframework.tray.menu.TrayMenuBuilder.() -> Unit,
    ) {
        created += id
        MacTrayInitializer.initialize(id, MacProbe.pngPath, id, onLeftClick, menu)
    }

    @Test
    fun `disposing with the menu open closes it and removes the item`() {
        if (!MacProbe.isMac) return
        val baseline = 0
        init("open-dispose") { SubMenu("s") { Item("x") {} } }
        MacProbe.awaitClickable("open-dispose")
        MacTrayInitializer.debugClick("open-dispose", secondary = true)
        await("menu open") { snapshot("open-dispose")?.menuOpen == true }
        val start = System.nanoTime()
        MacTrayInitializer.dispose("open-dispose")
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(tookMs < 500, "dispose blocked ${tookMs}ms behind the open menu")
        MacProbe.awaitStatusItems(baseline, "status item removed")
        // The main thread left the menu's tracking loop: a new tray still comes up.
        init("after-open-dispose") { Item("y") {} }
        assertEquals("y", awaitItem("after-open-dispose").menu.single().title)
    }

    @Test
    fun `a click while a menu is open closes it instead of stacking another`() {
        if (!MacProbe.isMac) return
        init("stack-a") { Item("a") {} }
        init("stack-b") { Item("b") {} }
        MacProbe.awaitClickable("stack-a")
        MacProbe.awaitClickable("stack-b")
        for (second in listOf("stack-b", "stack-a")) {
            MacTrayInitializer.debugClick("stack-a", secondary = true)
            await("menu A open") { snapshot("stack-a")?.menuOpen == true }
            MacTrayInitializer.debugClick(second, secondary = true)
            // A second tracking session nested in the first would orphan the first once it closes.
            await("menu A closed by the click on $second") {
                snapshot("stack-a")?.let { !it.menuOpen && it.mainMode != TRACKING } == true
            }
            assertTrue(snapshot("stack-b")?.menuOpen == false, "the click on $second opened a menu inside A's")
        }
    }

    @Test
    fun `a callback that disposes its own tray is safe`() {
        if (!MacProbe.isMac) return
        val ran = AtomicInteger()
        init("self-dispose") {
            Item("bye") {
                ran.incrementAndGet()
                MacTrayInitializer.dispose("self-dispose")
            }
        }
        MacProbe.awaitClickable("self-dispose")
        MacTrayInitializer.debugClick("self-dispose", secondary = true)
        await("menu open") { snapshot("self-dispose")?.menuOpen == true }
        // Selected from the open menu, like a user would.
        MacTrayInitializer.debugPerformItem("self-dispose", intArrayOf(0))
        await("callback ran") { ran.get() == 1 }
        await("tray gone") { MacTrayInitializer.debugIds().none { it == "self-dispose" } }
        init("after-self-dispose") { Item("z") {} }
        awaitItem("after-self-dispose")
    }

    @Suppress("LongMethod")
    @Test
    fun `click storm during rebuilds and disposals never crashes`() {
        if (!MacProbe.isMac) return
        val seed = seed()
        println("[torture] click storm seed=$seed")
        val baseline = 0
        val ids = List(4) { "storm-$it" }
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val callbacks = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        // Writers: rebuild and dispose trays.
        repeat(2) { w ->
            pool.execute {
                val random = Random(seed + w)
                runCatching {
                    while (!stop.get()) {
                        val id = ids.random(random)
                        if (random.nextInt(6) == 0) {
                            MacTrayInitializer.dispose(id)
                        } else {
                            created += id
                            MacTrayInitializer.update(
                                id,
                                MacProbe.pngPath,
                                "w$w",
                                if (random.nextBoolean()) ({ callbacks.incrementAndGet() }) else null,
                                {
                                    repeat(random.nextInt(0, 6)) { Item("i$it") { callbacks.incrementAndGet() } }
                                    SubMenu("s") { MenuFuzzer.emit(this, MenuFuzzer.menu(random, depth = 2)) }
                                },
                            )
                        }
                        Thread.sleep(random.nextLong(0, 4))
                    }
                }.onFailure { errors += it }
            }
        }
        // Clickers: left / right clicks, item selections, menu dismissals.
        repeat(2) { c ->
            pool.execute {
                val random = Random(seed + 100 + c)
                runCatching {
                    while (!stop.get()) {
                        val id = ids.random(random)
                        when (random.nextInt(5)) {
                            0 -> MacTrayInitializer.debugClick(id, secondary = false)
                            1 -> MacTrayInitializer.debugClick(id, secondary = true)
                            2 -> MacTrayInitializer.debugCancelMenu(id)
                            else ->
                                MacTrayInitializer.debugPerformItem(
                                    id,
                                    intArrayOf(random.nextInt(7), random.nextInt(4)),
                                )
                        }
                        Thread.sleep(random.nextLong(0, 6))
                    }
                }.onFailure { errors += it }
            }
        }
        Thread.sleep(iterations * 100L)
        stop.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "storm threads hung (seed=$seed)")
        ids.forEach { MacTrayInitializer.debugCancelMenu(it) }
        try {
            await("main thread out of menu tracking (seed=$seed)") {
                dev.nucleusframework.tray.internal.macos.MacNativeBridge
                    .nativeDebugMainMode() != TRACKING
            }
        } catch (e: AssertionError) {
            val all =
                ids.associateWith {
                    MacTrayInitializer.debugDescribe(it)?.lines()?.filter { l ->
                        !l.startsWith("item\t")
                    }
                }
            fail("$all", e)
        }
        // The main thread still answers, and the last state of every tray is applied.
        ids.forEach { id ->
            MacTrayInitializer.update(id, MacProbe.pngPath, "final", null, { Item("final $id") {} })
            created += id
        }
        ids.forEach { id ->
            var last: MacProbe.Snapshot? = null
            try {
                await("$id settles (seed=$seed)", timeoutMs = 10_000) {
                    MacTrayInitializer.debugCancelMenu(id)
                    last = snapshot(id)
                    last?.let { it.tooltip == "final" && it.menu.singleOrNull()?.title == "final $id" } == true
                }
            } catch (e: AssertionError) {
                val others =
                    ids.associateWith {
                        MacTrayInitializer.debugDescribe(it)?.lines()?.filter { l ->
                            !l.startsWith("item\t")
                        }
                    }
                fail(
                    "$id last=$last; others=$others; status items=${MacProbe.statusItems()}; " +
                        "main mode=${dev.nucleusframework.tray.internal.macos.MacNativeBridge.nativeDebugMainMode()}",
                    e,
                )
            }
        }
        assertTrue(errors.isEmpty(), "seed=$seed errors=${errors.map { it.toString() }}")
        println("[torture] click storm: ${callbacks.get()} callbacks ran")
        cleanup()
        MacProbe.awaitStatusItems(baseline, "every storm tray removed (seed=$seed)", 10_000)
    }

    @Test
    fun `thirty trays live side by side and all go away`() {
        if (!MacProbe.isMac) return
        val baseline = 0
        val clicks = AtomicInteger()
        val ids = List(30) { "many-$it" }
        ids.forEach { id -> init(id, onLeftClick = { clicks.incrementAndGet() }) { Item(id) {} } }
        ids.forEach { id ->
            assertEquals(id, awaitItem(id).menu.single().title)
            MacTrayInitializer.debugClick(id, secondary = false)
        }
        await("every primary action", timeoutMs = 5000) { clicks.get() == ids.size }
        MacProbe.awaitStatusItems(baseline + ids.size, "30 status items", 5000)
        ids.shuffled(Random(seed())).forEach { MacTrayInitializer.dispose(it) }
        MacProbe.awaitStatusItems(baseline, "all status items removed", 5000)
    }

    @Test
    fun `create and dispose a hundred times leaks no status item or callback`() {
        if (!MacProbe.isMac) return
        val baseline = 0
        val threadsBefore = Thread.getAllStackTraces().size
        repeat(100) { n ->
            val id = "leak-$n"
            init(id, onLeftClick = {}) { SubMenu("s") { Item("x$n") {} } }
            if (n % 10 == 0) awaitItem(id)
            MacTrayInitializer.dispose(id)
        }
        MacProbe.awaitStatusItems(baseline, "status items back to baseline", 10_000)
        val threadsAfter = Thread.getAllStackTraces().size
        assertTrue(threadsAfter - threadsBefore < 10, "threads grew from $threadsBefore to $threadsAfter")
    }

    @Test
    fun `a JVM with a live tray and an open menu still ends`() {
        if (!MacProbe.isMac) return
        for (mode in listOf("exit", "return", "exit-open")) {
            val process =
                ProcessBuilder(
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElseThrow(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.nucleusframework.tray.macos.MacTrayExitProbeMainKt",
                    mode,
                    MacProbe.pngPath,
                ).redirectErrorStream(true).start()
            val ended = process.waitFor(20, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText()
            if (!ended) {
                process.destroyForcibly()
                fail("JVM with a live tray ($mode) did not end:\n$output")
            }
            assertTrue("READY" in output, "probe never showed its tray ($mode):\n$output")
            assertEquals(0, process.exitValue(), "probe ($mode) exit code:\n$output")
        }
    }

    @Test
    fun `menu bar position stays valid across rebuilds`() {
        if (!MacProbe.isMac) return
        init("pos-stable") { Item("x") {} }
        val xy = IntArray(2)
        try {
            awaitValue("laid out", timeoutMs = 30_000) {
                if (MacTrayInitializer.statusItemPositionFor("pos-stable", xy)) xy.copyOf() else null
            }
        } catch (e: AssertionError) {
            fail("never laid out: ${snapshot("pos-stable")}", e)
        }
        repeat(20) { n ->
            MacTrayInitializer.update("pos-stable", MacProbe.pngPath, "t$n", null, { repeat(n) { Item("i$it") {} } })
            assertTrue(
                MacTrayInitializer.statusItemPositionFor("pos-stable", xy) ||
                    run {
                        Thread.sleep(50)
                        MacTrayInitializer.statusItemPositionFor("pos-stable", xy)
                    },
                "position lost after rebuild $n",
            )
            assertTrue(xy[0] > 8 && xy[1] in 0..60, "rebuild $n moved the item to $xy")
        }
    }
}
