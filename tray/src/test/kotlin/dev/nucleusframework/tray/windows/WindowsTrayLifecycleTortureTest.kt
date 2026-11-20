package dev.nucleusframework.tray.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import dev.nucleusframework.tray.NativeTray
import dev.nucleusframework.tray.internal.WindowsTrayInitializer
import dev.nucleusframework.tray.utils.ComposableIconUtils
import dev.nucleusframework.tray.utils.IconFileCache
import dev.nucleusframework.tray.utils.IconRenderProperties
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Lifecycle side of the Windows torture suite: exit, teardown under load, leaks. */
private const val NL = "\n"

class WindowsTrayLifecycleTortureTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val created = ConcurrentLinkedQueue<String>()

    @AfterTest
    fun cleanup() {
        created.forEach { runCatching { WindowsTrayInitializer.dispose(it) } }
        created.clear()
    }

    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    private fun await(
        what: String,
        timeoutMs: Long = 5000,
        probe: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe()) return
            Thread.sleep(10)
        }
        fail("timed out after ${timeoutMs}ms waiting for $what")
    }

    private fun identity(id: String): Pair<Long, Int> {
        var found: Pair<Long, Int>? = null
        await("identity of $id") { WindowsTrayInitializer.nativeIdentity(id)?.also { found = it } != null }
        return found!!
    }

    private fun trayThreads() =
        Thread.getAllStackTraces().keys.filter {
            it.name.startsWith("NucleusTray-") &&
                it.isAlive
        }

    private fun trayWindows() = Win32Probe.windowsOfClass(TrayFixtures.windowClass())

    @Test
    fun `a JVM with a live tray still ends and takes the icon with it`() {
        if (!isWindows) return
        for (mode in listOf("exit", "return")) {
            val java = File(System.getProperty("java.home"), "bin/java.exe").path
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.nucleusframework.tray.windows.TrayExitProbeMainKt",
                    mode,
                    TrayFixtures.icoPath,
                ).redirectErrorStream(true).start()
            val output = mutableListOf<String>()
            val line =
                process.inputStream
                    .bufferedReader()
                    .lineSequence()
                    .onEach { output += it }
                    .firstOrNull { it.startsWith("IDENTITY ") }
                    ?: fail("child never reported its tray ($mode):\n" + output.takeLast(30).joinToString("\n"))
            val (hwnd, uid) = line.removePrefix("IDENTITY ").split(" ").let { it[0].toLong() to it[1].toInt() }
            // "return": a tray thread must never keep the JVM alive once main is done.
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child JVM did not exit ($mode)")
            // Weak oracle: the shell also drops the rect of a dead window, so this cannot tell a
            // removed icon from a visual ghost; the shutdown hook is what removes it for real.
            await("icon of a JVM that ended with '$mode' gone", timeoutMs = 3000) {
                !Win32Probe.shellHasIcon(hwnd, uid)
            }
        }
    }

    @Test
    fun `disposing with the popup menu open returns promptly`() {
        if (!isWindows) return
        created += "open-dispose"
        WindowsTrayInitializer.initialize("open-dispose", TrayFixtures.icoPath, "t", null, { Item("x") {} })
        val (hwnd, _) = identity("open-dispose")
        Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong())
        await("popup open") { Win32Probe.openPopupMenu() != null }
        val started = System.nanoTime()
        WindowsTrayInitializer.dispose("open-dispose")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 1000, "dispose blocked ${ms}ms behind an open menu")
        await("popup closed") { Win32Probe.openPopupMenu() == null }
        assertTrue(!Win32Probe.isWindow(hwnd), "tray window survived dispose")
    }

    @Test
    fun `click storm during rebuilds and disposals never crashes`() {
        if (!isWindows) return
        val seed = seed()
        println("[torture] click storm seed=$seed")
        val ids = List(3) { "storm-$it" }
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val fired =
            java.util.concurrent.atomic
                .AtomicInteger()
        val pool = Executors.newFixedThreadPool(7)
        repeat(4) { worker ->
            pool.execute {
                val random = Random(seed + worker)
                runCatching {
                    while (!stop.get()) {
                        val id = ids.random(random)
                        if (random.nextInt(25) == 0) {
                            WindowsTrayInitializer.dispose(id)
                        } else {
                            created += id
                            val model = MenuFuzzer.menu(random, depth = 2)
                            WindowsTrayInitializer.update(
                                id,
                                TrayFixtures.icoPath,
                                "storm",
                                if (random.nextBoolean()) ({ fired.incrementAndGet() }) else null,
                                {
                                    MenuFuzzer.emit(this, model)
                                    Item("count") { fired.incrementAndGet() }
                                },
                            )
                        }
                    }
                }.onFailure { errors += it }
            }
        }
        repeat(3) { clicker ->
            pool.execute {
                val random = Random(seed + 100 + clicker)
                runCatching {
                    while (!stop.get()) {
                        val (hwnd, _) = WindowsTrayInitializer.nativeIdentity(ids.random(random)) ?: continue
                        when (random.nextInt(6)) {
                            0 -> Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
                            1 -> Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_RBUTTONUP.toLong())
                            2 -> Win32Probe.post(hwnd, Win32Probe.WM_CANCELMODE)
                            3 -> Win32Probe.post(hwnd, Win32Probe.registerMessage("TaskbarCreated"))
                            else -> Win32Probe.post(hwnd, Win32Probe.WM_COMMAND, 1000L + random.nextInt(120))
                        }
                        Win32Probe.openPopupMenu()?.let { Win32Probe.post(hwnd, Win32Probe.WM_CANCELMODE) }
                    }
                }.onFailure { errors += it }
            }
        }
        Thread.sleep(8_000)
        stop.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "storm threads hung (seed=$seed)")
        cleanup()
        assertTrue(errors.isEmpty(), "seed=$seed: ${errors.map { it.toString() }}")
        try {
            await("storm trays gone (seed=$seed)", 10_000) { trayWindows().isEmpty() && trayThreads().isEmpty() }
        } catch (e: AssertionError) {
            val dump =
                Thread
                    .getAllStackTraces()
                    .filterKeys {
                        it.name.startsWith(
                            "NucleusTray-",
                        )
                    }.entries
                    .joinToString(NL) { (t, st) ->
                        "${t.name} ${t.state}" + NL + st.take(25).joinToString(NL) { "    at $it" }
                    }
            fail("${e.message}" + NL + "windows=${trayWindows().size}" + NL + dump, e)
        }
        println("[torture] click storm callbacks fired=${fired.get()}")
    }

    @Test
    fun `thirty trays live side by side and all go away`() {
        if (!isWindows) return
        val ids = List(30) { "many-$it" }
        val clicks = ids.associateWith { CountDownLatch(1) }
        ids.forEach { id ->
            created += id
            WindowsTrayInitializer.initialize(
                id,
                TrayFixtures.icoPath,
                id,
                { clicks.getValue(id).countDown() },
                { Item(id) {} },
            )
        }
        val identities = ids.associateWith { identity(it) }
        identities.forEach { (id, ident) ->
            await("$id icon in the shell") { Win32Probe.shellHasIcon(ident.first, ident.second) }
            Win32Probe.post(ident.first, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
        }
        clicks.forEach { (id, latch) -> assertTrue(latch.await(3, TimeUnit.SECONDS), "$id click lost") }
        ids.shuffled(Random(seed())).forEach { WindowsTrayInitializer.dispose(it) }
        identities.forEach { (id, ident) ->
            assertTrue(!Win32Probe.shellHasIcon(ident.first, ident.second), "$id icon left")
        }
        await("tray threads ended") { trayThreads().isEmpty() }
    }

    @Test
    fun `composable icon updates do not pile up temp files`() {
        if (!isWindows) return
        val tray = NativeTray()
        val seen = mutableSetOf<String>()
        try {
            repeat(150) { n ->
                tray.updateComposable(
                    iconContent = { Box(Modifier.fillMaxSize().background(Color(0xFF000000.toInt() or (n * 4000)))) },
                    iconRenderProperties = IconRenderProperties.forCurrentOperatingSystem(),
                    tooltip = "frame $n",
                )
                Thread.sleep(15)
            }
            Thread.sleep(1500)
            // Same content, same file: a steady icon re-rendered on every recomposition is free.
            repeat(5) {
                seen +=
                    ComposableIconUtils.renderComposableToIcoFile(IconRenderProperties.forCurrentOperatingSystem()) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
            }
        } finally {
            tray.dispose()
        }
        assertEquals(1, seen.size, "identical renders produced distinct files: $seen")
        val onDisk = File(seen.single()).parentFile.listFiles()?.size ?: 0
        assertTrue(
            IconFileCache.size() <= 64 && onDisk <= 64,
            "150 icon frames left ${IconFileCache.size()} / $onDisk files",
        )
    }

    @Test
    fun `an evicted icon file does not blank the tray on explorer restart`() {
        if (!isWindows) return
        created += "evicted"
        val icon =
            ComposableIconUtils.renderComposableToIcoFile(IconRenderProperties.forCurrentOperatingSystem()) {
                Box(Modifier.fillMaxSize().background(Color.Blue))
            }
        WindowsTrayInitializer.initialize("evicted", icon, "t", null, { Item("x") {} })
        val (hwnd, uid) = identity("evicted")
        repeat(80) { n ->
            ComposableIconUtils.renderComposableToIcoFile(IconRenderProperties.forMenuItem()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF000000.toInt() or (n * 7919))))
            }
        }
        assertTrue(!File(icon).exists(), "test premise: the tray icon file was evicted")
        TrayFixtures.deleteShellIcon(hwnd, uid)
        Win32Probe.post(hwnd, Win32Probe.registerMessage("TaskbarCreated"))
        await("icon re-added from the in-memory icon") { Win32Probe.shellHasIcon(hwnd, uid) }
    }

    @Test
    fun `left click without a primary action opens the menu and reports it`() {
        if (!isWindows) return
        val opened = CountDownLatch(1)
        created += "no-primary"
        WindowsTrayInitializer.initialize(
            "no-primary",
            TrayFixtures.icoPath,
            "t",
            null,
            { Item("only") {} },
            { opened.countDown() },
        )
        val (hwnd, _) = identity("no-primary")
        Win32Probe.post(hwnd, Win32Probe.WM_TRAY_CALLBACK, 0, Win32Probe.WM_LBUTTONUP.toLong())
        await("popup from a left click") { Win32Probe.openPopupMenu() != null }
        val (popup, hmenu) = Win32Probe.openPopupMenu()!!
        assertEquals(listOf("only"), Win32Probe.readMenu(hmenu).map { it.text })
        Win32Probe.post(hwnd, Win32Probe.WM_CANCELMODE)
        assertTrue(opened.await(3, TimeUnit.SECONDS), "onMenuOpened never ran")
        await("popup closed") { Win32Probe.openPopupMenu() == null }
        check(Pointer.nativeValue(popup.pointer) != 0L)
    }

    @Test
    fun `create and dispose a hundred times leaks no thread, window or handle`() {
        if (!isWindows) return
        // Warm-up: class registration, fonts, the first icon load.
        WindowsTrayInitializer.initialize("warm", TrayFixtures.icoPath, "t", null, { Item("x") {} })
        identity("warm")
        WindowsTrayInitializer.dispose("warm")
        val (gdi0, user0) = Win32Probe.guiResources()
        repeat(100) { n ->
            val id = "cycle-$n"
            WindowsTrayInitializer.initialize(id, TrayFixtures.icoPath, "t$n", {}, {
                SubMenu("s") { Item("i", iconContent = {}) {} }
                CheckableItem("c", n % 2 == 0, {})
            })
            if (n % 2 == 0) identity(id)
            WindowsTrayInitializer.dispose(id)
        }
        await("threads ended") { trayThreads().isEmpty() }
        await("windows destroyed") { trayWindows().isEmpty() }
        val (gdi1, user1) = Win32Probe.guiResources()
        assertTrue(gdi1 - gdi0 < 15, "GDI $gdi0 -> $gdi1")
        assertTrue(user1 - user0 < 15, "USER $user0 -> $user1")
    }
}
