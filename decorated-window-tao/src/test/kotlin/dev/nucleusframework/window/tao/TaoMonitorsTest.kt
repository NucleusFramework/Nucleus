package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaoMonitorsTest {
    private fun row(
        id: String = "\\\\.\\DISPLAY1",
        name: String = "Generic PnP Monitor",
        bounds: String = "0\t0\t3840\t2160",
        work: String = "0\t0\t3840\t2100",
        scaleMilli: String = "2000",
        primary: String = "1",
    ) = "$id\t$name\t$bounds\t$work\t$scaleMilli\t$primary"

    @Test
    fun parsesAWellFormedRow() {
        val monitor = TaoMonitors.parseMonitor(row())
        requireNotNull(monitor)
        assertEquals("\\\\.\\DISPLAY1", monitor.id)
        assertEquals("Generic PnP Monitor", monitor.name)
        assertEquals(IntRect(0, 0, 3840, 2160), monitor.boundsPx)
        assertEquals(IntRect(0, 0, 3840, 2100), monitor.workAreaPx)
        assertEquals(2f, monitor.scaleFactor)
        assertTrue(monitor.isPrimary)
    }

    @Test
    fun convertsToDpWithTheGivenScale() {
        val monitor = requireNotNull(TaoMonitors.parseMonitor(row()))
        // Its own scale: 3840 physical px at 2.0 → 1920dp.
        assertEquals(1920f, monitor.boundsDp().right.value)
        // A window on a 1.0 monitor reads the same rectangle in its own space.
        assertEquals(3840f, monitor.boundsDp(scale = 1f).right.value)
    }

    @Test
    fun negativeOriginsSurviveTheRoundTrip() {
        val monitor =
            requireNotNull(
                TaoMonitors.parseMonitor(
                    row(bounds = "-1920\t-120\t1920\t1080", work = "-1920\t-120\t1920\t1040", scaleMilli = "1000"),
                ),
            )
        assertEquals(IntRect(-1920, -120, 0, 960), monitor.boundsPx)
        assertEquals(-1920f, monitor.boundsDp().left.value)
    }

    @Test
    fun fallsBackToFullBoundsWhenTheWorkAreaIsEmpty() {
        val monitor = requireNotNull(TaoMonitors.parseMonitor(row(work = "0\t0\t0\t0")))
        assertEquals(monitor.boundsPx, monitor.workAreaPx)
    }

    @Test
    fun rejectsMalformedRows() {
        assertNull(TaoMonitors.parseMonitor(""))
        assertNull(TaoMonitors.parseMonitor("too\tfew\tfields"))
        assertNull(TaoMonitors.parseMonitor(row(bounds = "0\t0\tnot-a-number\t2160")))
        // A zero-sized monitor is not something the geometry math can use.
        assertNull(TaoMonitors.parseMonitor(row(bounds = "0\t0\t0\t0")))
    }

    @Test
    fun containsPxIsHalfOpen() {
        val monitor = requireNotNull(TaoMonitors.parseMonitor(row(scaleMilli = "1000")))
        assertTrue(monitor.containsPx(0, 0))
        assertTrue(monitor.containsPx(3839, 2159))
        assertTrue(!monitor.containsPx(3840, 2160))
    }

    @Test
    fun enumerationNeverReportsZeroMonitors() {
        // Without a platform bridge (headless CI) this falls back to a single
        // synthesized monitor — a screen picker must never see an empty list.
        val monitors = TaoMonitors.all()
        assertTrue(monitors.isNotEmpty())
        assertTrue(monitors.any { it.isPrimary })
        assertEquals(TaoMonitors.primary().id, monitors.first { it.isPrimary }.id)
    }

    @Test
    fun identityIsTheId() {
        val a = requireNotNull(TaoMonitors.parseMonitor(row()))
        val b = requireNotNull(TaoMonitors.parseMonitor(row(name = "Other", scaleMilli = "1000")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
