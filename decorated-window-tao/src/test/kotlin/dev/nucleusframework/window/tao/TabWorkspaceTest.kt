package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.tao.workspace.HostGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tab model, drop resolution and drag sessions of [TabWorkspace], driven
 * without any native window: group windows are bare [TaoWindow] handles and
 * strip geometry is published by hand. The headful suite covers real windows.
 */
@Suppress("LargeClass") // one model, and the adversarial half of one gesture
class TabWorkspaceTest {
    private companion object {
        /** Enough repetitions to expose accumulated drift, still instant. */
        const val CHURN_CYCLES = 50

        /** A window frame whose client area starts at its own origin. */
        val FirstWindowFrame = longArrayOf(0L, 0L, 800L, 600L)
        val SecondWindowFrame = longArrayOf(1000L, 0L, 800L, 600L)
    }

    private val firstWindow = TaoWindow(handle = 1L)
    private val secondWindow = TaoWindow(handle = 2L)

    // ── Declaration and placement ────────────────────────────────────────

    @Test
    fun `the first tab opens a window and the next ones join it`() {
        val workspace = TabWorkspace()

        val alpha = workspace.register("a", "Alpha", groupId = null)
        assertEquals(1, workspace.groups.size)
        val group = workspace.groups.single()
        assertSame(group, alpha.group)
        assertEquals("a", group.selectedId, "the first tab of a window is selected")

        workspace.register("b", "Beta", groupId = null)
        assertEquals(1, workspace.groups.size, "a second tab must not open a second window")
        assertEquals(listOf("a", "b"), group.ids)
        assertEquals("b", group.selectedId, "an arriving tab is selected")
    }

    @Test
    fun `a named group is created on demand and keeps its name`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("b", "Beta", groupId = "right")
        workspace.register("c", "Gamma", groupId = "left")

