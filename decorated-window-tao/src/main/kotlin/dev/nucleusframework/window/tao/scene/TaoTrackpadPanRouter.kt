package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import dev.nucleusframework.window.tao.TaoScrollGesturePhase

/**
 * Turns the macOS trackpad scroll gesture stream ([TaoScrollGesturePhase])
 * into Compose's `PanStart` / `PanMove` / `PanEnd` (#654).
 *
 * Why the stream is not mapped one-to-one: Compose's `TrackpadScrollingLogic`
 * runs its own fling from the tracked velocity as soon as it sees `PanEnd`,
 * while AppKit keeps delivering the inertial *momentum* tail after the fingers
 * lift (`momentumPhase`). Closing the pan on the finger `Ended` would stack
 * the two animations and the tail would re-open a second pan. The pan is
 * therefore kept open across the momentum tail and closed on `MomentumEnded`.
 * AppKit does not say in advance whether a tail will follow, so the finger
 * `Ended` only *schedules* the `PanEnd` and a momentum event arriving within
 * [MOMENTUM_GRACE_MILLIS] cancels it. By the time the pan really ends the
 * tracked velocity is ~0 and Compose adds no fling of its own — the platform
 * drives the inertia, exactly as under AWT where every step is a plain wheel
 * event.
 *
 * [send] receives the pan offset in AWT `preciseWheelRotation` units (the
 * shape of [dev.nucleusframework.window.tao.TaoPointerScrollEvent.dxAwt]); the
 * caller converts to pixels. [schedule] runs `action` after `delayMillis` on
 * the UI thread and returns a cancel handle. UI thread only.
 */
internal class TaoTrackpadPanRouter(
    private val schedule: (delayMillis: Long, action: () -> Unit) -> (() -> Unit),
    private val send: (type: PointerEventType, panAwt: Offset) -> Unit,
) {
    private var active = false
    private var cancelPendingEnd: (() -> Unit)? = null

    /** True between `PanStart` and `PanEnd` (including a pending deferred end). */
    val isPanning: Boolean get() = active

    fun onGesture(
        phase: Int,
        deltaAwt: Offset,
    ) {
        when (phase) {
            // Fingers resting on the glass: nothing to pan yet. A Cancelled
            // that follows without a Began is a no-op below.
            TaoScrollGesturePhase.MAY_BEGIN -> Unit
            TaoScrollGesturePhase.BEGAN,
            TaoScrollGesturePhase.CHANGED,
            TaoScrollGesturePhase.MOMENTUM_BEGAN,
            TaoScrollGesturePhase.MOMENTUM_CHANGED,
            -> {
                clearPendingEnd()
                start()
                move(deltaAwt)
            }
            TaoScrollGesturePhase.ENDED -> {
                if (!active) return
                move(deltaAwt)
                clearPendingEnd()
                cancelPendingEnd =
                    schedule(MOMENTUM_GRACE_MILLIS) {
                        cancelPendingEnd = null
                        finish()
                    }
            }
            TaoScrollGesturePhase.CANCELLED,
            TaoScrollGesturePhase.MOMENTUM_ENDED,
            -> {
                if (!active) return
                move(deltaAwt)
                finish()
            }
            // Unknown wire value: ignore rather than desynchronise the pan.
            else -> Unit
        }
    }

    /** Teardown: drops a pending deferred end and forgets the open pan (no `PanEnd` is sent). */
    fun cancel() {
        clearPendingEnd()
        active = false
    }

    private fun start() {
        if (active) return
        active = true
        send(PointerEventType.PanStart, Offset.Zero)
    }

    private fun move(deltaAwt: Offset) {
        // Float compares, not `!= Offset.Zero`: the wire negation turns a
        // zero delta (Began / Ended steps) into -0.0, whose packed bits differ
        // from +0.0 and would leak zero-offset PanMoves into Compose.
        if (deltaAwt.x != 0f || deltaAwt.y != 0f) send(PointerEventType.PanMove, deltaAwt)
    }

    private fun finish() {
        clearPendingEnd()
        if (!active) return
        active = false
        send(PointerEventType.PanEnd, Offset.Zero)
    }

    private fun clearPendingEnd() {
        cancelPendingEnd?.invoke()
        cancelPendingEnd = null
    }

    internal companion object {
        /**
         * AppKit posts the first momentum event within a frame or two of the
         * finger `Ended`; anything past this is a swipe with no inertia.
         */
        const val MOMENTUM_GRACE_MILLIS: Long = 100L
    }
}
