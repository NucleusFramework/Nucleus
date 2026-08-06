package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.taoApplication
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * Stage-2 headful suite entry point. Launched by the `taoHeadfulTest` Gradle
 * task (plain JavaExec — `taoApplication` marshals to the AppKit main thread
 * itself, and `-XstartOnFirstThread` would deadlock the AWT classes the
 * Compose host touches). All cases share one Tao event loop and run
 * sequentially, each in a fresh real window. Exit code = number of failures.
 */
public object TaoHeadfulTestSuiteMain {
    // Substring match on the case name, e.g.
    // `-Dnucleus.tao.headful.filter=#418` to run one probe on its own.
    private val nameFilter: String? =
        System.getProperty("nucleus.tao.headful.filter")?.takeIf { it.isNotBlank() }

    private val allCases: List<TaoWindowTestCase> =
        listOf(
            TaoWindowTestCase("window maps, paints and reports a real size") {
                awaitUntil("window mapped with non-zero outer bounds") {
                    val b = bounds()
                    b != null && b[2] > 0 && b[3] > 0
                }
            },
            TaoWindowTestCase("setInnerSize fires onResized with the requested size") {
                var resizedW = 0
                var resizedH = 0
                window.onResized { w, h ->
                    resizedW = w
                    resizedH = h
                }
                awaitUntil("window mapped") { bounds() != null }
                settle()
                window.setInnerSize(RESIZE_W_DP, RESIZE_H_DP)
                // setInnerSize takes logical dp; onResized reports physical px.
                val expectedW = (RESIZE_W_DP * window.scaleFactor).toInt()
                val expectedH = (RESIZE_H_DP * window.scaleFactor).toInt()
                awaitUntil("onResized(~${expectedW}x$expectedH)") {
                    abs(resizedW - expectedW) <= RESIZE_TOLERANCE_PX &&
                        abs(resizedH - expectedH) <= RESIZE_TOLERANCE_PX
                }
            },
            TaoWindowTestCase(
                // openbox (the CI WM) is floating, so client move requests apply.
                "setOuterPosition moves the window and fires onMoved",
                skip = {
                    // xdg-shell has no client-side positioning: setOuterPosition
                    // is a documented no-op on native Wayland and onMoved never
                    // fires. GDK picks the wayland backend whenever
                    // WAYLAND_DISPLAY is set unless GDK_BACKEND forces x11 —
                    // mirror that selection here, including the
                    // NUCLEUS_TAO_LINUX_RENDERER=x11 escape hatch (the native
                    // loop setenvs GDK_BACKEND=x11 for it, but too late for
                    // the JVM's env snapshot to notice).
                    val backend = System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull()
                    val forcedX11 =
                        backend == "x11" ||
                            System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
                    val wayland = System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
                    if (isLinux && wayland) "no client positioning on Wayland (xdg-shell)" else null
                },
            ) {
                val moved = AtomicBoolean(false)
                awaitUntil("window mapped") { bounds() != null }
                settle()
                window.onMoved { _, _ -> moved.set(true) }
                val b = requireNotNull(bounds())
                // bounds() is physical px; setOuterPosition takes logical dp.
                val scale = window.scaleFactor.toDouble()
                window.setOuterPosition(b[0] / scale + MOVE_DELTA_DP, b[1] / scale + MOVE_DELTA_DP)
                awaitUntil("onMoved fired after setOuterPosition") { moved.get() }
            },
            TaoWindowTestCase(
                "maximize grows the window and restore shrinks it back",
            ) {
                awaitUntil("window mapped") { bounds() != null }
                settle()
                val before = requireNotNull(bounds())
                window.setMaximized(true)
                awaitUntil("outer bounds grew after maximize") {
                    val b = bounds() ?: return@awaitUntil false
                    b[2] > before[2] && b[3] >= before[3]
                }
                // Deliberately issued while the zoom animation may still be
                // in flight: regression test for the mid-animation
                // set_maximized(false) no-op fixed in the vendored tao
                // (PATCH(nucleus) in macos/window.rs::set_maximized).
                window.setMaximized(false)
                awaitUntil("outer bounds restored after unmaximize") {
                    val b = bounds() ?: return@awaitUntil false
                    abs(b[2] - before[2]) <= RESTORE_TOLERANCE_PX
                }
            },
            TaoWindowTestCase(
                // openbox supports iconify, so this runs on Linux CI too.
                "minimize and restore fire onMinimizedChanged both ways",
            ) {
                val minimized = AtomicBoolean(false)
                val restored = AtomicBoolean(false)
                awaitUntil("window mapped") { bounds() != null }
                settle()
                window.onMinimizedChanged { min -> if (min) minimized.set(true) else restored.set(true) }
                window.setMinimized(true)
                awaitUntil("onMinimizedChanged(true)") { minimized.get() }
                window.setMinimized(false)
                window.focus()
                awaitUntil("onMinimizedChanged(false)") { restored.get() }
            },
            TaoWindowTestCase("requestUserClose routes through onCloseRequested without destroying") {
                val closeRequested = AtomicInteger(0)
                awaitUntil("window mapped") { bounds() != null }
                settle()
                window.onCloseRequested { closeRequested.incrementAndGet() }
                window.requestUserClose()
                awaitUntil("onCloseRequested fired") { closeRequested.get() > 0 }
                // The handler owns the decision: the window must still be alive.
                settle()
                check(bounds() != null) { "window must survive a handled close request" }
            },
            // Real Wayland session e2e (not a synthetic unit test): export the
            // live surface via xdg_foreign, hand the token to the session
            // xdg-desktop-portal FileChooser as parent_window, then Close the
            // Request so no modal sticks around. Proves FileKit-style parenting.
            TaoWindowTestCase(
                name = "xdg_foreign export parents a real XDG portal FileChooser",
                timeoutMillis = 45_000L,
                skip = {
                    if (!isLinux) {
                        "Linux only"
                    } else {
                        val backend = System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull()
                        val forcedX11 =
                            backend == "x11" ||
                                System
                                    .getenv("NUCLEUS_TAO_LINUX_RENDERER")
                                    .orEmpty()
                                    .equals("x11", ignoreCase = true)
                        val wayland = System.getenv("WAYLAND_DISPLAY") != null && !forcedX11
                        when {
                            !wayland -> "requires native Wayland (WAYLAND_DISPLAY)"
                            !XdgPortalFileChooser.gdbusAvailable() -> "gdbus not on PATH"
                            !XdgPortalFileChooser.portalAvailable() ->
                                "xdg-desktop-portal FileChooser unavailable"
                            else -> null
                        }
                    }
                },
            ) {
                awaitUntil("window mapped") { bounds() != null }
                settle()
                // Export on the Tao main thread (nested GLib iteration is OK).
                val export =
                    checkNotNull(window.exportXdgForeignHandle(timeoutMs = 8_000L)) {
                        "exportXdgForeignHandle returned null on a realized Wayland window"
                    }
                try {
                    check(export.handle.isNotEmpty()) { "empty xdg_foreign handle" }
                    check('\u0000' !in export.handle) { "handle contains NUL" }
                    check(!export.handle.startsWith("wayland:")) {
                        "handle must be unprefixed; got ${export.handle.take(32)}"
                    }
                    check(export.portalParent == "wayland:${export.handle}")

                    // Portal IPC is blocking; park it off the event loop.
                    val open =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            XdgPortalFileChooser.openFile(
                                parentWindow = export.portalParent,
                                title = "Nucleus xdg_foreign e2e",
                            )
                        }
                    check(open.requestPath.startsWith("/org/freedesktop/portal/desktop/request/")) {
                        "unexpected request path: ${open.requestPath}"
                    }
                    println(
                        "xdg_foreign e2e: handle=${export.handle.take(12)}… " +
                            "portalParent=${export.portalParent.take(24)}… " +
                            "request=${open.requestPath}",
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        open.close()
                    }
                    // Second export while the first is still live must still
                    // yield a usable token (GDK reuses the active export).
                    val again =
                        checkNotNull(window.exportXdgForeignHandle(timeoutMs = 5_000L)) {
                            "re-export while live failed"
                        }
                    check(again.handle.isNotEmpty())
                    again.close()
                } finally {
                    export.close()
                    check(export.isClosed)
                }
            },
        ) + ChromeReviewHeadfulCases.all() + DisplayScaleHeadfulCases.all() + FramePacingHeadfulCases.all()

    private val cases: List<TaoWindowTestCase> =
        allCases.filter { nameFilter == null || it.name.contains(nameFilter, ignoreCase = true) }

    @JvmStatic
    fun main(args: Array<String>) {
        if (cases.isEmpty()) {
            // Distinct from the failure-count exit codes: an unmatched filter
            // is a usage error, not "one case failed".
            System.err.println("no headful case matches filter '$nameFilter'")
            exitProcess(BAD_FILTER_EXIT_CODE)
        }

        // The Tao loop owns the launcher thread forever; a hung case must not
        // hang CI — same watchdog pattern as TaoRuntimeResizableSmokeTest.
        val watchdogMillis =
            System.getProperty("nucleus.tao.headful.watchdogMillis")?.toLongOrNull()
                ?: GLOBAL_WATCHDOG_MILLIS
        thread(isDaemon = true, name = "tao-headful-watchdog") {
            Thread.sleep(watchdogMillis)
            System.err.println("WATCHDOG: headful suite exceeded ${watchdogMillis / 1000}s — halting")
            System.err.flush()
            Runtime.getRuntime().halt(WATCHDOG_EXIT_CODE)
        }

        val results = mutableListOf<TaoWindowTestResult>()

        taoApplication {
            var current by remember { mutableIntStateOf(0) }

            fun advance(result: TaoWindowTestResult) {
                results += result
                if (current + 1 < cases.size) {
                    current++
                } else {
                    // taoApplication ends with exitProcess(0); report and pick
                    // the exit code ourselves before it gets the chance.
                    reportAndExit(results)
                }
            }

            val case = cases[current]
            val skipReason = case.skip()
            // Published by the window content; the driver runs at APPLICATION
            // level so it survives the window scene's attach/re-composition.
            val windowHolder = remember(current) { mutableStateOf<dev.nucleusframework.window.tao.TaoWindow?>(null) }

            if (skipReason == null) {
                androidx.compose.runtime.key(current) {
                    DecoratedWindow(
                        onCloseRequest = { /* cases drive their own lifecycle */ },
                        title = "tao-headful: ${case.name}",
                        transparent = case.transparent,
                    ) {
                        // Default chrome surface; cases may paint over it via
                        // [TaoWindowTestCase.content] (scaffold, backdrop, …).
                        // Fully-transparent probes opt out so the Skia clear is
                        // what the compositor sees in empty regions.
                        if (case.paintDefaultBackground) {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray))
                        }
                        case.content(this)
                        val w = window
                        LaunchedEffect(w) { windowHolder.value = w }
                    }
                }
            }

            LaunchedEffect(current) {
                val running = cases[current]
                val skip = running.skip()
                if (skip != null) {
                    advance(TaoWindowTestResult(running.name, failure = null, skippedReason = skip, durationMillis = 0))
                    return@LaunchedEffect
                }
                System.err.println("[tao-headful] START ${running.name}")
                val start = System.currentTimeMillis()
                val failure =
                    try {
                        // Wait for the window content to publish its TaoWindow.
                        val deadline = System.currentTimeMillis() + WINDOW_PUBLISH_TIMEOUT_MILLIS
                        while (windowHolder.value == null) {
                            check(System.currentTimeMillis() < deadline) { "window never published its handle" }
                            kotlinx.coroutines.delay(WINDOW_PUBLISH_POLL_MILLIS)
                        }
                        running.driver(TaoWindowTestScope(windowHolder.value!!))
                        null
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c // app teardown — never record as a test failure
                    } catch (
                        @Suppress("TooGenericExceptionCaught") t: Throwable,
                    ) {
                        t
                    }
                System.err.println("[tao-headful] ${if (failure == null) "OK" else "FAIL"} ${running.name}")
                advance(
                    TaoWindowTestResult(
                        running.name,
                        failure,
                        durationMillis = System.currentTimeMillis() - start,
                    ),
                )
            }
        }

        // Unreachable: taoApplication never returns (exitProcess inside), and
        // reportAndExit terminates first. Kept as a hard backstop.
        reportAndExit(results)
    }

    private fun reportAndExit(results: List<TaoWindowTestResult>): Nothing {
        var failures = 0
        println()
        println("── Tao headful suite ──────────────────────────────────────────")
        for (r in results) {
            val status =
                when {
                    r.skippedReason != null -> "SKIP (${r.skippedReason})"
                    r.failure != null -> "FAIL"
                    else -> "PASS"
                }
            println("  [$status] ${r.name} (${r.durationMillis}ms)")
            if (r.failure != null) {
                failures++
                r.failure.printStackTrace(System.out)
            }
        }
        val ran = results.count { it.skippedReason == null }
        println("── $ran run, ${results.size - ran} skipped, $failures failed ──")
        if (results.size != cases.size) {
            println("ERROR: suite ended early (${results.size}/${cases.size} cases reported)")
            exitProcess(1)
        }
        exitProcess(if (failures > 0) 1 else 0)
    }

    private val isLinux: Boolean =
        System.getProperty("os.name", "").lowercase().let { os ->
            !os.contains("win") && !os.contains("mac") && !os.contains("darwin")
        }

    private const val WINDOW_PUBLISH_TIMEOUT_MILLIS = 15_000L
    private const val WINDOW_PUBLISH_POLL_MILLIS = 25L
    private const val GLOBAL_WATCHDOG_MILLIS = 240_000L
    private const val WATCHDOG_EXIT_CODE = 42
    private const val BAD_FILTER_EXIT_CODE = 43
    private const val RESIZE_W_DP = 640.0
    private const val RESIZE_H_DP = 480.0
    private const val RESIZE_TOLERANCE_PX = 64
    private const val RESTORE_TOLERANCE_PX = 32
    private const val MOVE_DELTA_DP = 60.0
}
