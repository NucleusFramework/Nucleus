package dev.nucleusframework.window.tao

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The per-panel geometry a [SatellitePlacement.Docked] carries — its own
 * extent on a layered side, its weight on a split side — and how
 * [SatelliteWorkspace] seeds, clamps and persists it. Driven without any
 * native window, like [SatelliteWorkspaceTest].
 */
class SatelliteDockedGeometryTest {
    private val a = TaoWindow(handle = 1L)
    private val b = TaoWindow(handle = 2L)

    private val floatingRight =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    // ── per-panel geometry: layered extents and split weights ────────────

    @Test
    fun `docking from a floating window brings its size along as the panel extent`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)

        workspace.dock("tools", DockSide.Right)
        val docked = assertIs<SatellitePlacement.Docked>(workspace.satellite("tools")?.placement)
        assertEquals(200.dp, docked.extent, "a right layer is as wide as the window was")
        assertEquals(1f, docked.weight)

        workspace.undock("tools")
        workspace.dock("tools", DockSide.Bottom)
        val bottom = assertIs<SatellitePlacement.Docked>(workspace.satellite("tools")?.placement)
        assertEquals(300.dp, bottom.extent, "a bottom layer is as tall as the window was")
    }

    @Test
    fun `re-docking keeps the extent along the same axis and re-seeds it across axes`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Right)
        workspace.setDockedExtent("tools", 240.dp)
        workspace.setDockedWeight("tools", 2.5f)

        workspace.dock("tools", DockSide.Left)
        val left = assertIs<SatellitePlacement.Docked>(workspace.satellite("tools")?.placement)
        assertEquals(240.dp, left.extent, "left and right share the width axis")
        assertEquals(2.5f, left.weight, "the weight travels with the panel")

        workspace.dock("tools", DockSide.Top)
        val top = assertIs<SatellitePlacement.Docked>(workspace.satellite("tools")?.placement)
        assertEquals(300.dp, top.extent, "a width is no height: the floating size seeds the top layer")
        assertEquals(2.5f, top.weight)
    }

    @Test
    fun `a panel moved between docks seeds its new side with the width it had`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("comments", "Comments", floatingRight, initiallyOpen = true)
        workspace.dock("comments", DockSide.Bottom)
        workspace.setDockedExtent("comments", 220.dp)

        // The top side has no extent of its own: the arriving panel gives it
        // the height it had at the bottom, and the preview promises exactly
        // that — the two must agree, or the drop lands somewhere the preview
        // did not show.
        val entry = requireNotNull(workspace.satellite("comments"))
        assertEquals(220.dp, workspace.plannedDockExtent(entry, DockSide.Top), "the preview height")
        workspace.dock("comments", DockSide.Top)
        assertEquals(220.dp, workspace.dockExtent(DockSide.Top), "the side took the panel's height")
        assertEquals(220.dp, assertIs<SatellitePlacement.Docked>(entry.placement).extent)

        // Across the axes a height is no width: the floating size seeds it,
        // and again the preview says the same.
        assertEquals(200.dp, workspace.plannedDockExtent(entry, DockSide.Left))
        workspace.dock("comments", DockSide.Left)
        assertEquals(200.dp, workspace.dockExtent(DockSide.Left))

        // A side that already has an extent keeps it.
        workspace.setDockExtent(DockSide.Right, 150.dp)
        assertEquals(150.dp, workspace.plannedDockExtent(entry, DockSide.Right))
        workspace.dock("comments", DockSide.Right)
        assertEquals(150.dp, workspace.dockExtent(DockSide.Right))
    }

    @Test
    fun `docked extent and weight are clamped and ignored for a floating satellite`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)

        workspace.setDockedExtent("tools", 10.dp)
        assertIs<SatellitePlacement.Floating>(workspace.satellite("tools")?.placement, "floating: untouched")

        workspace.dock("tools", DockSide.Right)
        workspace.setDockedExtent("tools", 10.dp)
        workspace.setDockedWeight("tools", -3f)
        val docked = assertIs<SatellitePlacement.Docked>(workspace.satellite("tools")?.placement)
        assertEquals(SatelliteWorkspace.MinDockExtent, docked.extent)
        assertTrue(docked.weight > 0f, "a weight is never zero or negative: ${docked.weight}")
        assertEquals(DockSide.Right, docked.side)
        assertEquals(0, docked.order)
    }

    @Test
    fun `a snapshot carries every panel's own extent and weight`() {
        val source = SatelliteWorkspace()
        source.join(a)
        source.register("tree", "Tree", floatingRight, initiallyOpen = true)
        source.register("toc", "Toc", floatingRight, initiallyOpen = true)
        source.dock("tree", DockSide.Right)
        source.dock("toc", DockSide.Right)
        source.setDockedExtent("tree", 180.dp)
        source.setDockedExtent("toc", 130.dp)
        source.setDockedWeight("toc", 3f)

        val target = SatelliteWorkspace()
        target.join(b)
        target.restore(source.snapshot())
        val tree = assertIs<SatellitePlacement.Docked>(target.register("tree", "Tree", floatingRight, true).placement)
        val toc = assertIs<SatellitePlacement.Docked>(target.register("toc", "Toc", floatingRight, true).placement)
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0, 180.dp, 1f), tree)
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 1, 130.dp, 3f), toc)
    }

    @Test
    fun `a docked placement refuses a weight that is not positive`() {
        assertFailsWith<IllegalArgumentException> { SatellitePlacement.Docked(DockSide.Left, weight = 0f) }
    }

    @Test
    fun `every side has an opposite across the content`() {
        for (side in DockSide.entries) {
            assertNotEquals(side, side.opposite)
            assertEquals(side, side.opposite.opposite)
            assertEquals(side.isVertical, side.opposite.isVertical)
        }
    }
}
