package dev.nucleusframework.tray.macos

import dev.nucleusframework.tray.NativeTray
import dev.nucleusframework.tray.internal.MacTrayInitializer
import dev.nucleusframework.tray.macos.MacProbe.await
import dev.nucleusframework.tray.macos.MacProbe.awaitItem
import dev.nucleusframework.tray.macos.MacProbe.awaitValue
import dev.nucleusframework.tray.macos.MacProbe.openAndClose
import dev.nucleusframework.tray.macos.MacProbe.snapshot
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import dev.nucleusframework.tray.windows.MenuFuzzer
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Torture suite for the macOS tray backend, against real `NSStatusItem`s in the menu bar. Clicks
 * are real mouse events sent to the status item's window; what AppKit holds (button, tooltip,
 * `NSMenu` tree, open menu) is read back from AppKit itself, and status-bar windows are counted.
 *
 * Seeded: every random test prints its seed; rerun one with `-PtraySeed=<seed>`, and scale the
 * fuzzers with `-PtrayIterations=<n>`.
 */
class MacTrayTortureTest {
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

    private fun icon(): String = MacProbe.pngPath

    private fun init(
        id: String,
        tooltip: String = id,
        iconPath: String = icon(),
        onLeftClick: (() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
        menu: (TrayMenuBuilder.() -> Unit)?,
    ) {
        created += id
        MacTrayInitializer.initialize(id, iconPath, tooltip, onLeftClick, menu, onMenuOpened)
    }

    private fun menu(id: String): List<MacProbe.Item> = awaitItem(id).menu

    @Test
    fun `fuzzed menus show exactly what was built`() {
        if (!MacProbe.isMac) return
        val seed = seed()
        println("[torture] fuzzed menus seed=$seed")
        val random = Random(seed)
        val id = "fuzz-menu"
        init(id, menu = null)
        awaitItem(id)
        repeat(iterations) { round ->
            val model = MenuFuzzer.menu(random, depth = 0)
            MacTrayInitializer.update(id, icon(), "round $round", null, { MenuFuzzer.emit(this, model) })
            val expected = MacProbe.expected(model)
            var last: List<MacProbe.Shape> = emptyList()
            try {
                await("round $round menu", timeoutMs = 4000) {
                    val s = snapshot(id) ?: return@await false
                    last = MacProbe.shape(s.menu)
                    s.tooltip == "round $round" && last == expected
                }
            } catch (e: AssertionError) {
                fail(
                    "seed=$seed round=$round: " + MacProbe.firstDiff(expected, last) +
                        "\nmodel=" + model.toString().take(1500),
                    e,
                )
            }
        }
        println("[torture] fuzzed menus: $iterations rounds")
    }

    @Test
    fun `every fuzzed entry with a callback reaches it`() {
        if (!MacProbe.isMac) return
        val seed = seed()
        println("[torture] fuzzed callbacks seed=$seed")
        val random = Random(seed)
        val clicked = ConcurrentLinkedQueue<String>()
        val id = "fuzz-click"
        init(id, menu = null)
        repeat(iterations / 3 + 5) { round ->
            val labels = List(random.nextInt(1, 12)) { "r$round-$it-" + MenuFuzzer.label(random) }
            val target = random.nextInt(labels.size)
            MacTrayInitializer.update(id, icon(), "click $round", null, {
                SubMenu("nest") { labels.forEachIndexed { i, l -> Item(l) { clicked += "$round:$i" } } }
            })
            await("round $round applied") { snapshot(id)?.tooltip == "click $round" }
            MacTrayInitializer.debugPerformItem(id, intArrayOf(0, target))
            await("round $round click (seed=$seed)", timeoutMs = 3000) { "$round:$target" in clicked }
        }
        assertEquals(iterations / 3 + 5, clicked.size, "extra callbacks ran: $clicked")
    }

    @Test
    fun `disposing one tray keeps the other tray menu callbacks alive`() {
        if (!MacProbe.isMac) return
        val clicks = AtomicInteger()
        init("iso-a", menu = { Item("A") {} })
        init("iso-b", menu = { Item("B") { clicks.incrementAndGet() } })
        assertEquals("B", menu("iso-b").single().title)

        MacTrayInitializer.dispose("iso-a")
        MacTrayInitializer.debugPerformItem("iso-b", intArrayOf(0))
        await("tray B callback after disposing tray A", timeoutMs = 3000) { clicks.get() == 1 }
    }

    @Test
    fun `an open menu on one tray does not freeze another tray`() {
        if (!MacProbe.isMac) return
        val clicks = AtomicInteger()
        init("freeze-a", menu = { Item("A") {} })
        init("freeze-b", menu = { Item("B") { clicks.incrementAndGet() } })
        MacProbe.awaitClickable("freeze-a")
        MacProbe.awaitClickable("freeze-b")

        MacTrayInitializer.debugClick("freeze-a", secondary = true)
        await("tray A menu open", timeoutMs = 3000) { snapshot("freeze-a")?.menuOpen == true }
        try {
            // The menu tracks on the main thread: callers must neither wait for it nor be ignored.
            val start = System.nanoTime()
            MacTrayInitializer.update(
                "freeze-b",
                icon(),
                "while A tracks",
                null,
                { Item("B2") { clicks.incrementAndGet() } },
            )
            init("freeze-c", menu = { Item("C") {} })
            val tookMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(tookMs < 500, "update / create blocked ${tookMs}ms behind tray A's open menu")
            await("tray B updated while A's menu is open", timeoutMs = 3000) {
                snapshot("freeze-b")?.menu?.singleOrNull()?.title == "B2"
            }
            awaitItem("freeze-c")
            MacTrayInitializer.debugPerformItem("freeze-b", intArrayOf(0))
            await("tray B callback while A's menu is open", timeoutMs = 3000) { clicks.get() == 1 }
            assertTrue(snapshot("freeze-a")?.menuOpen == true, "tray A's menu closed on its own")
        } finally {
            MacTrayInitializer.debugCancelMenu("freeze-a")
        }
        await("tray A menu closed") { snapshot("freeze-a")?.menuOpen == false }
    }

    @Test
    fun `primary action fires on a left click`() {
        if (!MacProbe.isMac) return
        val clicks = CountDownLatch(1)
        val opened = AtomicInteger()
        init("primary", onLeftClick = { clicks.countDown() }, onMenuOpened = { opened.incrementAndGet() }, menu = {
            Item("x") {}
        })
        MacProbe.awaitClickable("primary")
        MacTrayInitializer.debugClick("primary", secondary = false)
        assertTrue(clicks.await(3, TimeUnit.SECONDS), "primary action never ran")
        assertTrue(snapshot("primary")?.menuOpen == false, "a left click with a primary action opened the menu")
        assertEquals(0, opened.get(), "onMenuOpened ran without a menu")
    }

    @Test
    fun `left click without a primary action opens the menu and reports it`() {
        if (!MacProbe.isMac) return
        val opened = AtomicInteger()
        init("left-menu", onMenuOpened = { opened.incrementAndGet() }, menu = { Item("only") {} })
        MacProbe.awaitClickable("left-menu")
        println("MODES " + MacTrayInitializer.debugDescribe("left-menu")?.lines()?.filter { it.startsWith("mode") })
        MacTrayInitializer.debugClick("left-menu", secondary = false)
        var last: MacProbe.Snapshot? = null
        val open =
            try {
                awaitValue("menu from a left click", timeoutMs = 3000) {
                    snapshot("left-menu")?.also { last = it }?.takeIf { it.menuOpen }
                }
            } catch (e: AssertionError) {
                fail(
                    "no menu: $last, main mode=" +
                        dev.nucleusframework.tray.internal.macos.MacNativeBridge
                            .nativeDebugMainMode(),
                    e,
                )
            }
        assertEquals(listOf("only"), open.menu.map { it.title })
        MacTrayInitializer.debugCancelMenu("left-menu")
        await("menu closed") { snapshot("left-menu")?.menuOpen == false }
        await("onMenuOpened delivered") { opened.get() == 1 }
    }

    @Test
    fun `a right click with a primary action still opens the menu`() {
        if (!MacProbe.isMac) return
        val clicks = AtomicInteger()
        init("right-menu", onLeftClick = { clicks.incrementAndGet() }, menu = { Item("x") {} })
        awaitItem("right-menu")
        openAndClose("right-menu")
        Thread.sleep(200)
        assertEquals(0, clicks.get(), "a right click ran the primary action")
    }

    @Test
    fun `a throwing callback does not kill the tray`() {
        if (!MacProbe.isMac) return
        val good = AtomicInteger()
        init("throws", menu = {
            Item("boom") { error("user callback failure") }
            Item("ok") { good.incrementAndGet() }
        })
        assertEquals(2, menu("throws").size)
        repeat(5) { MacTrayInitializer.debugPerformItem("throws", intArrayOf(0)) }
        MacTrayInitializer.debugPerformItem("throws", intArrayOf(1))
        await("callback after throwing ones", timeoutMs = 3000) { good.get() == 1 }
        assertEquals(2, openAndClose("throws").menu.size, "tray menu lost after a throwing callback")
    }

    @Test
    fun `checkable items with the same label toggle independently`() {
        if (!MacProbe.isMac) return
        val a = AtomicInteger()
        val b = AtomicInteger()
        init("dup-check", menu = {
            CheckableItem("Same", checked = false, onCheckedChange = { a.incrementAndGet() })
            CheckableItem("Same", checked = false, onCheckedChange = { b.incrementAndGet() })
        })
        assertEquals(2, menu("dup-check").size)
        MacTrayInitializer.debugPerformItem("dup-check", intArrayOf(0))
        await("first checkable toggled", timeoutMs = 3000) { a.get() == 1 }
        val after =
            awaitValue(
                "checked state applied",
                timeoutMs = 3000,
            ) { snapshot("dup-check")?.menu?.takeIf { it[0].checked } }
        assertEquals(listOf(true, false), after.map { it.checked }, "toggling one item checked its namesake")
        assertEquals(0, b.get())
    }

    @Test
    fun `the reported position is the status item, top of the screen`() {
        if (!MacProbe.isMac) return
        init("region", menu = { Item("x") {} })
        val xy = IntArray(2)
        // The system menu bar may take a while to place a new item after a burst of others.
        awaitValue("laid-out position", timeoutMs = 30_000) {
            if (MacTrayInitializer.statusItemPositionFor("region", xy)) xy else null
        }
        // Points with a top-left origin: the menu bar is the top strip of the screen.
        assertTrue(xy[1] in 0..60, "status item y=${xy[1]} is not in the menu bar")
        assertTrue(xy[0] > 8, "status item x=${xy[0]} is the unlaid-out left edge")
        val region = MacTrayInitializer.statusItemRegionFor("region")
        assertTrue(region == "top-right" || region == "top-left", "region=$region")
        // The id-less query answers for the tray changed last.
        val global = IntArray(2)
        assertTrue(MacTrayInitializer.statusItemPosition(global))
        // Same item; y may move by a few points while the menu bar animates it into place.
        assertEquals(xy[0], global[0])
        assertTrue(global[1] in 0..60, "global position y=${global[1]} is not in the menu bar")
    }

    @Test
    fun `lifecycle churn across threads leaves nothing behind`() {
        if (!MacProbe.isMac) return
        val seed = seed()
        println("[torture] lifecycle churn seed=$seed")
        val baseline = 0
        val threads = 6
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { worker ->
            pool.execute {
                val random = Random(seed + worker)
                runCatching {
                    barrier.await()
                    repeat(iterations / 2 + 5) { step ->
                        val id = "churn-$worker-${random.nextInt(3)}"
                        when (random.nextInt(5)) {
                            0, 1 -> {
                                created += id
                                MacTrayInitializer.update(
                                    id,
                                    icon(),
                                    "w$worker s$step",
                                    if (random.nextBoolean()) ({}) else null,
                                    { MenuFuzzer.emit(this, MenuFuzzer.menu(random, depth = 1)) },
                                )
                            }
                            2 -> MacTrayInitializer.dispose(id)
                            3 ->
                                MacTrayInitializer.debugPerformItem(
                                    id,
                                    intArrayOf(random.nextInt(8), random.nextInt(4)),
                                )
                            else -> MacTrayInitializer.debugClick(id, secondary = false)
                        }
                    }
                }.onFailure { errors += it }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "churn workers hung (seed=$seed)")
        MacTrayInitializer.debugIds().forEach { MacTrayInitializer.debugCancelMenu(it) }
        cleanup()
        assertTrue(errors.isEmpty(), "seed=$seed errors=${errors.map { it.toString() }}")
        MacProbe.awaitStatusItems(baseline, "all status items removed (seed=$seed)", 10_000)
    }

    @Test
    fun `dispose racing a fresh tray leaves no ghost icon`() {
        if (!MacProbe.isMac) return
        val baseline = 0
        repeat(iterations / 2 + 5) { round ->
            val tray = NativeTray()
            tray.update(icon(), icon(), "ghost $round", null, { Item("x") {} })
            if (round % 3 == 0) Thread.sleep((round % 7).toLong())
            tray.dispose()
        }
        Thread.sleep(500)
        MacProbe.awaitStatusItems(baseline, "no status item survives dispose", 10_000)
        assertTrue(
            MacTrayInitializer.debugIds().none { it.startsWith("tray-") },
            "ids left: ${MacTrayInitializer.debugIds()}",
        )
    }

    @Test
    fun `rapid updates converge on the last one`() {
        if (!MacProbe.isMac) return
        repeat(30) { n -> init("rapid", tooltip = "v$n", menu = { Item("v$n") {} }) }
        await("menu converged on v29", timeoutMs = 5000) {
            snapshot("rapid")?.let { it.tooltip == "v29" && it.menu.singleOrNull()?.title == "v29" } == true
        }
    }

    @Test
    fun `garbage icons never crash the tray and keep the last good one`() {
        if (!MacProbe.isMac) return
        val dir =
            kotlin.io.path
                .createTempDirectory("tray-torture")
                .toFile()
                .apply { deleteOnExit() }
        val candidates =
            listOf(
                "",
                File(dir, "missing.png").path,
                File(dir, "empty.png").apply { writeBytes(ByteArray(0)) }.path,
                File(dir, "junk.png").apply { writeBytes(Random(1).nextBytes(4096)) }.path,
                File(dir, "header-only.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) }.path,
                dir.path,
                "/dev/zero",
                "/" + "a".repeat(2000) + ".png",
                "relative/nope.png",
            )
        init("garbage", menu = { Item("x") {} })
        val good = awaitValue("good icon") { snapshot("garbage")?.takeIf { it.imageHeight > 0 } }
        for (path in candidates + candidates.reversed()) {
            MacTrayInitializer.update("garbage", path, "icon $path".take(200), null, {
                Item("i", iconContent = {}) {}
                Item("p") {}
            })
        }
        MacTrayInitializer.update("garbage", candidates[3], "last", null, { Item("i") {} })
        val last = awaitValue("last garbage update applied") { snapshot("garbage")?.takeIf { it.tooltip == "last" } }
        assertEquals(good.imageHeight, last.imageHeight, "a garbage icon replaced the last good one")
        assertTrue(last.imageWidth > 0)
        assertEquals(1, openAndClose("garbage").menu.size)
    }

    @Test
    fun `superseded and disposed states release their callbacks`() {
        if (!MacProbe.isMac) return
        val refs = ArrayList<WeakReference<Any>>()

        fun tracked(): () -> Unit {
            val payload = ByteArray(64)
            refs += WeakReference(payload)
            return { payload.size }
        }
        repeat(40) { n ->
            init(
                "refs-${n % 4}",
                onLeftClick = tracked(),
                onMenuOpened = tracked(),
                menu = { SubMenu("s") { Item("x", onClick = tracked()) } },
            )
        }
        (0 until 4).forEach { awaitItem("refs-$it") }
        (0 until 4).forEach { MacTrayInitializer.dispose("refs-$it") }
        await("every callback collected", timeoutMs = 10_000) {
            System.gc()
            refs.none { it.get() != null }
        }
    }
}
