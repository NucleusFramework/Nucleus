package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.DockDropZone
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a drop preview is drawn ([DockLayoutState.landingRectPx]): along the
 * side's band, not the whole layout; inside the layers already docked on a
 * layered side; on the stack itself when the panel joins a split stack.
 *
 * The geometry is the reader layout — right side first and layered, bottom
 * inside it — laid out at 1000 × 600 px, offset in the window by (20, 40).
 */
class DockLandingRectTest {
    private val host = TaoWindow(handle = 1L)
    private val workspace = SatelliteWorkspace().apply { join(host) }
    private val state =
        DockLayoutState(workspace).apply {
            layeredSides = setOf(DockSide.Right)
            layoutBoundsInWindowPx = Rect(20f, 40f, 1020f, 640f)
            // The right band is the whole layout; the bottom band stops at the right stack.
            bandBoundsInWindowPx[DockSide.Right] = Rect(20f, 40f, 1020f, 640f)
            bandBoundsInWindowPx[DockSide.Bottom] = Rect(20f, 40f, 720f, 640f)
            bandBoundsInWindowPx[DockSide.Left] = Rect(20f, 40f, 720f, 440f)
            bandBoundsInWindowPx[DockSide.Top] = Rect(220f, 40f, 720f, 440f)
        }

    private fun docked(
        id: String,
        side: DockSide,
        order: Int,
        boundsInWindowPx: Rect,
    ): SatelliteEntry {
        val entry =
            workspace.register(
                id,
                id,
                SatellitePlacement.Docked(side, order, extent = 100.dp),
                initiallyOpen = true,
            )
        entry.dockedBoundsInWindowPx = boundsInWindowPx
        return entry
    }

    @Test
    fun `a bottom preview spans the bottom band, not the layout`() {
        state.docked = emptyList()
        assertEquals(Rect(0f, 540f, 700f, 600f), state.landingRectPx(DockSide.Bottom, 60f, joinsStack = true))
    }

    @Test
    fun `a layered side previews a new innermost layer`() {
        state.docked =
            listOf(
                docked("tree", DockSide.Right, 0, Rect(920f, 40f, 1020f, 640f)),
                docked("toc", DockSide.Right, 1, Rect(820f, 40f, 920f, 640f)),
            )
        assertEquals(Rect(740f, 0f, 800f, 600f), state.landingRectPx(DockSide.Right, 60f, joinsStack = true))
    }

    @Test
    fun `a split side with a stack previews the stack the panel joins`() {
        state.docked = listOf(docked("targum", DockSide.Left, 0, Rect(20f, 40f, 220f, 440f)))
        assertEquals(Rect(0f, 0f, 200f, 400f), state.landingRectPx(DockSide.Left, 60f, joinsStack = true))
        // The idle outline stays a strip at the edge of the band.
        assertEquals(Rect(0f, 0f, 60f, 400f), state.landingRectPx(DockSide.Left, 60f, joinsStack = false))
    }

    @Test
    fun `an empty side previews a strip at the edge of its band`() {
        state.docked = emptyList()
        assertEquals(Rect(200f, 0f, 700f, 60f), state.landingRectPx(DockSide.Top, 60f, joinsStack = true))
        assertEquals(Rect(0f, 0f, 60f, 400f), state.landingRectPx(DockSide.Left, 60f, joinsStack = true))
    }

    @Test
    fun `the side the dragged panel frees is counted as already gone`() {
        // The reader shape: the bottom band stops at the layered right stack,
        // and the left band stops above the bottom panel.
        val comments = docked("comments", DockSide.Bottom, 0, Rect(20f, 440f, 720f, 640f))
        state.docked = listOf(comments)

        // Previewing the left side while dragging the *only* bottom panel: the
        // bottom frees up, so the drop will run the full height of the band.
        assertEquals(
            Rect(0f, 0f, 60f, 600f),
            state.landingRectPx(DockSide.Left, 60f, joinsStack = true, dragged = comments),
        )
        // Without the drag it is the band as measured, above the panel.
        assertEquals(Rect(0f, 0f, 60f, 400f), state.landingRectPx(DockSide.Left, 60f, joinsStack = true))
    }

    @Test
    fun `a side the dragged panel shares with another is not freed`() {
        val comments = docked("comments", DockSide.Bottom, 0, Rect(20f, 440f, 380f, 640f))
        val sources = docked("sources", DockSide.Bottom, 1, Rect(380f, 440f, 720f, 640f))
        state.docked = listOf(comments, sources)

        assertEquals(
            Rect(0f, 0f, 60f, 400f),
            state.landingRectPx(DockSide.Left, 60f, joinsStack = true, dragged = comments),
            "the bottom side keeps its extent, so the left band is unchanged",
        )
    }

