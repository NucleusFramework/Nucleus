package dev.nucleusframework.sampletao

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import dev.nucleusframework.window.tao.D3D11TestTextureProducer
import dev.nucleusframework.window.tao.DmaBufTestTextureProducer
import dev.nucleusframework.window.tao.MetalTestTextureProducer
import dev.nucleusframework.window.tao.NucleusDrmFormat
import dev.nucleusframework.window.tao.NucleusYuvFormat
import dev.nucleusframework.window.tao.TaoStandalonePopup
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.isTaoStandalonePopupAvailable
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val TEX_W = 128
private const val TEX_H = 96

/**
 * TextureView demo (discussion #338): external GPU producers render an
 * animated pattern on their own device+thread; frames reach the Compose
 * scene via `markFrameAvailable` — draw-only invalidation, zero
 * recompositions per frame (watch the recomposition counter stay put
 * while the pattern animates). Windows uses D3D11 shared handles (the
 * keyed-mutex producer takes the tear-free staging path, the plain one is
 * sampled zero-copy); macOS uses Metal `IOSurface`s; Linux uses DMA-BUFs
 * imported as `EGLImage`s (zero copy, no per-frame work at all).
 */
@Suppress("FunctionNaming", "MagicNumber", "LongMethod")
@Composable
fun TextureTab(modifier: Modifier = Modifier) {
    val syncProducer = remember { createDemoProducer(TEX_W, TEX_H, synchronized = true) }
    val rawProducer = remember { createDemoProducer(TEX_W, TEX_H, synchronized = false) }
    // Linux only: a planar (I420) buffer, the layout a hardware video decoder
    // hands out, published with an acquire fence instead of a blocking finish.
    val planarProducer = remember { DmaBufTestTextureProducer.createYuv(TEX_W, TEX_H) }
    // The same layout with its chroma planes listed the other way round: both
    // boxes must look identical to the packed ones, which is what shows the plane
    // order is the buffer's business and not the app's.
    val swappedPlanarProducer =
        remember { DmaBufTestTextureProducer.createYuv(TEX_W, TEX_H, NucleusYuvFormat.YV12) }
    DisposableEffect(Unit) {
        onDispose {
            syncProducer?.close()
            rawProducer?.close()
            planarProducer?.close()
            swappedPlanarProducer?.close()
        }
    }

    val syncController = rememberTextureViewController()
    val rawController = rememberTextureViewController()
    val planarController = rememberTextureViewController()
    val swappedController = rememberTextureViewController()

    // Frame-rate probe: producer frames are counted on the producer thread,
    // composited draws inside the primary TextureView's draw pass. Both are
    // sampled once a second by [FrameRateReadout], which is the only thing that
    // recomposes for them — the tab's own recomposition counter must stay put.
    val meter = remember { FrameRateMeter() }

    // Producer loop, display-paced: `withFrameNanos` ticks once per composited
    // frame, so the pattern animates at the panel's refresh rate (90 Hz, 120 Hz
    // on ProMotion, …) instead of a hard-coded 60. The GPU work itself stays on
    // a background dispatcher — that is what proves markFrameAvailable is
    // thread-safe and that animation never touches the composition.
    LaunchedEffect(syncProducer, rawProducer, planarProducer) {
        // No producer (no D3D11 / Metal / DRM render node): stay out of the frame
        // clock entirely — a withFrameNanos awaiter re-arms the frame dispatcher
        // every tick, which would spin the render loop on empty frames.
        if (syncProducer == null && rawProducer == null && planarProducer == null) return@LaunchedEffect
        var tick = 0
        while (isActive) {
            withFrameNanos { }
            val hue = (tick % 360).toFloat()
            val bg = Color.hsv(hue, 0.65f, 0.75f).toArgb()
            withContext(Dispatchers.Default) {
                if (syncProducer != null) {
                    syncProducer.drawTestPattern(tick, bg)
                    syncController.markFrameAvailable()
                }
                if (rawProducer != null) {
                    rawProducer.drawTestPattern(tick, bg)
                    rawController.markFrameAvailable()
                }
                if (planarProducer != null) {
                    // The fence descriptor is handed straight over: the controller
                    // takes ownership and the compositor's GPU waits on it, so this
                    // producer never blocks on `glFinish`.
                    planarController.markFrameAvailable(planarProducer.drawTestPatternFenced(tick, bg))
                }
                if (swappedPlanarProducer != null) {
                    swappedPlanarProducer.drawTestPattern(tick, bg)
                    swappedController.markFrameAvailable()
                }
            }
            meter.onProducerFrame()
            tick++
        }
    }

    // Increments on every recomposition of this tab — must NOT follow the
    // producer (frame updates invalidate the draw pass only).
    val recompositions = remember { intArrayOf(0) }
    recompositions[0]++

    val label = TextStyle(color = Color(0xFFA0A4B0), fontSize = 11.sp)
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text =
                if (syncProducer == null) {
                    "TextureView — producer unavailable (needs Windows + D3D11/ANGLE, macOS + Metal, " +
                        "or Linux + a DRM render node)"
                } else {
                    "TextureView — external ${syncProducer.kind} producers, ${TEX_W}x$TEX_H at display rate " +
                        "(recompositions=${recompositions[0]})"
                },
            style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 13.sp),
        )

        FrameRateReadout(meter, style = label)

        BasicText(
            text = "${syncProducer?.syncMode ?: "Primary producer"} — shared import, contentScale variants:",
            style = label,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(160.dp, 120.dp).drawBehind { meter.onCompositedFrame() },
                    contentScale = ContentScale.FillBounds,
                )
                BasicText("FillBounds", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(120.dp, 120.dp),
                    contentScale = ContentScale.Fit,
                )
                BasicText("Fit (letterbox)", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(120.dp, 120.dp),
                    contentScale = ContentScale.Crop,
                )
                BasicText("Crop", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    TextureView(
                        source = syncProducer?.source,
                        controller = syncController,
                        modifier =
                            Modifier
                                .size(120.dp)
                                .rotate(15f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1F2630)),
                    )
                    BasicText(
                        text = "Compose on top",
                        style = TextStyle(color = Color.White, fontSize = 11.sp),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xCC15181D))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                BasicText("Rotated + overlap", style = label)
            }
        }

        BasicText("filterQuality on a ${TEX_W}x$TEX_H texture upscaled 2.5x:", style = label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(320.dp, 240.dp),
                    filterQuality = FilterQuality.None,
                )
                BasicText("None (nearest)", style = label)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextureView(
                    source = syncProducer?.source,
                    controller = syncController,
                    modifier = demoBox(320.dp, 240.dp),
                    filterQuality = FilterQuality.High,
                )
                BasicText("High (cubic)", style = label)
            }
        }

        BasicText("${rawProducer?.altSyncMode ?: "Second producer"}:", style = label)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextureView(
                source = rawProducer?.source,
                controller = rawController,
                modifier = demoBox(160.dp, 120.dp),
            )
        }

        if (planarProducer != null) {
            BasicText(
                text =
                    "Planar I420 DMA-BUF (a video decoder's layout) — three single-channel planes " +
                        "converted in the draw, published with an acquire fence (I420, then YV12):",
                style = label,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextureView(
                    source = planarProducer.source,
                    controller = planarController,
                    modifier = demoBox(160.dp, 120.dp),
                )
                TextureView(
                    source = swappedPlanarProducer?.source,
                    controller = swappedController,
                    modifier = demoBox(160.dp, 120.dp),
                )
            }
        }

        // Tray panel: a standalone, ownerless surface with its OWN Skia context.
        // The same producer imported a second time, onto that context — the box
        // in the panel must animate exactly like the ones above.
        var panelVisible by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A3340))
                        .clickable { panelVisible = !panelVisible }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                BasicText(
                    text = if (panelVisible) "Hide tray panel" else "Show tray panel",
                    style = TextStyle(color = Color(0xFFE6E6E6), fontSize = 12.sp),
                )
            }
            BasicText("standalone panel — its own Skia context", style = label)
        }
        // The complementary API to everything above: an in-process renderer
        // drawing on the scene's OWN context instead of importing a foreign
        // buffer. See GpuContextSection.
        GpuContextSection(label)

        if (isTaoStandalonePopupAvailable()) {
            TaoStandalonePopup(
                visible = panelVisible,
                position = WindowPosition.Absolute(40.dp, 40.dp),
                size = DpSize(220.dp, 380.dp),
                focusable = false,
                onOutsideClick = { panelVisible = false },
            ) {
                Column(
                    modifier =
                        Modifier
                            .background(Color(0xF015181D))
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("Tray panel", style = TextStyle(color = Color.White, fontSize = 12.sp))
                    TextureView(
                        source = syncProducer?.source,
                        controller = syncController,
                        modifier = demoBox(160.dp, 120.dp),
                        contentScale = ContentScale.FillBounds,
                    )
                    // Same in-process-renderer demo, resolved against THIS
                    // surface: on macOS/Linux the panel owns a private Skia
                    // context (different skiaContext@ id than the window's),
                    // on Windows it shares the headless one.
                    GpuContextSection(label, compact = true)
                }
            }
        }
    }
}

