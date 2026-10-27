package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.tao.TaoEventCode
import dev.nucleusframework.window.tao.TaoMouseButton
import dev.nucleusframework.window.tao.ffi.NativeTaoBridge
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * #660 end-to-end on Linux: a GDK touchpad pinch must reach Compose as
 * `ScaleStart` / `ScaleChange` / `ScaleEnd` at the cursor. Every case emits
 * `GdkEventTouchpadPinch` through the GtkWindow's `event` signal
 * ([NativeTaoBridge.nativeLinuxInjectGdkTouchpadPinch]), so `touch.rs`'s
 * absolute-scale / radian conversion, the JNI callback and
 * `TaoComposeSceneHostLinux.onTrackpadGesture` all run as for a real pinch.
 *
 * Unlike AppKit, GDK reports pinch and rotation as **one** gesture: every
 * event carries a scale and an angle, so `touch.rs` forwards a magnify and a
 * rotate step for each. A real pinch always carries some angle noise; the
 * cases below guard that it stays a Scale gesture, that a deliberate
 * rotation still reaches `detectTransformGestures`, and that the rotation
 * contacts never coexist with a mouse-only event.
 */
internal object LinuxTrackpadPinchHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            pinchArrivesAsScaleEventsAtTheCursor(),
            onePercentPinchZoomsTransformable(),
            cancelledPinchClosesTheScaleGesture(),
            pinchWithAngleNoiseStaysScaleOnly(),
            rotationTakesOverAPinchThatDoesNotZoom(),
            clickDuringRotationCancelsItWithoutATap(),
            rotationInTheTitleBarNeverDragsTheWindow(),
        )

    /** Begin / Update… / End at a fixed angle: one ScaleStart, one ScaleChange per step, one ScaleEnd. */
    private fun pinchArrivesAsScaleEventsAtTheCursor(): TaoWindowTestCase {
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 Linux GDK pinch arrives as Compose Scale events at the cursor",
            skip = { linuxOnly() },
            // The suite's default chrome is a fillMaxSize sibling stacked above
            // [content]; leaving it on gives the recorder 0 height.
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            recorder.reset()

            pinch(PHASE_BEGIN, 1.0)
            SCALES.forEach { pinch(PHASE_UPDATE, it) }
            pinch(PHASE_END, SCALES.last())
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            val events = recorder.snapshot()
            val scale = events.filter { it.type.isScale() }
            check(scale.map { it.type } == expectedScaleTypes(SCALES.size)) {
                "one ScaleStart, one ScaleChange per update, one ScaleEnd; recorded=${recorder.describe()}"
            }
            // GDK's scale is absolute; each ScaleChange must be the ratio to the previous one.
            val ratios = (listOf(1.0) + SCALES).zipWithNext { a, b -> (b / a).toFloat() }
            scale.filter { it.type == PointerEventType.ScaleChange }.zip(ratios).forEach { (event, ratio) ->
                check(abs(event.scaleFactor - ratio) <= FACTOR_TOLERANCE) {
                    "ScaleChange must carry GDK's per-event ratio ($ratio); recorded=${recorder.describe()}"
                }
            }
            val cursor = Offset(TARGET_X * window.scaleFactor, TARGET_Y * window.scaleFactor)
            scale.forEach {
                check((it.position - cursor).getDistance() <= POSITION_TOLERANCE_PX) {
                    "Scale events must sit at the cursor $cursor (got ${it.position}); recorded=${recorder.describe()}"
                }
            }
            check(events.none { it.pointerType == PointerType.Touch }) {
                "a pinch must not synthesise Touch contacts; recorded=${recorder.describe()}"
            }
            check(events.none { it.type == PointerEventType.Press || it.type == PointerEventType.Scroll }) {
                "a pinch must produce no Press and no Scroll; recorded=${recorder.describe()}"
            }
        }
    }

    /** A 1 % pinch zooms `Modifier.transformable` on its first update — no touch slop. */
    private fun onePercentPinchZoomsTransformable(): TaoWindowTestCase {
        val transform = Transform()
        return TaoWindowTestCase(
            name = "#660 Linux GDK 1% pinch zooms Modifier.transformable with no slop",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = { Transformable(transform) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()

            pinch(PHASE_BEGIN, 1.0)
            pinch(PHASE_UPDATE, 1.01)
            awaitUntilOrTimeout(REACTION_MILLIS) { transform.zoom != 1f }
            check(abs(transform.zoom - 1.01f) <= FACTOR_TOLERANCE) {
                "the first 1% update must zoom the transformable at once (zoom=${transform.zoom})"
            }
            pinch(PHASE_UPDATE, 0.99)
            pinch(PHASE_END, 0.99)
            awaitUntilOrTimeout(REACTION_MILLIS) { transform.zoom < 1f }
            check(abs(transform.zoom - 0.99f) <= FACTOR_TOLERANCE) {
                "the pinch-out must land on GDK's absolute 0.99 (zoom=${transform.zoom})"
            }
        }
    }

    /** A pinch the compositor cancels still closes with exactly one ScaleEnd. */
    private fun cancelledPinchClosesTheScaleGesture(): TaoWindowTestCase {
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 Linux GDK cancelled pinch closes the Scale gesture",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            recorder.reset()

            pinch(PHASE_BEGIN, 1.0)
            pinch(PHASE_UPDATE, 1.02)
            pinch(PHASE_CANCEL, 1.02)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()
            check(recorder.snapshot().filter { it.type.isScale() }.map { it.type } == expectedScaleTypes(1)) {
                "a cancelled pinch must close with one ScaleEnd; recorded=${recorder.describe()}"
            }
            check(recorder.snapshot().none { it.pointerType == PointerType.Touch }) {
                "a cancelled pinch must press no touch contact; recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * The shape of a real pinch: every update zooms and carries a degree or so
     * of rotation. It must stay a pure Scale gesture — no touch contact ever
     * pressed (a Scale event lists only the mouse pointer, so contacts pressed
     * alongside read as released on every scale step and re-pressed on every
     * rotate step: a touch tap per update) and every update zooms
     * `Modifier.transformable` exactly once.
     */
    private fun pinchWithAngleNoiseStaysScaleOnly(): TaoWindowTestCase {
        val transform = Transform()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 Linux GDK pinch with angle noise stays Scale-only",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = { Transformable(transform, Modifier.record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()
            recorder.reset()

            var scale = 1.0
            pinch(PHASE_BEGIN, scale, NOISE_RADIANS)
            repeat(NOISY_STEPS) { step ->
                scale *= NOISY_STEP_RATIO
                pinch(PHASE_UPDATE, scale, if (step % 2 == 0) NOISE_RADIANS else -NOISE_RADIANS / 2)
            }
            pinch(PHASE_END, scale)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            check(recorder.snapshot().none { it.pointerType == PointerType.Touch }) {
                "angle noise inside a pinch must press no touch contact; recorded=${recorder.describe()}"
            }
            check(recorder.snapshot().filter { it.type.isScale() }.map { it.type } == expectedScaleTypes(NOISY_STEPS)) {
                "every update must be one ScaleChange; recorded=${recorder.describe()}"
            }
            check(abs(transform.zoom - scale.toFloat()) <= FACTOR_TOLERANCE) {
                "every update must zoom transformable exactly once (zoom=${transform.zoom}, expected $scale)"
            }
            check(transform.rotation == 0f) { "a pinch must not rotate (rotation=${transform.rotation})" }
        }
    }

    /**
     * A two-finger twist that barely zooms: the pinch opens as Scale (no delay
     * for the common case), and once the rotation clearly dominates it takes
     * the gesture over — the Scale gesture closes, the contacts go down once
     * and up once, and `detectTransformGestures` rotates clockwise for GDK's
     * clockwise (positive) `angle_delta`.
     */
    private fun rotationTakesOverAPinchThatDoesNotZoom(): TaoWindowTestCase {
        val transform = Transform()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 Linux GDK rotation takes over a pinch that does not zoom",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder).detectTransform(transform)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()
            recorder.reset()

            twist()
            awaitUntil("rotation reached detectTransformGestures") { transform.rotation != 0f }
            settle()

            val events = recorder.snapshot()
            check(events.count { it.down } == 2 && events.count { it.up } == 2) {
                "the two contacts must go down once and up once; recorded=${recorder.describe()}"
            }
            val starts = events.count { it.type == PointerEventType.ScaleStart }
            check(starts <= 1 && events.count { it.type == PointerEventType.ScaleEnd } == starts) {
                "the pinch's Scale gesture opens at most once and closes at the takeover; " +
                    "recorded=${recorder.describe()}"
            }
            val firstDown = events.indexOfFirst { it.down }
            check(events.drop(firstDown).none { it.type.isScale() }) {
                "no Scale event may reach the scene while the contacts are down; recorded=${recorder.describe()}"
            }
            check(transform.rotation > 0f) {
                "GDK's positive angle_delta is clockwise: Compose must rotate clockwise " +
                    "(rotation=${transform.rotation})"
            }
            check(abs(transform.rotation - TWIST_TOTAL_DEGREES) <= ROTATION_TOLERANCE_DEGREES) {
                "the rotation must reach detectTransformGestures in full, the takeover's own " +
                    "degrees included (rotation=${transform.rotation}, twisted $TWIST_TOTAL_DEGREES°)"
            }
        }
    }

    /**
     * A real click during a rotation would reach the scene as a mouse-only
     * event, i.e. the contacts' release — a touch tap. It cancels the
     * rotation instead, and the rest of that gesture is ignored.
     */
    private fun clickDuringRotationCancelsItWithoutATap(): TaoWindowTestCase {
        val taps = AtomicInteger()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 Linux GDK click during a rotation cancels it without a tap",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .record(recorder)
                        .pointerInput(taps) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach {
                                        val touchUp = it.type == PointerType.Touch && it.changedToUpIgnoreConsumed()
                                        if (touchUp && !it.isConsumed) taps.incrementAndGet()
                                    }
                                }
                            }
                        },
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            // The click lands where the cursor is: put it on the gesture first,
            // so the press is neither a move (itself an interruption) nor a
            // press in the resize band at (0, 0).
            moveCursor(TARGET_X, TARGET_Y)
            settle()
            recorder.reset()
            taps.set(0)

            pinch(PHASE_BEGIN, 1.0)
            repeat(TWIST_STEPS / 2) { pinch(PHASE_UPDATE, 1.0, TWIST_STEP_RADIANS) }
            awaitUntil("the rotation took the gesture over") { recorder.snapshot().any { it.down } }
            window.dispatch(TaoEventCode.MOUSE_DOWN, TaoMouseButton.LEFT, 0)
            window.dispatch(TaoEventCode.MOUSE_UP, TaoMouseButton.LEFT, 0)
            repeat(TWIST_STEPS / 2) { pinch(PHASE_UPDATE, 1.0, TWIST_STEP_RADIANS) }
            pinch(PHASE_END, 1.0)
            settle()

            val events = recorder.snapshot()
            check(events.count { it.down && it.pointerType == PointerType.Touch } == 2) {
                "the contacts must go down once — the steps after the click are ignored; " +
                    "recorded=${recorder.describe()}"
            }
            check(events.any { it.type == PointerEventType.Press && it.pointerType == PointerType.Mouse }) {
                "the click must reach the scene; recorded=${recorder.describe()}"
            }
            check(taps.get() == 0) {
                "the click must cancel the rotation, not release its contacts as a tap (${taps.get()} taps); " +
                    "recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * The contacts of a rotation twisted over the title bar are Touch
     * pointers: they must never arm the title bar's window drag.
     */
    private fun rotationInTheTitleBarNeverDragsTheWindow(): TaoWindowTestCase {
        val recorder = EventRecorder()
        val drags = AtomicInteger()
        return TaoWindowTestCase(
            name = "#660 Linux GDK rotation over the title bar never drags the window",
            skip = { linuxOnly() },
            paintDefaultBackground = false,
            content = {
                val scope = this
                Column(Modifier.fillMaxSize().record(recorder)) {
                    with(scope) { TitleBar { _ -> } }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.DarkGray))
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            window.onDragWindow { drags.incrementAndGet() }
            recorder.reset()

            twist(y = TITLE_BAR_Y)
            settle()

            check(recorder.snapshot().any { it.down && it.pointerType == PointerType.Touch }) {
                "the rotation must have pressed its contacts over the bar; recorded=${recorder.describe()}"
            }
            check(drags.get() == 0) { "the rotation contacts started ${drags.get()} window drag(s)" }
        }
    }

    // ── Injection ───────────────────────────────────────────────────────────

    /** A twist that zooms by under a percent and turns [TWIST_TOTAL_DEGREES] clockwise. */
    private suspend fun TaoWindowTestScope.twist(y: Int = TARGET_Y) {
        pinch(PHASE_BEGIN, 1.0, y = y)
        repeat(TWIST_STEPS) { step ->
            pinch(PHASE_UPDATE, if (step % 2 == 0) 1.004 else 0.998, TWIST_STEP_RADIANS, y = y)
        }
        pinch(PHASE_END, 0.998, y = y)
    }

    /** Compose's pointer, through the CURSOR_MOVED wire (content px, 1/1024 fixed point). */
    private fun TaoWindowTestScope.moveCursor(
        x: Int,
        y: Int,
    ) {
        val fixed = window.scaleFactor * CURSOR_FIXED_SCALE
        window.dispatch(TaoEventCode.CURSOR_MOVED, (x * fixed).toInt(), (y * fixed).toInt())
    }

    private suspend fun TaoWindowTestScope.pinch(
        phase: Int,
        scale: Double,
        angleDeltaRadians: Double = 0.0,
        x: Int = TARGET_X,
        y: Int = TARGET_Y,
    ) {
        // GDK reports pinch coordinates in the toplevel's GdkWindow, i.e.
        // including the CSD shadow ring `touch.rs` subtracts again.
        val origin = NativeTaoBridge.nativeLinuxContentOrigin(window.handle)
        val delivered =
            NativeTaoBridge.nativeLinuxInjectGdkTouchpadPinch(
                window.handle,
                phase,
                x + (origin shr 32).toInt(),
                y + origin.toInt(),
                (scale * MICRO).toInt(),
                (angleDeltaRadians * MICRO).toInt(),
            )
        check(delivered) { "nativeLinuxInjectGdkTouchpadPinch returned false (window not realized?)" }
        settle(STEP_MILLIS)
    }

    // ── Compose content ─────────────────────────────────────────────────────

    private class Recorded(
        val type: PointerEventType,
        val pointerType: PointerType,
        val position: Offset,
        val scaleFactor: Float,
        val down: Boolean,
        val up: Boolean,
    ) {
        override fun toString(): String =
            when {
                type == PointerEventType.ScaleChange -> "$type($scaleFactor)"
                pointerType == PointerType.Touch -> "$type(touch)"
                else -> type.toString()
            }
    }

    /** Every pointer event seen on the Initial pass, in order (one entry per change). */
    private class EventRecorder {
        private val events = Collections.synchronizedList(mutableListOf<Recorded>())

        fun add(event: PointerEvent) {
            event.changes.forEach {
                events +=
                    Recorded(
                        type = event.type,
                        pointerType = it.type,
                        position = it.position,
                        scaleFactor = it.scaleFactor,
                        down = it.changedToDownIgnoreConsumed(),
                        up = it.changedToUpIgnoreConsumed(),
                    )
            }
        }

        fun snapshot(): List<Recorded> = synchronized(events) { events.toList() }

        /** Cases share their recorder with the registry; start each run clean. */
        fun reset() = events.clear()

        fun count(type: PointerEventType): Int = snapshot().count { it.type == type }

        fun describe(): String = snapshot().joinToString(prefix = "[", postfix = "]")
    }

    private fun Modifier.record(recorder: EventRecorder): Modifier =
        pointerInput(recorder) {
            awaitPointerEventScope {
                while (true) {
                    recorder.add(awaitPointerEvent(PointerEventPass.Initial))
                }
            }
        }

    private fun Modifier.detectTransform(transform: Transform): Modifier =
        pointerInput(transform) {
            detectTransformGestures { _, pan, zoom, rotation -> transform.apply(pan, zoom, rotation) }
        }

    private class Transform {
        @Volatile var zoom: Float = 1f

        @Volatile var rotation: Float = 0f

        fun apply(
            @Suppress("UNUSED_PARAMETER") pan: Offset,
            zoomChange: Float,
            rotationChange: Float,
        ) {
            zoom *= zoomChange
            rotation += rotationChange
        }

        fun reset() {
            zoom = 1f
            rotation = 0f
        }
    }

    @Composable
    private fun Transformable(
        transform: Transform,
        modifier: Modifier = Modifier,
    ) {
        val state = rememberTransformableState { zoom, pan, rotation -> transform.apply(pan, zoom, rotation) }
        Box(Modifier.fillMaxSize().then(modifier).transformable(state))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun PointerEventType.isScale(): Boolean =
        this == PointerEventType.ScaleStart ||
            this == PointerEventType.ScaleChange ||
            this == PointerEventType.ScaleEnd

    private fun expectedScaleTypes(changes: Int): List<PointerEventType> =
        listOf(PointerEventType.ScaleStart) +
            List(changes) { PointerEventType.ScaleChange } +
            PointerEventType.ScaleEnd

    private fun linuxOnly(): String? =
        if (Platform.Current != Platform.Linux) "Linux only — GdkEventTouchpadPinch injection" else null

    /** `GdkTouchpadGesturePhase`. */
    private const val PHASE_BEGIN = 0
    private const val PHASE_UPDATE = 1
    private const val PHASE_END = 2
    private const val PHASE_CANCEL = 3

    private const val MICRO = 1_000_000.0

    /** Must match `events.rs::CURSOR_FIXED_SCALE`. */
    private const val CURSOR_FIXED_SCALE = 1024f

    /** Widget-local logical px, well inside the 800×600 default window. */
    private const val TARGET_X = 400
    private const val TARGET_Y = 300

    /** Inside the title bar's 40 dp band, clear of its controls. */
    private const val TITLE_BAR_Y = 18

    /** GDK's absolute scale after each update. */
    private val SCALES = listOf(1.01, 1.03, 1.04, 1.02)

    private const val NOISY_STEPS = 12
    private const val NOISY_STEP_RATIO = 1.02

    /** About a degree per update — what a real pinch carries. */
    private const val NOISE_RADIANS = 0.017

    private const val TWIST_STEPS = 16

    /** 3° per update, clockwise. */
    private const val TWIST_STEP_RADIANS = 0.05235987755982988
    private const val TWIST_TOTAL_DEGREES = 48f
    private const val ROTATION_TOLERANCE_DEGREES = 12f

    private const val FACTOR_TOLERANCE = 2e-3f
    private const val POSITION_TOLERANCE_PX = 1.5f
    private const val STEP_MILLIS = 16L

    /** How long a transformable gets to react before the (soft) wait gives up. */
    private const val REACTION_MILLIS = 2_000L
}
