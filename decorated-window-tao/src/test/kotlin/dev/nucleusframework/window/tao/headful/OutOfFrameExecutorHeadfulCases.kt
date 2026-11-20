package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.random.Random

/**
 * Real-window torture and benchmark of `TaoOutOfFrameExecutor` (the
 * `PlatformOutOfFrameExecutor` that lets `SubcomposeLayout` deactivate a slot
 * scrolled out of view after the frame instead of inside it).
 *
 * Every case drives a `LazyColumn` from the frame clock itself
 * (`dispatchRawDelta` in `withFrameNanos`), so the scroll is exactly one step
 * per rendered frame on the real Tao loop. Items carry a `DisposableEffect`
 * whose disposal costs [DISPOSE_SPIN_MICROS] of CPU, standing for the
 * listeners and resources a real row releases, and each row holds a nested
 * `LazyRow` so deactivations nest (a drained block schedules more).
 *
 * Correctness oracle, checked after the walk has settled: every visible row
 * is live, no live row is far outside the viewport (a deferred deactivation
 * that never ran), and no row was disposed more often than it was entered.
 *
 * Measurement: per frame, the time from the frame clock tick to the draw of
 * an overlay drawn after the list (`work`, the part of the frame the executor
 * moves deactivations out of) and the tick-to-tick interval (`interval`, which
 * still pays for work drained between two frames). Printed as `[ooofe-bench]`
 * lines; compare runs with `-Dnucleus.tao.outOfFrameExecutor=true|false`:
 *
 * ```
 * ./gradlew :decorated-window-tao:taoHeadfulTest -Dnucleus.tao.headful.filter="out-of-frame" \
 *   -Dnucleus.tao.outOfFrameExecutor=false
 * ```
 */
