package dev.nucleusframework.window.tao.scene

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import dev.nucleusframework.window.tao.TaoScrollGesturePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * State machine of [TaoTrackpadPanRouter] (#654) against a hand-driven
 * scheduler: the finger `Ended` must defer `PanEnd` so AppKit's momentum tail
 * continues the same pan, and a swipe with no tail must still close.
 */
class TaoTrackpadPanRouterTest {
    private class Harness {
        val sent = mutableListOf<Pair<PointerEventType, Offset>>()
        private var pending: (() -> Unit)? = null
        var cancelled = 0
        var lastDelayMillis = -1L

        val router =
            TaoTrackpadPanRouter(
                schedule = { delayMillis, action ->
                    lastDelayMillis = delayMillis
                    pending = action
                    (
                        {
                            if (pending === action) pending = null
                            cancelled++
                        }
                    )
                },
                send = { type, delta -> sent += type to delta },
            )

        /** Fires the deferred end as the grace timer would. */
        fun elapseGrace() {
            val action = pending ?: return
            pending = null
            action()
        }

        val hasPendingEnd: Boolean get() = pending != null

        fun types() = sent.map { it.first }
    }

    private val down = Offset(0f, 1f)

    @Test
    fun `swipe without momentum ends after the grace period`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)

        assertEquals(listOf(PointerEventType.PanStart, PointerEventType.PanMove), h.types())
        assertTrue(h.hasPendingEnd, "Ended must only schedule the PanEnd")
        assertEquals(TaoTrackpadPanRouter.momentumGraceMillis, h.lastDelayMillis)

        h.elapseGrace()
        assertEquals(
            listOf(PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd),
            h.types(),
        )
    }

    @Test
    fun `terminal steps carrying a delta still pan when no gesture is open`() {
        // AppKit's Ended can hold the last finger movement, and the Began may
        // have been missed (window became key mid-gesture): the distance must
        // not be dropped.
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.ENDED, down)
        assertEquals(listOf(PointerEventType.PanStart, PointerEventType.PanMove), h.types())
        assertTrue(h.hasPendingEnd)
        h.elapseGrace()
        assertEquals(PointerEventType.PanEnd, h.types().last())

        h.sent.clear()
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_ENDED, down)
        assertEquals(
            listOf(PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd),
            h.types(),
        )
        assertFalse(h.hasPendingEnd)
    }

    @Test
    fun `momentum tail continues the pan and ends it once`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_BEGAN, down / 2f)
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_CHANGED, down / 4f)
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_ENDED, Offset.Zero)

        assertEquals(
            listOf(
                PointerEventType.PanStart,
                PointerEventType.PanMove,
                PointerEventType.PanMove,
                PointerEventType.PanMove,
                PointerEventType.PanEnd,
            ),
            h.types(),
        )
        assertEquals(1, h.cancelled, "the momentum Began must cancel the deferred PanEnd")
        assertFalse(h.hasPendingEnd)
        // A stale grace timer firing later must not emit a second PanEnd.
        h.elapseGrace()
        assertEquals(1, h.types().count { it == PointerEventType.PanEnd })
    }

    @Test
    fun `pan offsets pass through unchanged and zero deltas send no move`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, Offset(-2.5f, 0.75f))
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, Offset.Zero)
        // TaoWindow negates the wire delta, so a zero step arrives as -0.0.
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, Offset(-0f, -0f))

        assertEquals(
            listOf(
                PointerEventType.PanStart to Offset.Zero,
                PointerEventType.PanMove to Offset(-2.5f, 0.75f),
            ),
            h.sent,
        )
    }

    @Test
    fun `cancelled closes immediately and may-begin alone is silent`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.MAY_BEGIN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CANCELLED, Offset.Zero)
        assertTrue(h.sent.isEmpty(), "resting fingers then lift must not touch Compose")

        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.onGesture(TaoScrollGesturePhase.CANCELLED, Offset.Zero)
        assertEquals(
            listOf(PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd),
            h.types(),
        )
        assertFalse(h.hasPendingEnd)
    }

    @Test
    fun `a new swipe during the grace period keeps the same pan open`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)

        assertEquals(1, h.types().count { it == PointerEventType.PanStart })
        assertEquals(0, h.types().count { it == PointerEventType.PanEnd })
        assertFalse(h.hasPendingEnd)
    }

    @Test
    fun `cancel drops the pending end without sending PanEnd`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        h.router.cancel()

        assertFalse(h.hasPendingEnd)
        h.elapseGrace()
        assertEquals(listOf(PointerEventType.PanStart), h.types())
    }
}
