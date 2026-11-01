package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.D3D11TestTextureProducer
import dev.nucleusframework.window.tao.DmaBufTestTextureProducer
import dev.nucleusframework.window.tao.MetalTestTextureProducer
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoGpuRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewController
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.hasGlTextureImports
import dev.nucleusframework.window.tao.hasMetalTextureImports
import dev.nucleusframework.window.tao.hasWindowsTextureImports
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * External GPU textures and an in-process renderer on the scene's own GPU
 * context, under a random walk of everything an app does to them.
 *
 * Two GPU paths share the window's Skia context: [TextureView] *imports* a
 * texture a foreign producer keeps writing from its own thread (a DMA-BUF, an
 * IOSurface, a D3D11 shared handle), and [rememberTaoGpuRenderContext] lets a
 * renderer *draw* on the scene's context itself, under `withContextCurrent` /
 * `runOnGpuThread`. Both live one context rebuild away from a stale handle —
 * a Wayland hide/show tears the whole EGL stack down, a producer can be closed
 * while its view is still composing, a burst of frames can land during a
 * resize — and the failures there are freezes and GL errors, not assertions.
 *
 * So the monkey mounts, unmounts, swaps, resizes, hides, shows, minimizes,
 * closes producers under live views and floods frames from off-thread, and
 * checks the two things a video app cannot live without: the scene **keeps
 * rendering** (a frame heartbeat advances after every checkpoint, the shared
 * renderer keeps producing snapshots), and the loop **keeps answering**
 * ([MainLoopWatchdog]). At the end nothing may be left: no import alive on the
 * context once every view is gone, no producer thread that threw, one window.
 *
 * The producers are the platform test producers that ship with the module.
 * Where none can be made (no render node under Xvfb, no D3D11 on a bare
 * runner) the views run with a null source and the shared-context renderer
 * carries the GPU half on its own — the case says so in its log.
 */
internal object TextureViewMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> = listOf(randomActionsKeepTheSceneRendering())

    private fun randomActionsKeepTheSceneRendering(): TaoWindowTestCase {
        val fixture = TextureViewFixture()
        return TaoWindowTestCase(
            name =
                "texture view monkey $MONKEY_ACTIONS random actions keep the scene rendering " +
                    "on the shared GPU context",
            timeoutMillis = MONKEY_CASE_TIMEOUT_MILLIS,
            windowState =
                WindowState(
                    position = WindowPosition.Absolute(WINDOW_X_DP.dp, WINDOW_Y_DP.dp),
                    size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
                ),
            size = DpSize(WINDOW_W_DP.dp, WINDOW_H_DP.dp),
            paintDefaultBackground = false,
            content = { fixture.Content() },
            driver = {
                fixture.awaitReady(this)
                val monkey = TextureViewMonkey(this, fixture, monkeySeed())
                try {
                    monkey.run()
                    monkey.quiesceAndAssert()
                } finally {
                    fixture.shutdown()
                }
            },
        )
    }
}

/** A foreign producer behind one interface, whichever platform made it. */
private class TestProducer(
    val source: TextureViewSource,
    val kind: String,
    private val draw: (tick: Int, backgroundArgb: Int) -> Unit,
    private val closeProducer: () -> Unit,
) {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    /** Draws a frame unless closed; producers serialize draw and close themselves. */
    fun drawFrame(tick: Int) {
        if (!closed.get()) draw(tick, PRODUCER_BACKGROUND_ARGB)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) closeProducer()
    }

    companion object {
        /** The first producer this platform can make, or null. */
        fun create(
            widthPx: Int,
            heightPx: Int,
            variant: Int,
        ): TestProducer? {
            D3D11TestTextureProducer.create(widthPx, heightPx, useKeyedMutex = variant % 2 == 0)?.let {
                return TestProducer(it.source, "D3D11", it::drawTestPattern, it::close)
            }
            MetalTestTextureProducer.create(widthPx, heightPx)?.let {
                return TestProducer(it.source, "IOSurface", it::drawTestPattern, it::close)
            }
            val planar = variant % PRODUCER_VARIANTS == PLANAR_VARIANT
            val dmaBuf =
                if (planar) {
                    DmaBufTestTextureProducer.createYuv(widthPx, heightPx)
                } else {
                    DmaBufTestTextureProducer.create(widthPx, heightPx)
                }
            dmaBuf?.let {
                return TestProducer(
                    it.source,
                    if (planar) "DMA-BUF I420" else "DMA-BUF",
                    it::drawTestPattern,
                    it::close,
                )
            }
            return null
        }
    }
}

