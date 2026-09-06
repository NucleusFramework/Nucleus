package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.window.tao.ApplicationScope
import dev.nucleusframework.window.tao.DecoratedDialog
import dev.nucleusframework.window.tao.DecoratedWindow
import dev.nucleusframework.window.tao.SatelliteWindow
import dev.nucleusframework.window.tao.TaoDecoratedWindowScope
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.XdgPortalParent
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
    // `-Dnucleus.tao.headful.filter=#418` to run one probe on its own. Several
    // substrings separated by `|` run every case matching any of them, in suite
    // order — the way to replay an interference between two case families.
    private val nameFilter: String? =
        System.getProperty("nucleus.tao.headful.filter")?.takeIf { it.isNotBlank() }
    private val nameFilters: List<String> =
        nameFilter
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private val allCases: List<TaoWindowTestCase> =
        listOf(
            TaoWindowTestCase("window maps, paints and reports a real size") {
                awaitUntil("window mapped with non-zero outer bounds") {
                    window.hasRealFramePx()
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
                        when {
                            forcedX11Backend -> "Wayland-only (forced X11 backend)"
                            System.getenv("WAYLAND_DISPLAY") == null ->
                                "requires native Wayland (WAYLAND_DISPLAY)"
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
                val parent =
                    checkNotNull(window.xdgPortalParent(timeoutMs = 8_000L)) {
                        "xdgPortalParent returned null on a realized Wayland window"
                    }
                check(parent is XdgPortalParent.Wayland) {
                    "expected Wayland portal parent, got $parent"
                }
                try {
                    val export = parent.export
                    check(export.handle.isNotEmpty()) { "empty xdg_foreign handle" }
                    check('\u0000' !in export.handle) { "handle contains NUL" }
                    check(!export.handle.startsWith("wayland:")) {
                        "handle must be unprefixed; got ${export.handle.take(32)}"
                    }
                    check(parent.portalParent == "wayland:${export.handle}")
                    check(window.x11WindowId == null) { "XID must be null on native Wayland" }
                    check(window.x11PortalParent == null)

                    val open =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            XdgPortalFileChooser.openFile(
                                parentWindow = parent.portalParent,
                                title = "Nucleus xdg_foreign e2e",
                            )
                        }
                    check(open.requestPath.startsWith("/org/freedesktop/portal/desktop/request/")) {
                        "unexpected request path: ${open.requestPath}"
                    }
                    println(
                        "xdg_foreign e2e: handle=${export.handle.take(12)}… " +
                            "portalParent=${parent.portalParent.take(24)}… " +
                            "request=${open.requestPath}",
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        open.close()
                    }
                    val again =
                        checkNotNull(window.exportXdgForeignHandle(timeoutMs = 5_000L)) {
                            "re-export while live failed"
                        }
                    check(again.handle.isNotEmpty())
                    again.close()
                } finally {
                    parent.close()
                    check(parent.export.isClosed)
                }
            },
            // X11 / XWayland e2e: resolve the live XID and parent a real portal
            // FileChooser with `x11:<hex>`. Run the suite with
            // NUCLEUS_TAO_LINUX_RENDERER=x11 (or GDK_BACKEND=x11) on a Wayland
            // host to force XWayland; also runs on native X11 sessions.
            TaoWindowTestCase(
                name = "x11 XID parents a real XDG portal FileChooser",
                timeoutMillis = 45_000L,
                skip = {
                    if (!isLinux) {
                        "Linux only"
                    } else {
                        when {
                            !isX11Backend ->
                                "requires X11/XWayland (set NUCLEUS_TAO_LINUX_RENDERER=x11)"
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

                val xid =
                    checkNotNull(window.x11WindowId) {
                        "x11WindowId null under X11 backend " +
                            "(GDK_BACKEND=${System.getenv("GDK_BACKEND")}, " +
                            "NUCLEUS_TAO_LINUX_RENDERER=${System.getenv("NUCLEUS_TAO_LINUX_RENDERER")})"
                    }
                check(xid in 1L..0xffff_ffffL) { "XID out of range: $xid" }

                val portalFromProp =
                    checkNotNull(window.x11PortalParent) { "x11PortalParent null while XID is set" }
                check(portalFromProp == "x11:${xid.toString(16)}") {
                    "x11PortalParent mismatch: $portalFromProp vs xid=$xid"
                }
                // FileKit canonical form: lowercase bare hex, no 0x prefix.
                check(portalFromProp == portalFromProp.lowercase()) {
                    "portal parent must be lowercase: $portalFromProp"
                }
                check(!portalFromProp.contains("0x")) { "unexpected 0x in $portalFromProp" }

                val parent =
                    checkNotNull(window.xdgPortalParent()) {
                        "xdgPortalParent returned null under X11"
                    }
                check(parent is XdgPortalParent.X11) {
                    "expected X11 portal parent, got $parent"
                }
                check(parent.xid == xid)
                check(parent.portalParent == portalFromProp)
                // Wayland export must not succeed on the X11 backend.
                check(window.exportXdgForeignHandle(timeoutMs = 500L) == null) {
                    "exportXdgForeignHandle must be null on X11"
                }

                val open =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        XdgPortalFileChooser.openFile(
                            parentWindow = parent.portalParent,
                            title = "Nucleus x11 portal e2e",
                        )
                    }
                check(open.requestPath.startsWith("/org/freedesktop/portal/desktop/request/")) {
                    "unexpected request path: ${open.requestPath}"
                }
                println(
                    "x11 portal e2e: xid=$xid (0x${xid.toString(16)}) " +
                        "portalParent=$portalFromProp request=${open.requestPath}",
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    open.close()
                }
            },
            // macOS dialog-parent e2e (real AppKit sheet, not a pointer smoke
            // check): resolve the live NSWindow*, prove it is distinct from the
            // Compose NSView, then parent a real NSOpenPanel via
            // beginSheetModalForWindow: and cancel it. Mirrors the Linux portal
            // FileChooser round-trips. FileKit's JVM picker still uses
            // application-modal runModal() and has no .macos factory yet —
            // this proves the Nucleus side of the sheet-parent contract.
            TaoWindowTestCase(
                name = "nsWindowHandle parents a real NSOpenPanel sheet",
                timeoutMillis = 45_000L,
                skip = { if (!isMac) "macOS only" else null },
            ) {
                awaitUntil("window mapped") { bounds() != null }
                settle()

                val nsView = window.nativeHandle
                check(nsView != 0L) { "nativeHandle (NSView) must be non-zero on a mapped window" }

                val nsWindow =
                    checkNotNull(window.nsWindowHandle) {
                        "nsWindowHandle null on a realized macOS window"
                    }
                check(nsWindow != 0L) { "nsWindowHandle must not be zero" }
                // NSView* and NSWindow* are distinct AppKit objects.
                check(nsWindow != nsView) {
                    "nsWindowHandle must not equal nativeHandle (NSView); got both=$nsView"
                }
                // Stable while the window is alive (same pointer on re-read).
                check(window.nsWindowHandle == nsWindow) {
                    "nsWindowHandle must be stable across reads"
                }
                // Linux portal APIs must stay null on macOS.
                check(window.x11WindowId == null)
                check(window.x11PortalParent == null)
                check(window.exportXdgForeignHandle(timeoutMs = 200L) == null)
                check(window.xdgPortalParent(timeoutMs = 200L) == null)

                // Negative: an NSView pointer is not a sheet parent — AppKit
                // must not find it among NSApp.windows as an NSWindow.
                val wrongParent =
                    MacOsSheetParentProbe.probe(nsWindow = nsView, nsView = 0L)
                check(wrongParent == MacOsSheetParentProbe.WINDOW_NOT_FOUND) {
                    "NSView must not parent a sheet; got ${MacOsSheetParentProbe.describe(wrongParent)}"
                }

                // Positive: real beginSheetModalForWindow: + cancel.
                val code = MacOsSheetParentProbe.probe(nsWindow = nsWindow, nsView = nsView)
                check(code == MacOsSheetParentProbe.OK) {
                    "sheet parent probe failed: ${MacOsSheetParentProbe.describe(code)} " +
                        "(nsWindow=0x${nsWindow.toString(16)}, nsView=0x${nsView.toString(16)})"
                }

                // Window must still be alive after the nested runloop / sheet.
                settle()
                check(bounds() != null) { "window must survive sheet present + cancel" }
                check(window.nsWindowHandle == nsWindow) {
                    "nsWindowHandle must remain stable after sheet e2e"
                }

                println(
                    "macOS sheet parent e2e: nsWindow=0x${nsWindow.toString(16)} " +
                        "nsView=0x${nsView.toString(16)} probe=OK",
                )
            },
        ) +
            UnspecifiedSizeHeadfulCases.all() +
            LinuxDiscreteScrollHeadfulCases.all() +
            ChromeReviewHeadfulCases.all() +
            ChromeCoverageHeadfulCases.all() +
            DisplayScaleHeadfulCases.all() +
            FramePacingHeadfulCases.all() +
            MacWindowChromeStateHeadfulCases.all() +
            PopupScaleHeadfulCases.all() +
            NativePopupPlacementHeadfulCases.all() +
            NativePopupMarginInputHeadfulCases.all() +
            DialogAppearanceHeadfulCases.all() +
            ClipboardHeadfulCases.all() +
            AnimatedWindowSizeHeadfulCases.all() +
            AlwaysOnTopHeadfulCases.all() +
            SatelliteWindowHeadfulCases.all() +
            SatelliteWorkspaceHeadfulCases.all() +
            SatelliteWorkspaceStressHeadfulCases.all() +
            SatelliteWorkspaceMonkeyHeadfulCases.all() +
            TabWorkspaceHeadfulCases.all() +
            TabWorkspaceLifecycleHeadfulCases.all() +
            TabWorkspaceMotionHeadfulCases.all() +
            TabWorkspaceMouseHeadfulCases.all() +
            TabWorkspaceConcurrencyHeadfulCases.all() +
            TabWorkspaceStormHeadfulCases.all() +
            TabWorkspaceStressHeadfulCases.all() +
            WaylandWorkspaceHeadfulCases.all() +
            WaylandWorkspaceStressHeadfulCases.all() +
            WorkspaceFileDropHeadfulCases.all() +
            TabSatellitesHeadfulCases.all() +
            TabSatellitesChaosHeadfulCases.all() +
            TabWorkspacePointerHeadfulCases.all() +
            SatellitePlacementHeadfulCases.all() +
            WindowExtremesHeadfulCases.all() +
            WorkspaceLoadHeadfulCases.all() +
            MonitorAndScaleHeadfulCases.all() +
            WorkspaceRaceHeadfulCases.all() +
            ImeHeadfulCases.all() +
            WindowApiV2HeadfulCases.all() +
            // Last: the monkeys are the longest cases, and the robot ones leave the
            // real pointer wherever their last gesture ended.
            NativeViewMonkeyHeadfulCases.all() +
            TextureViewMonkeyHeadfulCases.all()

    private val cases: List<TaoWindowTestCase> =
        allCases.filter { case ->
            nameFilters.isEmpty() || nameFilters.any { case.name.contains(it, ignoreCase = true) }
        }

    @JvmStatic
    @Suppress("LongMethod") // one flat harness: case hosting, then the driver
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
            // A wedged loop thread is the usual reason we get here, and a CI
            // log has no `jstack`: print where every thread is parked so the
            // hang is diagnosable from the log alone (#658).
            for ((thread, frames) in Thread.getAllStackTraces()) {
                System.err.println("\"${thread.name}\" ${thread.state}")
                for (frame in frames) System.err.println("\tat $frame")
            }
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
            val dialogHolder = remember(current) { mutableStateOf<dev.nucleusframework.window.tao.TaoWindow?>(null) }
            val satelliteHolder = remember(current) { mutableStateOf<dev.nucleusframework.window.tao.TaoWindow?>(null) }

            if (skipReason == null) {
                androidx.compose.runtime.key(current) {
                    CaseWindow(case, windowHolder, dialogHolder, satelliteHolder)
                    ApplicationScopeSatellite(
                        case = case,
                        windowHolder = windowHolder,
                        dialogHolder = dialogHolder,
                        satelliteHolder = satelliteHolder,
                    )
                    case.applicationContent?.invoke(this, HeadfulWindows(windowHolder.value, dialogHolder.value))
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
                        val published =
                            awaitPublishedWindows(
                                windowHolder = windowHolder,
                                dialogHolder = dialogHolder,
                                satelliteHolder = satelliteHolder,
                                waitForDialog = running.dialogContent != null,
                                waitForSatellite = running.satelliteState != null,
                            )
                        // Per-case budget: a driver that never completes must
                        // fail its own case, not run out the global watchdog
                        // and take every other result down with it.
                        kotlinx.coroutines.withTimeout(running.timeoutMillis) {
                            running.driver(published)
                        }
                        null
                    } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                        // Ordered before CancellationException: this one is the
                        // case's own deadline, not app teardown.
                        IllegalStateException("case timed out after ${running.timeoutMillis}ms", t)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c // app teardown — never record as a test failure
                    } catch (
                        @Suppress("TooGenericExceptionCaught") t: Throwable,
                    ) {
                        t
                    }
                // Whatever the case did, it does not get to hand the next one
                // a held mouse button — see [HeadfulRobot.releaseEveryButton].
                HeadfulRobot.releaseEveryButton()
                System.err.println("[tao-headful] ${if (failure == null) "OK" else "FAIL"} ${running.name}")
                failure?.printStackTrace(System.err)
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

    /**
     * The reparenting call site: an application-scope satellite whose owner is
     * picked from the case's [TaoWindowTestCase.satelliteOwner] state, exactly
     * like a shared palette in an app. Composed only once the chosen owner has
     * published itself; a no-op for cases that host their satellite inside the
     * window content instead.
     */
    @Composable
    private fun ApplicationScope.ApplicationScopeSatellite(
        case: TaoWindowTestCase,
        windowHolder: MutableState<TaoWindow?>,
        dialogHolder: MutableState<TaoWindow?>,
        satelliteHolder: MutableState<TaoWindow?>,
    ) {
        val satelliteState = case.satelliteState ?: return
        val satelliteOwner = case.satelliteOwner ?: return
        val owner =
            when (satelliteOwner.value) {
                SatelliteOwner.CaseWindow -> windowHolder.value
                SatelliteOwner.DialogWindow -> dialogHolder.value
            } ?: return
        SatelliteWindow(
            onCloseRequest = case.satelliteOnCloseRequest,
            parent = owner,
            state = satelliteState,
            title = "tao-headful-satellite: ${case.name}",
            hideWhileParentFullscreenOrMaximized = case.satelliteHideWhileParentFills,
        ) {
            case.satelliteContent(this)
            val s = window
            LaunchedEffect(s) { satelliteHolder.value = s }
        }
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

    private val isMac: Boolean =
        System.getProperty("os.name", "").lowercase().let { os ->
            os.contains("mac") || os.contains("darwin")
        }

    private val isLinux: Boolean =
        System.getProperty("os.name", "").lowercase().let { os ->
            !os.contains("win") && !os.contains("mac") && !os.contains("darwin")
        }

    private suspend fun awaitPublishedWindows(
        windowHolder: MutableState<TaoWindow?>,
        dialogHolder: MutableState<TaoWindow?>,
        satelliteHolder: MutableState<TaoWindow?>,
        waitForDialog: Boolean,
        waitForSatellite: Boolean,
    ): TaoWindowTestScope {
        val deadline = System.currentTimeMillis() + WINDOW_PUBLISH_TIMEOUT_MILLIS
        while (windowHolder.value == null) {
            check(System.currentTimeMillis() < deadline) { "window never published its handle" }
            kotlinx.coroutines.delay(WINDOW_PUBLISH_POLL_MILLIS)
        }
        if (waitForDialog) {
            while (dialogHolder.value == null) {
                check(System.currentTimeMillis() < deadline) { "dialog never published its handle" }
                kotlinx.coroutines.delay(WINDOW_PUBLISH_POLL_MILLIS)
            }
        }
        if (waitForSatellite) {
            while (satelliteHolder.value == null) {
                check(System.currentTimeMillis() < deadline) { "satellite never published its handle" }
                kotlinx.coroutines.delay(WINDOW_PUBLISH_POLL_MILLIS)
            }
        }
        return TaoWindowTestScope(
            window = windowHolder.value!!,
            dialogWindow = dialogHolder.value,
            satelliteWindow = satelliteHolder.value,
        )
    }

    /**
     * True when the process was launched with an X11-forcing env var. The native
     * loop also setenvs `GDK_BACKEND=x11` from `NUCLEUS_TAO_LINUX_RENDERER`, but
     * that is too late for the JVM's env snapshot — honor both signals here.
     */
    private val forcedX11Backend: Boolean
        get() {
            val backend = System.getenv("GDK_BACKEND")?.split(',')?.firstOrNull()
            return backend == "x11" ||
                System.getenv("NUCLEUS_TAO_LINUX_RENDERER").orEmpty().equals("x11", ignoreCase = true)
        }

    /**
     * X11 session or forced XWayland. Native Wayland (no force) is false even
     * when `DISPLAY` is set for XWayland compatibility.
     */
    private val isX11Backend: Boolean
        get() {
            if (forcedX11Backend) return true
            // Pure X11 desktop: DISPLAY set, no WAYLAND_DISPLAY.
            return !System.getenv("DISPLAY").isNullOrEmpty() &&
                System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()
        }

    private const val WINDOW_PUBLISH_TIMEOUT_MILLIS = 15_000L
    private const val WINDOW_PUBLISH_POLL_MILLIS = 25L
    private const val GLOBAL_WATCHDOG_MILLIS = 900_000L
    private const val WATCHDOG_EXIT_CODE = 42
    private const val BAD_FILTER_EXIT_CODE = 43
    private const val RESIZE_W_DP = 640.0
    private const val RESIZE_H_DP = 480.0
    private const val RESIZE_TOLERANCE_PX = 64
    private const val RESTORE_TOLERANCE_PX = 32
    private const val MOVE_DELTA_DP = 60.0
}

/**
 * One case's real window (and optional dialog), composed fresh per case.
 *
 * Extracted from `main` so the suite loop stays readable: the AWT-free window
 * API v2 clone needs a second `DecoratedWindow` call site, since its state is a
 * different type from Compose's.
 */
@Composable
private fun ApplicationScope.CaseWindow(
    case: TaoWindowTestCase,
    windowHolder: MutableState<TaoWindow?>,
    dialogHolder: MutableState<TaoWindow?>,
    satelliteHolder: MutableState<TaoWindow?>,
) {
    val fallbackState =
        rememberWindowState(
            size = case.size ?: DpSize(800.dp, 600.dp),
        )
    // Default chrome surface; cases may paint over it via
    // [TaoWindowTestCase.content] (scaffold, backdrop, …).
    // Fully-transparent probes opt out so the Skia clear is
    // what the compositor sees in empty regions.
    val windowContent: @Composable TaoDecoratedWindowScope.() -> Unit = {
        if (case.paintDefaultBackground) {
            Box(Modifier.fillMaxSize().background(Color.DarkGray))
        }
        case.content(this)
        val w = window
        LaunchedEffect(w) { windowHolder.value = w }

        // Composed inside the window content so the satellite resolves this
        // case's window as its parent through LocalTaoWindow — the same call
        // site an app uses.
        val satelliteState = case.satelliteState
        if (satelliteState != null && case.satelliteOwner == null) {
            SatelliteWindow(
                onCloseRequest = case.satelliteOnCloseRequest,
                state = satelliteState,
                title = "tao-headful-satellite: ${case.name}",
                hideWhileParentFullscreenOrMaximized = case.satelliteHideWhileParentFills,
            ) {
                case.satelliteContent(this)
                val s = window
                LaunchedEffect(s) { satelliteHolder.value = s }
            }
        }
    }
    val nucleusState = case.nucleusWindowState
    if (nucleusState != null) {
        DecoratedWindow(
            onCloseRequest = { /* cases drive their own lifecycle */ },
            state = nucleusState,
            title = "tao-headful: ${case.name}",
            transparent = case.transparent,
            nativePopupLayers = case.nativePopupLayers,
            content = windowContent,
        )
    } else {
        DecoratedWindow(
            onCloseRequest = { /* cases drive their own lifecycle */ },
            state = case.windowState ?: fallbackState,
            title = "tao-headful: ${case.name}",
            transparent = case.transparent,
            nativePopupLayers = case.nativePopupLayers,
            content = windowContent,
        )
    }
    val dialogContent = case.dialogContent
    if (dialogContent != null && case.dialogVisible.value) {
        DecoratedDialog(
            onCloseRequest = { /* cases drive their own lifecycle */ },
            state =
                rememberDialogState(
                    size = case.dialogSize ?: DpSize(400.dp, 300.dp),
                ),
            title = "tao-headful-dialog: ${case.name}",
        ) {
            dialogContent()
            val w = window
            LaunchedEffect(w) { dialogHolder.value = w }
        }
    }
}
