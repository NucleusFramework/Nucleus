package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoWindow
import dev.nucleusframework.window.tao.event.TaoDirectManipulationEvent
import dev.nucleusframework.window.tao.event.TaoDirectManipulationStatus
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import kotlinx.coroutines.delay
import java.awt.event.KeyEvent
import java.util.Collections
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * #706 end-to-end on a real window: the window's DirectManipulation viewport,
 * its update pump, the JVM wire, `TaoDirectManipulationGesture`, the Windows
 * host and `ComposeScene`.
 *
 * A precision-touchpad contact cannot be injected (Windows has no synthetic
 * touchpad device, and a synthetic *touchscreen* contact is refused by a
 * viewport without a DirectComposition compositor), so the cases drive the
 * chain from the two ends that can be reached:
 *
 * - **the real viewport**: a mouse wheel notch handed to it the way
 *   Microsoft's DirectManipulation sample does
 *   (`nativeDiagDirectManipulationWheelFocus`) makes DirectManipulation run
 *   and report a manipulation of its own — real COM callbacks, real `Update`
 *   pumping, the real content reset;
 * - **a touchpad's stream**: status changes and content transforms queued on
 *   the viewport as its event handler would queue them
 *   (`nativeDiagDirectManipulationReplay`), delivered by the real pump
 *   through everything downstream, so a pinch and a pan with its inertia are
 *   checked against what Compose receives.
 */