/**
 * One [TextureView] slot: what it shows, how, and the producer thread feeding
 * it. The producer is swapped and closed independently of the view on purpose
 * — those orderings are the interesting ones.
 */
private class Slot(
    val index: Int,
) {
    var mounted by mutableStateOf(true)
    var producer by mutableStateOf<TestProducer?>(null)
    var sizeDp by mutableStateOf(DpSize(SLOT_W_DP.dp, SLOT_H_DP.dp))
    var filterQuality by mutableStateOf(FilterQuality.Low)
    var contentScale by mutableStateOf<ContentScale>(ContentScale.FillBounds)
    val controller = TextureViewController()

    /** Frames the producer thread published. */
    val producedFrames = AtomicLong()
}

private class TextureViewFixture {
    val slots = List(SLOT_COUNT) { Slot(it) }
    var sharedRendererMounted by mutableStateOf(true)

    /** The scene's GPU context as last published; a new instance means a rebuild. */
    var renderContext: TaoGpuRenderContext? = null
        private set
    val contextGenerations = AtomicInteger()

    /** Frames the scene rendered (the heartbeat) and the shared renderer produced. */
    val renderedFrames = AtomicLong()
    val sharedFrames = AtomicLong()

    /** Whatever a producer thread or the shared renderer threw. */
    val errors = CopyOnWriteArrayList<Throwable>()

    private val stopProducers = AtomicBoolean(false)
    private val producerThreads = mutableListOf<Thread>()
    private val producersMade = AtomicInteger()

    /** Read in `drawBehind` so every frame tick invalidates the draw and the clock keeps running. */
    private var heartbeatTick by mutableLongStateOf(0L)

    var producerKind: String? = null
        private set

    fun newProducer(): TestProducer? {
        val variant = producersMade.getAndIncrement()
        val producer = TestProducer.create(PRODUCER_W_PX, PRODUCER_H_PX, variant) ?: return null
        producerKind = producer.kind
        return producer
    }

    fun startProducers() {
        for (slot in slots) {
            producerThreads +=
                thread(isDaemon = true, name = "texture-monkey-producer-${slot.index}") {
                    val random = Random(slot.index.toLong())
                    var tick = 0
                    try {
                        while (!stopProducers.get()) {
                            val producer = slot.producer
                            if (producer != null && !producer.isClosed) {
                                producer.drawFrame(tick++)
                                slot.controller.markFrameAvailable()
                                slot.producedFrames.incrementAndGet()
                            }
                            Thread.sleep(MIN_PRODUCER_PERIOD_MILLIS + random.nextLong(PRODUCER_PERIOD_SPAN_MILLIS))
                        }
                    } catch (_: InterruptedException) {
                        // shutdown
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
        }
    }

    fun shutdown() {
        stopProducers.set(true)
        for (t in producerThreads) t.interrupt()
        for (t in producerThreads) t.join(PRODUCER_JOIN_MILLIS)
        for (slot in slots) slot.producer?.close()
    }

    @Composable
    fun Content() {
        val context = rememberTaoGpuRenderContext()
        SideEffect {
            if (context !== renderContext) {
                renderContext = context
                if (context != null) contextGenerations.incrementAndGet()
            }
        }
        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameNanos { renderedFrames.incrementAndGet() }
                heartbeatTick++
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(BACKDROP_ARGB))
                .drawBehind {
                    // The read is the point: it ties the draw to the heartbeat.
                    if (heartbeatTick < 0L) drawRect(Color.Red)
                },
        ) {
            Column(Modifier.fillMaxSize().padding(PAD_DP.dp)) {
                for (row in 0 until SLOT_ROWS) {
                    Row {
                        for (column in 0 until SLOT_COLUMNS) {
                            val slot = slots[row * SLOT_COLUMNS + column]
                            Box(Modifier.padding(PAD_DP.dp)) {
                                if (slot.mounted) {
                                    TextureView(
                                        source = slot.producer?.source,
                                        modifier = Modifier.size(slot.sizeDp).background(Color(SLOT_ARGB)),
                                        controller = slot.controller,
                                        filterQuality = slot.filterQuality,
                                        contentScale = slot.contentScale,
                                    )
                                } else {
                                    Box(Modifier.size(slot.sizeDp).background(Color(EMPTY_SLOT_ARGB)))
                                }
                            }
                        }
                    }
                }
                if (sharedRendererMounted && context != null) {
                    SharedContextCanvas(context)
                }
            }
        }
    }

