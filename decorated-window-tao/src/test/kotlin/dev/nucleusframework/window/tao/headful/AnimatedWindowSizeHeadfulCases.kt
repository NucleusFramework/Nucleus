package dev.nucleusframework.window.tao.headful

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.scene.TaoPresentDiagnostics
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * #576 — programmatic animated [WindowState.size] height must not make
 * TitleBar + body content tremble against the native window.
 *
 * Reproduces the issue's `animateDpAsState` → `WindowState.size` path on a
 * real Tao `DecoratedWindow` with TitleBar + content, samples native bounds
 * vs Compose layout/scene each frame, and gates the tremble metric.
 */
internal object AnimatedWindowSizeHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(animatedHeightDoesNotTremble(), zoomPresentsEveryStep(), appKitAnimatorDispatchesEveryStepInTime())

    private data class LayoutPx(
        var x: Int = 0,
        var y: Int = 0,
        var w: Int = 0,
        var h: Int = 0,
    )

    private data class Sample(
        val nanos: Long,
        val requestedH: Int,
        val stateH: Int,
        val outerX: Int,
        val outerY: Int,
        val outerW: Int,
        val outerH: Int,
        val innerW: Int,
        val innerH: Int,
        val sceneW: Int,
        val sceneH: Int,
        val titleX: Int,
        val titleY: Int,
        val titleW: Int,
        val titleH: Int,
        val contentX: Int,
        val contentY: Int,
        val contentW: Int,
        val contentH: Int,
    )

    /**
     * The title-bar double-click path (#576): a maximize / restore zoom is a
     * run of frame steps, and each must have its content presented before
     * the next arrives — otherwise the content trails the window edge for
     * the whole animation. On macOS tao steps the zoom itself (vendored
     * `set_maximized_async`), so the steps are plain resizes; on Windows the
     * maximize is instant — one size change each way, and DWM stretches the
     * previous frame over the new client area until it is presented.
     */
    private fun zoomPresentsEveryStep(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#576 maximize and restore zoom present every step in its own turn",
            timeoutMillis = CASE_TIMEOUT_MILLIS,
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle()
            val minSteps = if (Platform.Current == Platform.MacOS) MIN_ANIM_SAMPLES else MIN_ZOOM_STEPS_INSTANT
            val probe = PresentLagProbe(window, AtomicBoolean(true), minSteps)
            window.onResized { w, h -> probe.onResized(w, h) }
            window.setMaximized(true)
            awaitUntil("maximized") { window.isMaximized }
            settle(ZOOM_SETTLE_MILLIS)
            window.setMaximized(false)
            awaitUntil("restored") { !window.isMaximized }
            settle(ZOOM_SETTLE_MILLIS)
            probe.assertNone()
        }

    /**
     * The edge double-click zoom (`_zoomToScreenEdge:`) is AppKit's own
     * `setFrame:display:animate:YES`: a blocking animator whose private
     * run-loop mode services no tao observer, so every step's `Resized` waits
     * in tao's queue until the animation has ended and the content snaps into
     * the final bounds — the trailing of the title-bar zoom before #678, one
     * path over. No Robot here, so the case takes that AppKit path
     * programmatically: `set_maximized_async` on a non-resizable window is a
     * plain `setFrame:display:NO animate:YES`. Every `Resized` must be
     * dispatched while the native frame is at its size — outer minus inner
     * height is then the chrome, a constant; a step dispatched after the
     * animation reads the final outer height against its own inner one.
     */
    private fun appKitAnimatorDispatchesEveryStepInTime(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#576 AppKit frame animation (edge double-click zoom) dispatches every step in time",
            timeoutMillis = CASE_TIMEOUT_MILLIS,
            skip = { "AppKit's setFrame:display:animate: is macOS only".takeIf { Platform.Current != Platform.MacOS } },
        ) {
            awaitUntil("window mapped") { window.hasRealFramePx() }
            settle()
            window.setResizable(false)
            val chromes = CopyOnWriteArrayList<Long>()
            val probe = PresentLagProbe(window, AtomicBoolean(true))
            window.onResized { w, h ->
                probe.onResized(w, h)
                window.outerBoundsPx()?.let { chromes += it[3] - h }
            }
            window.setMaximized(true)
            awaitUntil("maximized") { window.isMaximized }
            settle(ZOOM_SETTLE_MILLIS)
            window.setMaximized(false)
            awaitUntil("restored") { !window.isMaximized }
            settle(ZOOM_SETTLE_MILLIS)
            probe.assertNone()
            val spread = (chromes.max() - chromes.min()).toInt()
            System.err.println("[#576] outer-minus-inner height spread over ${chromes.size} resize events: ${spread}px")
            check(spread <= PX_TOLERANCE) {
                "resize events were dispatched with the native frame ${spread}px away from their size — " +
                    "AppKit's animator ran to its end before tao delivered a step"
            }
        }

    private fun animatedHeightDoesNotTremble(): TaoWindowTestCase {
        val windowState =
            WindowState(
                position = WindowPosition.Aligned(Alignment.Center),
                size = DpSize(WINDOW_WIDTH_DP.dp, START_HEIGHT_DP.dp),
            )
        val targetHeight = mutableStateOf(START_HEIGHT_DP.dp)
        val samples = CopyOnWriteArrayList<Sample>()
        val recording = AtomicBoolean(false)
        val innerW = AtomicInteger(0)
        val innerH = AtomicInteger(0)
        val titleBar = AtomicReference(LayoutPx())
        val content = AtomicReference(LayoutPx())
        val latestRequestedH = AtomicInteger(0)

        return TaoWindowTestCase(
            name = "#576 animated WindowState.size height does not tremble TitleBar + content",
            timeoutMillis = CASE_TIMEOUT_MILLIS,
            paintDefaultBackground = false,
            windowState = windowState,
            size = DpSize(WINDOW_WIDTH_DP.dp, START_HEIGHT_DP.dp),
            content = {
                val density = LocalDensity.current.density
                val scene = LocalWindowInfo.current.containerSize
                val animatedHeight by animateDpAsState(
                    targetValue = targetHeight.value,
                    animationSpec = tween(durationMillis = ANIM_MILLIS, easing = LinearEasing),
                    label = "issue576-window-height",
                )
                windowState.size = DpSize(WINDOW_WIDTH_DP.dp, animatedHeight)
                latestRequestedH.set((animatedHeight.value * density).roundToInt())

                fun snapshot() {
                    if (!recording.get()) return
                    val outer = window.outerBoundsPx() ?: return
                    val tb = titleBar.get()
                    val body = content.get()
                    if (tb.w <= 0 || body.w <= 0) return
                    // SideEffect runs before layout. If containerSize already
                    // moved, wait for onGloballyPositioned of this frame.
                    if (abs(tb.h + body.h - scene.height) > PX_TOLERANCE) return
                    samples +=
                        Sample(
                            nanos = System.nanoTime(),
                            requestedH = latestRequestedH.get(),
                            stateH = (windowState.size.height.value * density).roundToInt(),
                            outerX = outer[0].toInt(),
                            outerY = outer[1].toInt(),
                            outerW = outer[2].toInt(),
                            outerH = outer[3].toInt(),
                            innerW = innerW.get(),
                            innerH = innerH.get(),
                            sceneW = scene.width,
                            sceneH = scene.height,
                            titleX = tb.x,
                            titleY = tb.y,
                            titleW = tb.w,
                            titleH = tb.h,
                            contentX = body.x,
                            contentY = body.y,
                            contentW = body.w,
                            contentH = body.h,
                        )
                }

                val phase = remember { mutableFloatStateOf(0f) }
                // Read in composition so every frame clock tick recomposes
                // and snapshot() runs during baseline, not only on layout changes.
                val frameTick = phase.floatValue
                TitleBar(
                    Modifier.onGloballyPositioned { coords ->
                        val p = coords.positionInWindow()
                        titleBar.set(
                            LayoutPx(
                                p.x.roundToInt(),
                                p.y.roundToInt(),
                                coords.size.width,
                                coords.size.height,
                            ),
                        )
                    },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(CONTENT_ARGB))
                        .drawBehind { drawRect(Color.Black.copy(alpha = (frameTick % 2f) * 0.01f)) }
                        .onGloballyPositioned { coords ->
                            val p = coords.positionInWindow()
                            content.set(
                                LayoutPx(
                                    p.x.roundToInt(),
                                    p.y.roundToInt(),
                                    coords.size.width,
                                    coords.size.height,
                                ),
                            )
                            snapshot()
                        },
                )

                SideEffect { snapshot() }

                LaunchedEffect(window) {
                    window.onResized { w, h ->
                        innerW.set(w)
                        innerH.set(h)
                    }
                    while (true) {
                        withFrameNanos { phase.floatValue += 1f }
                    }
                }
            },
            driver = {
                awaitUntil("window mapped") { bounds() != null }
                settle()
                val presentLag = PresentLagProbe(window, recording)
                window.onResized { w, h ->
                    innerW.set(w)
                    innerH.set(h)
                    presentLag.onResized(w, h)
                }
                recording.set(true)
                settle(BASELINE_MILLIS)
                check(samples.isNotEmpty()) { "no baseline samples before the height animation" }

                targetHeight.value = END_HEIGHT_DP.dp
                settle(ANIM_MILLIS + SETTLE_AFTER_ANIM_MILLIS)
                recording.set(false)

                val dump = writeSamples(samples)
                System.err.println("[#576] wrote ${samples.size} samples to $dump")
                assertNoTremble(samples)
                presentLag.assertNone()
            },
        )
    }

    /**
     * Counts resize events whose frame was not on its way by the time the
     * next one arrived. The host presents a resize's frame at the end of the
     * same run-loop turn (`MainEventsCleared`), after every listener has run —
     * so this listener, at event N, checks that event N-1 has been presented,
     * and [assertNone] that the last one has. Without the same-turn present
     * the render loop trails by one to two steps and Core Animation shows the
     * previous drawable stretched over the new bounds — the tremble itself
     * (#576). The Metal and ANGLE hosts record presents; the gate covers
     * macOS and Windows.
     */
    private class PresentLagProbe(
        private val window: TaoWindow,
        private val recording: AtomicBoolean,
        private val minChecked: Int = MIN_ANIM_SAMPLES,
    ) {
        private val checked = AtomicInteger(0)
        private val lagging = AtomicInteger(0)
        private val previous = AtomicReference<IntSize?>(null)

        fun onResized(
            w: Int,
            h: Int,
        ) {
            if (!recording.get()) return
            val size = IntSize(w, h)
            // tao echoes a programmatic resize twice in one turn (its own
            // dispatch and AppKit's `windowDidResize:`); only a size change
            // closes the previous step.
            if (previous.get() == size) return
            val prev = previous.getAndSet(size) ?: return
            checked.incrementAndGet()
            // The host presents inside the resize dispatch, before this
            // listener runs, so the last present is normally already this
            // size; the previous one is the most that may still be pending.
            val presented = TaoPresentDiagnostics.lastPresentedPx(window.handle)
            if (presented != size && presented != prev) lagging.incrementAndGet()
        }

        fun assertNone() {
            val last = previous.get()
            if (last != null && TaoPresentDiagnostics.lastPresentedPx(window.handle) != last) lagging.incrementAndGet()
            System.err.println("[#576] presentLag=${lagging.get()} of ${checked.get()} resize events")
            check(checked.get() >= minChecked) {
                "only ${checked.get()} resize events reached the window during the animation"
            }
            check(lagging.get() == 0) {
                "${lagging.get()} of ${checked.get()} resize events had no frame at their size presented before " +
                    "the next one arrived — Core Animation stretches the previous drawable over the new bounds"
            }
        }
    }

    private fun writeSamples(samples: List<Sample>): File {
        val path =
            System.getProperty("nucleus.issue576.samples")
                ?: File(System.getProperty("java.io.tmpdir"), "576-samples.csv").absolutePath
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine(
                    "nanos,requestedH,stateH,outerX,outerY,outerW,outerH,innerW,innerH," +
                        "sceneW,sceneH,titleX,titleY,titleW,titleH,contentX,contentY,contentW,contentH," +
                        "sceneVsInnerH,layoutVsSceneH,titleY,contentGap,outerY",
                )
                for (s in samples) {
                    val sceneVsInner = if (s.innerH > 0) abs(s.sceneH - s.innerH) else -1
                    val layoutVsScene = abs(s.titleH + s.contentH - s.sceneH)
                    val contentGap = abs(s.contentY - s.titleH)
                    appendLine(
                        listOf(
                            s.nanos,
                            s.requestedH,
                            s.stateH,
                            s.outerX,
                            s.outerY,
                            s.outerW,
                            s.outerH,
                            s.innerW,
                            s.innerH,
                            s.sceneW,
                            s.sceneH,
                            s.titleX,
                            s.titleY,
                            s.titleW,
                            s.titleH,
                            s.contentX,
                            s.contentY,
                            s.contentW,
                            s.contentH,
                            sceneVsInner,
                            layoutVsScene,
                            s.titleY,
                            contentGap,
                            s.outerY,
                        ).joinToString(","),
                    )
                }
            },
        )
        return file
    }

    private fun assertNoTremble(samples: List<Sample>) {
        check(samples.size >= MIN_SAMPLES) {
            "need at least $MIN_SAMPLES frames, got ${samples.size}"
        }
        val startH = samples.first().requestedH
        val animated =
            samples.filter { abs(it.requestedH - startH) > 0 }
        check(animated.size >= MIN_ANIM_SAMPLES) {
            "height animation never moved on the sampled frames " +
                "(start=$startH, samples=${samples.size}, animated=${animated.size})"
        }

        // A window's outer box always contains the scene it hosts, so a
        // negative chrome means the outer bounds were not real yet — openbox
        // under Xvfb reports a 1px outer height for a while after mapping, and
        // a baseline mode of -359 turned every gate below into noise. Estimate
        // only from frames where the invariant holds, falling back to the whole
        // run when the baseline window caught none of them.
        val realOuter = { s: Sample -> s.outerH >= s.sceneH }
        val baseline = samples.takeWhile { abs(it.requestedH - startH) <= 0 }.filter(realOuter)
        val chromeFrames = baseline.ifEmpty { samples.filter(realOuter) }
        check(chromeFrames.isNotEmpty()) {
            "no frame where the outer height covers the scene — the window never " +
                "reported real outer bounds (first sample outerH=${samples.first().outerH} " +
                "sceneH=${samples.first().sceneH})"
        }
        val chrome =
            chromeFrames
                .map { it.outerH - it.sceneH }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }!!
                .key

        val m = measureTremble(animated, chrome)

        System.err.println(
            "[#576] metric maxTitleY=${m.maxTitleY} maxContentGap=${m.maxContentGap} " +
                "maxSceneVsInner=${m.maxSceneVsInner} (over ${m.sceneVsInnerSamples} frames) " +
                "maxLayoutVsScene=${m.maxLayoutVsScene} " +
                "maxSceneVsOuter=${m.maxSceneVsOuter} (chrome=$chrome) " +
                "heightReversals=${m.nativeHeightReversals} originOsc=${m.maxOriginOscillation} " +
                "animatedFrames=${animated.size}",
        )

        // Without comparable frames the scene-vs-inner gate below is vacuous.
        check(m.sceneVsInnerSamples >= MIN_ANIM_SAMPLES) {
            "only ${m.sceneVsInnerSamples} of ${animated.size} animated frames had an " +
                "onResized inner height matching the live outer size — cannot judge tremble"
        }

        val failures = mutableListOf<String>()
        if (m.maxTitleY > PX_TOLERANCE) {
            failures += "TitleBar origin Y jumped ${m.maxTitleY}px (window-top pin)"
        }
        if (m.maxContentGap > PX_TOLERANCE) {
            failures += "content did not stay immediately under TitleBar (gap ${m.maxContentGap}px)"
        }
        if (m.maxLayoutVsScene > PX_TOLERANCE) {
            failures += "TitleBar+content height drifted from scene by ${m.maxLayoutVsScene}px"
        }
        if (m.maxSceneVsInner > PX_TOLERANCE) {
            failures += "Compose scene height drifted from native inner size by ${m.maxSceneVsInner}px"
        }
        // The outer gate catches chrome drift — TitleBar and frame disagreeing.
        // But the outer rectangle is a separate query from the resize event the
        // scene tracks: on a loaded Xvfb the X server's geometry lags the
        // scene by 2-3px for a couple of consecutive samples while the inner
        // gate stays at 0px. That is reporting latency, not tremble, and only
        // the outer query can see it. So an outer-only drift is a failure only
        // when the scene also lost the inner size; otherwise it is logged
        // through the metric line above.
        if (m.maxSceneVsOuter > PX_TOLERANCE && m.maxSceneVsInner > PX_TOLERANCE) {
            failures +=
                "Compose scene height drifted from native outer size by " +
                "${m.maxSceneVsOuter}px (chrome $chrome)"
        }
        if (m.nativeHeightReversals > 0) {
            failures += "native height reversed ${m.nativeHeightReversals} time(s) while the animation only grew"
        }
        if (m.maxOriginOscillation > PX_TOLERANCE) {
            failures += "window/content origin oscillated by ${m.maxOriginOscillation}px"
        }
        check(failures.isEmpty()) {
            failures.joinToString("; ")
        }
    }

    /** Worst-case drift seen across the animated frames. */
    private data class TrembleMetrics(
        val maxTitleY: Int,
        val maxContentGap: Int,
        val maxSceneVsInner: Int,
        val sceneVsInnerSamples: Int,
        val maxLayoutVsScene: Int,
        val maxSceneVsOuter: Int,
        val maxOriginOscillation: Int,
        val nativeHeightReversals: Int,
    )

    private fun measureTremble(
        animated: List<Sample>,
        chrome: Int,
    ): TrembleMetrics {
        var maxTitleY = 0
        var maxContentGap = 0
        var maxSceneVsInner = 0
        var sceneVsInnerSamples = 0
        var maxLayoutVsScene = 0
        var maxSceneVsOuter = 0
        var outerDriftRun = 0
        var maxOriginOscillation = 0
        var nativeHeightReversals = 0
        var prevInnerH = animated.first().innerH.takeIf { it > 0 } ?: animated.first().sceneH
        var prevOuterY = animated.first().outerY
        var haveOuterYDir = false
        var outerYRising = false

        for (s in animated) {
            maxTitleY = maxOf(maxTitleY, abs(s.titleY))
            maxContentGap = maxOf(maxContentGap, abs(s.contentY - s.titleH))
            maxLayoutVsScene = maxOf(maxLayoutVsScene, abs(s.titleH + s.contentH - s.sceneH))
            // `innerH` is the last onResized callback — it can race ahead of
            // this composition. Live inner size is outer minus chrome.
            val liveInner = s.outerH - chrome
            if (s.innerH > 0 && abs(s.innerH - liveInner) <= PX_TOLERANCE) {
                maxSceneVsInner = maxOf(maxSceneVsInner, abs(s.sceneH - s.innerH))
                sceneVsInnerSamples++
            }
            // `chrome` is a baseline constant, but GTK CSD frame extents are
            // not: outer bounds can take the new size a frame before the
            // resize event reaches Compose (52/52/…/56/52 on GNOME Wayland).
            // A one-frame skew is sampling, tremble is sustained — so only
            // count a drift that survives consecutive animated frames.
            val outerDrift = abs(s.outerH - s.sceneH - chrome)
            outerDriftRun = if (outerDrift > PX_TOLERANCE) outerDriftRun + 1 else 0
            if (outerDriftRun >= SUSTAINED_FRAMES) {
                maxSceneVsOuter = maxOf(maxSceneVsOuter, outerDrift)
            }
            maxOriginOscillation = maxOf(maxOriginOscillation, abs(s.titleX), abs(s.contentX))

            val nativeH = if (s.innerH > 0) s.innerH else s.sceneH
            if (nativeH + PX_TOLERANCE < prevInnerH) {
                nativeHeightReversals++
            }
            prevInnerH = nativeH

            val dy = s.outerY - prevOuterY
            if (dy != 0) {
                val nowRising = dy > 0
                if (haveOuterYDir && nowRising != outerYRising && abs(dy) > PX_TOLERANCE) {
                    maxOriginOscillation = maxOf(maxOriginOscillation, abs(dy))
                }
                outerYRising = nowRising
                haveOuterYDir = true
            }
            prevOuterY = s.outerY
        }

        return TrembleMetrics(
            maxTitleY = maxTitleY,
            maxContentGap = maxContentGap,
            maxSceneVsInner = maxSceneVsInner,
            sceneVsInnerSamples = sceneVsInnerSamples,
            maxLayoutVsScene = maxLayoutVsScene,
            maxSceneVsOuter = maxSceneVsOuter,
            maxOriginOscillation = maxOriginOscillation,
            nativeHeightReversals = nativeHeightReversals,
        )
    }

    private const val WINDOW_WIDTH_DP = 420
    private const val START_HEIGHT_DP = 360
    private const val END_HEIGHT_DP = 560
    private const val ANIM_MILLIS = 500

    // Past `animationResizeTime:` (~250 ms for a screen-sized zoom) with margin.
    private const val ZOOM_SETTLE_MILLIS = 800L

    // Windows maximizes without a zoom animation: the probe sees the restore
    // step close the maximize one, and nothing more.
    private const val MIN_ZOOM_STEPS_INSTANT = 1
    private const val BASELINE_MILLIS = 200L
    private const val SETTLE_AFTER_ANIM_MILLIS = 250L
    private const val CASE_TIMEOUT_MILLIS = 20_000L
    private const val MIN_SAMPLES = 20
    private const val MIN_ANIM_SAMPLES = 8
    private const val PX_TOLERANCE = 1

    /** Consecutive animated frames a scene-vs-outer drift must survive to count. */
    private const val SUSTAINED_FRAMES = 2
    private const val CONTENT_ARGB = 0xFF203040.toInt()
}