internal object WindowsDirectManipulationHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            everyWindowBindsAViewport(),
            aRealManipulationReachesTheJvmAndItsResetLeaksNothing(),
            pinchArrivesWithPlatformPhasesAtItsFocalPoint(),
            pinchZoomsTransformableByTheViewportsRatio(),
            panScrollsAsPanEventsWithTheInertiaInside(),
            aPanThatTurnsIntoAPinchClosesThePanFirst(),
            aManipulationTakenAwayClosesItsGesture(),
            ctrlWheelDuringAPinchFoldsIntoIt(),
        ) + WindowsDirectManipulationMonkeyHeadfulCases.all()

    private fun everyWindowBindsAViewport(): TaoWindowTestCase =
        TaoWindowTestCase(
            name = "#706 every window binds a DirectManipulation viewport",
            skip = ::windowsOnly,
        ) {
            awaitUntil("window mapped") { bounds() != null }
            check(NativeTaoBridge.nativeDirectManipulationAttached(window.handle)) {
                "the window has no DirectManipulation viewport: ${stats().contentToString()}"
            }
            val status = stats()[STAT_STATUS]
            check(status == TaoDirectManipulationStatus.ENABLED || status == TaoDirectManipulationStatus.READY) {
                "a viewport at rest is ENABLED or READY, stats=${stats().contentToString()}"
            }
        }

    /**
     * DirectManipulation animates a wheel notch handed to it as an inertia
     * sequence (ENABLED/READY → INERTIA → READY): the real handler, the real
     * pump and the real wire deliver it; the interpreter recognises no gesture
     * in an inertia the user did not start, and the content reset that follows
     * — a RUNNING → READY manipulation of its own — must not reach the JVM.
     */
    private fun aRealManipulationReachesTheJvmAndItsResetLeaksNothing(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#706 a real DirectManipulation manipulation reaches the JVM and its reset leaks no gesture",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { Box(Modifier.fillMaxSize()) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val tap = DmTap(window)
            try {
                check(NativeTaoBridge.nativeDiagDirectManipulationWheelFocus(true)) { "wheel focus hook refused" }
                val centre = Offset(scene.value.width / 2f, scene.value.height / 2f)
                val driver = RobotPointerDriver(window) { scene.value }
                driver.moveTo(centre)
                window.focus()
                driver.click(centre)
                settle()
                recorder.reset()
                tap.clear()
                robot { it.mouseWheel(WHEEL_NOTCHES) }
                awaitUntil("the viewport ran and settled") {
                    tap.statuses().any { it.status == TaoDirectManipulationStatus.INERTIA } &&
                        stats()[STAT_STATUS] == TaoDirectManipulationStatus.READY
                }
                // Let the reset run out, and anything it might leak arrive.
                settle(RESET_SETTLE_MILLIS)
                val statuses = tap.statuses()
                check(statuses.none { it.status == TaoDirectManipulationStatus.RUNNING }) {
                    "the content reset's own RUNNING leaked to the JVM: $statuses"
                }
                check(statuses.last().status == TaoDirectManipulationStatus.READY) {
                    "the manipulation must end READY: $statuses"
                }
                check((tap.contents() + statuses).any { abs(it.offsetY) > 1f }) {
                    "DirectManipulation must have moved the content: ${tap.describe()}"
                }
                check(stats()[STAT_RESETTING] == 0) { "the reset never settled: ${stats().contentToString()}" }
                check(recorder.snapshot().none { it.type.isPan() || it.type.isScale() }) {
                    "an inertia nobody started is no gesture: ${recorder.describe()}"
                }
                // The next manipulation starts from the identity again.
                tap.clear()
                robot { it.mouseWheel(-WHEEL_NOTCHES) }
                awaitUntil("the viewport ran again") {
                    tap.statuses().any { it.status == TaoDirectManipulationStatus.INERTIA }
                }
                val first = tap.statuses().first { it.status == TaoDirectManipulationStatus.INERTIA }
                check(abs(first.offsetY) < 1f && abs(first.scale - 1f) < SCALE_EPSILON) {
                    "the reset must bring the content back to identity, got $first"
                }
            } finally {
                NativeTaoBridge.nativeDiagDirectManipulationWheelFocus(false)
                tap.remove()
            }
        }
    }

    private fun pinchArrivesWithPlatformPhasesAtItsFocalPoint(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#706 a touchpad pinch arrives as Scale events with the platform's phases at its focal point",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { Box(Modifier.fillMaxSize()) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val focal = Offset(scene.value.width * 0.3f, scene.value.height * 0.6f)
            val stream = DmStream(focal)
            recorder.reset()
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            repeat(PINCH_STEPS) {
                stream.scale *= PINCH_STEP
                replay(stream.content())
            }
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitUntil("the pinch closed") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            val events = recorder.snapshot()
            val scale = events.filter { it.type.isScale() }
            check(
                scale.first().type == PointerEventType.ScaleStart,
            ) { "must open with ScaleStart: ${recorder.describe()}" }
            check(scale.last().type == PointerEventType.ScaleEnd) { "must close with ScaleEnd: ${recorder.describe()}" }
            check(
                scale.count { it.type == PointerEventType.ScaleStart } == 1 &&
                    scale.count { it.type == PointerEventType.ScaleEnd } == 1,
            ) { "one gesture, one start, one end: ${recorder.describe()}" }
            val product = scale.fold(1.0) { acc, e -> acc * e.scaleFactor }
            val expected = PINCH_STEP.toDouble().pow(PINCH_STEPS)
            check(abs(product / expected - 1.0) < RATIO_TOLERANCE) {
                "Compose must zoom by the viewport's own ratio $expected, got $product: ${recorder.describe()}"
            }
            val worst = scale.maxOf { hypot(it.position.x - focal.x, it.position.y - focal.y) }
            check(worst < 1f) { "every step at the focal point $focal, worst ${worst}px off: ${recorder.describe()}" }
            check(events.none { it.type.isPan() || it.type == PointerEventType.Scroll }) {
                "a pinch must not also pan or scroll: ${recorder.describe()}"
            }
        }
    }

    private fun pinchZoomsTransformableByTheViewportsRatio(): TaoWindowTestCase {
        val scene = SceneSize()
        val zoom = Zoom()
        return TaoWindowTestCase(
            name = "#706 a touchpad pinch zooms Modifier.transformable by the viewport's ratio, both ways",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Transformable(zoom, scene) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val stream = DmStream(Offset(scene.value.width / 2f, scene.value.height / 2f))
            zoom.reset()
            pinch(stream, factor = 2.5f, steps = 20)
            awaitUntilOrTimeout(REACTION_MILLIS) { abs(zoom.value - 2.5f) < ZOOM_TOLERANCE }
            check(abs(zoom.value - 2.5f) < ZOOM_TOLERANCE) { "pinch out by 2.5 must zoom by 2.5, got ${zoom.value}" }
            pinch(stream, factor = 0.2f, steps = 20)
            awaitUntilOrTimeout(REACTION_MILLIS) { abs(zoom.value - 0.5f) < ZOOM_TOLERANCE }
            check(
                abs(zoom.value - 0.5f) < ZOOM_TOLERANCE,
            ) { "then pinch in by 0.2 must land on 0.5, got ${zoom.value}" }
        }
    }

    /**
     * A pan and its inertial tail are one Compose pan: `PanStart`, the moves
     * of the fingers *and* of the glide, then one `PanEnd` — so Compose adds
     * no fling on top of the OS inertia, and the column moves by the distance
     * the viewport reported.
     */
    private fun panScrollsAsPanEventsWithTheInertiaInside(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        val scroll = ScrollProbe()
        return TaoWindowTestCase(
            name = "#706 a touchpad pan scrolls as Pan events with the inertia inside the gesture",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { ScrollableColumn(scroll) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("column measured") { (scroll.state?.maxValue ?: 0) > PAN_MIN_RANGE_PX }
            val stream = DmStream(Offset(scene.value.width / 2f, scene.value.height / 2f))
            val before = scroll.value()
            recorder.reset()
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            var travelled = 0f
            repeat(PAN_STEPS) {
                stream.y -= PAN_STEP_PX
                travelled += PAN_STEP_PX
                replay(stream.content())
            }
            replay(stream.status(TaoDirectManipulationStatus.INERTIA))
            var glide = PAN_STEP_PX
            while (glide > GLIDE_STOP_PX) {
                glide *= GLIDE_DECAY
                stream.y -= glide
                travelled += glide
                replay(stream.content())
            }
            val panEndsBeforeTheEnd = recorder.count(PointerEventType.PanEnd)
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitUntil("the pan closed") { recorder.count(PointerEventType.PanEnd) >= 1 }
            settle(FLING_SETTLE_MILLIS)

            check(panEndsBeforeTheEnd == 0) { "the lift into inertia must not close the pan: ${recorder.describe()}" }
            val pans = recorder.snapshot().filter { it.type.isPan() }
            check(pans.first().type == PointerEventType.PanStart) { "must open with PanStart: ${recorder.describe()}" }
            check(
                pans.count { it.type == PointerEventType.PanStart } == 1 &&
                    pans.count { it.type == PointerEventType.PanEnd } == 1,
            ) { "the pan and its tail are one gesture: ${recorder.describe()}" }
            check(recorder.snapshot().none { it.type.isScale() || it.type == PointerEventType.Scroll }) {
                "a pan must not zoom or wheel-scroll: ${recorder.describe()}"
            }
            val moved = scroll.value() - before
            check(abs(moved - travelled) < SCROLL_TOLERANCE_PX) {
                "the column must move by the viewport's distance ($travelled px), moved $moved px"
            }
        }
    }

    private fun aPanThatTurnsIntoAPinchClosesThePanFirst(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#706 a pan that turns into a pinch closes the pan before the pinch opens",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { Box(Modifier.fillMaxSize()) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val stream = DmStream(Offset(scene.value.width / 2f, scene.value.height / 2f))
            recorder.reset()
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            repeat(4) {
                stream.x += 6f
                replay(stream.content())
            }
            repeat(8) {
                stream.scale *= 1.05f
                stream.x += 1f
                replay(stream.content())
            }
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitUntil("the pinch closed") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()
            val types = recorder.snapshot().map { it.type }.filter { it.isPan() || it.isScale() }
            val panEnd = types.indexOf(PointerEventType.PanEnd)
            val scaleStart = types.indexOf(PointerEventType.ScaleStart)
            check(types.first() == PointerEventType.PanStart && panEnd in 0 until scaleStart) {
                "PanStart … PanEnd, then ScaleStart … ScaleEnd: ${recorder.describe()}"
            }
            check(types.drop(scaleStart).none { it.isPan() }) { "no pan step inside the pinch: ${recorder.describe()}" }
            check(types.last() == PointerEventType.ScaleEnd) { "closed by ScaleEnd: ${recorder.describe()}" }
        }
    }

    private fun aManipulationTakenAwayClosesItsGesture(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#706 a manipulation the viewport suspends closes its gesture at once",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { Box(Modifier.fillMaxSize()) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val stream = DmStream(Offset(scene.value.width / 2f, scene.value.height / 2f))
            recorder.reset()
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            repeat(5) {
                stream.scale *= 1.04f
                replay(stream.content())
            }
            replay(stream.status(TaoDirectManipulationStatus.SUSPENDED))
            awaitUntilOrTimeout(REACTION_MILLIS) { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            check(recorder.count(PointerEventType.ScaleEnd) == 1) {
                "SUSPENDED must close the pinch, no timer to wait for: ${recorder.describe()}"
            }
            // Whatever the viewport reports before its next RUNNING is not a gesture.
            stream.scale *= 1.1f
            replay(stream.content())
            replay(stream.status(TaoDirectManipulationStatus.READY))
            settle()
            check(recorder.count(PointerEventType.ScaleStart) == 1) { "no second gesture: ${recorder.describe()}" }
        }
    }

    /**
     * A mouse's Ctrl+wheel while a touchpad pinch is open is folded into that
     * pinch — one gesture, closed by the touchpad's own phase, not by the
     * wheel path's idle timer.
     */
    private fun ctrlWheelDuringAPinchFoldsIntoIt(): TaoWindowTestCase {
        val recorder = GestureRecorder()
        val scene = SceneSize()
        return TaoWindowTestCase(
            name = "#706 a Ctrl+wheel during a touchpad pinch folds into it and never closes it",
            skip = ::injectionSkipReason,
            paintDefaultBackground = false,
            content = { Recording(recorder, scene) { Box(Modifier.fillMaxSize()) } },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val centre = Offset(scene.value.width / 2f, scene.value.height / 2f)
            val driver = RobotPointerDriver(window) { scene.value }
            driver.moveTo(centre)
            window.focus()
            driver.click(centre)
            settle()
            val stream = DmStream(centre)
            recorder.reset()
            replay(stream.status(TaoDirectManipulationStatus.RUNNING))
            stream.scale *= 1.1f
            replay(stream.content())
            robot { ctrlWheel(it) }
            // Well past the wheel path's 120 ms debounce: it must not close the pinch.
            settle(WHEEL_DEBOUNCE_SETTLE_MILLIS)
            check(recorder.count(PointerEventType.ScaleEnd) == 0) {
                "the idle debounce must not close a touchpad pinch: ${recorder.describe()}"
            }
            check(recorder.count(PointerEventType.ScaleChange) >= 2) {
                "the Ctrl+wheel tick must land inside the pinch (focus lost?): ${recorder.describe()}"
            }
            stream.scale *= 1.1f
            replay(stream.content())
            replay(stream.status(TaoDirectManipulationStatus.READY))
            awaitUntil("the pinch closed") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()
            check(recorder.count(PointerEventType.ScaleStart) == 1 && recorder.count(PointerEventType.ScaleEnd) == 1) {
                "one gesture: ${recorder.describe()}"
            }
        }
    }

    // ── Driving the viewport ────────────────────────────────────────────────

    /** A touchpad's transform, moved by the case and replayed step by step. */
    internal class DmStream(
        var focal: Offset,
    ) {
        var scale = 1f
        var x = 0f
        var y = 0f
        var status = TaoDirectManipulationStatus.READY
            private set

        fun status(next: Int): FloatArray =
            event(TaoDirectManipulationEvent.STATUS, next, status).also { status = next }

        fun content(): FloatArray = event(TaoDirectManipulationEvent.CONTENT, status, status)

        /** What the native side does once a manipulation is over. */
        fun reset() {
            scale = 1f
            x = 0f
            y = 0f
        }

        private fun event(
            kind: Int,
            current: Int,
            previous: Int,
        ) = floatArrayOf(kind.toFloat(), current.toFloat(), previous.toFloat(), scale, x, y, focal.x, focal.y)
    }

    /** One replayed step, then a frame for the loop to deliver it. */
    internal suspend fun TaoWindowTestScope.replay(event: FloatArray) {
        check(NativeTaoBridge.nativeDiagDirectManipulationReplay(window.handle, event)) {
            "the viewport refused the replay (injection armed? viewport bound? ${stats().contentToString()})"
        }
        delay(FRAME_MILLIS)
    }

    private suspend fun TaoWindowTestScope.pinch(
        stream: DmStream,
        factor: Float,
        steps: Int,
    ) {
        val step = factor.toDouble().pow(1.0 / steps).toFloat()
        replay(stream.status(TaoDirectManipulationStatus.RUNNING))
        repeat(steps) {
            stream.scale *= step
            replay(stream.content())
        }
        replay(stream.status(TaoDirectManipulationStatus.READY))
        stream.reset()
    }

    internal fun TaoWindowTestScope.stats(): IntArray =
        NativeTaoBridge.nativeDiagDirectManipulationStats(window.handle) ?: IntArray(STAT_COUNT)

    internal suspend fun robot(gesture: (java.awt.Robot) -> Unit) {
        val ok =
            HeadfulRobot.inject { robot ->
                gesture(robot)
                true
            }
        checkNotNull(ok) { "the AWT Robot became unavailable mid-run: ${HeadfulRobot.unavailableReason}" }
    }

    /** One Ctrl+wheel-up notch: zoom in. */
    internal fun ctrlWheel(robot: java.awt.Robot) {
        robot.keyPress(KeyEvent.VK_CONTROL)
        try {
            robot.mouseWheel(-1)
        } finally {
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }
    }

    /** Records what the viewport delivers to the window, in order, ahead of the host. */
    internal class DmTap(
        private val window: TaoWindow,
    ) {
        private val events = Collections.synchronizedList(mutableListOf<TaoDirectManipulationEvent>())
        private val downstream = window.directManipulationListener

        init {
            window.directManipulationListener = { event ->
                events += event
                downstream?.invoke(event)
            }
        }

        fun statuses(): List<TaoDirectManipulationEvent> =
            synchronized(events) { events.filter { it.kind == TaoDirectManipulationEvent.STATUS } }

        fun contents(): List<TaoDirectManipulationEvent> =
            synchronized(events) { events.filter { it.kind == TaoDirectManipulationEvent.CONTENT } }

        fun clear() = events.clear()

        fun describe(): String = synchronized(events) { events.joinToString(prefix = "[", postfix = "]") }

        fun remove() {
            window.directManipulationListener = downstream
        }
    }

    // ── Content ─────────────────────────────────────────────────────────────

    internal class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero
    }

    internal class Zoom {
        @Volatile
        var value: Float = 1f

        fun reset() {
            value = 1f
        }
    }

    internal class ScrollProbe {
        @Volatile
        var state: ScrollState? = null

        fun value(): Int = state?.value ?: 0
    }

    internal class Recorded(
        val type: PointerEventType,
        val position: Offset,
        val scaleFactor: Float,
        val panOffset: Offset,
    ) {
        override fun toString(): String =
            when {
                type.isScale() -> "$type(${"%.4f".format(
                    java.util.Locale.ROOT,
                    scaleFactor,
                )} @${position.x.toInt()},${position.y.toInt()})"
                type.isPan() -> "$type(${"%.1f".format(
                    java.util.Locale.ROOT,
                    panOffset.x,
                )},${"%.1f".format(java.util.Locale.ROOT, panOffset.y)})"
                else -> type.toString()
            }
    }

    internal class GestureRecorder {
        private val events = Collections.synchronizedList(mutableListOf<Recorded>())

        fun add(event: PointerEvent) {
            val change = event.changes.firstOrNull() ?: return
            events += Recorded(event.type, change.position, change.scaleFactor, change.panOffset)
        }

        fun snapshot(): List<Recorded> = synchronized(events) { events.toList() }

        fun reset() = events.clear()

        fun count(type: PointerEventType): Int = snapshot().count { it.type == type }

        fun describe(): String = snapshot().joinToString(prefix = "[", postfix = "]")
    }

    /** Pan / Scale / Scroll events at the window root on the Initial pass, in order. */
    @Composable
    internal fun Recording(
        recorder: GestureRecorder,
        scene: SceneSize,
        content: @Composable () -> Unit,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { scene.value = it.size }
                .pointerInput(recorder) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type.isPan() || event.type.isScale() || event.type == PointerEventType.Scroll) {
                                recorder.add(event)
                            }
                        }
                    }
                },
        ) {
            content()
        }
    }

    @Composable
    internal fun Transformable(
        zoom: Zoom,
        scene: SceneSize,
    ) {
        @Suppress("DEPRECATION")
        val state = rememberTransformableState { zoomChange, _, _ -> zoom.value *= zoomChange }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { scene.value = it.size }
                .transformable(state),
        )
    }

    @Composable
    internal fun ScrollableColumn(probe: ScrollProbe) {
        val state = rememberScrollState()
        probe.state = state
        Column(Modifier.fillMaxSize().verticalScroll(state)) {
            repeat(COLUMN_ROWS) { Box(Modifier.fillMaxWidth().height(40.dp)) }
        }
    }

    internal fun PointerEventType.isScale(): Boolean =
        this == PointerEventType.ScaleStart || this == PointerEventType.ScaleChange || this == PointerEventType.ScaleEnd

    internal fun PointerEventType.isPan(): Boolean =
        this == PointerEventType.PanStart || this == PointerEventType.PanMove || this == PointerEventType.PanEnd

    // ── Skips ───────────────────────────────────────────────────────────────

    internal fun windowsOnly(): String? =
        if (Platform.Current != Platform.Windows) "Windows only — DirectManipulation is a Windows API" else null

    internal fun injectionSkipReason(): String? =
        windowsOnly()
            ?: if (System.getenv("NUCLEUS_TAO_INPUT_INJECTION") != "1") {
                "needs NUCLEUS_TAO_INPUT_INJECTION=1 (taoHeadfulTest sets it)"
            } else {
                robotDriverSkipReason()
            }

    // nativeDiagDirectManipulationStats layout.
    internal const val STAT_STATUS = 3
    internal const val STAT_RESETTING = 5
    private const val STAT_COUNT = 7

    internal const val FRAME_MILLIS = 8L
    private const val WHEEL_NOTCHES = 3
    private const val RESET_SETTLE_MILLIS = 600L
    private const val REACTION_MILLIS = 2_000L
    private const val WHEEL_DEBOUNCE_SETTLE_MILLIS = 400L
    private const val FLING_SETTLE_MILLIS = 800L
    private const val SCALE_EPSILON = 1e-4f

    private const val PINCH_STEPS = 24
    private const val PINCH_STEP = 1.04f
    private const val RATIO_TOLERANCE = 1e-3
    private const val ZOOM_TOLERANCE = 0.01f

    private const val PAN_STEPS = 20
    private const val PAN_STEP_PX = 12f
    private const val PAN_MIN_RANGE_PX = 1_200
    private const val GLIDE_DECAY = 0.85f
    private const val GLIDE_STOP_PX = 0.2f
    private const val SCROLL_TOLERANCE_PX = 6f
    private const val COLUMN_ROWS = 200
}