    /**
     * The in-process renderer of the GPU-context demo, reduced to what the
     * monkey needs: a render target on the scene's own Skia context, one
     * snapshot per frame, freed inside a later frame's GPU scope.
     */
    @Composable
    private fun SharedContextCanvas(context: TaoGpuRenderContext) {
        val renderer = remember(context) { SceneContextRenderer(context) }
        var frame by remember(context) { mutableStateOf<Image?>(null) }
        DisposableEffect(renderer) {
            onDispose { renderer.close() }
        }
        LaunchedEffect(renderer) {
            var tick = 0
            while (isActive) {
                val next =
                    try {
                        withFrameNanos { renderer.renderFrame(tick) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        // The renderer left the composition — not a failure.
                        throw cancelled
                    } catch (t: Throwable) {
                        errors += t
                        throw t
                    } ?: continue
                frame?.let(renderer::retire)
                frame = next
                sharedFrames.incrementAndGet()
                tick++
            }
        }
        Canvas(Modifier.padding(PAD_DP.dp).size(SHARED_W_DP.dp, SHARED_H_DP.dp).background(Color(SLOT_ARGB))) {
            val image = frame ?: return@Canvas
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(image, Rect.makeWH(size.width, size.height))
            }
        }
    }

    suspend fun awaitReady(scope: TaoWindowTestScope) {
        with(scope) {
            awaitUntil("the case window is mapped with a real frame") { window.hasRealFramePx() }
            awaitUntil("the scene published its GPU context") { renderContext != null }
            for (slot in slots) slot.producer = newProducer()
            startProducers()
            settle(SETTLE_AFTER_MAP_MILLIS)
            awaitUntil("the scene renders frames") { renderedFrames.get() > 0L }
            System.err.println(
                "[texture-monkey] backend=${renderContext?.backend} producers=" +
                    (producerKind ?: "none (null sources; the shared-context renderer carries the GPU half)"),
            )
        }
    }

    /** Whether any TextureView import is alive on the current context. */
    fun hasImports(): Boolean {
        val context = renderContext?.skiaContext ?: return false
        return when (Platform.Current) {
            Platform.Linux -> hasGlTextureImports(context)
            Platform.Windows -> hasWindowsTextureImports(context)
            Platform.MacOS -> hasMetalTextureImports(context)
            else -> false
        }
    }

    fun describe(): String =
        "context=${renderContext?.let { System.identityHashCode(it).toString(HEX) }} " +
            "generations=${contextGenerations.get()} rendered=${renderedFrames.get()} shared=${sharedFrames.get()} " +
            "sharedMounted=$sharedRendererMounted producers=$producerKind errors=${errors.size} " +
            slots.joinToString(prefix = "slots=[", postfix = "]") {
                "${it.index}:${if (it.mounted) "mounted" else "unmounted"}/" +
                    "${it.producer?.let { p -> if (p.isClosed) "closed" else "live" } ?: "none"}/" +
                    "${it.sizeDp.width.value.toInt()}x${it.sizeDp.height.value.toInt()}/frames=${it.producedFrames.get()}"
            }
}

