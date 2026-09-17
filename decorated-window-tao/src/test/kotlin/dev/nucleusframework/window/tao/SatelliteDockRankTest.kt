package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.DockDropZone
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The ranks of a dock side ([SatellitePlacement.Docked.order]) as
 * [SatelliteWorkspace.dock] and [SatelliteWorkspace.undock] keep them:
 * contiguous from `0`, inserted at the index asked for, and remembered per
 * side so a satellite floated and docked again comes back to its place.
 */
class SatelliteDockRankTest {
    private val a = TaoWindow(handle = 1L)
    private val panelOrigin = SatelliteDragOrigin.DockedPanel(a)

    private val floatingRight =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    @Test
    fun `dock order inserts at that rank and keeps the side contiguous`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("one", "One", floatingRight, initiallyOpen = true)
        workspace.register("two", "Two", floatingRight, initiallyOpen = true)
        workspace.register("three", "Three", floatingRight, initiallyOpen = true)

        workspace.dock("one", DockSide.Left)
        workspace.dock("two", DockSide.Left)
        // Out of range on either end clamps: the first rank, then the last.
        workspace.dock("three", DockSide.Left, order = -5)
        assertEquals(listOf("three", "one", "two"), workspace.ranksOn(DockSide.Left))
        workspace.dock("three", DockSide.Left, order = 99)
        assertEquals(listOf("one", "two", "three"), workspace.ranksOn(DockSide.Left))
        workspace.dock("three", DockSide.Left, order = 1)
        assertEquals(listOf("one", "three", "two"), workspace.ranksOn(DockSide.Left))
        // A re-dock on the same side with no rank keeps the one it has.
        workspace.dock("three", DockSide.Left)
        assertEquals(listOf("one", "three", "two"), workspace.ranksOn(DockSide.Left))
    }

    @Test
    fun `a satellite docked again on the side it left returns to its rank`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        for ((rank, id) in listOf("tree", "toc", "notes").withIndex()) {
            workspace.register(id, id, SatellitePlacement.Docked(DockSide.Right, order = rank), initiallyOpen = true)
        }

        workspace.undock("toc")
        // The gap closes behind it…
        assertEquals(listOf("tree", "notes"), workspace.ranksOn(DockSide.Right))
        assertEquals(1, (workspace.satellite("notes")!!.placement as SatellitePlacement.Docked).order)
        // …and it opens again where it was, through every path that names no rank.
        workspace.dock("toc", DockSide.Right)
        assertEquals(listOf("tree", "toc", "notes"), workspace.ranksOn(DockSide.Right))

        // The rank it *leaves* with is the one remembered, not the declared one.
        workspace.dock("tree", DockSide.Right, order = 2)
        assertEquals(listOf("toc", "notes", "tree"), workspace.ranksOn(DockSide.Right))
        workspace.undock("tree")
        workspace.dock("notes", DockSide.Left)
        workspace.dock("tree", DockSide.Right)
        assertEquals(listOf("toc", "tree"), workspace.ranksOn(DockSide.Right))
    }

    @Test
    fun `a satellite new to a side is appended there and keeps its rank elsewhere`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("tree", "Tree", SatellitePlacement.Docked(DockSide.Right, order = 0), initiallyOpen = true)
        workspace.register("toc", "Toc", SatellitePlacement.Docked(DockSide.Right, order = 1), initiallyOpen = true)
        workspace.register(
            "targum",
            "Targum",
            SatellitePlacement.Docked(DockSide.Left, order = 0),
            initiallyOpen = true,
        )

        // Moved to a side it never sat on: after what is there.
        workspace.dock("tree", DockSide.Left)
        assertEquals(listOf("targum", "tree"), workspace.ranksOn(DockSide.Left))
        assertEquals(listOf("toc"), workspace.ranksOn(DockSide.Right))
        assertEquals(0, (workspace.satellite("toc")!!.placement as SatellitePlacement.Docked).order)
        // Back to the right: at the rank it left, ahead of the toc.
        workspace.dock("tree", DockSide.Right)
        assertEquals(listOf("tree", "toc"), workspace.ranksOn(DockSide.Right))
        // A floating satellite that was never docked appends too.
        workspace.register("notes", "Notes", floatingRight, initiallyOpen = true)
        workspace.dock("notes", DockSide.Right)
        assertEquals(listOf("tree", "toc", "notes"), workspace.ranksOn(DockSide.Right))
    }

    @Test
    fun `a closed panel keeps its rank and the weight comes back with it`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("tree", "Tree", SatellitePlacement.Docked(DockSide.Right, order = 0), initiallyOpen = true)
        workspace.register("toc", "Toc", SatellitePlacement.Docked(DockSide.Right, order = 1), initiallyOpen = true)
        workspace.register("notes", "Notes", SatellitePlacement.Docked(DockSide.Right, order = 2), initiallyOpen = true)
        workspace.setDockedWeight("toc", 3f)

        workspace.close("toc")
        workspace.undock("notes")
        workspace.dock("notes", DockSide.Right)
        // The closed toc still holds rank 1; the notes return behind it.
        assertEquals(listOf("tree", "toc", "notes"), workspace.ranksOn(DockSide.Right))

        workspace.undock("toc")
        workspace.dock("toc", DockSide.Right)
        assertEquals(3f, (workspace.satellite("toc")!!.placement as SatellitePlacement.Docked).weight)
        assertEquals(listOf("tree", "toc", "notes"), workspace.ranksOn(DockSide.Right))
    }

    /** The ids docked on [side] of the owner, in rank order; every rank is asserted contiguous from 0. */
    private fun SatelliteWorkspace.ranksOn(side: DockSide): List<String> {
        val stack =
            satellites
                .filter { (it.placement as? SatellitePlacement.Docked)?.side == side }
                .sortedBy { (it.placement as SatellitePlacement.Docked).order }
        assertEquals(
            stack.indices.toList(),
            stack.map { (it.placement as SatellitePlacement.Docked).order },
            "ranks on $side",
        )
        return stack.map { it.id }
    }

    /**
     * Host `a` as the drag test sees it: outer frame at (100, 100), 800×600,
     * content the same size, DockLayout below a 40 px bar — so its screen rect
     * is (100, 140)–(900, 700), scale 1.
     */
    private fun SatelliteWorkspace.registerHostA(): HostGeometry {
        join(a)
        val geometry =
            HostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                containerSizePx = IntSize(800, 600)
            }
        dockHosts.register(geometry)
        return geometry
    }

    @Test
    fun `a docked drag dropped on its own stack takes the rank under the pointer`() {
        val workspace = SatelliteWorkspace()
        val geometry = workspace.registerHostA()
        val ids = listOf("tree", "toc", "notes")
        for (id in ids) {
            workspace.register(id, id, floatingRight, initiallyOpen = true).content = {}
            workspace.dock(id, DockSide.Left)
        }
        // Stacked down the left side, 200 px wide, in window px.
        val bounds =
            listOf(Rect(0f, 40f, 200f, 226f), Rect(0f, 226f, 200f, 413f), Rect(0f, 413f, 200f, 600f))
        ids.forEachIndexed { index, id ->
            workspace.satellite(id)!!.dockedBoundsInWindowPx = bounds[index]
            workspace.satellite(id)!!.dockHostContainerSizePx = IntSize(800, 600)
        }
        // What the layout publishes while the notes are dragged: the tree and
        // the toc cut at their centres, three ranks.
        geometry.zoneBoundsInWindowPx =
            mapOf(
                DockSide.Left to
                    DockDropZone(
                        strip = Rect(0f, 40f, 64f, 600f),
                        slots =
                            listOf(
                                Rect(0f, 40f, 200f, 133f),
                                Rect(0f, 133f, 200f, 319.5f),
                                Rect(0f, 319.5f, 200f, 600f),
                            ),
                    ),
            )

        // Over its own rank: nothing to preview, and a release leaves it alone.
        var session = requireNotNull(workspace.beginDrag("notes", panelOrigin, Offset(200f, 550f)))
        session.update(Offset(210f, 560f))
        assertNull(workspace.dockPreview, "its own slot is not a target")
        session.end(Offset(210f, 560f))
        assertEquals(ids, workspace.ranksOn(DockSide.Left))

        // Over the top of the tree: first rank.
        session = requireNotNull(workspace.beginDrag("notes", panelOrigin, Offset(200f, 550f)))
        session.update(Offset(250f, 200f))
        assertEquals(DockTarget(a, DockSide.Left, 0), workspace.dockPreview)
        session.end(Offset(250f, 200f))
        assertEquals(listOf("notes", "tree", "toc"), workspace.ranksOn(DockSide.Left))
        assertSame(a, workspace.satellite("notes")!!.dockHost)

        // A closed panel keeps its rank in the middle while the shown ones are aimed between.
        workspace.close("tree")
        // Shown: notes, toc. Dropping the toc at shown rank 0 lands ahead of both.
        geometry.zoneBoundsInWindowPx =
            mapOf(
                DockSide.Left to
                    DockDropZone(
                        strip = Rect(0f, 40f, 64f, 600f),
                        slots = listOf(Rect(0f, 40f, 200f, 320f), Rect(0f, 320f, 200f, 600f)),
                    ),
            )
        session = requireNotNull(workspace.beginDrag("toc", panelOrigin, Offset(200f, 550f)))
        session.end(Offset(250f, 200f))
        assertEquals(listOf("toc", "notes", "tree"), workspace.ranksOn(DockSide.Left))
    }
}
