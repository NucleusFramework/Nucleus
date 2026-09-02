package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Ownership, docking and layout-persistence rules of [SatelliteWorkspace],
 * driven without any native window: members are bare [TaoWindow] handles
 * (listener registration is pure Kotlin) and focus is fed through
 * [SatelliteWorkspace.noteFocus]. The headful suite covers the real windows.
 */
class SatelliteWorkspaceTest {
    private companion object {
        /** Enough repetitions to expose accumulated drift, still instant. */
        const val CHURN_CYCLES = 50
    }

    private val a = TaoWindow(handle = 1L)
    private val b = TaoWindow(handle = 2L)

    private val panelOrigin = SatelliteDragOrigin.DockedPanel(a)

    private val floatingRight =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    @Test
    fun `the first member to join owns the satellites until focus moves`() {
        val workspace = SatelliteWorkspace()
        assertNull(workspace.owner)

        workspace.join(a)
        workspace.join(b)
        assertSame(a, workspace.owner)

        workspace.noteFocus(b)
        assertSame(b, workspace.owner)

        workspace.leave(b)
        assertSame(a, workspace.owner)
        assertEquals(listOf(a), workspace.members)
    }

    @Test
    fun `pinning overrides focus until released`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.join(b)
        workspace.noteFocus(b)

        workspace.pinTo(a)
        assertSame(a, workspace.owner)