/** The GPU-context demo's renderer: see `GpuContextSection` in the tao demo. */
private class SceneContextRenderer(
    private val context: TaoGpuRenderContext,
) : AutoCloseable {
    private var surface: Surface? = null
    private val retired = ArrayDeque<Image>()
    private val paint = Paint()

    private fun <T> withGpuAccess(action: () -> T): T? =
        when (context) {
            is TaoOpenGlRenderContext -> context.withContextCurrent(action)
            else -> context.runOnGpuThread(action)
        }

    fun renderFrame(tick: Int): Image? =
        withGpuAccess {
            val target =
                surface
                    ?: Surface
                        .makeRenderTarget(context.skiaContext, false, ImageInfo.makeN32Premul(RT_W, RT_H))
                        .also { surface = it }
            val canvas = target.canvas
            canvas.clear(HUE_BASE_ARGB + (tick % HUE_SPAN) * HUE_STEP)
            paint.color = WHITE_ARGB
            canvas.drawCircle(
                RT_W / 2f + (RT_W / 3f) * kotlin.math.cos(tick / TICKS_PER_RADIAN).toFloat(),
                RT_H / 2f + (RT_H / 3f) * kotlin.math.sin(tick / TICKS_PER_RADIAN).toFloat(),
                DOT_RADIUS,
                paint,
            )
            target.flushAndSubmit()
            val snapshot = target.makeImageSnapshot()
            while (retired.size > RETIRED_KEPT) retired.removeFirst().close()
            snapshot
        }

    fun retire(image: Image) {
        retired.addLast(image)
    }

    override fun close() {
        withGpuAccess {
            while (retired.isNotEmpty()) retired.removeFirst().close()
            surface?.close()
            surface = null
        }
        paint.close()
    }

    private companion object {
        const val RT_W = 256
        const val RT_H = 192
        const val HUE_BASE_ARGB = 0xFF203040.toInt()
        const val HUE_SPAN = 64
        const val HUE_STEP = 0x010203
        const val WHITE_ARGB = 0xFFFFFFFF.toInt()
        const val TICKS_PER_RADIAN = 30.0
        const val DOT_RADIUS = 20f
        const val RETIRED_KEPT = 2
    }
}

private enum class TextureAction {
    MountSlot,
    UnmountSlot,

    /** A fresh producer for a slot; the old one is closed after the swap has composed. */
    SwapProducer,

    /** Closes a slot's producer while its view is still composing it. */
    CloseProducerUnderView,
    ResizeSlot,
    ChangeFilter,
    ChangeContentScale,

    /** Fifty frame signals from an IO thread with no drawing in between. */
    BurstFrames,
    ToggleSharedRenderer,
    ResizeWindow,
    ToggleMaximize,

    /** Hides and shows the window; on Wayland this rebuilds the whole EGL stack. */
    HideShow,
    MinimizeRestore,
    ChangeDpi,
    RedrawStorm,
}