    @Test
    fun `without a measured band the layout itself is the band`() {
        val bare = DockLayoutState(workspace).apply { layoutBoundsInWindowPx = Rect(0f, 0f, 400f, 300f) }
        assertEquals(Rect(340f, 0f, 400f, 300f), bare.landingRectPx(DockSide.Right, 60f, joinsStack = true))
    }
}

/**
 * Which sides a drag is offered ([hintedSides]): all four, minus the one the
 * dragged panel already occupies in the very window being hinted.
 */
class DockZoneHintSidesTest {
    private val host = TaoWindow(handle = 1L)
    private val other = TaoWindow(handle = 2L)
    private val workspace = SatelliteWorkspace().apply { join(host) }

    private val floating =
        SatellitePlacement.Floating(
            positioner = WindowPositioner(parentAnchor = WindowAnchor.Right, childAnchor = WindowAnchor.Left),
            size = DpSize(200.dp, 300.dp),
        )

    @Test
    fun `a floating satellite is offered every side`() {
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true)
        assertEquals(DockSide.entries, hintedSides(entry, host, workspace.satellites))
    }

    @Test
    fun `a docked panel is not offered the side it is alone on`() {
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true)
        workspace.dock("tools", DockSide.Bottom, host = host)
        assertEquals(
            listOf(DockSide.Left, DockSide.Right, DockSide.Top),
            hintedSides(entry, host, workspace.satellites),
        )
    }

    @Test
    fun `a docked panel with a neighbour is offered its own side, to be ranked among them`() {
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true)
        val other = workspace.register("colors", "Colors", floating, initiallyOpen = true)
        other.content = {}
        workspace.dock("tools", DockSide.Bottom, host = host)
        workspace.dock("colors", DockSide.Bottom, host = host)
        assertEquals(DockSide.entries, hintedSides(entry, host, workspace.satellites))
        // A closed neighbour is not shown, so there is nothing to rank against.
        workspace.close("colors")
        assertEquals(
            listOf(DockSide.Left, DockSide.Right, DockSide.Top),
            hintedSides(entry, host, workspace.satellites),
        )
    }

    @Test
    fun `another window offers the side too, since dropping there is a move`() {
        val entry = workspace.register("tools", "Tools", floating, initiallyOpen = true)
        workspace.join(other)
        workspace.dock("tools", DockSide.Bottom, host = host)
        assertEquals(DockSide.entries, hintedSides(entry, other, workspace.satellites))
    }
}

/**
 * The ranks a drop can take among the panels of a side
 * ([DockLayoutState.dropSlotsPx]) and the space drawn for one
 * ([DockLayoutState.dropRectPx]), on the reader layout of
 * [DockLandingRectTest]: layered right side, split bottom, layout px.
 */
class DockDropSlotsTest {
    private val host = TaoWindow(handle = 1L)
    private val workspace = SatelliteWorkspace().apply { join(host) }
    private val state =
        DockLayoutState(workspace).apply {
            layeredSides = setOf(DockSide.Right)
            layoutBoundsInWindowPx = Rect(20f, 40f, 1020f, 640f)
            bandBoundsInWindowPx[DockSide.Right] = Rect(20f, 40f, 1020f, 640f)
            bandBoundsInWindowPx[DockSide.Bottom] = Rect(20f, 40f, 720f, 640f)
        }

    private fun docked(
        id: String,
        side: DockSide,
        order: Int,
        boundsInLayoutPx: Rect,
    ): SatelliteEntry {
        val entry =
            workspace.register(id, id, SatellitePlacement.Docked(side, order, extent = 100.dp), initiallyOpen = true)
        entry.content = {}
        entry.dockedBoundsInWindowPx = boundsInLayoutPx.translate(Offset(20f, 40f))
        return entry
    }

    private val tree = docked("tree", DockSide.Right, 0, Rect(900f, 0f, 1000f, 600f))
    private val toc = docked("toc", DockSide.Right, 1, Rect(800f, 0f, 900f, 600f))
    private val comments = docked("comments", DockSide.Bottom, 0, Rect(0f, 540f, 350f, 600f))
    private val sources = docked("sources", DockSide.Bottom, 1, Rect(350f, 540f, 700f, 600f))

    init {
        state.docked = listOf(tree, toc, comments, sources)
    }

