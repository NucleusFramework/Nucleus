package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import dev.nucleusframework.window.tao.event.TaoDirectManipulationStatus
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.DmStream
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.GestureRecorder
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.Recording
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.SceneSize
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.ScrollProbe
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.ScrollableColumn
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.Transformable
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.Zoom
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.ctrlWheel
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.injectionSkipReason
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.isScale
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.replay
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.robot
import dev.nucleusframework.window.tao.headful.WindowsDirectManipulationHeadfulCases.stats
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/**
 * #706 torture: seeded random walks over everything that can reach a
 * window's touchpad input on Windows at once — touchpad pinches and pans (with
 * and without inertia) played through the real viewport pump, pan-to-pinch
 * turns, manipulations the viewport suspends or leaves open, garbage streams
 * (NaN, zero and negative scales, statuses out of order, unknown kinds), real
 * DirectManipulation runs of its own, and the mouse in the middle of it all:
 * clicks, moves, plain wheel notches and Ctrl+wheel ticks, plus window
 * resizes.
 *
 * A random walk has no single right answer, so the monkey checks what must
 * hold whatever happens: Compose never sees a scale gesture and a pan open at
 * once, starts and ends pair up, no factor or offset is NaN or infinite, every
 * gesture is closed once the input goes quiet, the event loop never stalls
 * ([MainLoopWatchdog]) — and for the well-formed gestures, the model: a pinch
 * zooms by the ratio the viewport reported, a pan scrolls the column by the
 * distance it reported (clamped to the column's range).
 *
 * `-Dnucleus.tao.headful.monkeySeed=<seed>` replays a red run, and
 * `-Dnucleus.tao.headful.dmMonkeySteps=<n>` lengthens one.
 */
internal object WindowsDirectManipulationMonkeyHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        SEEDS.map { offset -> monkey(seed = monkeySeed() + offset, label = "seed+$offset") }

    private fun monkey(
        seed: Long,
        label: String,
    ): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        val zoom = Zoom()
        val scroll = ScrollProbe()
        val steps = System.getProperty(STEPS_PROPERTY)?.toIntOrNull() ?: DEFAULT_STEPS
        return TaoWindowTestCase(
            name = "#706 touchpad monkey ($label): random pinches, pans, garbage and the mouse stay well-formed",
            skip = ::injectionSkipReason,
            timeoutMillis = steps * STEP_BUDGET_MILLIS + FIXED_BUDGET_MILLIS,
            paintDefaultBackground = false,
            content = { Arena(recorder, scene, zoom, scroll) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            awaitUntil("column measured") { (scroll.state?.maxValue ?: 0) > 0 }
            DmMonkey(this, recorder, scene, zoom, scroll, seed, steps).run()
        }
    }

    @Composable
    private fun Arena(
        recorder: GestureRecorder,
        scene: SceneSize,
        zoom: Zoom,
        scroll: ScrollProbe,
    ) {
        Recording(recorder, scene) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { Transformable(zoom, SceneSize()) }
                Box(Modifier.weight(1f).fillMaxHeight()) { ScrollableColumn(scroll) }
            }
        }
    }

    private enum class Action {
        PINCH,
        PAN,
        FLING,
        PAN_THEN_PINCH,
        SUSPENDED,
        LEFT_OPEN,
        GARBAGE,
        REAL_MANIPULATION,
        CTRL_WHEEL,
        PLAIN_WHEEL,
        CLICK,
        MOVE,
        RESIZE,
        IDLE,
    }

    private class DmMonkey(
        private val scope: TaoWindowTestScope,
        private val recorder: GestureRecorder,
        private val scene: SceneSize,
        private val zoom: Zoom,
        private val scroll: ScrollProbe,
        seed: Long,
        private val steps: Int,
    ) {
        private val random = Random(seed)
        private val journal = MonkeyJournal("dm-monkey", seed)
        private val robotDriver = RobotPointerDriver(scope.window) { scene.value }
        private val script = monkeyScript()
        private var stream = DmStream(Offset.Zero)

        suspend fun run() {
            System.err.println("[dm-monkey] seed=${journal.seed} steps=$steps")
            val watchdog = MainLoopWatchdog("dm-monkey") { journal.report() }.start()
            try {
                arm()
                for (step in 0 until (script?.size ?: steps)) {
                    journal.step = step
                    val action = script?.let { Action.valueOf(it[step]) } ?: pick()
                    journal.record(action)
                    journal.reach(action.name)
                    monkeyAction({ "step $step $action" }) { perform(action) }
                    checkWellFormed()
                }
                quiesce()
            } finally {
                NativeTaoBridge.nativeDiagDirectManipulationWheelFocus(false)
                val worst = watchdog.stop()
                check(worst < MONKEY_MAX_STALL_MILLIS) {
                    journal.failure("Dispatchers.Main stalled for ${worst}ms", state())
                }
            }
            System.err.println(
                "[dm-monkey] seed=${journal.seed} survived $steps steps; reached ${journal.reachedSummary()}",
            )
        }

        /** Takes the foreground so Robot wheel notches reach this window. */
        private suspend fun arm() {
            val centre = Offset(scene.value.width / 2f, scene.value.height / 2f)
            robotDriver.moveTo(centre)
            scope.window.focus()
            robotDriver.click(centre)
            scope.settle()
            recorder.reset()
        }

        private fun pick(): Action {
            val weights = WEIGHTS
            var roll = random.nextInt(weights.values.sum())
            for ((action, weight) in weights) {
                roll -= weight
                if (roll < 0) return action
            }
            return Action.IDLE
        }

        private suspend fun perform(action: Action) {
            when (action) {
                Action.PINCH -> pinch()
                Action.PAN -> pan(fling = false)
                Action.FLING -> pan(fling = true)
                Action.PAN_THEN_PINCH -> panThenPinch()
                Action.SUSPENDED -> suspended()
                Action.LEFT_OPEN -> leftOpen()
                Action.GARBAGE -> garbage()
                Action.REAL_MANIPULATION -> realManipulation()
                Action.CTRL_WHEEL -> {
                    robot { ctrlWheel(it) }
                    scope.settle(WHEEL_SETTLE_MILLIS)
                }
                Action.PLAIN_WHEEL -> {
                    robot { it.mouseWheel(if (random.nextBoolean()) 1 else -1) }
                    // Out of the smooth-scroll tween before a pan measures the column.
                    scope.settle(WHEEL_SETTLE_MILLIS)
                }
                Action.CLICK -> robotDriver.click(randomPoint())
                Action.MOVE -> robotDriver.moveTo(randomPoint())
                Action.RESIZE -> resize()
                Action.IDLE -> scope.settle(random.nextLong(IDLE_MAX_MILLIS))
            }
        }

        // ── Well-formed gestures, checked against the model ─────────────────

        private suspend fun pinch() {
            val onZoom = zoomPoint()
            begin(onZoom)
            val steps = 1 + random.nextInt(MAX_GESTURE_STEPS)
            val target = 2.0.pow(random.nextDouble(-1.5, 1.5)).toFloat()
            val step = target.toDouble().pow(1.0 / steps).toFloat()
            val before = zoom.value
            val mark = recorder.snapshot().size
            repeat(steps) {
                stream.scale *= step
                maybeJitter()
                replay(stream.content())
            }
            end()
            awaitClosed("pinch")
            val expected = before * stream.lastScale
            val factors = recorder.snapshot().drop(mark).filter { it.type.isScale() }
            val product = factors.fold(1.0) { acc, e -> acc * e.scaleFactor }
            check(abs(product / stream.lastScale - 1.0) < RATIO_TOLERANCE) {
                journal.failure("a pinch of ${stream.lastScale} reached Compose as $product", state())
            }
            check(abs(zoom.value / expected - 1f) < RATIO_TOLERANCE) {
                journal.failure("transformable zoom ${zoom.value}, expected $expected", state())
            }
            stream.reset()
        }

        private suspend fun pan(fling: Boolean) {
            begin(columnPoint())
            val before = scroll.value()
            var travelled = 0f
            val direction = if (random.nextBoolean()) 1f else -1f
            repeat(1 + random.nextInt(MAX_GESTURE_STEPS)) {
                val dy = direction * random.nextInt(1, MAX_PAN_STEP_PX)
                stream.y -= dy
                travelled += dy
                replay(stream.content())
            }
            if (fling) {
                replay(stream.status(TaoDirectManipulationStatus.INERTIA))
                var glide = direction * MAX_PAN_STEP_PX / 2f
                while (abs(glide) > GLIDE_STOP_PX) {
                    glide *= GLIDE_DECAY
                    stream.y -= glide
                    travelled += glide
                    replay(stream.content())
                }
            }
            end()
            awaitClosed("pan")
            // The router closes a pan without inertia after its grace; Compose
            // may then add a fling of its own — only the gliding pan is exact.
            if (fling) {
                scope.settle(FLING_SETTLE_MILLIS)
                val max = scroll.state?.maxValue ?: 0
                val expected = (before + travelled).coerceIn(0f, max.toFloat()) - before
                val moved = scroll.value() - before
                check(abs(moved - expected) < SCROLL_TOLERANCE_PX) {
                    journal.failure("a pan of $travelled px (expected $expected) moved the column $moved px", state())
                }
            }
            stream.reset()
        }

        private suspend fun panThenPinch() {
            begin(zoomPoint())
            repeat(1 + random.nextInt(4)) {
                stream.x += random.nextInt(-8, 9)
                stream.y += random.nextInt(1, 9)
                replay(stream.content())
            }
            repeat(1 + random.nextInt(MAX_GESTURE_STEPS)) {
                stream.scale *= random.nextDouble(0.9, 1.12).toFloat()
                stream.x += random.nextInt(-2, 3)
                replay(stream.content())
            }
            end()
            awaitClosed("pan then pinch")
            stream.reset()
        }

        private suspend fun suspended() {
            begin(zoomPoint())
            repeat(1 + random.nextInt(6)) {
                if (random.nextBoolean()) stream.scale *= 1.05f else stream.y += 5f
                replay(stream.content())
            }
            val takenAway = TAKEN_AWAY[random.nextInt(TAKEN_AWAY.size)]
            replay(stream.status(takenAway))
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitClosed("suspended")
            stream.reset()
        }

        /** A gesture whose end never comes: the next sequence's RUNNING must close it. */
        private suspend fun leftOpen() {
            begin(if (random.nextBoolean()) zoomPoint() else columnPoint())
            repeat(1 + random.nextInt(5)) {
                if (random.nextBoolean()) stream.scale *= 1.03f else stream.y -= 7f
                replay(stream.content())
            }
            // No READY: the next RUNNING (or the quiesce) closes it.
        }

        /** Streams no viewport produces: must be survived, not interpreted into NaNs. */
        private suspend fun garbage() {
            repeat(1 + random.nextInt(GARBAGE_EVENTS)) {
                val kind = if (random.nextInt(8) == 0) random.nextInt(-2, 5) else random.nextInt(2)
                val status = random.nextInt(-1, 8)
                val previous = random.nextInt(-1, 8)
                val scale = GARBAGE_SCALES[random.nextInt(GARBAGE_SCALES.size)]
                val offset = GARBAGE_OFFSETS[random.nextInt(GARBAGE_OFFSETS.size)]
                val point = randomPoint()
                val event =
                    floatArrayOf(
                        kind.toFloat(),
                        status.toFloat(),
                        previous.toFloat(),
                        scale,
                        offset,
                        -offset,
                        point.x,
                        point.y,
                    )
                replay(event)
            }
            // Whatever the garbage left open, a clean READY closes it.
            stream = DmStream(randomPoint())
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitClosed("garbage")
        }

        /** DirectManipulation itself runs a manipulation (a wheel notch handed to it). */
        private suspend fun realManipulation() {
            NativeTaoBridge.nativeDiagDirectManipulationWheelFocus(true)
            try {
                robot { it.mouseWheel(if (random.nextBoolean()) 2 else -2) }
                scope.awaitUntilOrTimeout(REAL_SETTLE_MILLIS) {
                    scope.stats()[WindowsDirectManipulationHeadfulCases.STAT_STATUS] ==
                        TaoDirectManipulationStatus.READY &&
                        scope.stats()[WindowsDirectManipulationHeadfulCases.STAT_RESETTING] == 0
                }
            } finally {
                NativeTaoBridge.nativeDiagDirectManipulationWheelFocus(false)
            }
        }

        private suspend fun resize() {
            val width = random.nextInt(MIN_SIZE_DP, MAX_SIZE_DP).toDouble()
            val height = random.nextInt(MIN_SIZE_DP, MAX_SIZE_DP).toDouble()
            scope.window.setInnerSize(width, height)
            scope.settle(RESIZE_SETTLE_MILLIS)
        }

        // ── Stream helpers ──────────────────────────────────────────────────

        private suspend fun begin(focal: Offset) {
            stream = DmStream(focal)
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
        }

        private suspend fun end() {
            replay(stream.status(TaoDirectManipulationStatus.READY))
        }

        private suspend fun replay(event: FloatArray) = with(scope) { replay(event) }

        private fun maybeJitter() {
            if (random.nextInt(4) == 0) stream.x += random.nextInt(-2, 3)
        }

        private val DmStream.lastScale: Float get() = scale

        private suspend fun awaitClosed(what: String) {
            val closed =
                scope.awaitUntilOrTimeout(CLOSE_MILLIS) {
                    val events = recorder.snapshot()
                    balanced(events, PointerEventType.ScaleStart, PointerEventType.ScaleEnd) &&
                        balanced(events, PointerEventType.PanStart, PointerEventType.PanEnd)
                }
            check(closed) { journal.failure("the $what left a gesture open", state()) }
        }

        private suspend fun quiesce() {
            // Close whatever LEFT_OPEN left behind the way the platform would.
            if (stream.status != TaoDirectManipulationStatus.READY) end()
            awaitClosed("run")
        }

        // ── Invariants ──────────────────────────────────────────────────────

        private fun checkWellFormed() {
            var scaleOpen = false
            var panOpen = false
            for (event in recorder.snapshot()) {
                when (event.type) {
                    PointerEventType.ScaleStart -> {
                        check(!scaleOpen) { journal.failure("ScaleStart inside an open scale gesture", state()) }
                        check(!panOpen) { journal.failure("ScaleStart while a pan is open", state()) }
                        scaleOpen = true
                    }
                    PointerEventType.ScaleChange -> {
                        check(scaleOpen) { journal.failure("ScaleChange outside a scale gesture", state()) }
                        check(event.scaleFactor.isFinite() && event.scaleFactor > 0f) {
                            journal.failure("ScaleChange factor ${event.scaleFactor}", state())
                        }
                    }
                    PointerEventType.ScaleEnd -> {
                        check(scaleOpen) { journal.failure("ScaleEnd without ScaleStart", state()) }
                        scaleOpen = false
                    }
                    PointerEventType.PanStart -> {
                        check(!panOpen) { journal.failure("PanStart inside an open pan", state()) }
                        check(!scaleOpen) { journal.failure("PanStart while a scale gesture is open", state()) }
                        panOpen = true
                    }
                    PointerEventType.PanMove -> {
                        check(panOpen) { journal.failure("PanMove outside a pan", state()) }
                        check(event.panOffset.x.isFinite() && event.panOffset.y.isFinite()) {
                            journal.failure("PanMove offset ${event.panOffset}", state())
                        }
                    }
                    PointerEventType.PanEnd -> {
                        check(panOpen) { journal.failure("PanEnd without PanStart", state()) }
                        panOpen = false
                    }
                    else -> Unit
                }
                check(event.position.x.isFinite() && event.position.y.isFinite()) {
                    journal.failure("${event.type} at ${event.position}", state())
                }
            }
            check(zoom.value.isFinite() && zoom.value > 0f) { journal.failure("zoom ${zoom.value}", state()) }
        }

        private fun balanced(
            events: List<WindowsDirectManipulationHeadfulCases.Recorded>,
            start: PointerEventType,
            end: PointerEventType,
        ): Boolean = events.count { it.type == start } == events.count { it.type == end }

        private fun state(): String =
            "zoom=${zoom.value} scroll=${scroll.value()} stats=${scope.stats().contentToString()} " +
                "last events=${recorder.snapshot().takeLast(EVENTS_IN_REPORT)}"

        // ── Geometry ────────────────────────────────────────────────────────

        private fun zoomPoint(): Offset =
            Offset(
                random.nextFloat() * scene.value.width * 0.45f + 2f,
                random.nextFloat() * (scene.value.height - 4f) + 2f,
            )

        private fun columnPoint(): Offset =
            Offset(
                scene.value.width * (0.55f + random.nextFloat() * 0.4f),
                random.nextFloat() * (scene.value.height - 4f) + 2f,
            )

        private fun randomPoint(): Offset =
            Offset(
                random.nextFloat() * (scene.value.width - 4f) + 2f,
                random.nextFloat() * (scene.value.height - 4f) + 2f,
            )
    }

    private val WEIGHTS =
        linkedMapOf(
            Action.PINCH to 14,
            Action.PAN to 8,
            Action.FLING to 10,
            Action.PAN_THEN_PINCH to 6,
            Action.SUSPENDED to 5,
            Action.LEFT_OPEN to 5,
            Action.GARBAGE to 6,
            Action.REAL_MANIPULATION to 4,
            Action.CTRL_WHEEL to 6,
            Action.PLAIN_WHEEL to 5,
            Action.CLICK to 5,
            Action.MOVE to 5,
            Action.RESIZE to 3,
            Action.IDLE to 4,
        )

    private val TAKEN_AWAY = intArrayOf(TaoDirectManipulationStatus.SUSPENDED, TaoDirectManipulationStatus.DISABLED)
    private val GARBAGE_SCALES =
        floatArrayOf(1f, 1.3f, 0.7f, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 1e-30f, 1e30f, Float.MIN_VALUE)
    private val GARBAGE_OFFSETS = floatArrayOf(0f, 12f, -300f, Float.NaN, Float.NEGATIVE_INFINITY, 1e9f)

    /** Three walks per run, from the (overridable) monkey seed. */
    private val SEEDS = listOf(0L, 1L, 2L)
    private const val STEPS_PROPERTY = "nucleus.tao.headful.dmMonkeySteps"
    private const val DEFAULT_STEPS = 120
    private const val STEP_BUDGET_MILLIS = 1_500L
    private const val FIXED_BUDGET_MILLIS = 30_000L

    private const val MAX_GESTURE_STEPS = 16
    private const val MAX_PAN_STEP_PX = 20
    private const val GLIDE_DECAY = 0.8f
    private const val GLIDE_STOP_PX = 0.3f
    private const val GARBAGE_EVENTS = 12
    private const val IDLE_MAX_MILLIS = 250L
    private const val WHEEL_SETTLE_MILLIS = 300L
    private const val CLOSE_MILLIS = 3_000L
    private const val REAL_SETTLE_MILLIS = 3_000L
    private const val FLING_SETTLE_MILLIS = 600L
    private const val RESIZE_SETTLE_MILLIS = 150L
    private const val MIN_SIZE_DP = 420
    private const val MAX_SIZE_DP = 900
    private const val RATIO_TOLERANCE = 2e-3
    private const val SCROLL_TOLERANCE_PX = 8f
    private const val EVENTS_IN_REPORT = 12
}