        assertEquals(listOf("left", "right"), workspace.groups.map { it.id })
        assertEquals(listOf("a", "c"), workspace.group("left")?.ids)
        assertEquals(listOf("b"), workspace.group("right")?.ids)
    }

    @Test
    fun `re-registering an id keeps its place and only refreshes the title`() {
        val workspace = TabWorkspace()
        val first = workspace.register("a", "Alpha", groupId = null)
        workspace.register("b", "Beta", groupId = null)
        workspace.select("a")

        val again = workspace.register("a", "Renamed", groupId = "somewhere-else")

        assertSame(first, again)
        assertEquals("Renamed", again.title)
        assertEquals(1, workspace.groups.size, "an already known id must not open a window")
        assertEquals(listOf("a", "b"), workspace.groups.single().ids)
        assertEquals("a", workspace.groups.single().selectedId, "the selection is left alone")
    }

    // ── Selection and closing ────────────────────────────────────────────

    @Test
    fun `closing the selected tab selects its right neighbour, then its left`() {
        val workspace = TabWorkspace()
        listOf("a" to "Alpha", "b" to "Beta", "c" to "Gamma").forEach { (id, title) ->
            workspace.register(id, title, groupId = null)
        }
        val group = workspace.groups.single()
        workspace.select("b")

        workspace.close("b")
        assertEquals("c", group.selectedId, "the neighbour to the right takes over")
        assertEquals(listOf("a", "c"), group.ids)

        workspace.select("c")
        workspace.close("c")
        assertEquals("a", group.selectedId, "nothing to the right: the one to the left")
    }

    @Test
    fun `closing an unselected tab leaves the selection alone`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        workspace.register("b", "Beta", groupId = null)
        workspace.select("a")

        workspace.close("b")

        assertEquals("a", workspace.groups.single().selectedId)
    }

    @Test
    fun `the last tab of a window takes the window with it`() {
        val workspace = TabWorkspace()
        val entry = workspace.register("a", "Alpha", groupId = null)
        val group = workspace.groups.single()
        workspace.attachWindow(group, firstWindow)

        workspace.close("a")

        assertTrue(workspace.groups.isEmpty(), "the group is dropped with its last tab")
        assertNull(group.window, "and its window is forgotten")
        assertNull(group.selectedId)
        assertNull(entry.group)
        assertNull(workspace.tab("a"), "a closed tab is gone, not hidden")
    }

    @Test
    fun `closing an unknown tab is a no-op`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)

        workspace.close("nope")
        workspace.close("a")
        workspace.close("a")

        assertTrue(workspace.groups.isEmpty())
    }

    // ── Moving ───────────────────────────────────────────────────────────

    @Test
    fun `a move to another group inserts at the index and selects there`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("b", "Beta", groupId = "left")
        workspace.register("x", "Xray", groupId = "right")
        workspace.register("y", "Yankee", groupId = "right")
        val left = requireNotNull(workspace.group("left"))
        val right = requireNotNull(workspace.group("right"))
        workspace.select("x")

        workspace.move("b", right, index = 1)

        assertEquals(listOf("a"), left.ids)
        assertEquals(listOf("x", "b", "y"), right.ids)
        assertEquals("b", right.selectedId, "the arriving tab is selected")
        assertSame(right, workspace.tab("b")?.group)
        assertEquals("a", left.selectedId, "the group it left selects a neighbour")
    }

    @Test
    fun `a move index beyond the strip appends and a negative one prepends`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("x", "Xray", groupId = "right")
        workspace.register("y", "Yankee", groupId = "right")
        val right = requireNotNull(workspace.group("right"))

        workspace.move("a", right, index = 99)
        assertEquals(listOf("x", "y", "a"), right.ids)

        workspace.move("a", right, index = -5)
        assertEquals(listOf("a", "x", "y"), right.ids, "a reorder clamps the same way")
    }

    @Test
    fun `a move within its own group is a reorder and keeps the selection`() {
        val workspace = TabWorkspace()
        listOf("a", "b", "c").forEach { workspace.register(it, it, groupId = null) }
        val group = workspace.groups.single()
        workspace.select("a")

        workspace.move("c", group, index = 0)

        assertEquals(listOf("c", "a", "b"), group.ids)
        assertEquals("a", group.selectedId, "reordering does not change which tab shows")
        assertEquals(1, workspace.groups.size, "and does not open or drop a window")
    }

    @Test
    fun `a move into a dropped group and of an unknown tab are both no-ops`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("x", "Xray", groupId = "right")
        val left = requireNotNull(workspace.group("left"))
        val right = requireNotNull(workspace.group("right"))

        // Emptying `right` drops it; a stale reference to it must not resurrect it.
        workspace.move("x", left)
        assertEquals(listOf("left"), workspace.groups.map { it.id })

        workspace.move("a", right)
        assertSame(left, workspace.tab("a")?.group, "the tab stays where it was")
        assertEquals(listOf("left"), workspace.groups.map { it.id })

        workspace.move("nope", left)
        assertEquals(listOf("a", "x"), left.ids)
    }

    // ── Tearing off ──────────────────────────────────────────────────────

    @Test
    fun `tearing a tab off a multi-tab window opens a window at the rect`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        workspace.register("b", "Beta", groupId = null)
        val source = workspace.groups.single()

        val torn = assertNotNull(workspace.tearOff("b", Rect(200f, 100f, 1000f, 700f), scaleFactor = 2f))

        assertEquals(2, workspace.groups.size)
        assertEquals(listOf("b"), torn.ids)
        assertEquals("b", torn.selectedId)
        assertEquals(listOf("a"), source.ids)
        // The rect is physical px; a window is placed in logical ones.
        assertEquals(DpOffset(100.dp, 50.dp), torn.position)
        assertEquals(DpSize(400.dp, 300.dp), torn.size)
    }

    @Test
    fun `tearing off the only tab of a window moves that window instead`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        val group = workspace.groups.single()

        val torn = workspace.tearOff("a", Rect(300f, 200f, 1100f, 800f), scaleFactor = 1f)

        assertSame(group, torn, "no second window for a tab that already had one")
        assertEquals(1, workspace.groups.size)
        assertEquals(DpOffset(300.dp, 200.dp), group.position)
        assertEquals(DpSize(800.dp, 600.dp), group.size)
    }

    @Test
    fun `a tear-off rect measured at an unusable scale falls back to one`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        workspace.register("b", "Beta", groupId = null)

        val torn = assertNotNull(workspace.tearOff("b", Rect(10f, 20f, 210f, 170f), scaleFactor = 0f))

        assertEquals(DpOffset(10.dp, 20.dp), torn.position)
        assertEquals(DpSize(200.dp, 150.dp), torn.size)
    }

    @Test
    fun `tearing off an unknown tab changes nothing`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)

        assertNull(workspace.tearOff("nope", Rect(0f, 0f, 100f, 100f), scaleFactor = 1f))
        assertEquals(1, workspace.groups.size)
    }

    // ── Drop resolution ──────────────────────────────────────────────────

    /**
     * The workspace as the drag tests see it: two windows side by side, each
     * with a strip 40 px tall across the top of its client area, holding
     * 100 px-wide tabs. Window 1 is at (0, 0), window 2 at (1000, 0).
     */
    private fun TabWorkspace.twoStripWindows(): Pair<TabWindowGroup, TabWindowGroup> {
        register("a", "Alpha", groupId = "left")
        register("b", "Beta", groupId = "left")
        register("x", "Xray", groupId = "right")
        val left = requireNotNull(group("left"))
        val right = requireNotNull(group("right"))
        attachWindow(left, firstWindow)
        attachWindow(right, secondWindow)
        publishStrip(left, FirstWindowFrame, tabCount = 2)
        publishStrip(right, SecondWindowFrame, tabCount = 1)
        return left to right
    }

    private fun TabWorkspace.publishStrip(
        group: TabWindowGroup,
        frame: LongArray,
        tabCount: Int,
        minimized: () -> Boolean = { false },
        scale: Float = 1f,
    ) {
        stripHosts.register(
            HostGeometry(
                requireNotNull(group.window),
                outerBoundsPx = { frame },
                scaleFactor = { scale },
                minimized = minimized,
            ).apply {
                layoutBoundsInWindowPx = Rect(0f, 0f, frame[2].toFloat(), 40f)
                containerSizePx = IntSize(frame[2].toInt(), frame[3].toInt())
            },
        )
        group.slotsInWindowPx = List(tabCount) { index -> Rect(index * 100f, 0f, (index + 1) * 100f, 40f) }
    }

    @Test
    fun `a drop resolves to the strip under the pointer and the index it falls at`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()

        // Left of the first tab's midpoint: index 0. Past it: index 1.
        assertEquals(TabDropTarget(left, 0), workspace.dropTargetAt(Offset(20f, 20f)))
        assertEquals(TabDropTarget(left, 1), workspace.dropTargetAt(Offset(80f, 20f)))
        assertEquals(TabDropTarget(left, 2), workspace.dropTargetAt(Offset(400f, 20f)), "past every tab: the end")
        assertEquals(TabDropTarget(right, 0), workspace.dropTargetAt(Offset(1020f, 20f)))
        assertEquals(TabDropTarget(right, 1), workspace.dropTargetAt(Offset(1080f, 20f)))

        assertNull(workspace.dropTargetAt(Offset(400f, 300f)), "below the strip is not a drop")
        assertNull(workspace.dropTargetAt(Offset(900f, 20f)), "between the two windows")
    }

    @Test
    fun `the dragged tab's own slot is counted out of the index`() {
        val workspace = TabWorkspace()
        val (left, _) = workspace.twoStripWindows()
        val beta = requireNotNull(workspace.tab("b"))

        // Hovering its own slot resolves to the index it already has, so the
        // strip does not offer to move it by one.
        assertEquals(TabDropTarget(left, 1), workspace.dropTargetAt(Offset(180f, 20f), exclude = beta))
        // And the first slot is still index 0 with the second one discounted.
        assertEquals(TabDropTarget(left, 0), workspace.dropTargetAt(Offset(20f, 20f), exclude = beta))
        assertEquals(TabDropTarget(left, 1), workspace.dropTargetAt(Offset(80f, 20f), exclude = beta))
    }

    @Test
    fun `a minimized window is never a drop target`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        val group = workspace.groups.single()
        workspace.attachWindow(group, firstWindow)
        var minimized = false
        workspace.publishStrip(group, FirstWindowFrame, tabCount = 1, minimized = { minimized })

        assertEquals(TabDropTarget(group, 1), workspace.dropTargetAt(Offset(80f, 20f)))
        // The frame is still on record while minimized, but nothing of it is on
        // screen: a drop there would land in an invisible window.
        minimized = true
        assertNull(workspace.dropTargetAt(Offset(80f, 20f)))
        minimized = false
        assertEquals(TabDropTarget(group, 1), workspace.dropTargetAt(Offset(80f, 20f)))
    }

    @Test
    fun `overlapping strips resolve to the window focused most recently`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("x", "Xray", groupId = "right")
        val left = requireNotNull(workspace.group("left"))
        val right = requireNotNull(workspace.group("right"))
        workspace.attachWindow(left, firstWindow)
        workspace.attachWindow(right, secondWindow)
        // Same frame: two windows exactly on top of each other.
        workspace.publishStrip(left, FirstWindowFrame, tabCount = 1)
        workspace.publishStrip(right, FirstWindowFrame, tabCount = 1)

        val onTheStrip = Offset(20f, 20f)
        assertEquals(left, workspace.dropTargetAt(onTheStrip)?.group, "the first window joined owns")

        secondWindow.let(workspace::noteWindowFocus)
        assertEquals(right, workspace.dropTargetAt(onTheStrip)?.group, "focus moved the front window")

        firstWindow.let(workspace::noteWindowFocus)
        assertEquals(left, workspace.dropTargetAt(onTheStrip)?.group)
    }

    @Test
    fun `a strip with no slots published yet resolves to index zero`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        val group = workspace.groups.single()
        workspace.attachWindow(group, firstWindow)
        workspace.publishStrip(group, FirstWindowFrame, tabCount = 0)

        assertEquals(TabDropTarget(group, 0), workspace.dropTargetAt(Offset(400f, 20f)))
    }

    // ── Drag sessions ────────────────────────────────────────────────────

    /** A strip origin whose window geometry is fixed and whose moves are recorded. */
    private fun stripOrigin(
        window: TaoWindow,
        frame: LongArray,
        moves: MutableList<Pair<Int, Int>> = mutableListOf(),
    ) = TabDragOrigin.Strip(window, outerBoundsPx = { frame }, move = { x, y -> moves += x to y })

    @Test
    fun `dragging one of several tabs shows a ghost and inserts where it is dropped`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()
        val beta = requireNotNull(workspace.tab("b"))

        // Grabbed 10 px into the second tab of the left window.
        val session =
            assertNotNull(
                workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)),
            )
        assertSame(beta, workspace.draggedTab)

        session.update(Offset(1020f, 20f))
        val ghost = assertNotNull(workspace.dragGhost, "a tab dragged out of a strip is previewed")
        assertSame(beta, ghost.tab)
        assertTrue(ghost.screenRectPx.contains(Offset(1020f, 20f)), "the ghost sits under the pointer")
        assertEquals(TabDropTarget(right, 0), workspace.dropPreview)

        session.end(Offset(1020f, 20f))

        assertEquals(listOf("b", "x"), right.ids, "dropped before the tab it was over")
        assertEquals("b", right.selectedId)
        assertEquals(listOf("a"), left.ids)
        assertNull(workspace.draggedTab)
        assertNull(workspace.dragGhost)
        assertNull(workspace.dropPreview)
    }

    @Test
    fun `dragging one of several tabs into empty space tears off a window under the pointer`() {
        val workspace = TabWorkspace()
        val (left, _) = workspace.twoStripWindows()

        val session =
            assertNotNull(
                workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)),
            )
        // Clear of both strips.
        session.update(Offset(500f, 400f))
        session.end(Offset(500f, 400f))

        assertEquals(listOf("a"), left.ids)
        val torn = assertNotNull(workspace.groups.firstOrNull { it.ids == listOf("b") })
        // Grabbed 10 px right and 20 px down inside the tab, so the window's
        // top-left lands that far up and left of the drop.
        assertEquals(DpOffset(490.dp, 380.dp), torn.position)
        assertEquals(DpSize(800.dp, 600.dp), torn.size, "the new window inherits the size of the old one")
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `dragging the only tab of a window moves the window and shows no ghost`() {
        val workspace = TabWorkspace()
        workspace.register("x", "Xray", groupId = "right")
        val right = requireNotNull(workspace.group("right"))
        workspace.attachWindow(right, secondWindow)
        workspace.publishStrip(right, SecondWindowFrame, tabCount = 1)
        val moves = mutableListOf<Pair<Int, Int>>()

        val session =
            assertNotNull(
                workspace.beginDrag("x", stripOrigin(secondWindow, SecondWindowFrame, moves), Offset(1020f, 20f)),
            )
        // The handle feeds the grab position first, then every move.
        session.update(Offset(1020f, 20f))
        session.update(Offset(1120f, 60f))

        assertEquals(listOf(1000 to 0, 1100 to 40), moves, "the window follows the pointer")
        assertNull(workspace.dragGhost, "a ghost would be a second copy of the window's only tab")
        assertNull(workspace.dropPreview, "its own strip is not a target")

        session.end(Offset(1120f, 60f))
        assertEquals(1, workspace.groups.size, "dropped in empty space: the window just stays there")
        assertEquals(listOf("x"), right.ids)
    }

    @Test
    fun `dropping the only tab of a window on another strip merges and closes it`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()
        // Make the right window single-tab and the left one the merge target.
        assertEquals(listOf("x"), right.ids)
        val moves = mutableListOf<Pair<Int, Int>>()

        val session =
            assertNotNull(
                workspace.beginDrag("x", stripOrigin(secondWindow, SecondWindowFrame, moves), Offset(1020f, 20f)),
            )
        session.update(Offset(80f, 20f))
        assertEquals(TabDropTarget(left, 1), workspace.dropPreview)
        session.end(Offset(80f, 20f))

        assertEquals(listOf("a", "x", "b"), left.ids)
        assertEquals("x", left.selectedId)
        assertEquals(listOf("left"), workspace.groups.map { it.id }, "the emptied window is gone")
        assertNull(right.window)
    }

    @Test
    fun `a teleporting pointer lands on the strip it was released over`() {
        val workspace = TabWorkspace()
        val (_, right) = workspace.twoStripWindows()

        val session =
            assertNotNull(
                workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)),
            )
        // One sample each, nothing in between: far off screen, back onto a
        // strip, off again, then onto the other one.
        listOf(
            Offset(-50_000f, -50_000f),
            Offset(20f, 20f),
            Offset(200_000f, 200_000f),
            Offset(1080f, 20f),
        ).forEach(session::update)

        assertEquals(TabDropTarget(right, 1), workspace.dropPreview)
        session.end(Offset(1080f, 20f))
        assertEquals(listOf("x", "b"), right.ids)
    }

    @Test
    fun `non-finite samples are ignored and leave the last position standing`() {
        val workspace = TabWorkspace()
        val (_, right) = workspace.twoStripWindows()
        val moves = mutableListOf<Pair<Int, Int>>()

        // A tear-off drag: the ghost must not move to NaN.
        val session =
            assertNotNull(
                workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)),
            )
        session.update(Offset(1020f, 20f))
        val good = assertNotNull(workspace.dragGhost).screenRectPx
        session.update(Offset(Float.NaN, Float.NaN))
        session.update(Offset(Float.POSITIVE_INFINITY, 20f))
        assertEquals(good, workspace.dragGhost?.screenRectPx)
        assertEquals(TabDropTarget(right, 0), workspace.dropPreview)
        session.end(Offset(Float.NaN, Float.NaN))
        assertEquals(listOf("b", "x"), right.ids, "the release resolves at the last usable position")

        // And a window drag: no NaN may reach window geometry.
        val single = requireNotNull(workspace.group("right"))
        workspace.publishStrip(single, SecondWindowFrame, tabCount = 2)
        workspace.move("b", requireNotNull(workspace.group("left")))
        val windowSession =
            assertNotNull(
                workspace.beginDrag("x", stripOrigin(secondWindow, SecondWindowFrame, moves), Offset(1020f, 20f)),
            )
        windowSession.update(Offset(1020f, 20f))
        windowSession.update(Offset(Float.NaN, 5f))
        windowSession.update(Offset(2f, Float.NEGATIVE_INFINITY))
        windowSession.cancel()
        assertEquals(listOf(1000 to 0, 1000 to 0, 1000 to 0), moves, "garbage samples reached window geometry")
    }

    @Test
    fun `a beginDrag with a non-finite pointer is refused`() {
        val workspace = TabWorkspace()
        workspace.twoStripWindows()

        assertNull(
            workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(Float.NaN, Float.NaN)),
        )
        assertNull(workspace.draggedTab)
    }

    @Test
    fun `a drag is refused while the strip has published no geometry`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = null)
        workspace.register("b", "Beta", groupId = null)
        val group = workspace.groups.single()
        workspace.attachWindow(group, firstWindow)

        assertNull(
            workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)),
            "no strip on screen yet, so no grab offset to speak of",
        )
        assertNull(workspace.beginDrag("nope", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)))
    }

    @Test
    fun `a superseded drag stops acting and cannot clear the live one`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()

        val first =
            assertNotNull(workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)))
        first.update(Offset(1020f, 20f))
        val second =
            assertNotNull(workspace.beginDrag("a", stripOrigin(firstWindow, FirstWindowFrame), Offset(10f, 20f)))
        second.update(Offset(1080f, 20f))

        first.update(Offset(400f, 400f))
        assertEquals(TabDropTarget(right, 1), workspace.dropPreview, "the superseded drag stole the live preview")
        first.end(Offset(400f, 400f))
        assertEquals(listOf("a", "b"), left.ids, "the superseded drag moved a tab")
        assertSame(requireNotNull(workspace.tab("a")), workspace.draggedTab)

        second.end(Offset(1080f, 20f))
        assertEquals(listOf("x", "a"), right.ids)
        assertNull(workspace.draggedTab)
    }

    @Test
    fun `ending or cancelling twice is a no-op`() {
        val workspace = TabWorkspace()
        val (_, right) = workspace.twoStripWindows()

        val session =
            assertNotNull(workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)))
        session.update(Offset(1020f, 20f))
        session.end(Offset(1020f, 20f))
        assertEquals(listOf("b", "x"), right.ids)

        session.end(Offset(20f, 20f))
        session.cancel()
        session.update(Offset(20f, 20f))

        assertEquals(listOf("b", "x"), right.ids, "a late release must not move the tab again")
        assertNull(workspace.dragGhost)
        assertNull(workspace.dropPreview)
    }

    @Test
    fun `a drag whose window closes mid-gesture still resolves`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()

        val session =
            assertNotNull(workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)))
        session.update(Offset(1020f, 20f))

        // The target window goes away under the pointer.
        workspace.close("x")
        assertTrue(workspace.groups.none { it === right })

        session.end(Offset(1020f, 20f))

        // Nothing to drop into there any more, so it tore off instead.
        assertEquals(listOf("a"), left.ids)
        assertEquals(listOf("b"), workspace.groups.first { it !== left }.ids)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `a drag whose tab is closed mid-gesture leaves the workspace alone`() {
        val workspace = TabWorkspace()
        val (left, right) = workspace.twoStripWindows()

        val session =
            assertNotNull(workspace.beginDrag("b", stripOrigin(firstWindow, FirstWindowFrame), Offset(110f, 20f)))
        session.update(Offset(1020f, 20f))
        workspace.close("b")

        session.end(Offset(1020f, 20f))

        assertNull(workspace.tab("b"), "a closed tab stays closed")
        assertEquals(listOf("a"), left.ids)
        assertEquals(listOf("x"), right.ids, "and does not come back in the drop target")
        assertNull(workspace.draggedTab)
        assertNull(workspace.dragGhost)
    }

    @Test
    fun `tear-off and merge churn keeps every tab in exactly one window`() {
        val workspace = TabWorkspace()
        val (left, _) = workspace.twoStripWindows()

        repeat(CHURN_CYCLES) {
            val torn = assertNotNull(workspace.tearOff("b", Rect(400f, 300f, 1200f, 900f), scaleFactor = 1f))
            assertEquals(listOf("b"), torn.ids)
            workspace.move("b", left, index = 1)
        }

        assertEquals(listOf("left", "right"), workspace.groups.map { it.id }.sorted())
        assertEquals(listOf("a", "b"), left.ids)
        assertEquals(1, workspace.tabs.count { it.id == "b" })
        assertSame(left, workspace.tab("b")?.group)
    }

    // ── Snapshots ────────────────────────────────────────────────────────

    @Test
    fun `snapshot and restore round trip including a tab declared later`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        workspace.register("b", "Beta", groupId = "left")
        workspace.register("x", "Xray", groupId = "right")
        workspace.select("a")
        val snapshot = workspace.snapshot()
        assertEquals(listOf("left", "right"), snapshot.groups.map { it.id })
        assertEquals(listOf("a", "b"), snapshot.groups.first().tabIds)
        assertEquals("a", snapshot.groups.first().selectedId)

        // The user rearranges everything, then asks for the layout back.
        val fresh = TabWorkspace()
        fresh.register("a", "Alpha", groupId = null)
        fresh.register("b", "Beta", groupId = null)
        fresh.restore(snapshot)

        assertEquals(listOf("a", "b"), requireNotNull(fresh.group("left")).ids)
        assertEquals("a", requireNotNull(fresh.group("left")).selectedId)
        assertNull(fresh.group("right"), "a group with no declared tab waits for one")

        fresh.register("x", "Xray", groupId = null)
        assertEquals(listOf("x"), requireNotNull(fresh.group("right")).ids, "declared later, restored anyway")
        assertEquals(listOf("a", "b"), requireNotNull(fresh.group("left")).ids)
    }

    @Test
    fun `a restore rebuilds strip order whatever order the tabs are declared in`() {
        val workspace = TabWorkspace()
        listOf("a", "b", "c").forEach { workspace.register(it, it, groupId = "one") }
        workspace.select("b")
        val snapshot = workspace.snapshot()

        val fresh = TabWorkspace()
        fresh.restore(snapshot)
        // Declared back to front.
        listOf("c", "b", "a").forEach { fresh.register(it, it, groupId = null) }

        assertEquals(listOf("a", "b", "c"), requireNotNull(fresh.group("one")).ids)
        assertEquals("b", requireNotNull(fresh.group("one")).selectedId)
        assertEquals(1, fresh.groups.size)
    }

    @Test
    fun `a restore moves a window that is already open and bumps its placement`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        val left = requireNotNull(workspace.group("left"))
        val before = left.placementRevision

        workspace.restore(
            TabLayoutSnapshot(
                groups =
                    listOf(
                        TabGroupSnapshot(
                            id = "left",
                            tabIds = listOf("a"),
                            selectedId = "a",
                            position = DpOffset(320.dp, 240.dp),
                            size = DpSize(500.dp, 400.dp),
                        ),
                    ),
            ),
        )

        assertEquals(DpOffset(320.dp, 240.dp), left.position)
        assertEquals(DpSize(500.dp, 400.dp), left.size)
        assertTrue(left.placementRevision > before, "the window has to be told to move")
    }

    @Test
    fun `a snapshot falls back to the recorded placement without a live window`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")
        val left = requireNotNull(workspace.group("left"))
        left.requestPlacement(DpOffset(64.dp, 48.dp), DpSize(500.dp, 400.dp))
        // A handle with no native window behind it reports no frame, which is
        // also the state of a group whose window has not been mapped yet.
        workspace.attachWindow(left, firstWindow)
        check(firstWindow.outerBoundsPx() == null) { "this fixture assumes an unmapped window" }

        val recorded = workspace.snapshot().groups.single()

        assertEquals(DpOffset(64.dp, 48.dp), recorded.position)
        assertEquals(DpSize(500.dp, 400.dp), recorded.size)
    }

    @Test
    fun `restoring an empty snapshot leaves the workspace alone`() {
        val workspace = TabWorkspace()
        workspace.register("a", "Alpha", groupId = "left")

        workspace.restore(TabLayoutSnapshot(groups = emptyList()))

        assertEquals(listOf("left"), workspace.groups.map { it.id })
        assertEquals(listOf("a"), requireNotNull(workspace.group("left")).ids)
        assertFalse(workspace.tabs.isEmpty())
    }
}