        workspace.pinTo(null)
        assertSame(b, workspace.owner)
    }

    @Test
    fun `without follow focus the owner is the pinned or first member`() {
        val workspace = SatelliteWorkspace(followFocus = false)
        workspace.join(a)
        workspace.join(b)
        workspace.noteFocus(b)
        assertSame(a, workspace.owner)

        workspace.pinTo(b)
        assertSame(b, workspace.owner)
    }

    @Test
    fun `docking a floating satellite seeds the side extent and hosts it in the owner`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        assertFalse(entry.isDocked)
        assertEquals(SatelliteWorkspace.DefaultDockExtent, workspace.dockExtent(DockSide.Right))

        workspace.dock("tools", DockSide.Right)

        val docked = assertIs<SatellitePlacement.Docked>(entry.placement)
        assertEquals(DockSide.Right, docked.side)
        assertEquals(0, docked.order)
        assertSame(a, entry.dockHost)
        assertEquals(200.dp, workspace.dockExtent(DockSide.Right))
        assertEquals(DockSide.Right, entry.preferredDockSide)
    }

    @Test
    fun `dock order appends after the panels already on that side`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.register("one", "One", floatingRight, initiallyOpen = true)
        workspace.register("two", "Two", floatingRight, initiallyOpen = true)
        workspace.register("three", "Three", floatingRight, initiallyOpen = true)

        workspace.dock("one", DockSide.Left)
        workspace.dock("two", DockSide.Left)
        workspace.dock("three", DockSide.Left, order = -5)

        assertEquals(0, (workspace.satellite("one")!!.placement as SatellitePlacement.Docked).order)
        assertEquals(1, (workspace.satellite("two")!!.placement as SatellitePlacement.Docked).order)
        assertEquals(-5, (workspace.satellite("three")!!.placement as SatellitePlacement.Docked).order)
    }

    @Test
    fun `undock without host geometry returns to the last floating placement`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        // The user dragged the window: that offset is what docking remembers.
        entry.windowState.offsetFromParent = DpOffset(40.dp, 50.dp)
        entry.windowState.size = DpSize(240.dp, 320.dp)

        workspace.dock("tools", DockSide.Bottom)
        workspace.undock("tools")

        val floating = assertIs<SatellitePlacement.Floating>(entry.placement)
        assertEquals(DpSize(240.dp, 320.dp), floating.size)
        assertEquals(WindowAnchor.TopLeft, floating.positioner.parentAnchor)
        assertEquals(WindowAnchor.TopLeft, floating.positioner.childAnchor)
        assertEquals(DpOffset(40.dp, 50.dp), floating.positioner.offset)
        assertNull(entry.dockHost)
        assertNull(entry.windowState.offsetFromParent)
        assertEquals(DockSide.Bottom, entry.preferredDockSide)
    }

    @Test
    fun `a member leaving rehosts the satellites docked into it`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        workspace.join(b)
        workspace.noteFocus(b)
        workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        assertSame(b, workspace.satellite("tools")!!.dockHost)

        workspace.leave(b)
        assertSame(a, workspace.satellite("tools")!!.dockHost)

        workspace.leave(a)
        assertNull(workspace.satellite("tools")!!.dockHost)

        // The next window to join picks the orphaned panel up.
        workspace.join(b)
        assertSame(b, workspace.satellite("tools")!!.dockHost)
    }

    @Test
    fun `open close and toggle only touch the open flag`() {
        val workspace = SatelliteWorkspace()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)

        workspace.close("tools")
        assertFalse(entry.isOpen)
        workspace.toggle("tools")
        assertTrue(entry.isOpen)
        workspace.close("tools")
        workspace.open("tools")
        assertTrue(entry.isOpen)
        assertEquals(floatingRight, entry.placement)
    }

    @Test
    fun `restore clamps a dock extent that would make the splitter unreachable`() {
        val workspace = SatelliteWorkspace()
        workspace.restore(
            SatelliteLayoutSnapshot(
                satellites = emptyMap(),
                dockExtents = mapOf(DockSide.Left to 0.dp, DockSide.Top to 4_000.dp),
            ),
        )

        assertEquals(SatelliteWorkspace.MinDockExtent, workspace.dockExtent(DockSide.Left))
        assertEquals(4_000.dp, workspace.dockExtent(DockSide.Top))
    }

    @Test
    fun `the planned extent of an untouched side is the satellite's own size`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)

        // floatingRight is 200 x 300: a vertical side takes the width, a
        // horizontal one the height — which is what a drop seeds and what the
        // preview has to draw.
        assertEquals(200.dp, workspace.plannedDockExtent(entry, DockSide.Left))
        assertEquals(300.dp, workspace.plannedDockExtent(entry, DockSide.Bottom))

        workspace.setDockExtent(DockSide.Left, 123.dp)
        assertEquals(123.dp, workspace.plannedDockExtent(entry, DockSide.Left), "an adopted extent wins")
    }

    @Test
    fun `snapshot and restore round trip including a satellite declared later`() {
        val source = SatelliteWorkspace()
        source.join(a)
        source.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val colors = source.register("colors", "Colors", floatingRight, initiallyOpen = true)
        colors.windowState.offsetFromParent = DpOffset(10.dp, 20.dp)
        source.dock("tools", DockSide.Left)
        source.setDockExtent(DockSide.Left, 333.dp)
        source.close("colors")

        val snapshot = source.snapshot()

        val target = SatelliteWorkspace()
        target.restore(snapshot)
        target.join(b)
        val tools = target.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val restoredColors = target.register("colors", "Colors", floatingRight, initiallyOpen = true)

        assertEquals(SatellitePlacement.Docked(DockSide.Left, 0), tools.placement)
        assertSame(b, tools.dockHost)
        assertEquals(333.dp, target.dockExtent(DockSide.Left))
        assertFalse(restoredColors.isOpen)
        val floating = assertIs<SatellitePlacement.Floating>(restoredColors.placement)
        assertEquals(DpOffset(10.dp, 20.dp), floating.positioner.offset)
        assertEquals(WindowConstraintAdjustment.Slide, floating.positioner.constraintAdjustment)
    }

    @Test
    fun `relocated saveable keys resolve across hosts by rotation of the anchor delta`() {
        val anchorA = 0x1234_5678_9ABC_DEF0L
        val anchorB = -0x0FED_CBA9_8765_4322L
        val delta = anchorA xor anchorB
        // Two call sites at depths 2 and 7 below the anchor: their hashes differ
        // between hosts by the delta rotated by the accumulated shifts.
        val siteA1 = 0x0000_00AB_CDEF_0123L
        val siteA2 = -0x7777_0000_1111_2222L
        val siteB1 = siteA1 xor delta.rotateLeft(6)
        val siteB2 = siteA2 xor delta.rotateLeft(21)
        val saved =
            SatelliteSavedState(
                anchor = anchorA,
                values =
                    mapOf(
                        siteA1.toString(36) to listOf<Any?>("first"),
                        siteA2.toString(36) to listOf<Any?>(42),
                        "explicit" to listOf<Any?>("named"),
                    ),
            )

        val registry = RelocatingSaveableStateRegistry(saved, anchorB)

        assertEquals("first", registry.consumeRestored(siteB1.toString(36)))
        assertEquals(42, registry.consumeRestored(siteB2.toString(36)))
        assertEquals("named", registry.consumeRestored("explicit"))
        assertNull(registry.consumeRestored(siteB1.toString(36)))
        assertNull(registry.consumeRestored(0x5555L.toString(36)))
    }

    /**
     * Host `a` as the drag tests see it: outer frame at (100, 100), 800×600,
     * content the same size (client origin = outer origin), DockLayout below a
     * 40 px bar — so its screen rect is (100, 140)–(900, 700), scale 1.
     */
    private fun SatelliteWorkspace.registerHostA(): DockHostGeometry {
        join(a)
        val geometry =
            DockHostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 800L, 600L) }, scaleFactor = { 1f }).apply {
                layoutBoundsInWindowPx = Rect(0f, 40f, 800f, 600f)
                containerSizePx = IntSize(800, 600)
            }
        registerDockHost(geometry)
        return geometry
    }

    @Test
    fun `dock target is the zone strip inside each edge of a registered layout`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()

        assertEquals(DockTarget(a, DockSide.Left), workspace.dockTargetAt(Offset(120f, 400f)))
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockTargetAt(Offset(880f, 400f)))
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(Offset(500f, 150f)))
        assertEquals(DockTarget(a, DockSide.Bottom), workspace.dockTargetAt(Offset(500f, 690f)))
        // Nearest edge wins in a corner.
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(Offset(130f, 150f)))
        assertNull(workspace.dockTargetAt(Offset(500f, 400f)), "content area is not a zone")
        assertNull(workspace.dockTargetAt(Offset(50f, 50f)), "outside the layout")
        assertNull(workspace.dockTargetAt(Offset(500f, 120f)), "the bar above the layout is not a zone")
    }

    @Test
    fun `a floating drag moves the window along and docks where it is released`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val satellite = TaoWindow(handle = 3L)
        val moves = mutableListOf<Pair<Int, Int>>()
        val origin =
            SatelliteDragOrigin.FloatingWindow(
                window = satellite,
                outerBoundsPx = { longArrayOf(400L, 300L, 200L, 150L) },
                move = { x, y -> moves += x to y },
            )

        // Grabbed 50 px right of and 10 px below the window's corner.
        val session = requireNotNull(workspace.beginDrag("tools", origin, Offset(450f, 310f)))
        assertSame(entry, workspace.draggedSatellite, "the zone hints need the drag to be published")
        session.update(Offset(600f, 400f))
        assertEquals(listOf(550 to 390), moves)
        assertNull(workspace.dockPreview)

        session.update(Offset(880f, 400f))
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockPreview)

        session.end(Offset(880f, 400f))
        assertNull(workspace.dockPreview)
        assertNull(workspace.draggedSatellite, "the hints must go away when the drag ends")
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0), entry.placement)
        assertSame(a, entry.dockHost)
    }

    @Test
    fun `a docked drag released over content lifts the panel out under the pointer`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        // The panel as DockLayout laid it out: full height of the layout, 220 px wide.
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
        entry.dockHostContainerSizePx = IntSize(800, 600)

        // Grabbed at screen (150, 200) = 50 px into the panel, 60 px down.
        val session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))

        // Hovering the panel's own zone is not a drop target, but the panel is
        // already out: the ghost follows from the first move.
        session.update(Offset(120f, 400f))
        assertNull(workspace.dockPreview)
        assertEquals(Rect(Offset(70f, 340f), Size(220f, 560f)), workspace.dragGhost?.screenRectPx)

        // Over the content: the ghost follows the pointer, in screen px, with
        // the grab point held under it.
        session.update(Offset(500f, 400f))
        assertNull(workspace.dockPreview)
        assertEquals(
            DragGhost(entry, Rect(Offset(450f, 340f), Size(220f, 560f)), scaleFactor = 1f),
            workspace.dragGhost,
        )

        session.end(Offset(500f, 400f))
        assertNull(workspace.dragGhost)
        assertNull(workspace.draggedSatellite)
        val floating = assertIs<SatellitePlacement.Floating>(entry.placement)
        assertEquals(DpOffset(350.dp, 240.dp), floating.positioner.offset)
        assertEquals(DpSize(220.dp, 560.dp), floating.size)
        assertNull(entry.dockHost)
    }

    @Test
    fun `a docked drag released in another zone re-docks and inside its own panel stays`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
        entry.dockHostContainerSizePx = IntSize(800, 600)

        var session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))
        session.update(Offset(160f, 300f))
        session.end(Offset(160f, 300f))
        assertEquals(SatellitePlacement.Docked(DockSide.Left, 0), entry.placement, "released inside its own panel")

        session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))
        assertSame(entry, workspace.draggedSatellite)
        session.update(Offset(500f, 690f))
        assertEquals(DockTarget(a, DockSide.Bottom), workspace.dockPreview)
        session.end(Offset(500f, 690f))
        assertEquals(SatellitePlacement.Docked(DockSide.Bottom, 0), entry.placement)
        assertSame(a, entry.dockHost)
        assertNull(workspace.dockPreview)
        assertNull(workspace.draggedSatellite)
    }

    @Test
    fun `a cancelled drag leaves no feedback and no placement change`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
        entry.dockHostContainerSizePx = IntSize(800, 600)

        val session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))
        session.update(Offset(500f, 400f))
        session.cancel()

        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dockPreview)
        assertNull(workspace.dragGhost)
        assertEquals(SatellitePlacement.Docked(DockSide.Left, 0), entry.placement)
    }

    // ── Adversarial drags: teleporting pointers, overlapping gestures,
    // ── unusable coordinates, hosts and satellites disappearing mid-drag.

    @Test
    fun `a teleporting pointer lands on the zone it was released in`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val moves = mutableListOf<Pair<Int, Int>>()
        val session =
            requireNotNull(
                workspace.beginDrag("tools", floatingOrigin(moves), Offset(450f, 310f)),
            )

        // No intermediate samples at all: straight from one edge of the desktop
        // to the other, across and out of the layout, several times.
        session.update(Offset(-5_000f, -5_000f))
        assertNull(workspace.dockPreview, "far off-screen is not a dock zone")
        session.update(Offset(120f, 400f))
        assertEquals(DockTarget(a, DockSide.Left), workspace.dockPreview)
        session.update(Offset(9_000f, 9_000f))
        assertNull(workspace.dockPreview)
        session.update(Offset(500f, 690f))
        assertEquals(DockTarget(a, DockSide.Bottom), workspace.dockPreview)

        session.end(Offset(880f, 400f))
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0), entry.placement)
        assertNull(workspace.draggedSatellite)
        // Every jump moved the window, and none of them overflowed.
        assertTrue(moves.all { (x, y) -> x in -1_000_000..1_000_000 && y in -1_000_000..1_000_000 }, "moves=$moves")
    }

    @Test
    fun `non-finite pointer samples are ignored and leave the last position standing`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val moves = mutableListOf<Pair<Int, Int>>()
        val session =
            requireNotNull(
                workspace.beginDrag("tools", floatingOrigin(moves), Offset(450f, 310f)),
            )

        session.update(Offset(880f, 400f))
        val afterGoodSample = moves.size
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockPreview)

        session.update(Offset.Unspecified)
        session.update(Offset(Float.NaN, 400f))
        session.update(Offset(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))

        // The preview still names the last usable position, and the window was
        // asked to go back to it rather than somewhere undefined.
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockPreview)
        assertTrue(moves.size > afterGoodSample)
        assertEquals(moves[afterGoodSample - 1], moves.last(), "moves=$moves")

        // A release carrying garbage still drops where the pointer last was.
        session.end(Offset.Unspecified)
        assertEquals(
            SatellitePlacement.Docked(DockSide.Right, 0),
            requireNotNull(workspace.satellite("tools")).placement,
        )
    }

    @Test
    fun `a superseded drag stops acting and cannot clear the live one`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val tools = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val colors = workspace.register("colors", "Colors", floatingRight, initiallyOpen = true)
        val staleMoves = mutableListOf<Pair<Int, Int>>()
        val stale = requireNotNull(workspace.beginDrag("tools", floatingOrigin(staleMoves), Offset(450f, 310f)))
        stale.update(Offset(880f, 400f))
        val movesBeforeSupersede = staleMoves.size

        // A second grab starts while the first was never released.
        val live = requireNotNull(workspace.beginDrag("colors", floatingOrigin(), Offset(450f, 310f)))
        assertSame(colors, workspace.draggedSatellite)

        // The abandoned session is inert: no window moves, no feedback writes.
        stale.update(Offset(120f, 400f))
        assertEquals(movesBeforeSupersede, staleMoves.size)
        assertSame(colors, workspace.draggedSatellite)
        stale.end(Offset(120f, 400f))
        assertFalse(tools.isDocked, "a stale release must not dock anything")
        assertSame(colors, workspace.draggedSatellite, "and must not clear the live drag")

        // The live one still works.
        live.update(Offset(880f, 400f))
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockPreview)
        live.end(Offset(880f, 400f))
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0), colors.placement)
        assertNull(workspace.draggedSatellite)
    }

    @Test
    fun `ending or cancelling twice is a no-op`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val session = requireNotNull(workspace.beginDrag("tools", floatingOrigin(), Offset(450f, 310f)))

        session.end(Offset(880f, 400f))
        val docked = entry.placement
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0), docked)

        // A duplicated release (a replayed event, a second finally block) must
        // not re-dock, re-order or resurrect the feedback.
        session.end(Offset(500f, 690f))
        session.cancel()
        session.update(Offset(120f, 400f))
        assertEquals(docked, entry.placement)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dockPreview)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `the tear-out ghost carries the host scale, not the composition's`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        // A 2x host: the panel rect is in physical pixels, and the ghost window
        // is placed in logical ones, so the scale has to travel with the rect.
        val geometry =
            DockHostGeometry(a, outerBoundsPx = { longArrayOf(100L, 100L, 1600L, 1200L) }, scaleFactor = { 2f }).apply {
                layoutBoundsInWindowPx = Rect(0f, 80f, 1600f, 1200f)
                containerSizePx = IntSize(1600, 1200)
            }
        workspace.registerDockHost(geometry)
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 80f, 440f, 1200f)
        entry.dockHostContainerSizePx = IntSize(1600, 1200)

        val session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(200f, 300f)))
        session.update(Offset(900f, 700f))

        val ghost = requireNotNull(workspace.dragGhost)
        assertEquals(2f, ghost.scaleFactor)
        assertEquals(Size(440f, 1120f), ghost.screenRectPx.size, "the rect stays in physical pixels")
    }

    @Test
    fun `a drag whose host leaves mid-gesture still resolves`() {
        val workspace = SatelliteWorkspace()
        val geometry = workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
        entry.dockHostContainerSizePx = IntSize(800, 600)
        val session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))
        session.update(Offset(500f, 400f))

        // The window the panel is being torn out of goes away underneath.
        workspace.unregisterDockHost(a, geometry)
        workspace.leave(a)

        session.end(Offset(500f, 400f))
        assertIs<SatellitePlacement.Floating>(entry.placement)
        assertNull(entry.dockHost)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `a drag whose satellite is closed mid-gesture changes nothing`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val session = requireNotNull(workspace.beginDrag("tools", floatingOrigin(), Offset(450f, 310f)))
        session.update(Offset(880f, 400f))

        val placementBeforeClose = entry.placement
        workspace.close("tools")
        workspace.unregister(entry)

        session.end(Offset(880f, 400f))

        // Closing does not un-register the entry from the workspace, so the
        // drop still resolves — what must hold is that the satellite is closed
        // and that nothing is left published.
        assertFalse(entry.isOpen)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dockPreview)
        assertNull(workspace.dragGhost)
        assertNotEquals(
            placementBeforeClose,
            entry.placement,
            "the drop was over a dock zone, so it should have taken effect",
        )
        assertIs<SatellitePlacement.Docked>(entry.placement)
    }

    @Test
    fun `dock and undock churn keeps one consistent placement`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val sides = DockSide.entries

        repeat(CHURN_CYCLES) { index ->
            val side = sides[index % sides.size]
            workspace.dock("tools", side)
            entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
            entry.dockHostContainerSizePx = IntSize(800, 600)
            assertEquals(side, (entry.placement as SatellitePlacement.Docked).side)
            assertSame(a, entry.dockHost)
            workspace.undock("tools")
            assertIs<SatellitePlacement.Floating>(entry.placement)
            assertNull(entry.dockHost)
            assertEquals(side, entry.preferredDockSide)
        }

        // No accumulated order drift: it is still the only panel on its side.
        workspace.dock("tools", DockSide.Right)
        assertEquals(SatellitePlacement.Docked(DockSide.Right, 0), entry.placement)
        assertNull(workspace.draggedSatellite, "churn must not leave a drag behind")
    }

    @Test
    fun `interleaved drags of two satellites keep their own placements`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val tools = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        val colors = workspace.register("colors", "Colors", floatingRight, initiallyOpen = true)

        repeat(CHURN_CYCLES) {
            val first = requireNotNull(workspace.beginDrag("tools", floatingOrigin(), Offset(450f, 310f)))
            first.update(Offset(120f, 400f))
            first.end(Offset(120f, 400f))
            val second = requireNotNull(workspace.beginDrag("colors", floatingOrigin(), Offset(450f, 310f)))
            second.update(Offset(880f, 400f))
            second.end(Offset(880f, 400f))
            workspace.undock("tools")
            workspace.undock("colors")
        }

        workspace.dock("tools", DockSide.Left)
        workspace.dock("colors", DockSide.Left)
        assertEquals(SatellitePlacement.Docked(DockSide.Left, 0), tools.placement)
        assertEquals(SatellitePlacement.Docked(DockSide.Left, 1), colors.placement)
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `a drop resolves against the state a restore left behind`() {
        val workspace = SatelliteWorkspace()
        workspace.registerHostA()
        val entry = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Left)
        entry.dockedBoundsInWindowPx = Rect(0f, 40f, 220f, 600f)
        entry.dockHostContainerSizePx = IntSize(800, 600)
        val snapshot = workspace.snapshot()

        val session = requireNotNull(workspace.beginDrag("tools", panelOrigin, Offset(150f, 200f)))
        session.update(Offset(500f, 400f))
        workspace.undock("tools")
        workspace.restore(snapshot)
        assertEquals(SatellitePlacement.Docked(DockSide.Left, 0), entry.placement)

        // The release reads the *current* placement, not the one the gesture
        // started from: released over the content, it tears the restored panel
        // out again rather than replaying the drop it was set up for.
        session.end(Offset(500f, 400f))
        assertNull(workspace.draggedSatellite)
        assertNull(workspace.dragGhost)
        assertIs<SatellitePlacement.Floating>(entry.placement)
        assertNull(entry.dockHost)
    }

    /** A floating origin whose geometry is fixed and whose moves are recorded. */
    private fun floatingOrigin(moves: MutableList<Pair<Int, Int>> = mutableListOf()) =
        SatelliteDragOrigin.FloatingWindow(
            window = TaoWindow(handle = 9L),
            outerBoundsPx = { longArrayOf(400L, 300L, 200L, 150L) },
            move = { x, y -> moves += x to y },
        )

    @Test
    fun `saved values keep composition order when providers unregister in reverse`() {
        val registry = RelocatingSaveableStateRegistry(saved = null, anchor = 1L)
        // Three call sites sharing one key — what Compose does with sibling
        // rememberSaveable / rememberScrollState calls in the same group.
        val entries =
            listOf<Any>("tool", 33f, 0).map { value ->
                registry.registerProvider("shared") { value }
            }

        // Compose forgets in reverse composition order, before the host's own
        // disposable effect gets to save.
        entries.asReversed().forEach { it.unregister() }

        assertEquals(mapOf("shared" to listOf<Any?>("tool", 33f, 0)), registry.performSave())
    }

    @Test
    fun `a re-registering provider keeps its place among the values`() {
        val registry = RelocatingSaveableStateRegistry(saved = null, anchor = 1L)
        registry.registerProvider("shared") { "first" }
        val second = registry.registerProvider("shared") { "second" }
        registry.registerProvider("shared") { "third" }

        // A recomposing rememberSaveable: unregisters, then registers again.
        second.unregister()
        registry.registerProvider("shared") { "second-again" }

        assertEquals(mapOf("shared" to listOf<Any?>("first", "second-again", "third")), registry.performSave())
    }

    @Test
    fun `restored values never consumed survive another host change`() {
        val saved = SatelliteSavedState(anchor = 1L, values = mapOf("kept" to listOf<Any?>("value")))
        val registry = RelocatingSaveableStateRegistry(saved, anchor = 2L)
        registry.registerProvider("other") { "live" }

        assertEquals(
            mapOf("kept" to listOf<Any?>("value"), "other" to listOf<Any?>("live")),
            registry.performSave(),
        )
    }

    @Test
    fun `re-registering an id keeps the workspace's memory of it`() {
        val workspace = SatelliteWorkspace()
        workspace.join(a)
        val first = workspace.register("tools", "Tools", floatingRight, initiallyOpen = true)
        workspace.dock("tools", DockSide.Top)
        workspace.unregister(first)

        val again = workspace.register("tools", "Renamed", floatingRight, initiallyOpen = false)

        assertSame(first, again)
        assertEquals("Renamed", again.title)
        assertTrue(again.isOpen)
        assertTrue(again.isDocked)
    }
}
