package dev.nucleusframework.window.tao.event

import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import kotlin.math.abs

/**
 * One step of a window's DirectManipulation viewport (#706), as the native
 * side reports it (`EventCallback.onDirectManipulation`). The transform and
 * the focal point are client-area physical pixels.
 */
internal data class TaoDirectManipulationEvent(
    val kind: Int,
    val status: Int,
    val previousStatus: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val focalX: Float,
    val focalY: Float,
) {
    internal companion object {
        /** The viewport moved from [previousStatus] to [status]. */
        const val STATUS: Int = 0

        /** A new content transform while the viewport is in [status]. */
        const val CONTENT: Int = 1
    }
}

/** `DIRECTMANIPULATION_STATUS` values, as the wire carries them. */
@Suppress("MagicNumber")
internal object TaoDirectManipulationStatus {
    const val BUILDING: Int = 0
    const val ENABLED: Int = 1
    const val DISABLED: Int = 2
    const val RUNNING: Int = 3
    const val INERTIA: Int = 4
    const val READY: Int = 5
    const val SUSPENDED: Int = 6
}

/**
 * Turns a DirectManipulation viewport's stream into the two gestures Compose
 * knows (#706): a pan in the macOS scroll-gesture shape ([TaoScrollGesturePhase],
 * inertia included) and a pinch with real magnify phases ([TaoTrackpadPhase]).
 *
 * The viewport reports one content transform (scale + translation) for
 * whatever the fingers do; which gesture a sequence is follows Chromium's
 * `DirectManipulationEventHandler`: a sequence is a pan while its scale stays
 * at the value it started with and becomes a pinch the moment the scale moves
 * — for good, since a slow pinch can open with a little translation, while
 * the translation that goes with a pinch is noise and is dropped. The lift
 * into inertia continues a pan as its momentum tail and ends a pinch (no
 * scaling inertia is configured, so nothing that follows is a zoom).
 *
 * Every sequence is rebased on the transform it starts from, so a native
 * reset that did not happen costs nothing but drift. Every open gesture is
 * closed by the sequence's end, by a status that is not a manipulation
 * (`SUSPENDED`, `DISABLED`, …) or by [cancel] — there is no timer anywhere.
 *
 * UI thread only.
 */