internal object OutOfFrameExecutorHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            scrollCase(name = "out-of-frame: steady scroll", walk = Walk.Steady),
            scrollCase(name = "out-of-frame: monkey scroll", walk = Walk.Monkey),
            scrollCase(name = "out-of-frame: back-and-forth scroll", walk = Walk.BackAndForth),
            scrollCase(name = "out-of-frame: list dropped mid-scroll", walk = Walk.DropMidScroll),
        )

    private enum class Walk { Steady, Monkey, BackAndForth, DropMidScroll }

    /** Effect bookkeeping of the outer rows, written on the UI thread only. */
    private class Ledger {
        val entered = IntArray(ITEM_COUNT)
        val disposed = IntArray(ITEM_COUNT)
        var innerEntered = 0
        var innerDisposed = 0

        fun live(index: Int) = entered[index] > disposed[index]

        fun liveIndices(): List<Int> = (0 until ITEM_COUNT).filter(::live)

        fun overDisposed(): List<Int> = (0 until ITEM_COUNT).filter { disposed[it] > entered[it] }
    }

    /** Per-frame samples, written on the UI thread only. */
    private class FrameSamples {
        val work = LongArray(SAMPLE_CAPACITY)
        val interval = LongArray(SAMPLE_CAPACITY)
        var count = 0
        var tickNanos = 0L
        var lastTickNanos = 0L
        var recording = false

        /** Written on every tick and read by the overlay's draw, so the overlay redraws every frame. */
        val frameStamp = mutableLongStateOf(0L)

        fun onTick(now: Long) {
            if (recording && lastTickNanos != 0L && count < SAMPLE_CAPACITY) interval[count] = now - lastTickNanos
            lastTickNanos = now
            tickNanos = now
            frameStamp.longValue = now
        }

        fun onDrawn(now: Long) {
            if (!recording || tickNanos == 0L || count >= SAMPLE_CAPACITY) return
            work[count] = now - tickNanos
            count++
            tickNanos = 0L
        }

        fun report(tag: String): String {
            val n = count
            if (n < 2) return "[ooofe-bench] $tag: too few frames ($n)"
            val w = work.copyOf(n).sortedArray()
            val i = interval.copyOfRange(1, n).sortedArray()

            fun LongArray.ms(q: Double) = this[((size - 1) * q).toInt()] / NANOS_PER_MILLI
            val jank = i.count { it > JANK_INTERVAL_NANOS }
            return "[ooofe-bench] $tag frames=$n " +
                "work p50=%.2f p95=%.2f p99=%.2f max=%.2f ms | ".format(
                    Locale.ROOT,
                    w.ms(0.5),
                    w.ms(0.95),
                    w.ms(0.99),
                    w.ms(1.0),
                ) +
                "interval p50=%.2f p95=%.2f p99=%.2f max=%.2f ms jank=%d".format(
                    Locale.ROOT,
                    i.ms(0.5),
                    i.ms(0.95),
                    i.ms(0.99),
                    i.ms(1.0),
                    jank,
                )
        }
    }

    /** What the driver asks the frame loop to do next. */
    private class Steering {
        @Volatile var pixelsPerFrame = 0f

        @Volatile var jumpTo = -1

        @Volatile var frames = 0L
    }

    private fun spin(micros: Long) {
        val until = System.nanoTime() + micros * 1_000
        while (System.nanoTime() < until) Unit
    }

    @Composable
    private fun TortureList(
        state: LazyListState,
        ledger: Ledger,
        samples: FrameSamples,
        steering: Steering,
        listShown: MutableState<Boolean>,
    ) {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos {
                    samples.onTick(System.nanoTime())
                    steering.frames++
                    val jump = steering.jumpTo
                    if (jump >= 0) {
                        steering.jumpTo = -1
                        state.requestScrollToItem(jump)
                    } else if (steering.pixelsPerFrame != 0f) {
                        state.dispatchRawDelta(steering.pixelsPerFrame)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (listShown.value) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(count = ITEM_COUNT, key = { it }) { index ->
                        DisposableEffect(index) {
                            ledger.entered[index]++
                            onDispose {
                                spin(DISPOSE_SPIN_MICROS)
                                ledger.disposed[index]++
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)) {
                            Box(
                                Modifier.width(ROW_LABEL_DP.dp).height(ROW_HEIGHT_DP.dp).drawBehind {
                                    drawRect(if (index % 2 == 0) Color.Gray else Color.LightGray)
                                },
                            )
                            LazyRow(Modifier.fillMaxWidth().height(ROW_HEIGHT_DP.dp)) {
                                items(count = INNER_COUNT) { cell ->
                                    DisposableEffect(Unit) {
                                        ledger.innerEntered++
                                        onDispose { ledger.innerDisposed++ }
                                    }
                                    Box(
                                        Modifier.width(CELL_DP.dp).height(ROW_HEIGHT_DP.dp).drawBehind {
                                            drawRect(if ((index + cell) % 3 == 0) Color.DarkGray else Color.White)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Drawn after the list: its draw closes the frame's `work` sample.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (samples.frameStamp.longValue != 0L) samples.onDrawn(System.nanoTime())
                },
            )
        }
    }

    private fun scrollCase(
        name: String,
        walk: Walk,
    ): TaoWindowTestCase {
        val state = LazyListState()
        val ledger = Ledger()
        val samples = FrameSamples()
        val steering = Steering()
        val listShown = mutableStateOf(true)
        return TaoWindowTestCase(
            name = name,
            timeoutMillis = CASE_TIMEOUT_MILLIS,
            size = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp),
            // The default background is a sibling filling the window: the list would get no height.
            paintDefaultBackground = false,
            content = { TortureList(state, ledger, samples, steering, listShown) },
            driver = {
                awaitUntil("window mapped") { window.hasRealFramePx() }
                awaitUntil("rows composed") { ledger.liveIndices().isNotEmpty() }
                settle(WARMUP_MILLIS)
                val random = Random(monkeySeed())
                samples.recording = true
                when (walk) {
                    Walk.Steady -> {
                        steering.pixelsPerFrame = STEADY_PX_PER_FRAME
                        settle(MEASURE_MILLIS)
                    }
                    Walk.BackAndForth -> {
                        // Short reversals: rows scrolled away come back before
                        // the next frame, the case a deferred deactivation skips.
                        val until = System.currentTimeMillis() + MEASURE_MILLIS
                        var sign = 1f
                        while (System.currentTimeMillis() < until) {
                            steering.pixelsPerFrame = sign * STEADY_PX_PER_FRAME
                            sign = -sign
                            settle(REVERSAL_MILLIS)
                        }
                    }
                    Walk.Monkey -> {
                        val until = System.currentTimeMillis() + MEASURE_MILLIS
                        while (System.currentTimeMillis() < until) {
                            when (random.nextInt(4)) {
                                0 -> steering.jumpTo = random.nextInt(ITEM_COUNT)
                                else ->
                                    steering.pixelsPerFrame =
                                        random.nextInt(-MONKEY_MAX_PX, MONKEY_MAX_PX + 1).toFloat()
                            }
                            settle(random.nextLong(MONKEY_MIN_STEP_MILLIS, MONKEY_MAX_STEP_MILLIS))
                        }
                    }
                    Walk.DropMidScroll -> {
                        // The list leaves the composition right after frames that
                        // deferred deactivations: the queued blocks then target
                        // slots of a disposed SubcomposeLayout.
                        steering.pixelsPerFrame = STEADY_PX_PER_FRAME
                        repeat(DROP_ROUNDS) {
                            val target = steering.frames + random.nextLong(1, DROP_MAX_FRAMES)
                            awaitUntil("scroll frames") { steering.frames >= target }
                            listShown.value = false
                            awaitUntil("list dropped") { ledger.liveIndices().isEmpty() }
                            listShown.value = true
                            awaitUntil("list back") { ledger.liveIndices().isNotEmpty() }
                        }
                    }
                }
                steering.pixelsPerFrame = 0f
                samples.recording = false
                System.err.println(samples.report("$name executor=${executorMode()}"))

                settle(SETTLE_AFTER_WALK_MILLIS)
                checkLedger(name, state, ledger)
            },
        )
    }

    private fun checkLedger(
        name: String,
        state: LazyListState,
        ledger: Ledger,
    ) {
        val over = ledger.overDisposed()
        check(over.isEmpty()) { "$name: rows disposed more often than entered: $over" }
        check(ledger.innerDisposed <= ledger.innerEntered) {
            "$name: inner cells disposed ${ledger.innerDisposed} > entered ${ledger.innerEntered}"
        }
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        check(visible.isNotEmpty()) { "$name: nothing visible after the walk" }
        val live = ledger.liveIndices()
        val missing = visible.filterNot(ledger::live)
        check(missing.isEmpty()) { "$name: visible rows without a live effect: $missing" }
        val low = visible.min() - PREFETCH_MARGIN
        val high = visible.max() + PREFETCH_MARGIN
        val stale = live.filter { it < low || it > high }
        check(stale.isEmpty()) {
            "$name: rows still live far from the viewport $visible (deactivation never ran): $stale"
        }
        System.err.println(
            "[ooofe-oracle] $name OK live=${live.size} visible=${visible.size} " +
                "innerLive=${ledger.innerEntered - ledger.innerDisposed}",
        )
    }

    private fun executorMode(): String = System.getProperty("nucleus.tao.outOfFrameExecutor") ?: "default"

    private const val WINDOW_WIDTH_DP = 800
    private const val WINDOW_HEIGHT_DP = 600
    private const val ITEM_COUNT = 5_000
    private const val INNER_COUNT = 40
    private const val ROW_HEIGHT_DP = 24
    private const val ROW_LABEL_DP = 60
    private const val CELL_DP = 48
    private const val DISPOSE_SPIN_MICROS = 150L
    private const val STEADY_PX_PER_FRAME = 45f
    private const val MONKEY_MAX_PX = 160
    private const val MONKEY_MIN_STEP_MILLIS = 30L
    private const val MONKEY_MAX_STEP_MILLIS = 250L
    private const val REVERSAL_MILLIS = 60L
    private const val DROP_ROUNDS = 25
    private const val DROP_MAX_FRAMES = 6L
    private const val PREFETCH_MARGIN = 4
    private const val WARMUP_MILLIS = 800L
    private const val MEASURE_MILLIS = 6_000L
    private const val SETTLE_AFTER_WALK_MILLIS = 500L
    private const val CASE_TIMEOUT_MILLIS = 60_000L
    private const val SAMPLE_CAPACITY = 8_192
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val JANK_INTERVAL_NANOS = 25_000_000L
}