    @Test
    fun `a layered side is cut at the layers' centres, from its edge through the strip`() {
        val strip = state.landingRectPx(DockSide.Right, 60f, joinsStack = false)
        assertEquals(Rect(740f, 0f, 800f, 600f), strip)
        assertEquals(
            listOf(Rect(950f, 0f, 1000f, 600f), Rect(850f, 0f, 950f, 600f), Rect(740f, 0f, 850f, 600f)),
            state.dropSlotsPx(DockSide.Right, strip, dragged = null),
        )
        // The dragged layer is left out: its neighbours' centres are the cuts,
        // and the region it stands in is the rank it already holds.
        assertEquals(
            listOf(Rect(950f, 0f, 1000f, 600f), Rect(740f, 0f, 950f, 600f)),
            state.dropSlotsPx(DockSide.Right, strip, dragged = toc),
        )
        assertEquals(
            1,
            DockDropZone(strip, state.dropSlotsPx(DockSide.Right, strip, dragged = toc)).slotAt(Offset(850f, 300f)),
        )
        assertEquals(DockTarget(host, DockSide.Right, 1), workspace.ownTarget(toc, host))
    }

    @Test
    fun `a split side is cut along its length, from the band's start`() {
        val strip = state.landingRectPx(DockSide.Bottom, 60f, joinsStack = false)
        assertEquals(
            listOf(Rect(0f, 540f, 175f, 600f), Rect(175f, 540f, 525f, 600f), Rect(525f, 540f, 700f, 600f)),
            state.dropSlotsPx(DockSide.Bottom, strip, dragged = null),
        )
    }

    @Test
    fun `no slots without another panel, or before it is placed`() {
        val strip = state.landingRectPx(DockSide.Left, 60f, joinsStack = false)
        assertEquals(emptyList(), state.dropSlotsPx(DockSide.Left, strip, dragged = null))
        tree.dockedBoundsInWindowPx = null
        assertEquals(emptyList(), state.dropSlotsPx(DockSide.Right, strip, dragged = null))
        assertNull(DockDropZone(strip).slotAt(Offset.Zero))
    }

    @Test
    fun `the pointer picks the slot it is in, else the nearest end`() {
        val zone =
            DockDropZone(
                strip = Rect(0f, 540f, 700f, 600f),
                slots = listOf(Rect(0f, 540f, 175f, 600f), Rect(175f, 540f, 525f, 600f), Rect(525f, 540f, 700f, 600f)),
            )
        assertEquals(1, zone.slotAt(Offset(300f, 570f)))
        assertEquals(0, zone.slotAt(Offset(-50f, 570f)), "past the start")
        assertEquals(2, zone.slotAt(Offset(900f, 570f)), "past the end")
        assertEquals(1, zone.slotAt(Offset(300f, 100f)), "off the stack: the rank under the pointer's x")
    }

    @Test
    fun `a pinned layer hides the ranks in front of it, for itself and for the others`() {
        val pinned =
            workspace.register(
                "pinned",
                "pinned",
                SatellitePlacement.Docked(DockSide.Left, order = 0, extent = 100.dp),
                initiallyOpen = true,
                reorderable = false,
            )
        pinned.content = {}
        pinned.dockedBoundsInWindowPx = Rect(20f, 40f, 120f, 640f)
        // The helper takes layout px; the pinned entry above is set in window px.
        val movable = docked("movable", DockSide.Left, 1, Rect(100f, 0f, 200f, 600f))
        state.layeredSides = state.layeredSides + DockSide.Left
        state.docked = listOf(pinned, movable)
        state.bandBoundsInWindowPx[DockSide.Left] = Rect(20f, 40f, 1020f, 640f)
        val strip = state.landingRectPx(DockSide.Left, 60f, joinsStack = false)
        assertEquals(Rect(200f, 0f, 260f, 600f), strip, "inset behind the two layers")

        // Dragging the movable layer: rank 0 would push the pinned one in, so
        // it is not on offer — an empty rect keeping the ranks aligned — and
        // rank 1 covers the whole region, the pinned layer included.
        assertEquals(
            listOf(Rect.Zero, Rect(0f, 0f, 260f, 600f)),
            state.dropSlotsPx(DockSide.Left, strip, dragged = movable),
        )
        val zone = DockDropZone(strip, state.dropSlotsPx(DockSide.Left, strip, dragged = movable))
        assertEquals(1, zone.slotAt(Offset(50f, 300f)), "aimed at the pinned layer, it lands behind it")
        // Aimed in front of it, the movable layer is shown right behind it.
        assertEquals(Rect(100f, 0f, 160f, 600f), state.dropRectPx(DockSide.Left, movable, 0, 60f))
        // The pinned layer itself is offered no rank at all.
        assertEquals(emptyList(), state.dropSlotsPx(DockSide.Left, strip, dragged = pinned))
    }