internal class TaoDirectManipulationGesture(
    private val sink: Sink,
) {
    /** Where the recognised gestures go. Pan deltas are content motion, px. */
    interface Sink {
        fun pan(
            phase: TaoScrollGesturePhase,
            dxPx: Float,
            dyPx: Float,
            focalX: Float,
            focalY: Float,
        )

        /** [phase] is a [TaoTrackpadPhase]; [scaleFactor] is multiplicative, per step. */
        fun pinch(
            phase: Int,
            scaleFactor: Float,
            focalX: Float,
            focalY: Float,
        )
    }

    private enum class State {
        /** No sequence, or one whose gesture is not known yet. */
        IDLE,
        PAN,

        /** The inertial tail of a pan. */
        FLING,
        PINCH,

        /** The sequence's gesture already ended (a pinch lifted into inertia). */
        SPENT,
    }

    private var inSequence = false
    private var state = State.IDLE
    private var baseScale = 1f
    private var lastScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var focalX = 0f
    private var focalY = 0f

    /** Whether a pan (or its tail) or a pinch is open. */
    val gestureOpen: Boolean get() = state == State.PAN || state == State.FLING || state == State.PINCH

    fun onEvent(event: TaoDirectManipulationEvent) {
        when (event.kind) {
            TaoDirectManipulationEvent.STATUS -> onStatus(event)
            TaoDirectManipulationEvent.CONTENT -> onContent(event)
        }
    }

    /** Closes whatever is open as cancelled: teardown, or the input went elsewhere. */
    fun cancel() {
        finish(cancelled = true)
        inSequence = false
    }

    private fun onStatus(event: TaoDirectManipulationEvent) {
        if (event.status == event.previousStatus) return
        noteFocal(event)
        when (event.status) {
            TaoDirectManipulationStatus.RUNNING -> {
                // A new sequence — also out of an inertial tail: fingers back
                // on the pad stop the glide, like AppKit's MayBegin.
                finish(cancelled = false)
                startSequence(event)
            }
            TaoDirectManipulationStatus.INERTIA -> onLift(event)
            TaoDirectManipulationStatus.READY -> {
                finish(cancelled = false)
                inSequence = false
            }
            else -> {
                // SUSPENDED / DISABLED / BUILDING / ENABLED: the manipulation
                // did not end, it was taken away.
                if (inSequence) cancel()
            }
        }
    }

    private fun onLift(event: TaoDirectManipulationEvent) {
        // Inertia the user did not start (a programmatic zoom) is not a gesture.
        if (event.previousStatus != TaoDirectManipulationStatus.RUNNING || !inSequence) return
        when (state) {
            State.PAN -> {
                pan(TaoScrollGesturePhase.ENDED)
                pan(TaoScrollGesturePhase.MOMENTUM_BEGAN)
                state = State.FLING
            }
            // A flick too quick for any update before the lift: the glide is
            // the whole pan.
            State.IDLE -> {
                pan(TaoScrollGesturePhase.BEGAN)
                pan(TaoScrollGesturePhase.ENDED)
                pan(TaoScrollGesturePhase.MOMENTUM_BEGAN)
                state = State.FLING
            }
            State.PINCH -> {
                sink.pinch(TaoTrackpadPhase.ENDED, 1f, focalX, focalY)
                state = State.SPENT
            }
            State.FLING, State.SPENT -> Unit
        }
    }

    private fun onContent(event: TaoDirectManipulationEvent) {
        if (!event.isUsableTransform()) return
        if (!inSequence) {
            // The status that opened this sequence never reached us: adopt it
            // from here, with this transform as its origin.
            if (event.status == TaoDirectManipulationStatus.RUNNING) startSequence(event)
            return
        }
        noteFocal(event)
        val stepScale = event.scale / lastScale
        val dx = event.offsetX - lastX
        val dy = event.offsetY - lastY
        val scaled = abs(event.scale / baseScale - 1f) > SCALE_EPSILON
        lastScale = event.scale
        lastX = event.offsetX
        lastY = event.offsetY
        if (stepScale !in MIN_STEP_SCALE..MAX_STEP_SCALE || abs(dx) > MAX_STEP_PX || abs(dy) > MAX_STEP_PX) {
            // No finger moves the content this far between two updates: the
            // transform jumped (a reset we were not told about). Rebase on it.
            baseScale = event.scale
            return
        }
        step(scaled, stepScale, dx, dy)
    }

    /** One content update of the open sequence, recognised by [state]. */
    private fun step(
        scaled: Boolean,
        stepScale: Float,
        dx: Float,
        dy: Float,
    ) {
        when (state) {
            State.IDLE ->
                if (scaled) {
                    beginPinch(stepScale)
                } else if (dx != 0f || dy != 0f) {
                    state = State.PAN
                    sink.pan(TaoScrollGesturePhase.BEGAN, dx, dy, focalX, focalY)
                }
            State.PAN ->
                if (scaled) {
                    // A slow pinch that opened with some translation.
                    pan(TaoScrollGesturePhase.CANCELLED)
                    beginPinch(stepScale)
                } else if (dx != 0f || dy != 0f) {
                    sink.pan(TaoScrollGesturePhase.CHANGED, dx, dy, focalX, focalY)
                }
            State.FLING ->
                if (dx != 0f || dy != 0f) {
                    sink.pan(TaoScrollGesturePhase.MOMENTUM_CHANGED, dx, dy, focalX, focalY)
                }
            State.PINCH ->
                if (stepScale != 1f) sink.pinch(TaoTrackpadPhase.CHANGED, stepScale, focalX, focalY)
            State.SPENT -> Unit
        }
    }

    private fun TaoDirectManipulationEvent.isUsableTransform(): Boolean =
        scale.isFinite() && scale > 0f && offsetX.isFinite() && offsetY.isFinite()

    private fun beginPinch(stepScale: Float) {
        state = State.PINCH
        sink.pinch(TaoTrackpadPhase.BEGAN, stepScale, focalX, focalY)
    }

    private fun startSequence(event: TaoDirectManipulationEvent) {
        inSequence = true
        state = State.IDLE
        val scale = if (event.scale.isFinite() && event.scale > 0f) event.scale else 1f
        baseScale = scale
        lastScale = scale
        lastX = if (event.offsetX.isFinite()) event.offsetX else 0f
        lastY = if (event.offsetY.isFinite()) event.offsetY else 0f
    }

    private fun finish(cancelled: Boolean) {
        when (state) {
            State.PAN -> pan(if (cancelled) TaoScrollGesturePhase.CANCELLED else TaoScrollGesturePhase.ENDED)
            State.FLING -> pan(if (cancelled) TaoScrollGesturePhase.CANCELLED else TaoScrollGesturePhase.MOMENTUM_ENDED)
            State.PINCH ->
                sink.pinch(if (cancelled) TaoTrackpadPhase.CANCELLED else TaoTrackpadPhase.ENDED, 1f, focalX, focalY)
            State.IDLE, State.SPENT -> Unit
        }
        state = State.IDLE
    }

    private fun pan(phase: TaoScrollGesturePhase) = sink.pan(phase, 0f, 0f, focalX, focalY)

    private fun noteFocal(event: TaoDirectManipulationEvent) {
        if (event.focalX.isFinite() && event.focalY.isFinite()) {
            focalX = event.focalX
            focalY = event.focalY
        }
    }

    internal companion object {
        /**
         * How far a sequence's scale may drift from where it started and
         * still be a pan: DirectManipulation keeps a pan's scale exact, and a
         * pinch clears a tenth of a percent within its first frames.
         */
        const val SCALE_EPSILON: Float = 1e-3f

        // One update's worth of manipulation beyond which the transform is
        // taken to have jumped rather than moved (see onContent).
        private const val MIN_STEP_SCALE: Float = 1f / 16f
        private const val MAX_STEP_SCALE: Float = 16f
        private const val MAX_STEP_PX: Float = 10_000f

        /**
         * `-Dnucleus.tao.directManipulation=false` leaves the precision
         * touchpad on the OS's legacy emulation (wheel ticks for a pan,
         * Ctrl+wheel ticks for a pinch). Also off with
         * `-Dnucleus.tao.trackpadPanEvents=false`: a DirectManipulation pan
         * can only reach Compose as Pan events. Read once.
         */
        val enabled: Boolean =
            System.getProperty("nucleus.tao.directManipulation", "true").toBoolean() &&
                System.getProperty("nucleus.tao.trackpadPanEvents", "true").toBoolean()
    }
}
