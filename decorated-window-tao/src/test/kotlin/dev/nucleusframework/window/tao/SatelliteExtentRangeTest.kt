package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A panel's own thickness limits ([SatelliteEntry.minExtent] /
 * [SatelliteEntry.maxExtent]): on the side it shares, on its own layer, and on
 * the preview of docking it — without a window, since all of it is arithmetic
 * on the workspace.
 */
class SatelliteExtentRangeTest {
    @Test
    fun `a panel's range clamps its thickness, the side it joins, and the preview`() {
        val workspace = SatelliteWorkspace()
        val wide =
            workspace.register(
                "wide",
                "Wide",
                SatellitePlacement.Docked(DockSide.Right),
                initiallyOpen = true,
                minExtent = 200.dp,
                maxExtent = 300.dp,
            )
        val narrow =
            workspace.register(
                "narrow",
                "Narrow",
                SatellitePlacement.Floating(size = DpSize(120.dp, 400.dp)),
                initiallyOpen = true,
                maxExtent = 250.dp,
            )

        workspace.setDockExtent(DockSide.Right, 100.dp)
        assertEquals(200.dp, workspace.dockExtent(DockSide.Right), "the side cannot go under its panel's minimum")
        workspace.setDockExtent(DockSide.Right, 500.dp)
        assertEquals(300.dp, workspace.dockExtent(DockSide.Right), "nor over its maximum")

        // The side is 300 dp; the newcomer allows 250 at most, so the preview
        // says 250 — and the drop produces 250.
        assertEquals(250.dp, workspace.plannedDockExtent(narrow, DockSide.Right))
        workspace.dock("narrow", DockSide.Right)
        assertEquals(250.dp, workspace.dockExtent(DockSide.Right), "the drop is what the preview promised")

        // A panel's own layer obeys its own range.
        workspace.setDockedExtent("wide", 50.dp)
        assertEquals(200.dp, (wide.placement as SatellitePlacement.Docked).extent)
    }

    @Test
    fun `a restore bounds a side by the panels it puts there, not the ones it moves away`() {
        val workspace = SatelliteWorkspace()
        workspace.register(
            "wide",
            "Wide",
            SatellitePlacement.Docked(DockSide.Left),
            initiallyOpen = true,
            minExtent = 400.dp,
        )
        workspace.register("plain", "Plain", SatellitePlacement.Floating(), initiallyOpen = true)

        // The wide panel floats in the snapshot and the plain one takes the
        // left side at 250 dp: nothing there asks for 400 any more.
        workspace.restore(
            SatelliteLayoutSnapshot(
                satellites =
                    mapOf(
                        "wide" to SatelliteSnapshot(SatellitePlacement.Floating(), isOpen = true),
                        "plain" to SatelliteSnapshot(SatellitePlacement.Docked(DockSide.Left), isOpen = true),
                    ),
                dockExtents = mapOf(DockSide.Left to 250.dp),
            ),
        )

        assertEquals(250.dp, workspace.dockExtent(DockSide.Left), "a panel moved away still bounded the side")
    }
}
