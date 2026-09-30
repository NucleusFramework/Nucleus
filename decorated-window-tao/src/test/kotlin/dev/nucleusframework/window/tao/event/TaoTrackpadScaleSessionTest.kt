package dev.nucleusframework.window.tao.event

import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaoTrackpadScaleSessionTest {
    @Test
    fun startChangeEndEmitsScaleSequence() {
        val h = Harness()
        h.session.start()
        h.session.change(1.05f)
        h.session.end()
        assertEquals(
            listOf(
                PointerEventType.ScaleStart to 1f,
                PointerEventType.ScaleChange to 1.05f,
                PointerEventType.ScaleEnd to 1f,
            ),
            h.sent,
        )
        assertFalse(h.session.active)
    }

    @Test
    fun changeOpensTheGestureIfNeeded() {
        val h = Harness()
        h.session.change(1.02f)
        assertTrue(h.session.active)
        assertEquals(
            listOf(
                PointerEventType.ScaleStart to 1f,
                PointerEventType.ScaleChange to 1.02f,
            ),
            h.sent,
        )
    }

    @Test
    fun identityFactorIsNotAMove() {
        val h = Harness()
        h.session.start()
        h.session.change(1f)
        h.session.magnifyBy(0f)
        assertEquals(listOf(PointerEventType.ScaleStart to 1f), h.sent)
    }

    @Test
    fun magnifyByUsesOnePlusDelta() {
        val h = Harness()
        h.session.magnifyBy(0.01f)
        assertEquals(PointerEventType.ScaleChange to 1.01f, h.sent.last())
        h.session.magnifyBy(-0.5f)
        assertEquals(PointerEventType.ScaleChange to 0.5f, h.sent.last())
    }

    @Test
    fun magnifyByFloorsACollapse() {
        val h = Harness()
        h.session.magnifyBy(-2f)
        assertEquals(
            TaoTrackpadScaleSession.MIN_GESTURE_SCALE,
            h.sent.last().second,
        )
    }

    @Test
    fun smartMagnifyIsAClosedBurst() {
        val h = Harness()
        h.session.smartMagnify()
        assertEquals(
            listOf(
                PointerEventType.ScaleStart to 1f,
                PointerEventType.ScaleChange to TaoTrackpadScaleSession.SMART_MAGNIFY_FACTOR,
                PointerEventType.ScaleEnd to 1f,
            ),
            h.sent,
        )
        assertFalse(h.session.active)
    }

    @Test
    fun endWithoutStartIsANoOp() {
        val h = Harness()
        h.session.end()
        assertTrue(h.sent.isEmpty())
    }

    @Test
    fun aSecondStartIsIgnoredWhileActive() {
        val h = Harness()
        h.session.start()
        h.session.start()
        assertEquals(listOf(PointerEventType.ScaleStart to 1f), h.sent)
    }

    private class Harness {
        val sent = mutableListOf<Pair<PointerEventType, Float>>()
        val session = TaoTrackpadScaleSession { type, factor -> sent += type to factor }
    }
}