/**
 * Platform-agnostic view of the two bundled test producers, so the demo body
 * stays identical on Windows (D3D11 shared handle) and macOS (Metal
 * `IOSurface`). [synchronized] only means something on Windows, where it picks
 * the keyed-mutex producer.
 */
private class DemoTextureProducer(
    val source: TextureViewSource,
    val kind: String,
    val syncMode: String,
    val altSyncMode: String,
    private val pattern: (tick: Int, backgroundArgb: Int) -> Unit,
    private val closeProducer: () -> Unit,
) : AutoCloseable {
    fun drawTestPattern(
        tick: Int,
        backgroundArgb: Int,
    ) = pattern(tick, backgroundArgb)

    override fun close() = closeProducer()
}

private fun createDemoProducer(
    widthPx: Int,
    heightPx: Int,
    synchronized: Boolean,
): DemoTextureProducer? {
    // Each factory returns null off its platform, so the first non-null wins.
    D3D11TestTextureProducer.create(widthPx, heightPx, useKeyedMutex = synchronized)?.let { producer ->
        return DemoTextureProducer(
            source = producer.source,
            kind = "D3D11",
            syncMode = "Keyed mutex (tear-free staging)",
            altSyncMode = "No mutex — true zero copy (producer just flushes)",
            pattern = producer::drawTestPattern,
            closeProducer = producer::close,
        )
    }
    MetalTestTextureProducer.create(widthPx, heightPx)?.let { producer ->
        return DemoTextureProducer(
            source = producer.source,
            kind = "Metal IOSurface",
            syncMode = "IOSurface import (one GPU copy per frame)",
            altSyncMode = "Second producer — own MTLDevice + IOSurface",
            pattern = producer::drawTestPattern,
            closeProducer = producer::close,
        )
    }
    // Linux: the second producer allocates the mirrored byte order on purpose —
    // both boxes must look identical, which is what proves the DRM FourCC (and
    // not the app) is what tells the driver how to read the buffer.
    val fourcc = if (synchronized) NucleusDrmFormat.ARGB8888 else NucleusDrmFormat.ABGR8888
    DmaBufTestTextureProducer.create(widthPx, heightPx, fourcc)?.let { producer ->
        return DemoTextureProducer(
            source = producer.source,
            kind = "DMA-BUF",
            syncMode = "DMA-BUF EGLImage import (true zero copy)",
            altSyncMode = "Second producer — same import, ABGR8888 buffer",
            pattern = producer::drawTestPattern,
            closeProducer = producer::close,
        )
    }
    return null
}