    @Test
    fun `a layer dropped at a rank is drawn where that rank puts it, at its own extent`() {
        // Layered right: rank 1 is between the tree (900..1000) and the toc, which moves in to make room.
        assertEquals(Rect(840f, 0f, 900f, 600f), state.dropRectPx(DockSide.Right, null, 1, 60f))
        assertEquals(Rect(940f, 0f, 1000f, 600f), state.dropRectPx(DockSide.Right, null, 0, 60f), "the side's edge")
        assertEquals(Rect(740f, 0f, 800f, 600f), state.dropRectPx(DockSide.Right, null, 2, 60f), "past the innermost")
        // The toc dragged to rank 0: only the tree stays, behind it.
        assertEquals(Rect(940f, 0f, 1000f, 600f), state.dropRectPx(DockSide.Right, toc, 0, 60f))
    }

    @Test
    fun `a panel dropped in a split stack is drawn as the share the weights give it`() {
        // Split bottom, the sources dragged: they and the comments share the length again.
        assertEquals(Rect(350f, 540f, 700f, 600f), state.dropRectPx(DockSide.Bottom, sources, 1, 60f))
        assertEquals(Rect(0f, 540f, 350f, 600f), state.dropRectPx(DockSide.Bottom, sources, 0, 60f))
        // A third panel, weight 1, in the middle: a third each.
        val notes = workspace.register("notes", "notes", SatellitePlacement.Floating(), initiallyOpen = true)
        assertRectEquals(Rect(700f / 3, 540f, 1400f / 3, 600f), state.dropRectPx(DockSide.Bottom, notes, 1, 60f))
        // Twice the weight of each of the others, between them: half the stack.
        workspace.dock("notes", DockSide.Left, host = host)
        workspace.setDockedWeight("notes", 2f)
        assertRectEquals(Rect(175f, 540f, 525f, 600f), state.dropRectPx(DockSide.Bottom, notes, 1, 60f))
    }

    private fun assertRectEquals(
        expected: Rect,
        actual: Rect,
    ) {
        val close =
            listOf(expected.left to actual.left, expected.top to actual.top)
                .plus(expected.right to actual.right)
                .plus(expected.bottom to actual.bottom)
                .all { (e, a) -> abs(e - a) < 0.01f }
        assertTrue(close, "expected $expected, was $actual")
    }

    @Test
    fun `dropped on an empty side, the space is the strip along its edge`() {
        assertEquals(Rect(0f, 0f, 60f, 600f), state.dropRectPx(DockSide.Left, null, 0, 60f))
    }
}

/**
 * Which zone a drag resolves to ([SatelliteWorkspace.dockTargetAt] with the
 * dragged rect): the zone the satellite on screen has been brought against,
 * with the pointer as a second trigger and as the tie-break.
 *
 * Host a's layout is (100, 140)-(900, 700) on screen, zone width 64 px.
 */
class DockTargetFromDraggedRectTest {
    private val a = TaoWindow(handle = 1L)

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