private class TextureViewMonkey(
    private val scope: TaoWindowTestScope,
    private val fixture: TextureViewFixture,
    seed: Long,
) {
    private val random = Random(seed)
    private val journal = MonkeyJournal("texture-monkey", seed)
    private var worstStallMillis = 0L

    /** Windows alive when the run started: earlier cases may have left some behind, they are not this run's. */
    private val windowsAtStart = TaoApplication.liveWindowCount()

    suspend fun run() {
        System.err.println(
            "[texture-monkey] seed=${journal.seed} actions=$MONKEY_ACTIONS " +
                "(replay with -D$MONKEY_SEED_PROPERTY=${journal.seed})",
        )
        val watchdog = MainLoopWatchdog("texture-monkey", journal::report).start()
        try {
            while (journal.step < MONKEY_ACTIONS) {
                val action = TextureAction.entries[random.nextInt(TextureAction.entries.size)]
                journal.record(action)
                monkeyAction({ journal.failure("$action", fixture.describe()) }) { apply(action) }
                checkNoErrors()
                if ((journal.step + 1) % CHECKPOINT_EVERY == 0) checkpoint()
                journal.step++
            }
        } finally {
            worstStallMillis = watchdog.stop()
        }
    }

    suspend fun quiesceAndAssert() {
        restoreWindow()
        for (slot in fixture.slots) slot.mounted = true
        fixture.sharedRendererMounted = true
        scope.settle(SETTLE_AFTER_MAP_MILLIS)
        expectRendering("after the monkey")

        // Every view gone: nothing may still be imported on the context.
        for (slot in fixture.slots) slot.mounted = false
        converge("no texture import is left once every view is unmounted") { !fixture.hasImports() }
        for (slot in fixture.slots) {
            slot.producer?.close()
            slot.producer = null
        }
        scope.settle(SETTLE_AFTER_MAP_MILLIS)
        expectRendering("with every view gone")
        checkNoErrors()

        check(TaoApplication.liveWindowCount() == windowsAtStart) {
            "${TaoApplication.liveWindowCount()} native windows are alive, $windowsAtStart when the run started"
        }
        System.err.println(
            "[texture-monkey] seed=${journal.seed} survived $MONKEY_ACTIONS actions; " +
                "worst main-dispatcher round trip ${worstStallMillis}ms; context generations " +
                "${fixture.contextGenerations.get()}; rendered ${fixture.renderedFrames.get()} frames, " +
                "shared renderer ${fixture.sharedFrames.get()}; reached ${journal.reachedSummary()}",
        )
        check(worstStallMillis <= MONKEY_MAX_STALL_MILLIS) {
            "the main dispatcher took ${worstStallMillis}ms to answer a heartbeat — the loop stalled"
        }
        check(journal.reachedCount("hideShow") > 0) { "the run never hid the window" }
        check(journal.reachedCount("swapped") > 0) { "the run never swapped a producer" }
        check(fixture.sharedFrames.get() > 0L) { "the shared-context renderer never produced a frame" }
    }

    private suspend fun apply(action: TextureAction) {
        val slot = fixture.slots[random.nextInt(fixture.slots.size)]
        when (action) {
            TextureAction.MountSlot -> slot.mounted = true
            TextureAction.UnmountSlot -> slot.mounted = false
            TextureAction.SwapProducer -> {
                val old = slot.producer
                slot.producer = fixture.newProducer()
                scope.settle(STEP_SETTLE_MILLIS)
                old?.close()
                journal.reach("swapped")
            }
            TextureAction.CloseProducerUnderView -> {
                slot.producer?.close()
                journal.reach("closedUnderView")
            }
            TextureAction.ResizeSlot ->
                slot.sizeDp =
                    DpSize(
                        (MIN_SLOT_DP + random.nextInt(SLOT_SPAN_DP)).dp,
                        (MIN_SLOT_DP + random.nextInt(SLOT_SPAN_DP)).dp,
                    )
            TextureAction.ChangeFilter -> slot.filterQuality = FILTERS[random.nextInt(FILTERS.size)]
            TextureAction.ChangeContentScale -> slot.contentScale = CONTENT_SCALES[random.nextInt(CONTENT_SCALES.size)]
            TextureAction.BurstFrames ->
                withContext(Dispatchers.IO) {
                    repeat(BURST_FRAMES) { slot.controller.markFrameAvailable() }
                }
            TextureAction.ToggleSharedRenderer -> fixture.sharedRendererMounted = !fixture.sharedRendererMounted
            TextureAction.ResizeWindow ->
                scope.window.setInnerSize(
                    MIN_INNER_W_DP + random.nextDouble(INNER_W_SPAN_DP),
                    MIN_INNER_H_DP + random.nextDouble(INNER_H_SPAN_DP),
                )
            TextureAction.ToggleMaximize -> scope.window.setMaximized(!scope.window.isMaximized)
            TextureAction.HideShow -> {
                scope.window.hide()
                scope.settle(HIDE_MILLIS)
                scope.window.show()
                journal.reach("hideShow")
            }
            TextureAction.MinimizeRestore -> {
                scope.window.setMinimized(true)
                scope.settle(HIDE_MILLIS)
                scope.window.setMinimized(false)
                journal.reach("minimized")
            }
            TextureAction.ChangeDpi -> {
                val scale = SCALE_HOPS[random.nextInt(SCALE_HOPS.size)]
                scope.window.dispatch(TaoEventCode.SCALE_FACTOR_CHANGED, (scale * SCALE_MILLI).roundToInt(), 0)
            }
            TextureAction.RedrawStorm -> repeat(REDRAW_STORM) { scope.window.requestRedraw() }
        }
        scope.settle(STEP_SETTLE_MILLIS)
    }

    /** The scene must still be producing frames once the window is visible again. */
    private suspend fun checkpoint() {
        restoreWindow()
        expectRendering("checkpoint at step ${journal.step}")
    }

    private suspend fun restoreWindow() {
        scope.window.dispatch(
            TaoEventCode.SCALE_FACTOR_CHANGED,
            (scope.window.scaleFactor * SCALE_MILLI).roundToInt(),
            0,
        )
        scope.window.setMinimized(false)
        scope.window.setMaximized(false)
        scope.window.show()
        scope.window.setInnerSize(WINDOW_W_DP.toDouble(), WINDOW_H_DP.toDouble())
        scope.settle(STEP_SETTLE_MILLIS)
    }

    private suspend fun expectRendering(moment: String) {
        val rendered = fixture.renderedFrames.get()
        converge("$moment: the scene keeps rendering frames") {
            fixture.renderedFrames.get() >= rendered + HEARTBEAT_FRAMES
        }
        if (fixture.sharedRendererMounted) {
            val shared = fixture.sharedFrames.get()
            converge("$moment: the shared-context renderer keeps producing frames") {
                fixture.sharedFrames.get() >= shared + HEARTBEAT_FRAMES
            }
        }
        converge("$moment: the GPU context is published") { fixture.renderContext != null }
    }

    private fun checkNoErrors() {
        val first = fixture.errors.firstOrNull() ?: return
        throw IllegalStateException(
            journal.failure("a producer or the shared renderer threw: $first", fixture.describe()),
            first,
        )
    }

    private suspend fun converge(
        description: String,
        predicate: () -> Boolean,
    ) {
        scope.awaitUntil(
            description,
            timeoutMillis = CONVERGE_MILLIS,
            detail = { fixture.describe() },
            predicate = predicate,
        )
    }
}

