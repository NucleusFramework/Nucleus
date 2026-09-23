package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.core.runtime.Platform
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * #660 end-to-end: a platform-recognized pinch must reach Compose as
 * `ScaleStart` / `ScaleChange` / `ScaleEnd` at the cursor, never as a scroll
 * and never as two synthetic Touch contacts.
 *
 * The injected gesture is a real Ctrl+wheel through the AWT Robot, so the
 * whole chain runs: OS wheel message → the vendored tao patch that routes a
 * Ctrl-flagged `WM_MOUSEWHEEL` to the magnify hook (GTK: the host's own
 * routing) → `onTrackpadGesture` → `TaoTrackpadScaleSession` → `ComposeScene`
 * → foundation's `transformable`.
 *
 * Windows and Linux only: those are the two hosts that turn Ctrl+wheel into a
 * scale gesture. macOS gets its pinch from an AppKit `magnifyWithEvent:`, for
 * which there is no injector — [MacOsTrackpadScrollHeadfulCases] covers the
 * scroll half of the same wire.
 */
internal object TrackpadScaleHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            ctrlWheelArrivesAsScaleAndPlainWheelStaysScroll(),
            ctrlWheelZoomsTransformable(),
        )

    /**
     * A Ctrl+wheel burst opens one scale gesture, carries a zoom-in ratio on
     * every tick and closes on the idle debounce — with no `Scroll` event and
     * no movement of the scrollable under the cursor. A plain wheel notch
     * afterwards is still an ordinary `Scroll` and produces no scale step.
     */
    private fun ctrlWheelArrivesAsScaleAndPlainWheelStaysScroll(): TaoWindowTestCase {
        val recorder = ScaleRecorder()
        val scene = SceneSize()
        val scrollPx = AtomicInteger(0)
        val scrollMax = AtomicInteger(0)
        return TaoWindowTestCase(
            name = "#660 Ctrl+wheel arrives as Compose Scale events and a plain wheel stays Scroll",
            skip = { ctrlWheelZoomOnly() },
            // The suite's default chrome is a fillMaxSize sibling stacked above
            // [content]; leaving it on gives the recorder 0 height.
            paintDefaultBackground = false,
            content = {
                Recording(recorder, scene) { ScrollableColumn(scrollPx, scrollMax) }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("column has overflow") { scrollMax.get() > 0 }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val driver = RobotPointerDriver(window) { scene.value }
            driver.armInput(
                scope = this,
                center = scene.center(),
                probed = { recorder.count(PointerEventType.ScaleChange) > 0 },
                reset = { recorder.reset() },
            )
            val scrollBefore = scrollPx.get()

            ctrlWheel(notches = -1, ticks = WHEEL_TICKS)
            awaitUntil("a scale step reached Compose") { recorder.count(PointerEventType.ScaleChange) > 0 }
            awaitUntilOrTimeout(SCALE_END_MILLIS) { recorder.count(PointerEventType.ScaleEnd) >= 1 }

            val gesture = recorder.snapshot()
            check(gesture.firstOrNull()?.type == PointerEventType.ScaleStart) {
                "a Ctrl+wheel burst must open with ScaleStart; recorded=${recorder.describe()}"
            }
            check(gesture.none { it.type == PointerEventType.Scroll }) {
                "a Ctrl+wheel must never also be delivered as Scroll; recorded=${recorder.describe()}"
            }
            val changes = gesture.filter { it.type == PointerEventType.ScaleChange }
            check(changes.isNotEmpty() && changes.all { it.scaleFactor > 1f }) {
                "wheel-up must carry a zoom-in ratio (> 1) on every step; recorded=${recorder.describe()}"
            }
            check(gesture.last().type == PointerEventType.ScaleEnd) {
                "the idle debounce must close the gesture with ScaleEnd; recorded=${recorder.describe()}"
            }
            check(gesture.count { it.type == PointerEventType.ScaleEnd } == 1) {
                "exactly one ScaleEnd per burst; recorded=${recorder.describe()}"
            }
            check(scrollPx.get() == scrollBefore) {
                "a Ctrl+wheel must zoom, never scroll the column " +
                    "(offset $scrollBefore → ${scrollPx.get()}); recorded=${recorder.describe()}"
            }

            // Baseline taken right before the notch: whatever the burst still
            // had in flight must not land in the plain wheel's window.
            val before = recorder.snapshot().size
            plainWheel(notches = 1)
            awaitUntil("plain wheel notch recorded as Scroll") { recorder.count(PointerEventType.Scroll) >= 1 }
            val afterWheel = recorder.snapshot().drop(before)
            check(afterWheel.none { it.type.isScale() }) {
                "a plain wheel notch must produce no scale step; recorded=${recorder.describe()}"
            }
            awaitUntilOrTimeout(SCROLL_REACTION_MILLIS) { scrollPx.get() != scrollBefore }
            check(scrollPx.get() != scrollBefore) {
                "a plain wheel notch must still scroll the column; offset=${scrollPx.get()}"
            }
        }
    }

    /**
     * Through foundation: `Modifier.transformable` consumes the scale gesture
     * and zooms immediately — no touch slop, no span threshold, which is the
     * whole point of #660.
     */
    private fun ctrlWheelZoomsTransformable(): TaoWindowTestCase {
        val scene = SceneSize()
        val zoom = Zoom()
        return TaoWindowTestCase(
            name = "#660 Ctrl+wheel zooms Modifier.transformable with no slop",
            skip = { ctrlWheelZoomOnly() },
            paintDefaultBackground = false,
            content = { Transformable(zoom, scene) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            awaitUntil("scene measured") { scene.value.width > 0 }
            val driver = RobotPointerDriver(window) { scene.value }
            driver.armInput(
                scope = this,
                center = scene.center(),
                probed = { zoom.value != 1f },
                reset = { zoom.reset() },
            )

            ctrlWheel(notches = -1, ticks = WHEEL_TICKS)
            awaitUntilOrTimeout(SCROLL_REACTION_MILLIS) { zoom.value > 1f }
            check(zoom.value > 1f) {
                "wheel-up with Ctrl must zoom the transformable in; zoom=${zoom.value}"
            }

            val zoomedIn = zoom.value
            ctrlWheel(notches = 1, ticks = WHEEL_TICKS)
            awaitUntilOrTimeout(SCROLL_REACTION_MILLIS) { zoom.value < zoomedIn }
            check(zoom.value < zoomedIn) {
                "wheel-down with Ctrl must zoom back out; zoom=$zoomedIn → ${zoom.value}"
            }
        }
    }

    // ── Injection ───────────────────────────────────────────────────────────

    /**
     * Puts the pointer on [center] and makes sure an injected Ctrl+wheel
     * actually reaches this window, then leaves the case a clean slate.
     *
     * Win32 delivers `WM_MOUSEWHEEL` to the **focused** window, not the hovered
     * one, and `SetForegroundWindow` from a process the user never activated is
     * a no-op — so a wheel injected right after the window maps can land
     * wherever the session left the focus. A real click takes the foreground;
     * [probed] is what proves it, since nothing the window publishes says
     * whether the *wheel* is arriving. The click and the probe tick are on an
     * empty / scrollable surface and zoom nothing the cases measure, and
     * [reset] runs once the probe gesture has closed.
     */
    private suspend fun RobotPointerDriver.armInput(
        scope: TaoWindowTestScope,
        center: Offset,
        probed: () -> Boolean,
        reset: () -> Unit,
    ) {
        moveTo(center)
        repeat(ARM_ATTEMPTS) { attempt ->
            scope.window.focus()
            click(center)
            scope.settle()
            ctrlWheel(notches = -1, ticks = 1)
            if (scope.awaitUntilOrTimeout(ARM_PROBE_MILLIS, probed)) {
                // Let the idle debounce close the probe's gesture, so the
                // case's own burst is the only one in the recording.
                scope.settle(ARM_SETTLE_MILLIS)
                reset()
                return
            }
            System.err.println("[probe] Ctrl+wheel did not reach the window (attempt ${attempt + 1})")
        }
        error("an injected Ctrl+wheel never reached the case window; ${HeadfulRobot.lastAimReport}")
    }

    /**
     * [ticks] wheel notches with Ctrl held down for the whole burst — the
     * shape a precision touchpad pinch takes on Windows. [notches] is AWT's
     * sign: negative is wheel-up, i.e. zoom in.
     */
    private suspend fun ctrlWheel(
        notches: Int,
        ticks: Int,
    ) {
        inject { robot ->
            robot.keyPress(KeyEvent.VK_CONTROL)
            try {
                repeat(ticks) {
                    robot.mouseWheel(notches)
                    Thread.sleep(WHEEL_STEP_MILLIS)
                }
            } finally {
                robot.keyRelease(KeyEvent.VK_CONTROL)
            }
        }
    }

    private suspend fun plainWheel(notches: Int) = inject { robot -> robot.mouseWheel(notches) }

    private suspend fun inject(gesture: (java.awt.Robot) -> Unit) {
        val ok =
            HeadfulRobot.inject { robot ->
                gesture(robot)
                true
            }
        checkNotNull(ok) { "the AWT Robot became unavailable mid-run: ${HeadfulRobot.unavailableReason}" }
    }

    // ── Compose content ─────────────────────────────────────────────────────

    /** Scene size in physical px, published by the recording root. */
    private class SceneSize {
        @Volatile
        var value: IntSize = IntSize.Zero

        fun center(): Offset = Offset(value.width / 2f, value.height / 2f)
    }

    private class Zoom {
        @Volatile
        var value: Float = 1f

        fun apply(change: Float) {
            value *= change
        }

        fun reset() {
            value = 1f
        }
    }

    private class Recorded(
        val type: PointerEventType,
        val scaleFactor: Float,
    ) {
        override fun toString(): String = if (type.isScale()) "$type($scaleFactor)" else type.toString()
    }

    /** Scroll / Scale events seen at the window root on the Initial pass, in order. */
    private class ScaleRecorder {
        private val events = Collections.synchronizedList(mutableListOf<Recorded>())

        fun add(event: PointerEvent) {
            val change = event.changes.firstOrNull() ?: return
            events += Recorded(event.type, change.scaleFactor)
        }

        fun snapshot(): List<Recorded> = synchronized(events) { events.toList() }

        /** Cases share their recorder with the registry; start each run clean. */
        fun reset() = events.clear()

        fun count(type: PointerEventType): Int = snapshot().count { it.type == type }

        fun describe(): String = snapshot().joinToString(prefix = "[", postfix = "]")
    }

    @Composable
    private fun Recording(
        recorder: ScaleRecorder,
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
                            if (event.type == PointerEventType.Scroll || event.type.isScale()) {
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
    private fun Transformable(
        zoom: Zoom,
        scene: SceneSize,
    ) {
        val state = rememberTransformableState { zoomChange, _, _ -> zoom.apply(zoomChange) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { scene.value = it.size }
                .transformable(state),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun PointerEventType.isScale(): Boolean =
        this == PointerEventType.ScaleStart ||
            this == PointerEventType.ScaleChange ||
            this == PointerEventType.ScaleEnd

    /**
     * Ctrl+wheel is a scale gesture on Windows and Linux only; macOS takes its
     * pinch from AppKit's own recognizer, which has no injector.
     */
    private fun ctrlWheelZoomOnly(): String? =
        when (Platform.Current) {
            Platform.Windows, Platform.Linux -> robotDriverSkipReason()
            else -> "Windows / Linux only — Ctrl+wheel is the injectable pinch"
        }

    /** How many times a click + probe tick is retried before the case gives up. */
    private const val ARM_ATTEMPTS = 3
    private const val ARM_PROBE_MILLIS = 1_500L

    /** Idle debounce (120 ms) plus slack, so the probe's gesture is closed and recorded before the reset. */
    private const val ARM_SETTLE_MILLIS = 500L

    /** Notches per burst: enough steps that a slop-gated path would still be visible. */
    private const val WHEEL_TICKS = 4
    private const val WHEEL_STEP_MILLIS = 16L

    /** Upper bound for the idle debounce that closes the gesture (120 ms) plus delivery. */
    private const val SCALE_END_MILLIS = 3_000L

    /** How long a scrollable / transformable gets to react before the soft wait gives up. */
    private const val SCROLL_REACTION_MILLIS = 2_000L
}
