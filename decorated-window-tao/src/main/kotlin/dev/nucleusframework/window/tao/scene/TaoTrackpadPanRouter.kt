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
 * [graceMillis] cancels it. By the time the pan really ends the tracked
 * velocity is ~0 and Compose adds no fling of its own — the platform drives
 * the inertia, exactly as under AWT where every step is a plain wheel event.
 *
 * A terminal step (`Ended`, `Cancelled`, `MomentumEnded`) that still carries a
 * delta is delivered even when no pan is open — AppKit's `Ended` can hold the
 * last finger movement, and a `Began` may have been missed (window became key
 * mid-gesture) — so no scroll distance is ever dropped.
 *
 * [send] receives the pan offset in AWT `preciseWheelRotation` units (the
 * shape of [dev.nucleusframework.window.tao.TaoPointerScrollEvent.dxAwt]); the
 * caller converts to pixels. [schedule] runs `action` after `delayMillis` on
 * the UI thread and returns a cancel handle. UI thread only.
 */
internal class TaoTrackpadPanRouter(
    private val schedule: (delayMillis: Long, action: () -> Unit) -> (() -> Unit),
    private val send: (type: PointerEventType, panAwt: Offset) -> Unit,
    private val graceMillis: Long = momentumGraceMillis,
) {
    private var active = false
    private var cancelPendingEnd: (() -> Unit)? = null

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
                move(deltaAwt)
                if (!active) return
                clearPendingEnd()
                cancelPendingEnd =
                    schedule(graceMillis) {
                        cancelPendingEnd = null
                        finish()
                    }
            }
            TaoScrollGesturePhase.CANCELLED,
            TaoScrollGesturePhase.MOMENTUM_ENDED,
            -> {
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

    /** Opens the pan if needed and moves it; a zero delta is not a move. */
    private fun move(deltaAwt: Offset) {
        // Float compares, not `!= Offset.Zero`: the wire negation turns a
        // zero delta (Began / Ended steps) into -0.0, whose packed bits differ
        // from +0.0 and would leak zero-offset PanMoves into Compose.
        if (deltaAwt.x == 0f && deltaAwt.y == 0f) return
        start()
        send(PointerEventType.PanMove, deltaAwt)
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
         * How long a finger `Ended` waits for AppKit's momentum tail before the
         * pan is closed. AppKit posts the first momentum event within a frame
         * or two; the default leaves ample room and costs nothing for a swipe
         * without inertia (its fling velocity is ~0 anyway). Override with
         * `-Dnucleus.tao.trackpadMomentumGraceMillis=<ms>` if a machine ever
         * shows a stacked fling at the end of a flick.
         */
        const val DEFAULT_MOMENTUM_GRACE_MILLIS: Long = 150L

        val momentumGraceMillis: Long =
            System
                .getProperty("nucleus.tao.trackpadMomentumGraceMillis")
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?: DEFAULT_MOMENTUM_GRACE_MILLIS
    }
}
