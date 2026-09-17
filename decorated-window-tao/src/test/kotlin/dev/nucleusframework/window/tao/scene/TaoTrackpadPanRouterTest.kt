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
 * continues the same pan, a swipe with no tail must still close, and no
 * truncated stream may leave the pan open.
 */
class TaoTrackpadPanRouterTest {
    private class Harness {
        val sent = mutableListOf<Pair<PointerEventType, Offset>>()
        private var pending: (() -> Unit)? = null
        private var fireAtMillis = 0L
        var nowMillis = 0L
        var cancelled = 0
        var lastDelayMillis = -1L

        val router =
            TaoTrackpadPanRouter(
                schedule = { delayMillis, action ->
                    lastDelayMillis = delayMillis
                    fireAtMillis = nowMillis + delayMillis
                    pending = action
                    (
                        {
                            if (pending === action) pending = null
                            cancelled++
                        }
                    )
                },
                send = { type, delta -> sent += type to delta },
                clock = { nowMillis },
            )

        /** Advances the clock to the pending timer and fires it, as the scheduler would. */
        fun elapseTimer() {
            val action = pending ?: return
            pending = null
            nowMillis = fireAtMillis
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

        h.elapseTimer()
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
        h.elapseTimer()
        assertEquals(PointerEventType.PanEnd, h.types().last())

        h.sent.clear()
        h.router.onGesture(TaoScrollGesturePhase.CANCELLED, down)
        assertEquals(
            listOf(PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd),
            h.types(),
        )
        assertFalse(h.hasPendingEnd)
    }

    @Test
    fun `a momentum tail arriving after the pan closed is handed back unhandled`() {
        // Grace elapsed before AppKit's first momentum step (loaded machine):
        // Compose is already flinging; a second pan would stack the inertia, so
        // the router reports the steps unhandled for the caller to scroll with.
        val h = Harness()
        assertTrue(h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero))
        assertTrue(h.router.onGesture(TaoScrollGesturePhase.CHANGED, down))
        assertTrue(h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero))
        h.elapseTimer()
        assertEquals(PointerEventType.PanEnd, h.types().last())

        h.sent.clear()
        assertFalse(h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_BEGAN, down))
        assertFalse(h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_CHANGED, down))
        assertFalse(h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_ENDED, down))
        assertTrue(h.sent.isEmpty(), "late momentum must not open a second pan, got ${h.types()}")
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
        assertFalse(h.hasPendingEnd)
        // A stale timer firing later must not emit a second PanEnd.
        h.elapseTimer()
        assertEquals(1, h.types().count { it == PointerEventType.PanEnd })
    }

    @Test
    fun `fingers resting on the glass during the tail close the pan at once`() {
        // AppKit interrupts a momentum tail with MayBegin and does not always
        // follow with MomentumEnded; the next swipe must get its own PanStart.
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_BEGAN, down)
        h.router.onGesture(TaoScrollGesturePhase.MAY_BEGIN, Offset.Zero)
        assertEquals(PointerEventType.PanEnd, h.types().last())
        assertFalse(h.hasPendingEnd)

        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        assertEquals(2, h.types().count { it == PointerEventType.PanStart })
    }

    @Test
    fun `a truncated stream is closed by the stall watchdog`() {
        // Every open step moves the end deadline, so a tail that simply stops
        // (window lost key status, terminal step never delivered) still ends.
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        assertTrue(h.hasPendingEnd, "an open pan must always have an end timer armed")
        assertEquals(TaoTrackpadPanRouter.DEFAULT_STALL_MILLIS, h.lastDelayMillis)

        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        assertEquals(TaoTrackpadPanRouter.momentumGraceMillis, h.lastDelayMillis, "Ended pulls the deadline in")
        h.router.onGesture(TaoScrollGesturePhase.MOMENTUM_BEGAN, down)
        // The momentum step pushes the deadline back out to the stall window
        // without touching the in-flight timer: on firing, that timer re-arms
        // for the remainder instead of ending the pan.
        h.elapseTimer()
        assertEquals(
            0,
            h.types().count { it == PointerEventType.PanEnd },
            "grace timer must defer to the later deadline",
        )
        assertEquals(
            TaoTrackpadPanRouter.DEFAULT_STALL_MILLIS - TaoTrackpadPanRouter.momentumGraceMillis,
            h.lastDelayMillis,
            "re-armed for the remainder of the stall window",
        )
        h.elapseTimer()
        assertEquals(PointerEventType.PanEnd, h.types().last())
        assertEquals(1, h.types().count { it == PointerEventType.PanEnd })
    }

    @Test
    fun `finger steps move the deadline without re-scheduling the timer`() {
        // One coroutine per gesture, not one per 120 Hz step.
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        repeat(10) {
            h.nowMillis += 8
            h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        }
        assertEquals(0, h.cancelled, "steps that only push the deadline out must not cancel the timer")
        h.elapseTimer()
        assertEquals(0, h.types().count { it == PointerEventType.PanEnd }, "the timer fired before the moved deadline")
        assertTrue(h.hasPendingEnd, "…and re-armed for the remainder")
        h.elapseTimer()
        assertEquals(PointerEventType.PanEnd, h.types().last())
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
        // The grace timer still in flight defers to the stall deadline.
        h.elapseTimer()
        assertEquals(0, h.types().count { it == PointerEventType.PanEnd })
    }

    @Test
    fun `finishNow closes an open pan and is a no-op otherwise`() {
        val h = Harness()
        h.router.finishNow()
        assertTrue(h.sent.isEmpty())

        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.CHANGED, down)
        h.router.finishNow()
        assertEquals(PointerEventType.PanEnd, h.types().last())
        assertFalse(h.hasPendingEnd)
    }

    @Test
    fun `cancel drops the pending end without sending PanEnd`() {
        val h = Harness()
        h.router.onGesture(TaoScrollGesturePhase.BEGAN, Offset.Zero)
        h.router.onGesture(TaoScrollGesturePhase.ENDED, Offset.Zero)
        h.router.cancel()

        assertFalse(h.hasPendingEnd)
        h.elapseTimer()
        assertEquals(listOf(PointerEventType.PanStart), h.types())
    }
}