    @Test
    fun `the dragged rect decides the zone, not the pointer`() {
        val workspace = workspace()

        // A 200 x 300 palette pushed against the left edge: its own edge is in
        // the zone while the pointer sits in the middle of the palette, far
        // from any edge of the layout.
        val atLeft = Rect(120f, 300f, 320f, 600f)
        assertEquals(
            DockTarget(a, DockSide.Left),
            workspace.dockTargetAt(atLeft, atLeft.center),
            "the palette's own edge has entered the left zone",
        )
        assertNull(workspace.dockTargetAt(atLeft.center), "the pointer alone is over the content")

        // Aligned from the outside too: pushed 20 px past the edge is still
        // brought against it.
        val justOver = Rect(80f, 300f, 280f, 600f)
        assertEquals(DockTarget(a, DockSide.Left), workspace.dockTargetAt(justOver, justOver.center))

        // Deep past the edge is no longer an alignment — but the pointer, now
        // over the left strip itself, still is.
        val overhanging = Rect(20f, 300f, 220f, 600f)
        assertEquals(DockTarget(a, DockSide.Left), workspace.dockTargetAt(overhanging, Offset(120f, 450f)))
        assertNull(workspace.dockTargetAt(overhanging, Offset(200f, 450f)), "neither edge nor pointer is at a zone")

        // Over the middle: no zone, whatever the pointer does.
        val middle = Rect(400f, 350f, 600f, 500f)
        assertNull(workspace.dockTargetAt(middle, middle.center), "nothing has entered a zone")

        // Beside the layout, not on it: no drop, even with the pointer inside.
        val beside = Rect(950f, 300f, 1150f, 600f)
        assertNull(workspace.dockTargetAt(beside, beside.center))

        // Aligned with two sides at once: the pointer decides.
        val topLeftCorner = Rect(120f, 150f, 320f, 250f)
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(topLeftCorner, Offset(300f, 240f)))
    }

    @Test
    fun `an inset zone is the target, not the window's own edge`() {
        val workspace = workspace()
        // What a layered right side draws while two columns are already
        // docked: the strip is inset 200 px behind them, not at x 900.
        val geometry = requireNotNull(workspace.dockHostGeometry(a))
        geometry.zoneBoundsInWindowPx = mapOf(DockSide.Right to DockDropZone(Rect(540f, 40f, 604f, 600f)))

        // The palette brought against the drawn strip docks…
        val onStrip = Rect(440f, 300f, 700f, 600f)
        assertEquals(DockTarget(a, DockSide.Right), workspace.dockTargetAt(onStrip, onStrip.center))
        // …while the window's own right edge, behind the columns, is nothing.
        val atWindowEdge = Rect(700f, 300f, 900f, 600f)
        assertNull(workspace.dockTargetAt(atWindowEdge, atWindowEdge.center))
        // The pointer in the drawn strip is a target too.
        assertNull(workspace.dockTargetAt(atWindowEdge, Offset(880f, 400f)), "the window edge is not a zone")
        assertEquals(
            DockTarget(a, DockSide.Right),
            workspace.dockTargetAt(atWindowEdge, Offset(670f, 400f)),
            "the pointer inside the drawn strip",
        )
        // A side the layout does not draw is not a target at all.
        assertNull(workspace.dockTargetAt(Rect(120f, 300f, 320f, 600f), Offset(120f, 400f)), "no left zone is drawn")
    }

    @Test
    fun `the pointer over a stack picks a rank, and beats a strip across its corner`() {
        val workspace = workspace()
        val geometry = requireNotNull(workspace.dockHostGeometry(a))
        // A split left side with two panels (window px 0..200 wide, 40..600
        // tall) and an empty top side whose strip runs across the stack's top.
        geometry.zoneBoundsInWindowPx =
            mapOf(
                DockSide.Left to
                    DockDropZone(
                        strip = Rect(0f, 40f, 64f, 600f),
                        slots =
                            listOf(
                                Rect(0f, 40f, 200f, 180f),
                                Rect(0f, 180f, 200f, 460f),
                                Rect(0f, 460f, 200f, 600f),
                            ),
                    ),
                DockSide.Top to DockDropZone(Rect(0f, 40f, 800f, 104f)),
            )
        // The dragged ghost sits over the content, the pointer over the stack.
        val ghost = Rect(400f, 300f, 600f, 450f)
        assertEquals(DockTarget(a, DockSide.Left, 1), workspace.dockTargetAt(ghost, Offset(250f, 400f)))
        assertEquals(DockTarget(a, DockSide.Left, 2), workspace.dockTargetAt(ghost, Offset(250f, 650f)))
        // In the corner both the top strip and the first rank hold the pointer: the rank wins.
        assertEquals(DockTarget(a, DockSide.Left, 0), workspace.dockTargetAt(ghost, Offset(250f, 160f)))
        // Brought against the strip with the pointer away from the stack: the nearest rank along it.
        val atLeft = Rect(120f, 300f, 320f, 600f)
        assertEquals(DockTarget(a, DockSide.Left, 1), workspace.dockTargetAt(atLeft, Offset(220f, 450f)))
        // An unranked side stays unranked.
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(ghost, Offset(500f, 170f)))
    }

    @Test
    fun `a dragged rect covering every zone is resolved by the pointer`() {
        val workspace = workspace()
        // Larger than the layout: every side is within reach at once.
        val covering = Rect(50f, 100f, 950f, 750f)

        assertEquals(DockTarget(a, DockSide.Left), workspace.dockTargetAt(covering, Offset(120f, 400f)))
        assertEquals(DockTarget(a, DockSide.Bottom), workspace.dockTargetAt(covering, Offset(500f, 690f)))
        // Pointer off the layout: the closest alignment decides instead — the
        // covering rect overhangs the top by the least.
        assertEquals(DockTarget(a, DockSide.Top), workspace.dockTargetAt(covering, Offset(0f, 0f)))
    }
}
