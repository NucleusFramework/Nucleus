package dev.nucleusframework.lab.probes.input

import androidx.compose.ui.geometry.Offset
import dev.nucleusframework.lab.probes.input.scroll.ScrollMeter
import dev.nucleusframework.lab.probes.input.scroll.gesturesTsv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollMeterTest {
    @Test
    fun `wheel gesture closes after the idle gap and measures what scrolled`() {
        val meter = ScrollMeter(idleMs = 180)
        meter.onDelta(nowMs = 0, delta = Offset(0f, 1f), scrollValuePx = 100)
        meter.onDelta(nowMs = 50, delta = Offset(0f, 1f), scrollValuePx = 120)
        assertNull(meter.onIdleCheck(nowMs = 150, scrollValuePx = 140), "still inside the idle window")

        val stat = assertNotNull(meter.onIdleCheck(nowMs = 240, scrollValuePx = 160))
        assertEquals(1, stat.index)
        assertEquals(2, stat.events)
        assertEquals(2f, stat.rawSum.y)
        assertEquals(60, stat.pxScrolled)
        assertEquals(50, stat.durationMs)
        assertEquals(1f, stat.maxRawAbsY)
    }

    @Test
    fun `a pan boundary splits two quick swipes instead of merging them`() {
        val meter = ScrollMeter()
        meter.onDelta(0, Offset(0f, 2f), 0)
        val first = assertNotNull(meter.onBoundary(10, 20))
        meter.onDelta(20, Offset(0f, -3f), 20)
        val second = assertNotNull(meter.onBoundary(30, 0))
        assertEquals(listOf(1, 2), listOf(first.index, second.index))
        assertEquals(-3f, second.rawSum.y)
        assertNull(meter.onBoundary(40, 0), "nothing open")
    }

    @Test
    fun `a delta after the idle gap starts a new gesture baseline`() {
        val meter = ScrollMeter(idleMs = 100)
        meter.onDelta(0, Offset(0f, 1f), 0)
        meter.onDelta(500, Offset(0f, 1f), 40)
        val stat = assertNotNull(meter.onBoundary(510, 50))
        assertEquals(1, stat.events)
        assertEquals(10, stat.pxScrolled)
    }

    @Test
    fun `fps counts frames over the gesture window`() {
        val meter = ScrollMeter()
        meter.onDelta(0, Offset(0f, 1f), 0)
        repeat(30) { meter.onFrame() }
        val stat = assertNotNull(meter.onBoundary(500, 0))
        assertEquals(60, stat.fps)
    }

    @Test
    fun `tsv is locale-stable and oldest first`() {
        val meter = ScrollMeter()
        meter.onDelta(0, Offset(0f, 1.5f), 0)
        val a = assertNotNull(meter.onBoundary(10, 15))
        meter.onDelta(20, Offset(0f, 0.5f), 15)
        val b = assertNotNull(meter.onBoundary(30, 20))
        val tsv = gesturesTsv(listOf(b, a), "Test")
        val rows = tsv.lines().drop(3).filter { it.isNotBlank() }
        assertTrue(rows[0].startsWith("1\t1\t1.50\t15\t"), rows[0])
        assertTrue(rows[1].startsWith("2\t1\t0.50\t5\t"), rows[1])
    }
}
