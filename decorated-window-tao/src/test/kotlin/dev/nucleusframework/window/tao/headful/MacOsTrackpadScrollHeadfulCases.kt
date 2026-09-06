package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.headful.MacScrollWheelProbe.Momentum
import dev.nucleusframework.window.tao.headful.MacScrollWheelProbe.Phase
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * macOS trackpad / wheel parity with the AWT backend — issues #652, #653 and
 * #654. Every case injects real `scrollWheel:` NSEvents into the tao content
 * view ([MacScrollWheelProbe]) and observes what Compose receives at the
 * root, so the whole chain runs: tao `scroll_wheel` → JNI loop → `TaoWindow`
 * → scene host → `ComposeScene`.
 *
 * The AWT reference (OpenJDK `AWTView.m` + `CPlatformResponder`) is
 * `preciseWheelRotation = -[event deltaX/Y]`, where the legacy delta of a
 * precise (trackpad) event is `scrollingDelta × 0.1` in points — no display
 * scale anywhere. Compose Desktop's `MacOSCocoaConfig` then turns one unit
 * into `10.dp`, which is also the pixel amount a trackpad pan must carry in
 * `PointerInputChange.panOffset` for the two paths to move content equally.
 */
internal object MacOsTrackpadScrollHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            swipeLeftScrollsHorizontalContentForward(),
            preciseDeltasMatchAwtWithoutDisplayScale(),
            trackpadGestureArrivesAsPanAndWheelStaysScroll(),
            trackpadPanScrollsVerticalColumn(),
        )

    // ── #652 ────────────────────────────────────────────────────────────────

    /**
     * A two-finger swipe *left* reveals content on the right — the row's
     * scroll offset must grow, exactly as it does under AWT. Before the fix
     * the horizontal sign was inverted (tao already flips `scrollingDeltaX`
     * and the Kotlin side negated it again), so the row tried to scroll
     * *before* its start and never moved.
     */
    private fun swipeLeftScrollsHorizontalContentForward(): TaoWindowTestCase {
        val recorder = PointerRecorder()
        val scrollPx = AtomicInteger(0)
        val scrollMax = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#652 two-finger swipe left scrolls a horizontal row forward, like AWT",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Recording(recorder) { ScrollableRow(scrollPx, scrollMax) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("row has overflow") { scrollMax.get() > 0 }
            settle()
            recorder.reset()
            swipe(dx = -SWIPE_DELTA_PT, dy = 0f, steps = SWIPE_STEPS, momentum = false)
            waitFor(SCROLL_REACTION_MILLIS) { scrollPx.get() != 0 }
            check(scrollPx.get() > 0) {
                "swipe left (scrollingDeltaX < 0) must scroll the row forward as under AWT; " +
                    "offset=${scrollPx.get()} recorded=${recorder.describe()}"
            }
        }
    }

    // ── #653 (and the #652 sign on the plain Scroll path) ───────────────────

    /**
     * A precise scroll that is *not* part of a trackpad gesture (no phase —
     * e.g. a mouse with smooth-scroll firmware) stays a Compose `Scroll`
     * event and must carry AWT's `preciseWheelRotation`:
     * `-scrollingDelta / 10`, independent of the display scale. Before the
     * fix tao converted the delta to physical pixels first, so a Retina
     * display doubled it (2.0 instead of 1.0) and X still had the wrong sign.
     */
    private fun preciseDeltasMatchAwtWithoutDisplayScale(): TaoWindowTestCase {
        val recorder = PointerRecorder()
        return TaoWindowTestCase(
            name = "#653 precise scroll deltas match AWT preciseWheelRotation regardless of display scale",
            timeoutMillis = HIDPI_CASE_TIMEOUT_MILLIS,
            skip = { macOnly() },
            // The suite's default chrome is a fillMaxSize sibling stacked above
            // [content]; leaving it on gives the recorder 0 height.
            paintDefaultBackground = false,
            content = { Recording(recorder) {} },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            // The doubling only shows on a HiDPI display: flip the screen to
            // its 2x twin for the duration of the case when the window sits on
            // a 1x display (same trick as the #507 probe), else run as-is.
            // Decided before the try so the finally restores the mode even
            // when the window never reports the new scale.
            val baseScale = window.scaleFactor
            val switched = baseScale < HIDPI && hiDpiSwitchAvailable()
            try {
                if (switched) switchDisplayTo2x()
                recorder.reset()
                val scale = window.scaleFactor
                System.err.println("[probe] window scale factor = $scale (switched to HiDPI: $switched)")

                // Fingers up on a natural-scrolling trackpad: AppKit -10 points → AWT +1.
                inject(dx = 0f, dy = -SWIPE_DELTA_PT, precise = true)
                awaitUntil("vertical Scroll event recorded") { recorder.count(PointerEventType.Scroll) >= 1 }
                val vertical = recorder.snapshot().first { it.type == PointerEventType.Scroll }
                checkClose(vertical.scrollDelta, Offset(0f, 1f)) {
                    "vertical precise delta -10pt at scale $scale must reach Compose as AWT +1.0 " +
                        "(got ${vertical.scrollDelta}; recorded=${recorder.describe()})"
                }

                // Fingers left: AppKit -10 points → AWT +1 on X.
                inject(dx = -SWIPE_DELTA_PT, dy = 0f, precise = true)
                awaitUntil("horizontal Scroll event recorded") { recorder.count(PointerEventType.Scroll) >= 2 }
                val horizontal = recorder.snapshot().filter { it.type == PointerEventType.Scroll }[1]
                checkClose(horizontal.scrollDelta, Offset(1f, 0f)) {
                    "horizontal precise delta -10pt at scale $scale must reach Compose as AWT +1.0 on X " +
                        "(got ${horizontal.scrollDelta}; recorded=${recorder.describe()})"
                }
            } finally {
                if (switched) restoreDisplayTo1x(baseScale)
            }
        }
    }

    /**
     * Whether the main display can be flipped to the HiDPI twin of its current
     * mode. `false` (and a log line) when the helper or a twin mode is
     * unavailable — the case then runs at the current scale, which still
     * checks the sign.
     */
    private fun hiDpiSwitchAvailable(): Boolean {
        val unavailable = MacDisplayModeTool.unavailableReason() ?: return true
        System.err.println("[probe] HiDPI switch unavailable: $unavailable")
        return false
    }

    /** Flips the display and waits for the window to report the new backing scale. */
    private suspend fun TaoWindowTestScope.switchDisplayTo2x() {
        System.err.println("[probe] setmode 2x -> ${MacDisplayModeTool.run("2x")}")
        awaitUntil("window reports a HiDPI backing scale") { window.scaleFactor >= HIDPI }
        settle(DISPLAY_SETTLE_MILLIS)
    }

    private suspend fun TaoWindowTestScope.restoreDisplayTo1x(baseScale: Float) {
        System.err.println("[probe] restoring 1x -> ${MacDisplayModeTool.run("1x")}")
        awaitUntil("window back at the original scale ($baseScale)") {
            abs(window.scaleFactor - baseScale) < SCALE_TOLERANCE
        }
        settle(DISPLAY_SETTLE_MILLIS)
    }

    // ── #654 ────────────────────────────────────────────────────────────────

    /**
     * A phased trackpad gesture (Began → Changed… → Ended, then the inertial
     * momentum tail) must surface as `PanStart` / `PanMove` / `PanEnd` with
     * `panOffset` in pixels — never as `Scroll` — and the pan must stay open
     * across the momentum tail so Compose does not add its own fling on top of
     * macOS's. A wheel notch afterwards is still an ordinary `Scroll`.
     */
    private fun trackpadGestureArrivesAsPanAndWheelStaysScroll(): TaoWindowTestCase {
        val recorder = PointerRecorder()
        return TaoWindowTestCase(
            name = "#654 trackpad gesture arrives as Compose Pan events and a wheel notch stays Scroll",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Recording(recorder) {} },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            val scale = window.scaleFactor

            recorder.reset()
            swipe(dx = 0f, dy = -SWIPE_DELTA_PT, steps = SWIPE_STEPS, momentum = true)
            waitFor(PAN_END_MILLIS) { recorder.count(PointerEventType.PanEnd) >= 1 }
            val gesture = recorder.snapshot()
            check(gesture.isNotEmpty() && gesture.first().type == PointerEventType.PanStart) {
                "a trackpad gesture must open with PanStart; recorded=${recorder.describe()}"
            }
            check(gesture.none { it.type == PointerEventType.Scroll }) {
                "a trackpad gesture must not also be delivered as Scroll; recorded=${recorder.describe()}"
            }
            val moves = gesture.filter { it.type == PointerEventType.PanMove }
            // SWIPE_STEPS finger moves + 2 momentum moves, and nothing else:
            // the zero-delta Began / Ended steps must not leak as PanMove(0, 0).
            check(moves.size == SWIPE_STEPS + 2) {
                "expected exactly ${SWIPE_STEPS + 2} PanMove (fingers + momentum), no zero-offset ones; " +
                    "recorded=${recorder.describe()}"
            }
            check(moves.none { it.panOffset.x == 0f && it.panOffset.y == 0f }) {
                "zero-delta gesture steps must not reach Compose as PanMove; recorded=${recorder.describe()}"
            }
            val fingerMoves = moves.take(SWIPE_STEPS)
            fingerMoves.forEach { move ->
                // AppKit -10 points (fingers up) → Compose pan +10 dp = 10 × scale px.
                checkClose(move.panOffset, Offset(0f, SWIPE_DELTA_PT * scale)) {
                    "PanMove.panOffset must be -scrollingDelta × scale px (scale=$scale); " +
                        "got ${move.panOffset}; recorded=${recorder.describe()}"
                }
            }
            check(gesture.last().type == PointerEventType.PanEnd) {
                "PanEnd must close the gesture after the momentum tail; recorded=${recorder.describe()}"
            }
            check(gesture.count { it.type == PointerEventType.PanEnd } == 1) {
                "exactly one PanEnd per gesture (the momentum tail must not restart the pan); " +
                    "recorded=${recorder.describe()}"
            }

            // A classic wheel notch: AppKit +1 line (scroll up) → AWT -1.
            val before = gesture.size
            inject(dx = 0f, dy = 1f, precise = false)
            awaitUntil("wheel notch recorded as Scroll") { recorder.count(PointerEventType.Scroll) >= 1 }
            val afterWheel = recorder.snapshot().drop(before)
            val wheel = afterWheel.single { it.type == PointerEventType.Scroll }
            checkClose(wheel.scrollDelta, Offset(0f, -1f)) {
                "wheel notch +1 line must reach Compose as AWT -1.0 (got ${wheel.scrollDelta})"
            }
            check(afterWheel.none { it.type == PointerEventType.PanMove }) {
                "a wheel notch must not produce Pan events; recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * End-to-end through foundation: Compose's `TrackpadScrollingLogic`
     * consumes the pan and moves a `verticalScroll` column, and a gesture
     * with no momentum tail still gets its `PanEnd` (deferred, then flushed).
     */
    private fun trackpadPanScrollsVerticalColumn(): TaoWindowTestCase {
        val recorder = PointerRecorder()
        val scrollPx = AtomicInteger(0)
        val scrollMax = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#654 trackpad pan scrolls a vertical column through Compose's trackpad logic",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Recording(recorder) { ScrollableColumn(scrollPx, scrollMax) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("column has overflow") { scrollMax.get() > 0 }
            settle()
            recorder.reset()
            swipe(dx = 0f, dy = -SWIPE_DELTA_PT, steps = SWIPE_STEPS, momentum = false)
            waitFor(SCROLL_REACTION_MILLIS) { scrollPx.get() > 0 }
            check(scrollPx.get() > 0) {
                "fingers up must scroll the column down; offset=${scrollPx.get()} recorded=${recorder.describe()}"
            }
            check(recorder.count(PointerEventType.PanMove) >= SWIPE_STEPS) {
                "the column must have been driven by Pan events; recorded=${recorder.describe()}"
            }
            waitFor(PAN_END_MILLIS) { recorder.count(PointerEventType.PanEnd) >= 1 }
            check(recorder.count(PointerEventType.PanEnd) == 1) {
                "a gesture without momentum must still end with exactly one PanEnd; recorded=${recorder.describe()}"
            }
        }
    }

    // ── Injection ───────────────────────────────────────────────────────────

    /**
     * Two-finger swipe: Began, [steps] × Changed([dx], [dy]) points, Ended,
     * optionally followed by AppKit's decaying momentum tail.
     */
    private suspend fun TaoWindowTestScope.swipe(
        dx: Float,
        dy: Float,
        steps: Int,
        momentum: Boolean,
    ) {
        inject(dx = 0f, dy = 0f, precise = true, phase = Phase.BEGAN)
        repeat(steps) {
            settle(STEP_MILLIS)
            inject(dx = dx, dy = dy, precise = true, phase = Phase.CHANGED)
        }
        settle(STEP_MILLIS)
        inject(dx = 0f, dy = 0f, precise = true, phase = Phase.ENDED)
        if (momentum) {
            // Whole points: the CGEvent delta fields are integers, so the
            // injector cannot carry fractions (see nativeDiagInjectScrollWheel).
            settle(STEP_MILLIS)
            inject(dx = momentumStep(dx), dy = momentumStep(dy), precise = true, momentum = Momentum.BEGAN)
            settle(STEP_MILLIS)
            inject(dx = momentumTail(dx), dy = momentumTail(dy), precise = true, momentum = Momentum.CHANGED)
            settle(STEP_MILLIS)
            inject(dx = 0f, dy = 0f, precise = true, momentum = Momentum.ENDED)
        }
    }

    /** Decaying momentum tail of a finger delta [d], in whole points. */
    private fun momentumStep(d: Float): Float = (d * MOMENTUM_STEP_RATIO).toInt().toFloat()

    private fun momentumTail(d: Float): Float = (d * MOMENTUM_TAIL_RATIO).toInt().toFloat()

    private fun TaoWindowTestScope.inject(
        dx: Float,
        dy: Float,
        precise: Boolean,
        phase: Int = Phase.NONE,
        momentum: Int = Momentum.NONE,
    ) {
        val delivered =
            MacScrollWheelProbe.inject(
                window = window,
                x = TARGET_X,
                y = TARGET_Y,
                dx = dx,
                dy = dy,
                precise = precise,
                phase = phase,
                momentum = momentum,
            )
        check(delivered) { "nativeDiagInjectScrollWheel returned false (window or content view gone?)" }
    }

    /** Polls [predicate] for up to [millis] without failing — the caller asserts. */
    private suspend fun TaoWindowTestScope.waitFor(
        millis: Long,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + millis
        while (!predicate() && System.currentTimeMillis() < deadline) settle(POLL_MILLIS)
    }

    // ── Compose content ─────────────────────────────────────────────────────

    private class Recorded(
        val type: PointerEventType,
        val scrollDelta: Offset,
        val panOffset: Offset,
    ) {
        override fun toString(): String =
            when (type) {
                PointerEventType.Scroll -> "Scroll$scrollDelta"
                PointerEventType.PanMove -> "PanMove$panOffset"
                else -> type.toString()
            }
    }

    /** Scroll / Pan events seen at the window root on the Initial pass, in order. */
    private class PointerRecorder {
        private val events = Collections.synchronizedList(mutableListOf<Recorded>())

        fun add(event: PointerEvent) {
            val change = event.changes.firstOrNull() ?: return
            events += Recorded(event.type, change.scrollDelta, change.panOffset)
        }

        fun snapshot(): List<Recorded> = synchronized(events) { events.toList() }

        /** Cases share their recorder with the registry; start each run clean. */
        fun reset() = events.clear()

        fun count(type: PointerEventType): Int = snapshot().count { it.type == type }

        fun describe(): String = snapshot().joinToString(prefix = "[", postfix = "]")
    }

    @Composable
    private fun Recording(
        recorder: PointerRecorder,
        content: @Composable () -> Unit,
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(recorder) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Scroll,
                            PointerEventType.PanStart,
                            PointerEventType.PanMove,
                            PointerEventType.PanEnd,
                            -> recorder.add(event)
                            else -> Unit
                        }
                    }
                }
            },
        ) {
            content()
        }
    }

    @Composable
    private fun ScrollableColumn(
        scrollPx: AtomicInteger,
        scrollMax: AtomicInteger,
    ) {
        val state = rememberScrollState()
        scrollPx.set(state.value)
        scrollMax.set(state.maxValue)
        Column(Modifier.fillMaxSize().verticalScroll(state)) {
            repeat(CELL_COUNT) { i ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(CELL_SIZE_DP.dp)
                        .background(if (i % 2 == 0) Color.DarkGray else Color.Gray),
                )
            }
        }
    }

    @Composable
    private fun ScrollableRow(
        scrollPx: AtomicInteger,
        scrollMax: AtomicInteger,
    ) {
        val state = rememberScrollState()
        scrollPx.set(state.value)
        scrollMax.set(state.maxValue)
        Row(Modifier.fillMaxSize().horizontalScroll(state)) {
            repeat(CELL_COUNT) { i ->
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(CELL_SIZE_DP.dp)
                        .background(if (i % 2 == 0) Color.DarkGray else Color.Gray),
                )
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun macOnly(): String? =
        when {
            Platform.Current != Platform.MacOS -> "macOS only — AppKit scrollWheel: injection"
            !MacScrollWheelProbe.available -> "nucleus_tao_metal not loaded"
            else -> null
        }

    private inline fun checkClose(
        actual: Offset,
        expected: Offset,
        message: () -> String,
    ) {
        check(abs(actual.x - expected.x) <= DELTA_TOLERANCE && abs(actual.y - expected.y) <= DELTA_TOLERANCE, message)
    }

    /** Content-local injection point (points, top-left origin), well inside the 800×600 default window. */
    private const val TARGET_X = 400f
    private const val TARGET_Y = 300f

    /** AppKit points per injected finger move. */
    private const val SWIPE_DELTA_PT = 10f
    private const val SWIPE_STEPS = 3
    private const val MOMENTUM_STEP_RATIO = 0.6f
    private const val MOMENTUM_TAIL_RATIO = 0.3f
    private const val STEP_MILLIS = 16L
    private const val POLL_MILLIS = 16L

    /** How long a scrollable gets to react before the (soft) wait gives up. */
    private const val SCROLL_REACTION_MILLIS = 2_000L

    /** Upper bound for the deferred PanEnd (momentum grace + delivery). */
    private const val PAN_END_MILLIS = 3_000L

    private const val DELTA_TOLERANCE = 0.05f

    private const val HIDPI = 2f
    private const val SCALE_TOLERANCE = 0.01f
    private const val DISPLAY_SETTLE_MILLIS = 1_000L
    private const val HIDPI_CASE_TIMEOUT_MILLIS = 90_000L

    private const val CELL_COUNT = 80
    private const val CELL_SIZE_DP = 48
}
