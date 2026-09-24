@file:OptIn(InternalComposeUiApi::class)

package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.nucleusframework.window.tao.TaoPointerScrollEvent
import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import dev.nucleusframework.window.tao.TaoTrackpadPhase
import dev.nucleusframework.window.tao.event.AWT_PIXEL_TO_ROTATION
import dev.nucleusframework.window.tao.event.TaoDirectManipulationEvent
import dev.nucleusframework.window.tao.event.TaoDirectManipulationGesture
import dev.nucleusframework.window.tao.event.TaoTrackpadScaleSession
import dev.nucleusframework.window.tao.event.TaoWheelPinchZoom
import dev.nucleusframework.window.tao.event.dispatchTrackpadScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Windows host's touchpad pan and pinch, from both of their sources.
 *
 * - **DirectManipulation** (#706, the default): the window's viewport reports
 *   the manipulation as a raw stream ([onDirectManipulation]);
 *   [TaoDirectManipulationGesture] recognises a pan — sent through
 *   [TaoSceneScrollRouter] as Compose Pan events, the macOS shape (#654),
 *   inertia included — or a pinch with real phases and focal point (#660).
 * - **The legacy emulation** — no viewport (disabled, refused, a touchpad
 *   driver that never offers the contact) — and a mouse's Ctrl+wheel: the OS
 *   sends Ctrl-flagged WM_MOUSEWHEEL ticks, which the vendored Tao patch
 *   routes to the magnify hook ([onCtrlWheel]). A tick has no phase, so the
 *   ticks keep ONE continuous Compose scale gesture: the first opens
 *   `ScaleStart`, each is a `ScaleChange`, and an idle debounce sends
 *   `ScaleEnd`.
 *
 * [scope] runs the debounce on the UI thread; [schedule] and [clock] are
 * the router's timer and time, supplied by tests only. UI thread only.
 */
internal class TaoWindowsTouchpadInput(
    private val target: Target,
    private val scope: CoroutineScope,
    schedule: ((delayMillis: Long, action: () -> Unit) -> (() -> Unit))? = null,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** What the input needs from its host, read live at dispatch time. */
    interface Target : TaoSceneScrollRouter.Target {
        val keyboardModifiers: PointerKeyboardModifiers

        /** Where the cursor is, scene px: the centre of a Ctrl+wheel zoom. */
        val pointerPosition: Offset
    }

    private var pinchCenter = Offset.Zero
    private val scaleSession =
        TaoTrackpadScaleSession { type, factor ->
            target.scene?.dispatchTrackpadScale(
                x = pinchCenter.x,
                y = pinchCenter.y,
                type = type,
                scaleFactor = factor,
                keyboardModifiers = target.keyboardModifiers,
            )
        }
    private var pinchEndJob: Job? = null

    /** A DirectManipulation pinch owns [scaleSession]: its own phases close it, not the debounce. */
    private var manipulationPinchOpen = false

    /** False while the window takes no input (a modal child is up). */
    private var inputEnabled = true

    private val scrollRouter =
        TaoSceneScrollRouter(
            target,
            schedule = schedule,
            clock = clock,
            // An orphaned momentum step in the wheel shape would be read in
            // Windows notches (a viewport fraction each) — a jump, not a glide;
            // Compose's own fling already carries the flick once the pan closed.
            orphanedMomentumAsWheel = false,
        )

    private val gesture =
        TaoDirectManipulationGesture(
            object : TaoDirectManipulationGesture.Sink {
                override fun pan(
                    phase: TaoScrollGesturePhase,
                    dxPx: Float,
                    dyPx: Float,
                    focalX: Float,
                    focalY: Float,
                ) = onPan(phase, dxPx, dyPx, focalX, focalY)

                override fun pinch(
                    phase: Int,
                    scaleFactor: Float,
                    focalX: Float,
                    focalY: Float,
                ) = onPinch(phase, scaleFactor, focalX, focalY)
            },
        )

    /** Whether a pan is open, its deferred `PanEnd` included. */
    val panOpen: Boolean get() = scrollRouter.panOpen

    /** Whether a scale gesture is open, whichever source opened it. */
    val pinchOpen: Boolean get() = scaleSession.active

    /**
     * One step of the window's DirectManipulation viewport. While the window
     * takes no input ([inputEnabled] false) the gesture is still tracked — so
     * what it opened before is closed — but no step moves the scene.
     */
    fun onDirectManipulation(
        event: TaoDirectManipulationEvent,
        inputEnabled: Boolean,
    ) {
        this.inputEnabled = inputEnabled
        gesture.onEvent(event)
    }

    /**
     * One Ctrl+wheel tick, in WHEEL_DELTA-normalized notches (positive = zoom
     * in, fractional for a precision touchpad on the legacy emulation).
     */
    fun onCtrlWheel(notches: Float) {
        // Precision touchpads can deliver many fractional deltas; the
        // multiplicative curve lets small ticks accumulate smoothly without
        // each message behaving like a large zoom step.
        val step = TaoWheelPinchZoom.stepFromWheelDelta(notches)
        if (manipulationPinchOpen) {
            // Folded into the pinch in progress, which its own phases close.
            scaleSession.change(step)
            return
        }
        // Another device: a touchpad pan still open ends where it was.
        scrollRouter.finishPan()
        pinchCenter = target.pointerPosition
        scaleSession.change(step)
        schedulePinchEnd()
    }

    /** Closes an open pan now: a click or a wheel notch ends the gesture for Compose too. */
    fun finishPan() = scrollRouter.finishPan()

    /** Teardown: the scene is going away, so no `ScaleEnd` / `PanEnd` is sent. */
    fun cancel() {
        pinchEndJob?.cancel()
        pinchEndJob = null
        scrollRouter.cancel()
    }

    private fun onPan(
        phase: TaoScrollGesturePhase,
        dxPx: Float,
        dyPx: Float,
        focalX: Float,
        focalY: Float,
    ) {
        if (!inputEnabled && (dxPx != 0f || dyPx != 0f)) return
        // A Ctrl+wheel zoom still waiting for its debounce is over: the
        // touchpad took over, and Compose must not see a pan inside a zoom.
        if (phase == TaoScrollGesturePhase.BEGAN && !manipulationPinchOpen) endPinch()
        // Content motion in physical px → AWT `preciseWheelRotation` units,
        // the shape the router takes (and turns back into px at 10 dp a unit).
        val unitPx = AWT_PIXEL_TO_ROTATION * target.scale
        scrollRouter.onScroll(
            focalX,
            focalY,
            TaoPointerScrollEvent(
                dxAwt = -dxPx / unitPx,
                dyAwt = -dyPx / unitPx,
                scrollAmount = 1,
                gesturePhase = phase,
            ),
            target.keyboardModifiers,
        )
    }

    private fun onPinch(
        phase: Int,
        scaleFactor: Float,
        focalX: Float,
        focalY: Float,
    ) {
        pinchCenter = Offset(focalX, focalY)
        val factor = scaleFactor.coerceAtLeast(TaoTrackpadScaleSession.MIN_GESTURE_SCALE)
        when (phase) {
            TaoTrackpadPhase.BEGAN -> {
                // A Ctrl+wheel burst still open is a different gesture, and a
                // pan whose end still waits out its grace is over.
                endPinch()
                scrollRouter.finishPan()
                manipulationPinchOpen = true
                if (!inputEnabled) return
                scaleSession.start()
                scaleSession.change(factor)
            }
            TaoTrackpadPhase.CHANGED -> if (inputEnabled) scaleSession.change(factor)
            else -> {
                manipulationPinchOpen = false
                scaleSession.end()
            }
        }
    }

    /** Re-arms the idle timer that closes the scale gesture once ticks stop. */
    private fun schedulePinchEnd() {
        pinchEndJob?.cancel()
        pinchEndJob =
            scope.launch {
                delay(PINCH_IDLE_END_MS.milliseconds)
                endPinch()
            }
    }

    private fun endPinch() {
        pinchEndJob?.cancel()
        pinchEndJob = null
        scaleSession.end()
    }

    internal companion object {
        /** Idle gap that closes a Ctrl+wheel scale gesture. */
        const val PINCH_IDLE_END_MS: Long = 120L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
