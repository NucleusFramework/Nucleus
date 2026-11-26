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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A fixed panel: [SatelliteEntry.isFloatable] `false` keeps it out of a
 * window of its own — [SatelliteWorkspace.undock] refuses it, a drag released
 * over the content leaves it docked and shows no tear-out ghost, a snapshot
 * that floats it is ignored — and [SatelliteEntry.isReorderable] `false` pins
 * its rank: its own drag is offered none, and another panel can only be
 * dropped after it.
 */
class SatelliteFixedPanelTest {
    private val a = TaoWindow(handle = 1L)
    private val panelOrigin = SatelliteDragOrigin.DockedPanel(a)

    private val floating =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    /** Host `a`: layout (100, 140)–(900, 700) on screen, scale 1. */
    private fun workspace(): Pair<SatelliteWorkspace, HostGeometry> {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        val geometry =
            HostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                containerSizePx = IntSize(800, 600)
            }
        workspace.dockHosts.register(geometry)
        return workspace to geometry
    }

    /** A fixed panel of the left side, with a rect the drag code can read. */
    private fun SatelliteWorkspace.fixedPanel(
        id: String,
        order: Int = 0,
        boundsInWindowPx: Rect = Rect(0f, 40f, 200f, 600f),
    ): SatelliteEntry {
        val entry =
            register(
                id,
                id,
                SatellitePlacement.Docked(DockSide.Left, order = order),
                initiallyOpen = true,
                dockSides = setOf(DockSide.Left),
                floatable = false,
                reorderable = false,
            )
        entry.content = {}
        entry.dockedBoundsInWindowPx = boundsInWindowPx
        entry.dockHostContainerSizePx = IntSize(800, 600)
        return entry
    }

    @Test
    fun `undock refuses a fixed panel`() {
        val (workspace, _) = workspace()
        val entry = workspace.fixedPanel("tree")

        workspace.undock("tree")
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)

        workspace.undock("tree", floating)
        assertIs<SatellitePlacement.Docked>(entry.placement, "an explicit placement is refused too")
    }

    @Test
    fun `a fixed satellite must be declared docked`() {
        val (workspace, _) = workspace()
        assertFailsWith<IllegalArgumentException> {
            workspace.register("tree", "Tree", floating, initiallyOpen = true, floatable = false)
        }
        assertFailsWith<IllegalArgumentException> {
            workspace.register("toc", "Toc", floating, initiallyOpen = true, reorderable = false)
        }
    }

    @Test
    fun `a drag released over the content leaves a fixed panel docked, with no ghost`() {
        val (workspace, _) = workspace()
        val entry = workspace.fixedPanel("tree")

        val session = requireNotNull(workspace.beginDrag("tree", panelOrigin, Offset(150f, 300f)))
        session.update(Offset(500f, 400f))
        assertNull(workspace.dragGhost, "a fixed panel shows no tear-out ghost")
        assertNull(workspace.dockPreview, "the middle of the layout is no zone")
        session.end(Offset(500f, 400f))
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
        assertNull(workspace.draggedSatellite)

        // Outside every layout — where a floating panel would be torn out.
        val away = requireNotNull(workspace.beginDrag("tree", panelOrigin, Offset(150f, 300f)))
        away.end(Offset(2_000f, 2_000f))
        assertIs<SatellitePlacement.Docked>(entry.placement, "released off every window, it stays docked")
    }

    @Test
    fun `a transfer drag with no record leaves a fixed panel docked`() {
        val (workspace, _) = workspace()
        val entry = workspace.fixedPanel("tree")

        val session = requireNotNull(workspace.beginTransferDrag("tree", panelOrigin))
        session.end()
        assertEquals(DockSide.Left, assertIs<SatellitePlacement.Docked>(entry.placement).side)
    }

    @Test
    fun `a snapshot that floats a fixed panel is ignored, but its open state is not`() {
        val (workspace, _) = workspace()
        val entry = workspace.fixedPanel("tree")

        workspace.restore(
            SatelliteLayoutSnapshot(
                satellites = mapOf("tree" to SatelliteSnapshot(floating, isOpen = false)),
                dockExtents = emptyMap(),
            ),
        )
        assertIs<SatellitePlacement.Docked>(entry.placement)
        assertEquals(false, entry.isOpen)
    }

    @Test
    fun `a pinned panel is offered no rank and its drag changes nothing`() {
        val (workspace, geometry) = workspace()
        val tree = workspace.fixedPanel("tree", order = 0, boundsInWindowPx = Rect(0f, 40f, 200f, 320f))
        val toc = workspace.fixedPanel("toc", order = 1, boundsInWindowPx = Rect(0f, 320f, 200f, 600f))
        // Declared for the left side only, and pinned there: nothing to offer.
        assertEquals(emptyList(), hintedSides(toc, a, workspace.satellites))
        assertEquals(DockTarget(a, DockSide.Left), workspace.ownTarget(toc, a), "its own side, at no rank")
        geometry.zoneBoundsInWindowPx =
            mapOf(
                DockSide.Left to
                    DockDropZone(
                        strip = Rect(0f, 40f, 64f, 600f),
                        slots = listOf(Rect(0f, 40f, 200f, 180f), Rect(0f, 180f, 200f, 600f)),
                    ),
            )

        val session = requireNotNull(workspace.beginDrag("toc", panelOrigin, Offset(200f, 550f)))
        session.update(Offset(250f, 200f))
        assertNull(workspace.dockPreview, "a pinned panel takes no rank, so its own side is no target")
        session.end(Offset(250f, 200f))
        assertEquals(0, assertIs<SatellitePlacement.Docked>(tree.placement).order)
        assertEquals(1, assertIs<SatellitePlacement.Docked>(toc.placement).order)
    }

    @Test
    fun `another panel is docked after the pinned ones, whatever rank it asks for`() {
        val (workspace, _) = workspace()
        workspace.fixedPanel("tree", order = 0)
        workspace.fixedPanel("toc", order = 1)
        val notes =
            workspace.register(
                "notes",
                "Notes",
                SatellitePlacement.Docked(DockSide.Left, order = 2),
                initiallyOpen = true,
            )
        notes.content = {}

        workspace.dock("notes", DockSide.Left, order = 0)
        assertEquals(2, assertIs<SatellitePlacement.Docked>(notes.placement).order, "pushed past the pinned pair")
        assertEquals(0, assertIs<SatellitePlacement.Docked>(workspace.satellite("tree")!!.placement).order)
        assertEquals(1, assertIs<SatellitePlacement.Docked>(workspace.satellite("toc")!!.placement).order)

        // A pinned panel re-docked takes its own rank back, ahead of the movable one.
        workspace.dock("tree", DockSide.Left)
        assertEquals(0, assertIs<SatellitePlacement.Docked>(workspace.satellite("tree")!!.placement).order)
        assertEquals(2, assertIs<SatellitePlacement.Docked>(notes.placement).order)
    }

    @Test
    fun `a panel that can go nowhere is no drag handle`() {
        val (workspace, _) = workspace()
        val tree = workspace.fixedPanel("tree")
        assertEquals(false, workspace.canBeDragged(tree), "alone, one side, pinned: nothing a drag could do")

        // A second panel on the side gives a movable one somewhere to go…
        val notes =
            workspace.register(
                "notes",
                "Notes",
                SatellitePlacement.Docked(DockSide.Left, order = 1),
                initiallyOpen = true,
                dockSides = setOf(DockSide.Left),
                floatable = false,
            )
        notes.content = {}
        assertEquals(true, workspace.canBeDragged(notes))
        // …but not to the pinned one, which still cannot take another rank.
        assertEquals(false, workspace.canBeDragged(tree))

        // Another member's dock is somewhere to go, for either of them.
        workspace.join(TaoWindow(handle = 2L))
        assertEquals(true, workspace.canBeDragged(tree))
    }
}
