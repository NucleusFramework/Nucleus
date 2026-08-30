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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.TitleBar
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
    fun all(): List<TaoWindowTestCase> = listOf(animatedHeightDoesNotTremble())

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
                window.onResized { w, h ->
                    innerW.set(w)
                    innerH.set(h)
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
            },
        )
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

        val baseline = samples.takeWhile { abs(it.requestedH - startH) <= 0 }
        val chrome =
            baseline
                .map { it.outerH - it.sceneH }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: (samples.first().outerH - samples.first().sceneH)

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
        if (m.maxSceneVsOuter > PX_TOLERANCE) {
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
