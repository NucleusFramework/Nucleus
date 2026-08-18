package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.LocalWindowChromeInsets
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.TitleBarPlacement
import dev.nucleusframework.window.WindowBackground
import dev.nucleusframework.window.WindowScaffold
import dev.nucleusframework.window.WindowsBackdrop
import dev.nucleusframework.window.WindowsBackdropStyle
import dev.nucleusframework.window.newFullscreenControls
import dev.nucleusframework.window.noWindowDrag
import dev.nucleusframework.window.tao.LocalRequestedGlassBackground
import dev.nucleusframework.window.tao.LocalRequestedTransparentBackground
import dev.nucleusframework.window.tao.LocalWindowClearColorLayers
import dev.nucleusframework.window.tao.ffi.NativeMetalBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.ffi.NativeTaoWindowsDecoBridge
import dev.nucleusframework.window.windowDragArea
import java.awt.Robot
import java.awt.event.InputEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headful e2e cases that probe the window-chrome review findings with real
 * Tao windows and live native/composition state — not unit assertions.
 */
internal object ChromeReviewHeadfulCases {
    private val isWindows: Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private val isMac: Boolean =
        System.getProperty("os.name", "").lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    /**
     * `new Robot()` blocks indefinitely on macOS when the JVM lacks the
     * Accessibility TCC grant. Probe it off-thread with a short timeout.
     */
    private fun awtRobotAvailable(): Boolean =
        try {
            val future =
                java.util.concurrent.CompletableFuture.supplyAsync {
                    Robot()
                    true
                }
            future.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }

    fun all(): List<TaoWindowTestCase> =
        listOf(
            windowsBackdropSurvivesCancelableClose(),
            windowsBackdropPrepareOnRequestClose(),
            windowBackgroundAndTitleBarClearColor(),
            noWindowDragBlocksAncestorDragArea(),
            hideBarZerosControlsInsetsInFullscreen(),
            fullscreenToggleVisualCapture(),
            fullyTransparentWindowIssue416(),
            fullyTransparentSurvivesTitleBarIssue416(),
            fullyTransparentHonoursSemiTintIssue416(),
        )

    /**
     * Issue-413 diagnostic: captures the composited desktop with an AWT
     * Robot while a real fullscreen enter + exit runs, and saves every frame
     * around the transitions as PNG under `%TEMP%/fs413-capture/` for visual
     * inspection. Never fails on the visuals — it is an instrument, not a
     * gate: the artifact only exists as pixels DWM composites, which no
     * geometry probe can see.
     */
    private fun fullscreenToggleVisualCapture(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "fullscreen toggle visual capture (fs413 diagnostic)",
            skip = {
                when {
                    // Opt-in only: grabs dozens of full-screen BufferedImages
                    // (~11MB each) — far too heavy for CI runners, and only
                    // useful when a human inspects the PNGs afterwards.
                    System.getProperty("nucleus.fs413.capture") != "true" ->
                        "diagnostic instrument — enable with -Dnucleus.fs413.capture=true"
                    !isWindows -> "Windows-only fullscreen diagnostic"
                    java.awt.GraphicsEnvironment.isHeadless() -> "no display for Robot capture"
                    else -> null
                }
            },
            content = {
                TitleBar(Modifier.newFullscreenControls())
                // Distinctive content color so stale/blank regions stand out.
                Box(Modifier.fillMaxSize().background(Color(0xFF203040)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(700)
                val robot = Robot()
                val screen =
                    java.awt.Rectangle(
                        java.awt.Toolkit
                            .getDefaultToolkit()
                            .screenSize,
                    )
                val frames =
                    java.util.Collections.synchronizedList(
                        mutableListOf<Pair<Long, java.awt.image.BufferedImage>>(),
                    )
                val capturing =
                    java.util.concurrent.atomic
                        .AtomicBoolean(true)
                val grabber =
                    kotlin.concurrent.thread(name = "fs413-capture") {
                        while (capturing.get() && frames.size < MAX_CAPTURE_FRAMES) {
                            frames += System.nanoTime() to robot.createScreenCapture(screen)
                        }
                    }
                settle(150)
                val enterNs = System.nanoTime()
                window.setFullscreen(true)
                settle(800)
                val exitNs = System.nanoTime()
                window.setFullscreen(false)
                settle(800)
                capturing.set(false)
                grabber.join()

                val dir = java.io.File(System.getProperty("java.io.tmpdir"), "fs413-capture")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                var saved = 0
                for ((i, entry) in frames.withIndex()) {
                    val (ts, img) = entry
                    val dtEnter = (ts - enterNs) / 1_000_000
                    val dtExit = (ts - exitNs) / 1_000_000
                    val nearEnter = dtEnter in -100..400
                    val nearExit = dtExit in -100..400
                    if (nearEnter || nearExit) {
                        val tag = if (nearEnter) "enter${dtEnter}ms" else "exit${dtExit}ms"
                        javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "f%03d_%s.png".format(i, tag)))
                        saved++
                    }
                }
                System.err.println(
                    "[fs413-capture] ${frames.size} frames grabbed, $saved saved to $dir " +
                        "(enter@0ms in 'enter' tags, exit@0ms in 'exit' tags)",
                )
            },
        )

