package dev.nucleusframework.window.tao.headful

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.headful.MacTrackpadGestureProbe.Kind
import dev.nucleusframework.window.tao.headful.MacTrackpadGestureProbe.Phase
import java.util.Collections
import kotlin.math.abs

/**
 * #660 end-to-end on macOS: an AppKit magnify gesture must reach Compose as
 * `ScaleStart` / `ScaleChange` / `ScaleEnd` at the cursor, never as two
 * synthetic Touch contacts. Every case queues real gesture NSEvents on
 * `NSApp` ([MacTrackpadGestureProbe]), so the whole chain runs:
 * the `touchpad_gestures.m` local monitor → Rust loop → `TaoWindow` →
 * `TaoComposeSceneHost.onTrackpadGesture` → `ComposeScene`.
 *
 * Rotation still synthesises two Touch pointers (Compose has no rotation
 * event); the rotate cases guard that half and its interplay with a pinch —
 * a real trackpad pinch interleaves magnify and rotate events.
 */
internal object MacOsTrackpadScaleHeadfulCases {
    fun all(): List<TaoWindowTestCase> =
        listOf(
            pinchArrivesAsScaleEventsAtTheCursor(),
            onePercentPinchZoomsTransformable(),
            pinchAtMapEdgeReachesOnlyTheMap(),
            smartMagnifyIsOneDiscreteScaleStep(),
            cancelledPinchClosesTheScaleGesture(),
            rotateStillRotatesDetectTransformGestures(),
            pinchFirstOwnsAnInterleavedGesture(),
            rotateFirstOwnsAnInterleavedGesture(),
        )