/**
 * Counts producer frames (any thread) and composited draw passes (draw thread),
 * and turns them into per-second rates. Deliberately allocation-free on both hot
 * paths: the whole point of the tab is that a producer frame costs the
 * composition nothing.
 */
private class FrameRateMeter {
    private val producerFrames = AtomicInteger()
    private val compositedFrames = AtomicInteger()

    fun onProducerFrame() {
        producerFrames.incrementAndGet()
    }

    fun onCompositedFrame() {
        compositedFrames.incrementAndGet()
    }

    /** Producer / composited frames since the previous call. */
    fun sample(): Pair<Int, Int> = producerFrames.getAndSet(0) to compositedFrames.getAndSet(0)
}

/**
 * Shows the two rates, refreshed once a second. Kept in its own composable so
 * the state read (and the recomposition it causes) stays out of `TextureTab` —
 * otherwise the tab's "recompositions" counter would climb every second and stop
 * proving that producer frames never recompose anything.
 */
@Suppress("FunctionNaming")
@Composable
private fun FrameRateReadout(
    meter: FrameRateMeter,
    style: TextStyle,
) {
    var rates by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(meter) {
        while (isActive) {
            delay(1_000)
            rates = meter.sample()
        }
    }
    BasicText(
        text = "producer ${rates.first} fps · composited ${rates.second} fps",
        style = style,
    )
}

private fun demoBox(
    width: Dp,
    height: Dp,
): Modifier =
    Modifier
        .size(width, height)
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF1F2630))