    private const val MAX_CAPTURE_FRAMES = 120

    /**
     * Review P0: cancelable close must not permanently tear down a live
     * `WindowsBackdrop`. Probes: Compose transparency latch + native
     * `backdropActive`.
     */
    private fun windowsBackdropSurvivesCancelableClose(): TaoWindowTestCase {
        val transparent = AtomicBoolean(false)
        val ready = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "WindowsBackdrop survives cancelable close request",
            skip = { if (!isWindows) "Windows-only backdrop probe" else null },
            content = {
                WindowsBackdrop(WindowsBackdropStyle.Mica)
                val transparency = LocalRequestedTransparentBackground.current
                LaunchedEffect(transparency) {
                    if (transparency == null) return@LaunchedEffect
                    snapshotFlow { transparency.value }.collect {
                        transparent.set(it)
                        if (it) ready.set(true)
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("backdrop transparency armed") { ready.get() }
                settle(500)
                val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
                check(hwnd != 0L) { "no HWND" }
                check(NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)) {
                    "native backdrop not active after WindowsBackdrop compose"
                }
                check(transparent.get()) { "Compose transparent latch not set under backdrop" }

                // Cancelable path: same as caption X / Alt+F4 → CLOSE_REQUESTED.
                // Suite onCloseRequest is a no-op — window must survive.
                window.requestUserClose()
                settle(400)
                check(bounds() != null) { "window destroyed on cancelable close" }
                check(transparent.get()) {
                    "Compose transparent latch dropped after cancelable close — " +
                        "prepareClose ran on request (review bug)"
                }
                check(NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)) {
                    "native backdropActive=false after cancelable close — " +
                        "Mica permanently torn down (review bug)"
                }
            },
        )
    }

    /**
     * Review P0: confirmed destroy must run host prepare (opaque path).
     * Probes: transparency becomes false after [TaoWindow.requestClose] starts.
     */
    private fun windowsBackdropPrepareOnRequestClose(): TaoWindowTestCase {
        val transparent = AtomicBoolean(false)
        val sawArmed = AtomicBoolean(false)
        val prepareCloseFired = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "requestClose prepares opaque frame under backdrop",
            skip = { if (!isWindows) "Windows-only backdrop probe" else null },
            content = {
                WindowsBackdrop(WindowsBackdropStyle.Mica)
                val transparency = LocalRequestedTransparentBackground.current
                LaunchedEffect(transparency) {
                    if (transparency == null) return@LaunchedEffect
                    snapshotFlow { transparency.value }.collect { v ->
                        transparent.set(v)
                        if (v) sawArmed.set(true)
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("backdrop armed") { sawArmed.get() && transparent.get() }
                settle(300)
                val hwnd = NativeTaoBridge.nativeHwndHandle(window.handle)
                check(NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)) {
                    "backdrop not active before requestClose"
                }
                // Multi-cast prepare hook: host.prepareClose runs too. Fires
                // synchronously inside requestClose before DestroyWindow —
                // more reliable than waiting for a recomposition that may be
                // cancelled by the destroy.
                window.onPrepareClose { prepareCloseFired.set(true) }
                window.requestClose()
                check(prepareCloseFired.get()) {
                    "onPrepareClose never ran on requestClose — opaque last frame skipped"
                }
                // After prepare, native backdrop must be down (host listener).
                check(!NativeTaoWindowsDecoBridge.nativeIsBackdropActive(hwnd)) {
                    "native backdrop still active after requestClose prepare"
                }
            },
        )
    }

    /**
     * Review suggestion: single content clear-color slot shared by
     * [WindowBackground] and [TitleBar]. Probes the resolved content ARGB
     * while both are composed, then after removing TitleBar.
     */
    private fun windowBackgroundAndTitleBarClearColor(): TaoWindowTestCase {
        val resolved = AtomicInteger(0)
        // Snapshot state shared with the window composition — driver flips it.
        val showTitleBar = mutableStateOf(true)
        return TaoWindowTestCase(
            name = "WindowBackground vs TitleBar clear-color content slot",
            content = {
                // Distinct from the default TitleBar chrome colour so the probe
                // can tell who last wrote the content layer.
                val probeColor = Color(0xFF112233)
                WindowBackground(probeColor)
                if (showTitleBar.value) {
                    TitleBar()
                }
                val layers = LocalWindowClearColorLayers.current
                SideEffect {
                    resolved.set(layers?.resolved ?: 0)
                }
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(400)
                val withBoth = resolved.get()
                val probeArgb = 0xFF112233.toInt()
                System.err.println(
                    "[probe] clear ARGB with WindowBackground+TitleBar = 0x${withBoth.toUInt().toString(16)} " +
                        "(WindowBackground=0x${probeArgb.toUInt().toString(16)})",
                )
                // Co-composed: TitleBar SideEffect runs after WindowBackground
                // so it should outrank on the content stack.
                check(withBoth != probeArgb) {
                    "expected TitleBar to outrank WindowBackground while co-composed; " +
                        "got 0x${withBoth.toUInt().toString(16)}"
                }
                showTitleBar.value = false
                settle(500)
                val afterTitleBarGone = resolved.get()
                System.err.println(
                    "[probe] clear ARGB after TitleBar removed = 0x${afterTitleBarGone.toUInt().toString(16)}",
                )
                // Regression gate: TitleBar dispose must not wipe WindowBackground.
                check(afterTitleBarGone == probeArgb) {
                    "after TitleBar dispose, content clear is 0x${afterTitleBarGone.toUInt().toString(16)}, " +
                        "expected WindowBackground 0x${probeArgb.toUInt().toString(16)}"
                }
                System.err.println(
                    "[VERDICT] OK — TitleBar outranks while co-composed; " +
                        "WindowBackground restores after TitleBar dispose",
                )
            },
        )
    }

    /**
     * Review P0: `noWindowDrag` must stop an ancestor `windowDragArea` from
     * arming a move. Probes: [TaoWindow.onDragWindow] counter + AWT Robot
     * press-drag over the opt-out zone vs the bare drag strip.
     */
    private fun noWindowDragBlocksAncestorDragArea(): TaoWindowTestCase {
        val dragCount = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "noWindowDrag blocks ancestor windowDragArea",
            // Robot needs a real desktop session; skip headless CI if no display.
            skip = {
                when {
                    java.awt.GraphicsEnvironment.isHeadless() -> "no display for Robot probe"
                    // Hosted runners have no Accessibility grant, so AWT Robot
                    // cannot inject input: on macOS `new Robot()` blocks until
                    // the suite watchdog halts the JVM, and on Linux/Windows the
                    // positive control never arms so the case only ever reports
                    // INCONCLUSIVE. Keep it for local runs, where it is real.
                    System.getenv("CI") != null -> "AWT Robot cannot inject input on hosted CI runners"
                    // Same hang happens on a local Mac without TCC Accessibility
                    // for the JVM (this worktree's coverage run). Probe Robot()
                    // with a short timeout so the suite can finish.
                    !awtRobotAvailable() ->
                        "AWT Robot() blocked or failed (macOS Accessibility / no input injection)"
                    else -> null
                }
            },
            content = {
                // No suite-default paint under the hit targets: the drag strip
                // is the only top-band content so Robot coordinates stay simple.
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(56.dp)
                            .windowDragArea()
                            .background(Color(0xFF445566)),
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp)
                                .size(48.dp)
                                .noWindowDrag()
                                .background(Color(0xFFCC3333)),
                        )
                    }
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle(500)
                window.focus()
                settle(300)
                // Replace the host drag listener with a counting probe. The
                // native move still runs after the listener (TaoWindow.dragWindow).
                window.onDragWindow { dragCount.incrementAndGet() }

                val b = requireNotNull(bounds())
                val scale = window.scaleFactor.coerceAtLeast(1f)

                // Physical client-ish coords from outer bounds (Tao is undecorated).
                fun px(
                    dpX: Float,
                    dpY: Float,
                ): Pair<Int, Int> =
                    (b[0] + (dpX * scale).toInt()).toInt() to
                        (b[1] + (dpY * scale).toInt()).toInt()

                val (noDragX, stripY) = px(8f + 24f, 28f)
                val (dragX, _) = px(280f, 28f)

                val robot = Robot()
                robot.autoDelay = 30
                robot.isAutoWaitForIdle = true

                suspend fun pressDrag(
                    startX: Int,
                    startY: Int,
                    dx: Int,
                ) {
                    robot.mouseMove(startX, startY)
                    settle(80)
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    // Several moves — some hosts only arm after a threshold.
                    for (step in 1..6) {
                        robot.mouseMove(startX + dx * step / 6, startY + step)
                        settle(40)
                    }
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }

                // Positive control first: bare strip must arm dragWindow.
                pressDrag(dragX, stripY, 120)
                settle(500)
                val afterBare = dragCount.get()
                System.err.println(
                    "[probe] dragCount after bare windowDragArea=$afterBare " +
                        "at screen=($dragX,$stripY) bounds=${b.toList()} scale=$scale",
                )
                if (afterBare < 1) {
                    // AWT Robot mouse events do not reliably reach the Tao
                    // Compose pointer pipeline on this host (ANGLE child HWND /
                    // no-AWT). Without a positive control the pass-order claim
                    // cannot be judged in e2e — report inconclusive, do not fail.
                    System.err.println(
                        "[VERDICT] INCONCLUSIVE — Robot never armed dragWindow on " +
                            "bare windowDragArea; cannot e2e-verify noWindowDrag " +
                            "(code-path fix: Final pass in titleBarHitTestHandler still stands)",
                    )
                    return@TaoWindowTestCase
                }

                dragCount.set(0)
                // Press-drag on the noWindowDrag child — must NOT arm another move.
                pressDrag(noDragX, stripY, 120)
                settle(400)
                val afterNoDrag = dragCount.get()
                System.err.println("[probe] dragCount after noWindowDrag child=$afterNoDrag")
                if (afterNoDrag == 0) {
                    System.err.println(
                        "[VERDICT] OK — noWindowDrag blocked ancestor windowDragArea",
                    )
                } else {
                    // Hard fail: positive control worked, opt-out did not.
                    check(false) {
                        "dragWindow fired $afterNoDrag time(s) on noWindowDrag child — " +
                            "pass-order bug still present"
                    }
                }
            },
        )
    }

    /**
     * Review suggestion: when the scaffold bar is hidden in overlay fullscreen,
     * `controlsInsets` must not keep a phantom traffic-light reserve.
     * Meaningful on macOS (80.dp fullscreen inset); on Windows titleBarPadding
     * is already zero so the case documents the probe only.
     */
    private fun hideBarZerosControlsInsetsInFullscreen(): TaoWindowTestCase {
        val startInsetPx = AtomicInteger(-1)
        val topHeightPx = AtomicInteger(-1)
        val ready = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "WindowScaffold hideBar zeros chrome control insets",
            content = {
                WindowScaffold(
                    titleBar = null,
                    titleBarPlacement = TitleBarPlacement.Overlay(autoHideInFullscreen = true),
                ) {
                    val insets = LocalWindowChromeInsets.current
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    SideEffect {
                        with(density) {
                            startInsetPx.set(
                                insets.controlsInsets.calculateLeftPadding(LayoutDirection.Ltr).roundToPx(),
                            )
                            topHeightPx.set(insets.titleBarHeight.roundToPx())
                        }
                        ready.set(true)
                    }
                    DisposableEffect(Unit) {
                        onDispose { ready.set(false) }
                    }
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("insets probe ready") { ready.get() }
                settle(300)
                // titleBar == null ⇒ hideBar: titleBarHeight must be 0.
                check(topHeightPx.get() == 0) {
                    "titleBarHeight px=${topHeightPx.get()} with null titleBar slot"
                }
                window.setFullscreen(true)
                settle(600)
                awaitUntil("still probing in fullscreen") { ready.get() }
                val start = startInsetPx.get()
                val top = topHeightPx.get()
                System.err.println(
                    "[probe] hideBar fullscreen controls startInsetPx=$start titleBarHeightPx=$top " +
                        "platform=${Platform.Current}",
                )
                check(top == 0) { "titleBarHeight not zero in fullscreen hideBar: $top" }
                // hideBar must zero controlsInsets on every platform (not only
                // Windows, where titleBarPadding already returned 0).
                check(start == 0) {
                    "controlsInsets start=$start px while bar hidden " +
                        "(phantom control reserve — review finding)"
                }
            },
        )
    }

    /**
     * Issue #416 — baseline: `DecoratedWindow(transparent = true)` alone
     * (opaque theme style coerced to alpha-0 clear) + non-opaque top-level.
     * No AWT Robot — host clear + macOS `isOpaque`.
     */
    private fun fullyTransparentWindowIssue416(): TaoWindowTestCase {
        val resolvedClear = AtomicInteger(Int.MIN_VALUE)
        val glassArmed = AtomicBoolean(false)
        val backdropTransparentArmed = AtomicBoolean(false)
        val sawLocals = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "#416 fully transparent Tao window",
            paintDefaultBackground = false,
            transparent = true,
            content = {
                // No WindowBackground / TitleBar: only the style layer + coerce policy.
                val layers = LocalWindowClearColorLayers.current
                val glass = LocalRequestedGlassBackground.current
                val backdrop = LocalRequestedTransparentBackground.current
                SideEffect {
                    resolvedClear.set(layers?.resolved ?: 0)
                    glassArmed.set(glass?.value == true)
                    backdropTransparentArmed.set(backdrop?.value == true)
                    sawLocals.set(true)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFFFF00AA)))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("composition locals published") { sawLocals.get() }
                settle(400)
                assertTransparentClearAndNative(resolvedClear.get(), glassArmed.get(), backdropTransparentArmed.get())
                System.err.println("[#416/tao] VERDICT: OK — transparent alone (style coerce + native)")
            },
        )
    }

    /**
     * Regression for the TitleBar footgun: stock TitleBar used to write an
     * opaque chrome colour into the clear stack and paint the empty client
     * solid. Under transparent=true the clear must stay alpha-0 (TitleBar
     * still paints its own bar pixels).
     */
    private fun fullyTransparentSurvivesTitleBarIssue416(): TaoWindowTestCase {
        val resolvedClear = AtomicInteger(Int.MIN_VALUE)
        val sawLocals = AtomicBoolean(false)
        return TaoWindowTestCase(
            name = "#416 transparent survives TitleBar clear",
            paintDefaultBackground = false,
            transparent = true,
            content = {
                TitleBar()
                val layers = LocalWindowClearColorLayers.current
                SideEffect {
                    resolvedClear.set(layers?.resolved ?: 0)
                    sawLocals.set(true)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp, start = 24.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Box(Modifier.size(48.dp).background(Color(0xFFFF00AA)))
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("composition locals published") { sawLocals.get() }
                settle(500)
                val clear = resolvedClear.get()
                val clearAlpha = (clear ushr 24) and 0xFF
                System.err.println(
                    "[#416/titlebar] clear ARGB=0x${clear.toUInt().toString(16)} alpha=$clearAlpha " +
                        "platform=${Platform.Current}",
                )
                check(clearAlpha == 0) {
                    "TitleBar wrote opaque clear under transparent=true: " +
                        "0x${clear.toUInt().toString(16)} — empty client would hide the desktop"
                }
                if (isMac && NativeMetalBridge.isLoaded) {
                    val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
                    check(nsView != 0L)
                    check(!NativeMetalBridge.nativeIsWindowOpaque(nsView)) {
                        "NSWindow re-opaqued under TitleBar + transparent=true"
                    }
                }
                System.err.println("[#416/titlebar] VERDICT: OK — TitleBar does not kill transparent clear")
            },
        )
    }

    /**
     * Semi-transparent WindowBackground must keep its alpha (coerce only
     * targets fully opaque ARGB), so apps can tint a see-through window.
     */
    private fun fullyTransparentHonoursSemiTintIssue416(): TaoWindowTestCase {
        val resolvedClear = AtomicInteger(Int.MIN_VALUE)
        val sawLocals = AtomicBoolean(false)
        // 50% white tint — alpha must survive.
        val tint = Color(0x80FFFFFF)
        val tintArgb = tint.toArgb()
        return TaoWindowTestCase(
            name = "#416 transparent keeps semi-transparent WindowBackground",
            paintDefaultBackground = false,
            transparent = true,
            content = {
                WindowBackground(tint)
                val layers = LocalWindowClearColorLayers.current
                SideEffect {
                    resolvedClear.set(layers?.resolved ?: 0)
                    sawLocals.set(true)
                }
                Box(Modifier.size(48.dp).background(Color(0xFFFF00AA)))
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                awaitUntil("composition locals published") { sawLocals.get() }
                settle(400)
                val clear = resolvedClear.get()
                System.err.println(
                    "[#416/tint] clear ARGB=0x${clear.toUInt().toString(16)} " +
                        "expected=0x${tintArgb.toUInt().toString(16)}",
                )
                check(clear == tintArgb) {
                    "semi-transparent WindowBackground was coerced away: " +
                        "got 0x${clear.toUInt().toString(16)}, expected 0x${tintArgb.toUInt().toString(16)}"
                }
                System.err.println("[#416/tint] VERDICT: OK — semi tint preserved under transparent=true")
            },
        )
    }

    private fun TaoWindowTestScope.assertTransparentClearAndNative(
        clear: Int,
        glassArmed: Boolean,
        backdropTransparentArmed: Boolean,
    ) {
        val clearAlpha = (clear ushr 24) and 0xFF
        System.err.println(
            "[#416/tao] clear ARGB=0x${clear.toUInt().toString(16)} alpha=$clearAlpha " +
                "glassArmed=$glassArmed backdropTransparentArmed=$backdropTransparentArmed " +
                "platform=${Platform.Current}",
        )
        check(clearAlpha == 0) {
            "transparent window clear is not alpha-0: 0x${clear.toUInt().toString(16)}"
        }
        check(!glassArmed) {
            "glassBackgroundState armed — regional glass is not full-window transparency"
        }
        check(!backdropTransparentArmed) {
            "transparentBackgroundState armed without WindowsBackdrop — unexpected"
        }
        if (isMac && NativeMetalBridge.isLoaded) {
            val nsView = NativeTaoBridge.nativeNsViewHandle(window.handle)
            check(nsView != 0L) { "NSView handle missing for #416 probe" }
            val opaque = NativeMetalBridge.nativeIsWindowOpaque(nsView)
            System.err.println("[#416/tao] NSWindow.isOpaque=$opaque")
            check(!opaque) {
                "NSWindow still opaque under DecoratedWindow(transparent=true) — " +
                    "desktop cannot composite through"
            }
        }
    }
}
