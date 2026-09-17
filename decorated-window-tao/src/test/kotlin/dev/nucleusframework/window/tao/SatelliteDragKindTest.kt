package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an app can ask about a drag in flight ([SatelliteWorkspace.dragKind])
 * and about the window it draws in ([TaoWindow.canPlaceOnScreen]) — the two
 * public answers chrome needs to tell "move the window" from "move the
 * satellite".
 */
class SatelliteDragKindTest {
    private val a = TaoWindow(handle = 1L)

    private val floating =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    private fun workspace(): SatelliteWorkspace =
        SatelliteWorkspace().apply {
            join(a)
            dockHosts.register(
                HostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                    layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                    containerSizePx = IntSize(800, 600)
                },
            )
        }

    private val satellite = TaoWindow(handle = 3L)
    private val origin =
        SatelliteDragOrigin.FloatingWindow(
            window = satellite,
            outerBoundsPx = { longArrayOf(400L, 300L, 200L, 150L) },
            move = { _, _ -> },
        )

    @Test
    fun `a pointer drag is carried by the window, and the kind clears with it`() {
        val workspace = workspace()
        workspace.register("tools", "Tools", floating, initiallyOpen = true)
        assertNull(workspace.dragKind, "nothing is dragging")

        val session = requireNotNull(workspace.beginDrag("tools", origin, Offset(500f, 310f)))
        assertEquals(SatelliteDragKind.Window, workspace.dragKind)
        session.update(Offset(500f, 690f))
        assertEquals(SatelliteDragKind.Window, workspace.dragKind, "still the window's own drag")
        session.end(Offset(500f, 690f))
        assertNull(workspace.dragKind, "the release clears it")

        val cancelled = requireNotNull(workspace.beginDrag("tools", origin, Offset(500f, 310f)))
        cancelled.cancel()
        assertNull(workspace.dragKind)
    }

    @Test
    fun `a transfer drag is carried by the platform session, and publishes no ghost`() {
        val workspace = workspace()
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true)
        entry.content = {}
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)

        val session = requireNotNull(workspace.beginTransferDrag("tools", SatelliteDragOrigin.DockedPanel(a)))
        assertEquals(SatelliteDragKind.Transfer, workspace.dragKind)
        assertEquals(entry, workspace.draggedSatellite, "the satellite is published either way")
        assertNull(workspace.dragGhost, "no window follows a transfer drag")
        session.end()
        assertNull(workspace.dragKind)
    }

    @Test
    fun `a window that is not a native Wayland surface places on screen`() {
        // Without a native surface the kind is unknown, which is the answer
        // every platform but Wayland gives: the app places its own windows.
        assertTrue(a.canPlaceOnScreen)
        assertEquals(!a.isNativeWaylandSurface, a.canPlaceOnScreen)
    }
}