    /**
     * Began / Changed… / Ended arrives as exactly one ScaleStart, one
     * ScaleChange per non-zero magnification carrying `1 + magnification`,
     * and one ScaleEnd — all at the cursor, with no press, no Touch pointer
     * and no Scroll.
     */
    private fun pinchArrivesAsScaleEventsAtTheCursor(): TaoWindowTestCase {
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS pinch arrives as Compose Scale events at the cursor",
            skip = { macOnly() },
            // The suite's default chrome is a fillMaxSize sibling stacked above
            // [content]; leaving it on gives the recorder 0 height.
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            recorder.reset()

            magnify(Phase.BEGAN, 0.0)
            MAGNIFICATIONS.forEach { magnify(Phase.CHANGED, it) }
            magnify(Phase.ENDED, 0.0)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            val events = recorder.snapshot()
            val scale = events.filter { it.type.isScale() }
            check(scale.map { it.type } == expectedScaleTypes(MAGNIFICATIONS.size)) {
                "one ScaleStart, one ScaleChange per magnification, one ScaleEnd; recorded=${recorder.describe()}"
            }
            val factors = scale.filter { it.type == PointerEventType.ScaleChange }.map { it.scaleFactor }
            MAGNIFICATIONS.zip(factors).forEach { (magnification, factor) ->
                check(abs(factor - (1f + magnification.toFloat())) <= FACTOR_TOLERANCE) {
                    "ScaleChange must carry 1 + magnification ($magnification → $factor); " +
                        "recorded=${recorder.describe()}"
                }
            }
            val cursor = Offset(TARGET_X * window.scaleFactor, TARGET_Y * window.scaleFactor)
            scale.forEach {
                check((it.position - cursor).getDistance() <= POSITION_TOLERANCE_PX) {
                    "Scale events must sit at the cursor $cursor (got ${it.position}); recorded=${recorder.describe()}"
                }
                check(it.pointerType == PointerType.Mouse) {
                    "Scale events must come from the mouse pointer (got ${it.pointerType})"
                }
            }
            check(events.none { it.pointerType == PointerType.Touch }) {
                "a pinch must not synthesise Touch contacts any more; recorded=${recorder.describe()}"
            }
            check(events.none { it.type == PointerEventType.Press || it.type == PointerEventType.Scroll }) {
                "a pinch must produce no Press and no Scroll; recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * Through foundation: a 1 % pinch zooms `Modifier.transformable` on its
     * first step — under the two-touch synthesis it took ~13 such steps to
     * clear the touch slop — and a pinch-out zooms it back.
     */
    private fun onePercentPinchZoomsTransformable(): TaoWindowTestCase {
        val zoom = Transform()
        return TaoWindowTestCase(
            name = "#660 macOS 1% pinch zooms Modifier.transformable with no slop",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Transformable(zoom) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            zoom.reset()

            magnify(Phase.BEGAN, 0.0)
            magnify(Phase.CHANGED, ONE_PERCENT)
            awaitUntilOrTimeout(REACTION_MILLIS) { zoom.zoom != 1f }
            check(abs(zoom.zoom - (1f + ONE_PERCENT.toFloat())) <= FACTOR_TOLERANCE) {
                "the first 1% step must zoom the transformable at once (zoom=${zoom.zoom})"
            }
            magnify(Phase.CHANGED, -ONE_PERCENT * 2)
            awaitUntilOrTimeout(REACTION_MILLIS) { zoom.zoom < 1f }
            magnify(Phase.ENDED, 0.0)
            check(zoom.zoom < 1f) { "a pinch-out must zoom back out (zoom=${zoom.zoom})" }
            check(zoom.rotation == 0f && zoom.pan == Offset.Zero) {
                "a pure pinch must neither rotate nor pan (rotation=${zoom.rotation} pan=${zoom.pan})"
            }
        }
    }

    /**
     * The MapLibre report: the cursor 10 dp inside the map's left edge. The
     * two-touch synthesis planted a contact 120 px left of the cursor, in the
     * neighbouring chrome; the Scale events must hit the map only.
     */
    private fun pinchAtMapEdgeReachesOnlyTheMap(): TaoWindowTestCase {
        val chrome = EventRecorder()
        val map = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS pinch at a map edge reaches only the map",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width((TARGET_X - EDGE_INSET_DP).dp).fillMaxHeight().record(chrome))
                    Box(Modifier.weight(1f).fillMaxHeight().record(map))
                }
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            chrome.reset()
            map.reset()

            magnify(Phase.BEGAN, 0.0)
            repeat(EDGE_STEPS) { magnify(Phase.CHANGED, ONE_PERCENT) }
            magnify(Phase.ENDED, 0.0)
            awaitUntil("map got ScaleEnd") { map.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            check(map.count(PointerEventType.ScaleChange) == EDGE_STEPS) {
                "every step must reach the map under the cursor; map=${map.describe()}"
            }
            check(chrome.snapshot().none { it.type.isScale() || it.type == PointerEventType.Press }) {
                "the neighbouring chrome must see no part of the pinch; chrome=${chrome.describe()}"
            }
        }
    }

    /** A smart-magnify (two-finger double tap) is one discrete 1.5× Scale step. */
    private fun smartMagnifyIsOneDiscreteScaleStep(): TaoWindowTestCase {
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS smart-magnify is one discrete Scale step",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            recorder.reset()

            inject(Kind.SMART_MAGNIFY, Phase.NONE, 0.0)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()
            val scale = recorder.snapshot().filter { it.type.isScale() }
            check(scale.map { it.type } == expectedScaleTypes(1)) {
                "smart-magnify must be ScaleStart, one ScaleChange, ScaleEnd; recorded=${recorder.describe()}"
            }
            check(abs(scale[1].scaleFactor - SMART_MAGNIFY_FACTOR) <= FACTOR_TOLERANCE) {
                "smart-magnify must carry the 1.5× step; recorded=${recorder.describe()}"
            }
        }
    }