private const val MONKEY_ACTIONS = 200
private const val CHECKPOINT_EVERY = 20
private const val MONKEY_CASE_TIMEOUT_MILLIS = 300_000L
private const val CONVERGE_MILLIS = 6_000L
private const val STEP_SETTLE_MILLIS = 25L
private const val HIDE_MILLIS = 120L
private const val HEARTBEAT_FRAMES = 3L
private const val BURST_FRAMES = 50
private const val REDRAW_STORM = 20

private const val SLOT_ROWS = 2
private const val SLOT_COLUMNS = 2
private const val SLOT_COUNT = SLOT_ROWS * SLOT_COLUMNS
private const val SLOT_W_DP = 240
private const val SLOT_H_DP = 150
private const val MIN_SLOT_DP = 40
private const val SLOT_SPAN_DP = 260
private const val SHARED_W_DP = 240
private const val SHARED_H_DP = 120
private const val PAD_DP = 6

private const val PRODUCER_W_PX = 320
private const val PRODUCER_H_PX = 200
private const val PRODUCER_VARIANTS = 3
private const val PLANAR_VARIANT = 2
private const val PRODUCER_BACKGROUND_ARGB = 0xFF1F2630.toInt()
private const val MIN_PRODUCER_PERIOD_MILLIS = 4L
private const val PRODUCER_PERIOD_SPAN_MILLIS = 28L
private const val PRODUCER_JOIN_MILLIS = 2_000L

private const val WINDOW_X_DP = 120
private const val WINDOW_Y_DP = 80
private const val WINDOW_W_DP = 760
private const val WINDOW_H_DP = 560
private const val MIN_INNER_W_DP = 300.0
private const val INNER_W_SPAN_DP = 600.0
private const val MIN_INNER_H_DP = 200.0
private const val INNER_H_SPAN_DP = 500.0

private val SCALE_HOPS = floatArrayOf(1f, 1.25f, 1.5f, 2f)
private const val SCALE_MILLI = 1000
private val FILTERS = listOf(FilterQuality.None, FilterQuality.Low, FilterQuality.Medium, FilterQuality.High)
private val CONTENT_SCALES = listOf(ContentScale.FillBounds, ContentScale.Fit, ContentScale.Crop, ContentScale.None)

private const val BACKDROP_ARGB = 0xFF2B2B2B
private const val SLOT_ARGB = 0xFF101418
private const val EMPTY_SLOT_ARGB = 0xFF555555
private const val HEX = 16
