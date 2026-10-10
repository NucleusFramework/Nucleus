package dev.nucleusframework.lab.probes.window

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.lab.probes.window.shared.MonitorRow
import dev.nucleusframework.lab.probes.window.shared.WindowSnapshot
import dev.nucleusframework.lab.probes.window.shared.expectedTopLeft
import dev.nucleusframework.lab.probes.window.state.Outcome
import dev.nucleusframework.lab.probes.window.state.PendingRequest
import dev.nucleusframework.lab.probes.window.state.PlacementChoice
import dev.nucleusframework.lab.probes.window.state.PositionTarget
import dev.nucleusframework.lab.probes.window.state.SizePreset
import dev.nucleusframework.lab.probes.window.state.V2Readback
import dev.nucleusframework.lab.probes.window.state.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class StateVerificationTest {
    private val work = IntRect(0, 50, 1920, 1080)

    private fun snapshot(
        outer: IntRect? = IntRect(100, 100, 900, 700),
        maximized: Boolean = false,
        fullscreen: Boolean = false,
        canPlace: Boolean = true,
        monitor: String = "Main",
    ) = WindowSnapshot(outer, 1f, maximized, false, fullscreen, false, true, true, "AppKit", canPlace, monitor, work)

    private fun v2(
        width: Float = 800f,
        height: Float = 600f,
        screenId: String? = "1",
    ) = V2Readback(true, screenId, "Floating", false, null, width, height)

    @Test
    fun `aligned positions land inside the work area`() {
        assertEquals(0 to 50, expectedTopLeft(Alignment.TopStart, IntSize(800, 600), work))
        assertEquals(1120 to 480, expectedTopLeft(Alignment.BottomEnd, IntSize(800, 600), work))
    }

    @Test
    fun `placement is judged from the native flags`() {
        val request = PendingRequest.Placement(PlacementChoice.Maximized)
        assertEquals(Outcome.Applied, verify(request, snapshot(maximized = true), v2(), 0).outcome)
        assertEquals(Outcome.Mismatch, verify(request, snapshot(), v2(), 0).outcome)
    }

    @Test
    fun `size tolerates two dp of rounding and no more`() {
        val request = PendingRequest.Size(SizePreset.Medium)
        assertEquals(Outcome.Applied, verify(request, snapshot(), v2(801.5f, 558.5f), 0).outcome)
        assertEquals(Outcome.Mismatch, verify(request, snapshot(), v2(790f, 560f), 0).outcome)
    }

    @Test
    fun `position and screen are not applicable on native Wayland`() {
        val wayland = snapshot(canPlace = false)
        assertEquals(
            Outcome.NotApplicable,
            verify(PendingRequest.Position(PositionTarget.Center), wayland, v2(), 0).outcome,
        )
        val monitor = MonitorRow("2", "Side", work, work, 2f, false)
        assertEquals(Outcome.NotApplicable, verify(PendingRequest.Screen(monitor), wayland, v2(), 0).outcome)
    }

    @Test
    fun `position compares the outer frame with the alignment`() {
        val request = PendingRequest.Position(PositionTarget.TopStart)
        assertEquals(Outcome.Applied, verify(request, snapshot(IntRect(10, 60, 810, 660)), v2(), 0).outcome)
        assertEquals(Outcome.Mismatch, verify(request, snapshot(IntRect(400, 400, 1200, 1000)), v2(), 0).outcome)
    }

    @Test
    fun `screen needs both the monitor and the v2 id`() {
        val side = MonitorRow("2", "Side", work, work, 2f, false)
        assertEquals(
            Outcome.Applied,
            verify(PendingRequest.Screen(side), snapshot(monitor = "Side"), v2(screenId = "2"), 0).outcome,
        )
        assertEquals(
            Outcome.Mismatch,
            verify(PendingRequest.Screen(side), snapshot(monitor = "Main"), v2(screenId = "1"), 0).outcome,
        )
    }
}
