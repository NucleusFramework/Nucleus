package dev.nucleusframework.window.tao

import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class TaoWindowScrollTest {
    @Test
    fun lineScrollKeepsWheelRotationSeparateFromScrollAmount() {
        val event = dispatchScroll(TaoEventCode.SCROLL_LINE, dx = 100, dy = -200)

        assertEquals(-1f, event.dxAwt)
        assertEquals(2f, event.dyAwt)
        assertEquals(expectedLineScrollAmount(), event.scrollAmount)
    }

    @Test
    fun pixelScrollMirrorsMacOsAwtPreciseWheelRotationScale() {
        // Wire = logical AppKit points × 100 (#653): 10 pt right, 20 pt up.
        val event = dispatchScroll(TaoEventCode.SCROLL_PIXEL, dx = 1000, dy = -2000)

        assertEquals(-1f, event.dxAwt)
        assertEquals(2f, event.dyAwt)
        assertEquals(1, event.scrollAmount)
        assertEquals(null, event.gesturePhase)
    }

    @Test
    fun scrollGestureIsShapedLikePixelScrollWithItsPhase() {
        val gesture = dispatchGesture(TaoScrollGesturePhase.MOMENTUM_CHANGED.wire, dxFixed = 1000, dyFixed = -2000)

        assertEquals(-1f, gesture.dxAwt)
        assertEquals(2f, gesture.dyAwt)
        assertEquals(1, gesture.scrollAmount)
        assertEquals(TaoScrollGesturePhase.MOMENTUM_CHANGED, gesture.gesturePhase)
    }

    @Test
    fun unknownGestureWireCodeDegradesToPlainPreciseScroll() {
        val event = dispatchGesture(phaseWire = 99, dxFixed = 0, dyFixed = -1000)

        assertEquals(1f, event.dyAwt)
        assertEquals(null, event.gesturePhase)
    }

    private fun dispatchGesture(
        phaseWire: Int,
        dxFixed: Int,
        dyFixed: Int,
    ): TaoPointerScrollEvent {
        var event: TaoPointerScrollEvent? = null
        TaoWindow(handle = 1L).apply {
            onPointerScroll { event = it }
            dispatchScrollGesture(phaseWire, dxFixed, dyFixed)
        }
        return requireNotNull(event)
    }

    private fun dispatchScroll(
        code: Int,
        dx: Int,
        dy: Int,
    ): TaoPointerScrollEvent {
        var event: TaoPointerScrollEvent? = null
        TaoWindow(handle = 1L).apply {
            onPointerScroll { event = it }
            dispatch(code, dx, dy)
        }
        return requireNotNull(event)
    }

    private fun expectedLineScrollAmount(): Int =
        when (Platform.Current) {
            Platform.Linux -> 3
            else -> 1
        }
}
