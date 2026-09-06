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
 * An open pan is always bounded: every step re-arms an end timer ([graceMillis]
 * after the finger `Ended`, [stallMillis] otherwise), so a stream that is cut
 * short — fingers resting on the glass during the tail (`MayBegin`, which
 * closes the pan at once), a window losing key status, a terminal step that
 * never arrives — cannot leave Compose's scroll session open. A finger step
 * that still carries a delta is delivered even when no pan is open (AppKit's
 * `Ended` can hold the last finger movement, and a `Began` may have been
 * missed), so no finger distance is dropped; momentum steps, in contrast, only
 * continue an open pan — a late tail after the grace already closed the pan
 * is handed back to the caller (`false`) rather than stacked on Compose's fling.
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
    private val stallMillis: Long = DEFAULT_STALL_MILLIS,
) {
    private var active = false
    private var cancelPendingEnd: (() -> Unit)? = null

    /**
     * Routes one gesture step. Returns `false` for a momentum step that found
     * no open pan (the grace closed it first): the caller decides what to do
     * with its delta — the router itself never opens a pan for the tail.
     */
    fun onGesture(
        phase: TaoScrollGesturePhase,
        deltaAwt: Offset,
    ): Boolean {
        when (phase) {
            // Fingers touched the glass: a running momentum tail is over (AppKit
            // does not always follow with MomentumEnded); with no pan open,
            // nothing to do until Began.
            TaoScrollGesturePhase.MAY_BEGIN -> finish()
            TaoScrollGesturePhase.BEGAN,
            TaoScrollGesturePhase.CHANGED,
            -> {
                start()
                move(deltaAwt)
                armEnd(stallMillis)
            }
            TaoScrollGesturePhase.ENDED -> {
                move(deltaAwt)
                if (active) armEnd(graceMillis)
            }
            TaoScrollGesturePhase.CANCELLED -> {
                move(deltaAwt)
                finish()
            }
            // The inertial tail only ever continues an open pan. Once the pan
            // is closed — the grace elapsed before AppKit's first momentum
            // step, or the Began was never seen — Compose's own fling is
            // running, and opening a second pan would stack the platform
            // inertia on top of it (content overshoots by ~2×). The step is
            // reported as unhandled instead.
            TaoScrollGesturePhase.MOMENTUM_BEGAN,
            TaoScrollGesturePhase.MOMENTUM_CHANGED,
            -> {
                if (!active) return false
                move(deltaAwt)
                armEnd(stallMillis)
            }
            TaoScrollGesturePhase.MOMENTUM_ENDED -> {
                if (!active) return false
                move(deltaAwt)
                finish()
            }
        }
        return true
    }

    /** Closes an open pan now (a click, a wheel notch: the gesture is over). */
    fun finishNow() = finish()

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

    /** (Re-)arms the single end timer of the open pan. */
    private fun armEnd(delayMillis: Long) {
        clearPendingEnd()
        if (!active) return
        cancelPendingEnd =
            schedule(delayMillis) {
                cancelPendingEnd = null
                finish()
            }
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

        /**
         * Watchdog between two steps of an open pan. Finger and momentum steps
         * arrive at frame rate, so a gap this long means the stream was cut
         * short; the price of closing a pan whose fingers merely paused on the
         * glass is a new `PanStart` when they move again.
         */
        const val DEFAULT_STALL_MILLIS: Long = 1_000L

        val momentumGraceMillis: Long =
            System
                .getProperty("nucleus.tao.trackpadMomentumGraceMillis")
                ?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?: DEFAULT_MOMENTUM_GRACE_MILLIS
    }
}
