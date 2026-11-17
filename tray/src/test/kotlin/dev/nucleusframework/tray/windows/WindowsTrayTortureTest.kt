package dev.nucleusframework.tray.windows

import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.menu.TrayMenuBuilder
import java.io.File
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
 * Torture suite for the Windows tray backend, driven black-box through the real notification
 * area: the hidden tray window gets the very messages the shell sends it, the popup menu is read
 * back from the `#32768` window, and Shell icons / GDI / USER handles are counted.
 *
 * Seeded: every random test prints its seed; rerun one with `-PtraySeed=<seed>`, and scale the
 * fuzzers with `-PtrayIterations=<n>`.
 */
class WindowsTrayTortureTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val created = ConcurrentLinkedQueue<String>()
    private val iterations = System.getProperty("nucleus.tray.torture.iterations")?.toIntOrNull() ?: 60

    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    @AfterTest
    fun cleanup() {
        created.forEach { runCatching { WindowsTrayInitializer.dispose(it) } }
        created.clear()
    }

    // ---------------------------------------------------------------- helpers

    private fun icon(): String = TrayFixtures.icoPath

    private fun init(
        id: String,
        tooltip: String = id,
        iconPath: String = icon(),
        onLeftClick: (() -> Unit)? = null,
        onMenuOpened: (() -> Unit)? = null,
        menu: (TrayMenuBuilder.() -> Unit)?,
    ) {
        created += id
        WindowsTrayInitializer.initialize(id, iconPath, tooltip, onLeftClick, menu, onMenuOpened)
    }

    private fun identity(id: String): Pair<Long, Int> =
        awaitValue("identity of $id") { WindowsTrayInitializer.nativeIdentity(id) }

    /** Right-clicks the tray, reads the popup the shell would show, then dismisses it. */
    private fun readMenu(hwnd: Long): List<Win32Probe.Item> {
        check(Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong()))
        val (popup, hmenu) =
            try {
                awaitValue("popup menu", timeoutMs = 3000) { Win32Probe.openPopupMenu() }
            } catch (e: AssertionError) {
                val answers = Win32Probe.sendWithTimeout(hwnd, 0, timeoutMs = 1000)
                val menus = Win32Probe.windowsOfClass("#32768").size
                val threads =
                    Thread
                        .getAllStackTraces()
                        .filterKeys {
                            it.name.startsWith(
                                "NucleusTray-",
                            )
                        }.entries
                        .joinToString(" | ") { (t, st) ->
                            t.name + " " + st.take(4).joinToString(" < ")
                        }
                fail("${e.message}; tray window answers=$answers, #32768 windows=$menus; threads: $threads", e)
            }
        val items = Win32Probe.readMenu(hmenu)
        dismissMenu(hwnd)
        return items
    }

    /** Like [readMenu], but an empty menu (no popup within 400 ms) reads as an empty list. */
    private fun readMenuOrEmpty(hwnd: Long): List<Win32Probe.Item> {
        check(Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong()))
        val deadline = System.nanoTime() + 400_000_000
        while (System.nanoTime() < deadline) {
            Win32Probe.openPopupMenu()?.let { (popup, hmenu) ->
                val items = Win32Probe.readMenu(hmenu)
                dismissMenu(hwnd)
                return items
            }
            Thread.sleep(10)
        }
        return emptyList()
    }

    private fun rawHandle(hwnd: com.sun.jna.platform.win32.WinDef.HWND): Long =
        com.sun.jna.Pointer
            .nativeValue(hwnd.pointer)

    private fun dismissMenu(owner: Long) {
        // Only WM_CANCELMODE: the system reuses `#32768` windows, so a stray Esc left in the
        // queue would close the *next* popup the instant it opens.
        Win32Probe.post(owner, Win32Probe.WM_CANCELMODE)
        awaitTrue("popup dismissed") { Win32Probe.openPopupMenu() == null }
    }

    private fun command(
        hwnd: Long,
        id: Int,
    ) = check(Win32Probe.post(hwnd, Win32Probe.WM_COMMAND, id.toLong()))

    private fun <T : Any> awaitValue(
        what: String,
        timeoutMs: Long = 5000,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun awaitTrue(
        what: String,
        timeoutMs: Long = 5000,
        probe: () -> Boolean,
    ) {
        awaitValue(what, timeoutMs) { if (probe()) Unit else null }
    }

    private fun trayWindows() = Win32Probe.windowsOfClass(TrayFixtures.windowClass())

    // ---------------------------------------------------------------- tests

    @Test
    fun `fuzzed menus show exactly what was built`() {
        if (!isWindows) return
        val seed = seed()
        println("[torture] fuzzed menus seed=$seed")
        val random = Random(seed)
        val id = "fuzz-menu"
        init(id, menu = null)
        val (hwnd, _) = identity(id)
        repeat(iterations) { round ->
            val model = MenuFuzzer.menu(random, depth = 0)
            WindowsTrayInitializer.update(id, icon(), "round $round", null, { MenuFuzzer.emit(this, model) })
            val expected = MenuFuzzer.expected(model)
            // The update is applied asynchronously on the tray thread: wait until it shows.
            var lastRead: List<Win32Probe.Item> = emptyList()
            try {
                awaitTrue("round $round menu", timeoutMs = 4000) {
                    lastRead = readMenuOrEmpty(hwnd)
                    MenuFuzzer.shape(lastRead) == expected
                }
            } catch (e: AssertionError) {
                fail(
                    "seed=$seed round=$round: " + MenuFuzzer.firstDiff(expected, MenuFuzzer.shape(lastRead)) +
                        "\nmodel=" + model.toString().take(1500),
                    e,
                )
            }
        }
    }

    @Test
    fun `disposing one tray keeps the other tray menu callbacks alive`() {
        if (!isWindows) return
        val clicks = AtomicInteger()
        init("iso-a", menu = { Item("A") {} })
        init("iso-b", menu = { Item("B") { clicks.incrementAndGet() } })
        val (hwndB, _) = identity("iso-b")
        val itemB = readMenu(hwndB).single()

        WindowsTrayInitializer.dispose("iso-a")
        command(hwndB, itemB.id)
        awaitTrue("tray B callback after disposing tray A", timeoutMs = 3000) { clicks.get() == 1 }
    }

    @Test
    fun `an open menu on one tray does not freeze another tray`() {
        if (!isWindows) return
        val clicks = AtomicInteger()
        init("freeze-a", menu = { Item("A") {} })
        init("freeze-b", onLeftClick = { clicks.incrementAndGet() }, menu = { Item("B") {} })
        val (hwndA, _) = identity("freeze-a")
        val (hwndB, _) = identity("freeze-b")

        check(Win32Probe.post(hwndA, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong()))
        val (popup, _) = awaitValue("tray A popup", timeoutMs = 3000) { Win32Probe.openPopupMenu() }
        try {
            check(Win32Probe.post(hwndB, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong()))
            awaitTrue("tray B left click while A's menu is open", timeoutMs = 3000) { clicks.get() == 1 }
            assertTrue(
                Win32Probe.sendWithTimeout(hwndB, 0, timeoutMs = 2000),
                "tray B's window stopped answering while tray A's menu is open",
            )
        } finally {
            dismissMenu(hwndA)
        }
    }

    @Test
    fun `primary action fires without any UI event loop`() {
        if (!isWindows) return
        val clicks = CountDownLatch(1)
        init("primary", onLeftClick = { clicks.countDown() }, menu = { Item("x") {} })
        val (hwnd, _) = identity("primary")
        check(Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong()))
        assertTrue(clicks.await(3, TimeUnit.SECONDS), "primary action never ran")
    }

    @Test
    fun `a throwing callback does not kill the tray`() {
        if (!isWindows) return
        val good = AtomicInteger()
        init("throws", menu = {
            Item("boom") { error("user callback failure") }
            Item("ok") { good.incrementAndGet() }
        })
        val (hwnd, _) = identity("throws")
        val (boom, ok) = readMenu(hwnd)
        repeat(5) { command(hwnd, boom.id) }
        command(hwnd, ok.id)
        awaitTrue("callback after throwing ones", timeoutMs = 3000) { good.get() == 1 }
        assertEquals(2, readMenu(hwnd).size, "tray menu lost after a throwing callback")
    }

    @Test
    fun `checkable items with the same label toggle independently`() {
        if (!isWindows) return
        val a = AtomicInteger()
        val b = AtomicInteger()
        init("dup-check", menu = {
            CheckableItem("Same", checked = false, onCheckedChange = { a.incrementAndGet() })
            CheckableItem("Same", checked = false, onCheckedChange = { b.incrementAndGet() })
        })
        val (hwnd, _) = identity("dup-check")
        val first = readMenu(hwnd).first()
        command(hwnd, first.id)
        awaitTrue("first checkable toggled", timeoutMs = 3000) { a.get() == 1 }
        val after =
            awaitValue("checked state applied", timeoutMs = 3000) {
                readMenu(hwnd).takeIf { it[0].checked }
            }
        assertEquals(listOf(true, false), after.map { it.checked }, "toggling one item checked its namesake")
    }

    @Test
    fun `the icon comes back after an explorer restart`() {
        if (!isWindows) return
        init("explorer", menu = { Item("x") {} })
        val (hwnd, uid) = identity("explorer")
        awaitTrue("icon registered") { Win32Probe.shellHasIcon(hwnd, uid) }
        TrayFixtures.deleteShellIcon(hwnd, uid)
        awaitTrue("icon removed") { !Win32Probe.shellHasIcon(hwnd, uid) }
        Win32Probe.post(hwnd, Win32Probe.registerMessage("TaskbarCreated"))
        awaitTrue("icon re-added after TaskbarCreated", timeoutMs = 3000) { Win32Probe.shellHasIcon(hwnd, uid) }
    }

    @Test
    fun `menu icons in submenus are released`() {
        if (!isWindows) return
        init("gdi", menu = null)
        val (hwnd, _) = identity("gdi")
        val icon = icon()

        fun menu(round: Int): TrayMenuBuilder.() -> Unit =
            {
                SubMenu("Sub $round") {
                    repeat(8) { Item("leaf $it", iconContent = {}, onClick = {}) }
                }
            }
        // Warm up, then measure the steady state.
        repeat(5) {
            WindowsTrayInitializer.update("gdi", icon, "t", null, menu(it))
            // The tray starts with no menu: wait for this round's to be applied.
            awaitTrue("warm-up round $it") { readMenuOrEmpty(hwnd).singleOrNull()?.text == "Sub $it" }
        }
        val (gdiBefore, _) = Win32Probe.guiResources()
        repeat(40) {
            WindowsTrayInitializer.update("gdi", icon, "t", null, menu(it + 100))
            var seen: Any? = null
            try {
                awaitTrue("round $it applied") {
                    readMenu(hwnd).also { m -> seen = m }.singleOrNull()?.text == "Sub ${it + 100}"
                }
            } catch (e: AssertionError) {
                fail("round $it menu=$seen", e)
            }
        }
        val (gdiAfter, _) = Win32Probe.guiResources()
        assertTrue(gdiAfter - gdiBefore < 40, "GDI objects grew by ${gdiAfter - gdiBefore} over 40 menu rebuilds")
    }

    @Test
    fun `lifecycle churn across threads leaves nothing behind`() {
        if (!isWindows) return
        val seed = seed()
        println("[torture] lifecycle churn seed=$seed")
        val (gdiBaseline, userBaseline) = Win32Probe.guiResources()
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
                        when (random.nextInt(4)) {
                            0, 1 -> {
                                created += id
                                WindowsTrayInitializer.update(
                                    id,
                                    icon(),
                                    "w$worker s$step",
                                    if (random.nextBoolean()) ({}) else null,
                                    { MenuFuzzer.emit(this, MenuFuzzer.menu(random, depth = 1)) },
                                )
                            }
                            2 -> WindowsTrayInitializer.dispose(id)
                            else ->
                                WindowsTrayInitializer.nativeIdentity(id)?.let { (h, _) ->
                                    Win32Probe.post(h, Win32Probe.WM_COMMAND, 1000L + random.nextInt(20))
                                }
                        }
                    }
                }.onFailure { errors += it }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "churn workers hung (seed=$seed)")
        cleanup()
        assertTrue(errors.isEmpty(), "seed=$seed errors=${errors.map { it.toString() }}")
        awaitTrue("all tray windows destroyed (seed=$seed)", timeoutMs = 10_000) { trayWindows().isEmpty() }
        val (gdi, user) = Win32Probe.guiResources()
        assertTrue(gdi - gdiBaseline < 30, "GDI leak after churn: $gdiBaseline -> $gdi (seed=$seed)")
        assertTrue(user - userBaseline < 30, "USER leak after churn: $userBaseline -> $user (seed=$seed)")
    }

    @Test
    fun `dispose racing a fresh tray leaves no ghost icon`() {
        if (!isWindows) return
        repeat(iterations / 2 + 5) { round ->
            val tray = dev.nucleusframework.tray.NativeTray()
            tray.update(icon(), icon(), "ghost $round", null, { Item("x") {} })
            if (round % 3 == 0) Thread.sleep((round % 7).toLong())
            tray.dispose()
        }
        Thread.sleep(500)
        awaitTrue("no tray window survives dispose", timeoutMs = 10_000) { trayWindows().isEmpty() }
    }

    @Test
    fun `rapid updates converge on the last one`() {
        if (!isWindows) return
        val tray = dev.nucleusframework.tray.NativeTray()
        try {
            repeat(30) { n -> tray.update(icon(), icon(), "v$n", null, { Item("v$n") {} }) }
            val hwnd =
                awaitValue("tray window") { trayWindows().singleOrNull()?.let { rawHandle(it) } }
            awaitTrue("menu converged on v29", timeoutMs = 5000) { readMenu(hwnd).singleOrNull()?.text == "v29" }
        } finally {
            tray.dispose()
        }
    }

    @Test
    fun `garbage icons never crash the tray`() {
        if (!isWindows) return
        val dir = createTempDir()
        val candidates =
            listOf(
                "",
                File(dir, "missing.ico").path,
                File(dir, "empty.ico").apply { writeBytes(ByteArray(0)) }.path,
                File(dir, "junk.ico").apply { writeBytes(Random(1).nextBytes(4096)) }.path,
                File(dir, "header-only.ico").apply { writeBytes(byteArrayOf(0, 0, 1, 0, 1, 0)) }.path,
                File(dir, "huge-count.ico").apply { writeBytes(byteArrayOf(0, 0, 1, 0, -1, -1) + ByteArray(64)) }.path,
                dir.path,
                "\\\\?\\C:\\nope\\x.ico",
                "C:\\" + "a".repeat(400) + ".ico",
                icon(),
            )
        init("garbage", menu = { Item("x") {} })
        val (hwnd, _) = identity("garbage")
        for (path in candidates + candidates.reversed()) {
            WindowsTrayInitializer.update("garbage", path, "icon $path".take(200), null, {
                Item("i", iconContent = {}) {}
                Item("p") {}
            })
        }
        awaitTrue("tray still answers") { readMenu(hwnd).size == 2 }
    }

    private fun createTempDir(): File =
        kotlin.io.path
            .createTempDirectory("tray-torture")
            .toFile()
            .apply { deleteOnExit() }
}