    /** A pinch the system cancels still closes with exactly one ScaleEnd. */
    private fun cancelledPinchClosesTheScaleGesture(): TaoWindowTestCase {
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS cancelled pinch closes the Scale gesture",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Box(Modifier.fillMaxSize().record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            recorder.reset()

            magnify(Phase.BEGAN, 0.0)
            magnify(Phase.CHANGED, ONE_PERCENT)
            magnify(Phase.CANCELLED, 0.0)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()
            check(recorder.snapshot().filter { it.type.isScale() }.map { it.type } == expectedScaleTypes(1)) {
                "a cancelled pinch must close with one ScaleEnd; recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * Rotation keeps the two-touch synthesis: `detectTransformGestures` sees
     * the angle change (clockwise on screen for AppKit's counter-clockwise
     * `rotation`, flipped into Compose's y-down space) and no zoom, and no
     * Scale event is emitted.
     */
    private fun rotateStillRotatesDetectTransformGestures(): TaoWindowTestCase {
        val transform = Transform()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS two-finger rotate still rotates detectTransformGestures",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .record(recorder)
                        .pointerInput(transform) {
                            detectTransformGestures { _, pan, zoom, rotation -> transform.apply(pan, zoom, rotation) }
                        },
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()
            recorder.reset()

            rotate(Phase.BEGAN, 0.0)
            repeat(ROTATE_STEPS) { rotate(Phase.CHANGED, ROTATE_STEP_DEGREES) }
            rotate(Phase.ENDED, 0.0)
            awaitUntil("rotation reached detectTransformGestures") { transform.rotation != 0f }
            settle()

            check(transform.rotation < 0f) {
                "a counter-clockwise AppKit rotation must rotate Compose content counter-clockwise " +
                    "(negative rotationZ); rotation=${transform.rotation}"
            }
            check(abs(transform.zoom - 1f) <= FACTOR_TOLERANCE) {
                "a pure rotation must not zoom (zoom=${transform.zoom})"
            }
            check(recorder.snapshot().none { it.type.isScale() }) {
                "a rotation must emit no Scale event; recorded=${recorder.describe()}"
            }
        }
    }

    /**
     * A real trackpad interleaves magnify and rotate. When the pinch begins
     * first it owns the gesture: the rotate steps are dropped, so no touch
     * contact is ever pressed (a Scale event lists every active pointer; one
     * without the contacts read as their release, and each rotate step
     * re-pressed them — a touch tap per step), and every magnification zooms
     * `Modifier.transformable` exactly once.
     */
    private fun pinchFirstOwnsAnInterleavedGesture(): TaoWindowTestCase {
        val transform = Transform()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS pinch-first interleaved gesture stays Scale-only",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = { Transformable(transform, Modifier.record(recorder)) },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()
            recorder.reset()

            magnify(Phase.BEGAN, 0.0)
            rotate(Phase.BEGAN, 0.0)
            repeat(INTERLEAVED_STEPS) {
                magnify(Phase.CHANGED, INTERLEAVED_MAGNIFICATION)
                rotate(Phase.CHANGED, ROTATE_STEP_DEGREES)
            }
            rotate(Phase.ENDED, 0.0)
            magnify(Phase.ENDED, 0.0)
            awaitUntil("ScaleEnd recorded") { recorder.count(PointerEventType.ScaleEnd) >= 1 }
            settle()

            check(recorder.snapshot().none { it.pointerType == PointerType.Touch }) {
                "a rotation inside a pinch must press no touch contact; recorded=${recorder.describe()}"
            }
            val scaleTypes = recorder.snapshot().filter { it.type.isScale() }.map { it.type }
            check(scaleTypes == expectedScaleTypes(INTERLEAVED_STEPS)) {
                "every magnification must be one ScaleChange; recorded=${recorder.describe()}"
            }
            check(abs(transform.zoom - interleavedZoom()) <= FACTOR_TOLERANCE) {
                "every magnification must zoom transformable exactly once (zoom=${transform.zoom}, " +
                    "expected ${interleavedZoom()})"
            }
        }
    }

    /**
     * When the rotation begins first it owns the gesture: the contacts go
     * down once and up once, the magnify steps widen them (as before #660) so
     * `detectTransformGestures` both rotates and zooms, and no Scale event is
     * emitted.
     */
    private fun rotateFirstOwnsAnInterleavedGesture(): TaoWindowTestCase {
        val transform = Transform()
        val recorder = EventRecorder()
        return TaoWindowTestCase(
            name = "#660 macOS rotate-first interleaved gesture keeps its contacts down and zooms them",
            skip = { macOnly() },
            paintDefaultBackground = false,
            content = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .record(recorder)
                        .pointerInput(transform) {
                            detectTransformGestures { _, pan, zoom, rotation -> transform.apply(pan, zoom, rotation) }
                        },
                )
            },
        ) {
            awaitUntil("window mapped") { bounds() != null }
            settle()
            transform.reset()
            recorder.reset()

            rotate(Phase.BEGAN, 0.0)
            magnify(Phase.BEGAN, 0.0)
            repeat(INTERLEAVED_STEPS) {
                rotate(Phase.CHANGED, ROTATE_STEP_DEGREES)
                magnify(Phase.CHANGED, INTERLEAVED_MAGNIFICATION)
            }
            magnify(Phase.ENDED, 0.0)
            rotate(Phase.ENDED, 0.0)
            settle()

            val events = recorder.snapshot()
            check(events.count { it.down } == 2 && events.count { it.up } == 2) {
                "the two contacts must go down once and up once; recorded=${recorder.describe()}"
            }
            check(events.none { it.type.isScale() }) {
                "a magnify inside a rotation must emit no Scale event; recorded=${recorder.describe()}"
            }
            check(transform.rotation < 0f) { "the rotation must reach detectTransformGestures (${transform.rotation})" }
            check(transform.zoom > 1f) { "the magnify steps must widen the contacts (zoom=${transform.zoom})" }
        }
    }

    private fun interleavedZoom(): Float =
        Math.pow(1.0 + INTERLEAVED_MAGNIFICATION, INTERLEAVED_STEPS.toDouble()).toFloat()

    // ── Injection ───────────────────────────────────────────────────────────

    private suspend fun TaoWindowTestScope.magnify(
        phase: Int,
        magnification: Double,
    ) = inject(Kind.MAGNIFY, phase, magnification)

    private suspend fun TaoWindowTestScope.rotate(
        phase: Int,
        degrees: Double,
    ) = inject(Kind.ROTATE, phase, degrees)

    private suspend fun TaoWindowTestScope.inject(
        kind: Int,
        phase: Int,
        value: Double,
    ) {
        val delivered = MacTrackpadGestureProbe.inject(window, kind, phase, TARGET_X, TARGET_Y, value)
        check(delivered) { "nativeDiagInjectTrackpadGesture returned false (injection disabled or window gone?)" }
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

    private class Transform {
        @Volatile var zoom: Float = 1f

        @Volatile var rotation: Float = 0f

        @Volatile var pan: Offset = Offset.Zero

        fun apply(
            panChange: Offset,
            zoomChange: Float,
            rotationChange: Float,
        ) {
            zoom *= zoomChange
            rotation += rotationChange
            pan += panChange
        }

        fun reset() {
            zoom = 1f
            rotation = 0f
            pan = Offset.Zero
        }
    }

    @Composable
    private fun Transformable(
        transform: Transform,
        modifier: Modifier = Modifier,
    ) {
        val state = rememberTransformableState { _, zoom, pan, rotation -> transform.apply(pan, zoom, rotation) }
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

    private fun macOnly(): String? =
        when {
            Platform.Current != Platform.MacOS -> "macOS only — AppKit gesture NSEvent injection"
            !MacTrackpadGestureProbe.available -> "nucleus_tao_metal not loaded"
            else -> null
        }

    /** Content-local injection point (points, top-left origin), well inside the 800×600 default window. */
    private const val TARGET_X = 400f
    private const val TARGET_Y = 300f

    private val MAGNIFICATIONS = listOf(0.01, 0.02, 0.01, -0.02)
    private const val ONE_PERCENT = 0.01
    private const val SMART_MAGNIFY_FACTOR = 1.5f

    /** The map's left edge sits this far left of the cursor. */
    private const val EDGE_INSET_DP = 10f
    private const val EDGE_STEPS = 3

    private const val ROTATE_STEPS = 4
    private const val ROTATE_STEP_DEGREES = 5.0
    private const val INTERLEAVED_STEPS = 5
    private const val INTERLEAVED_MAGNIFICATION = 0.02

    private const val FACTOR_TOLERANCE = 1e-3f
    private const val POSITION_TOLERANCE_PX = 1.5f
    private const val STEP_MILLIS = 16L

    /** How long a transformable gets to react before the (soft) wait gives up. */
    private const val REACTION_MILLIS = 2_000L
}
