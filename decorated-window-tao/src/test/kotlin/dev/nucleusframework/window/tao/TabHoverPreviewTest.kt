package dev.nucleusframework.window.tao

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The hover card of a tab strip, without a window: what the strip reports as
 * the hovered tab, and where the card is placed against the tab's own slot.
 *
 * The headful suite covers the pointer actually travelling along a real strip;
 * everything here is the state machine and the geometry behind it.
 */
class TabHoverPreviewTest {
    private companion object {
        /** Three placed tabs, left to right: "a" at 0..100, "b" at 100..200, "c" at 200..300. */
        val Slots =
            listOf(
                Rect(0f, 0f, 100f, 40f),
                Rect(100f, 0f, 200f, 40f),
                Rect(200f, 0f, 300f, 40f),
            )
        val WindowSize = IntSize(width = 800, height = 600)
        val Below = IntOffset(x = 0, y = 4)
        val CardSize = IntSize(width = 260, height = 150)
    }

    private fun strip(): TabStripScope {
        val workspace = TabWorkspace()
        for (id in listOf("a", "b", "c")) workspace.register(id, id.uppercase(), groupId = null)
        val group = requireNotNull(workspace.groups.firstOrNull())
        group.slotsInWindowPx = Slots
        // Said out loud rather than inherited from the declaration order — a
        // tab is only hoverable while it is not the one being read, so which
        // one is selected decides what every case below may hover.
        workspace.select("a")
        return TabStripScopeImpl(workspace, group)
    }

    @Test
    fun `the strip reports the tab the pointer rests on, and nothing once it leaves`() {
        val strip = strip()

        strip.group.noteHoverEnter("b")
        assertSame(strip.workspace.tab("b"), strip.hoveredTab, "the hovered tab is the one entered")

        // Another tab's exit is not this one's: the pointer crossing a
        // neighbour on its way out must not put the card away.
        strip.group.noteHoverExit("c")
        assertSame(strip.workspace.tab("b"), strip.hoveredTab, "a neighbour's exit took the hover with it")

        strip.group.noteHoverExit("b")
        assertNull(strip.hoveredTab, "the hover outlived the pointer")
    }

    @Test
    fun `a press puts the card away until the pointer has been elsewhere`() {
        val strip = strip()
        strip.group.noteHoverEnter("b")
        strip.group.noteHoverPress("b")

        assertNull(strip.hoveredTab, "a card stayed under a tab being clicked")

        // Moving on to another tab is a new hover, and a browser shows its card.
        strip.group.noteHoverEnter("c")
        assertSame(strip.workspace.tab("c"), strip.hoveredTab, "the click blocked the next tab's card too")
    }

    @Test
    fun `a press on a tab the pointer is not on changes nothing`() {
        val strip = strip()
        strip.group.noteHoverEnter("b")

        strip.group.noteHoverPress("c")

        assertSame(strip.workspace.tab("b"), strip.hoveredTab, "a press elsewhere took this tab's card")
    }

    @Test
    fun `no card while a tab is being dragged`() {
        val strip = strip()
        strip.group.noteHoverEnter("b")

        // Carrying a tab passes it over its neighbours; every one of them is
        // hovered on the way, and none of them is being pointed at.
        strip.workspace.draggedTab = strip.workspace.tab("a")
        assertNull(strip.hoveredTab, "a card followed a tab being carried")

        strip.workspace.draggedTab = null
        assertSame(strip.workspace.tab("b"), strip.hoveredTab, "the hover did not come back after the drag")
    }

    @Test
    fun `a tab that has left the group is no longer hovered`() {
        val strip = strip()
        strip.group.noteHoverEnter("b")

        strip.workspace.close("b")

        assertNull(strip.hoveredTab, "a closed tab kept the hover, and its card an anchor")
    }

    @Test
    fun `the anchor of a card is the tab's own slot, and nothing before it is placed`() {
        val strip = strip()

        assertEquals(Slots[1], strip.group.slotInWindowPx("b"), "the slot of a placed tab")
        assertNull(strip.group.slotInWindowPx("nobody"), "an unknown tab has no slot")

        val unplaced = TabWorkspace()
        unplaced.register("a", "A", groupId = null)
        val fresh = requireNotNull(unplaced.groups.firstOrNull())
        assertNull(fresh.slotInWindowPx("a"), "a tab the strip has not placed yet has no anchor")
    }

    @Test
    fun `the card hangs from the tab's leading edge, below it`() {
        val position = TabHoverPreviewPosition(anchorPx = Slots[1], offsetPx = Below)

        val at =
            position.calculatePosition(
                anchorBounds = IntRect.Zero,
                windowSize = WindowSize,
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = CardSize,
            )

        assertEquals(IntOffset(x = 100, y = 44), at, "the card is not under the left edge of its tab")
    }

    @Test
    fun `a right-to-left strip hangs the card from the tab's right edge`() {
        // The third slot, 200..300: a card mirrored off the second one would
        // start at -60 and be slid back to 0 by the clamp, which is the same
        // number a left-aligned card at the window's edge gives — it would
        // pass whether the mirroring worked or not.
        val position = TabHoverPreviewPosition(anchorPx = Slots[2], offsetPx = Below)

        val at =
            position.calculatePosition(
                anchorBounds = IntRect.Zero,
                windowSize = WindowSize,
                layoutDirection = LayoutDirection.Rtl,
                popupContentSize = CardSize,
            )

        // The card grows into the reading direction: its right edge on the
        // tab's right edge, so it runs leftwards under the tabs that follow.
        assertEquals(IntOffset(x = 300 - CardSize.width, y = 44), at, "the card was not mirrored")
    }

    @Test
    fun `the selected tab has no card`() {
        val strip = strip()
        strip.group.noteHoverEnter("a")

        assertNull(strip.hoveredTab, "a card was offered for the tab already on screen")

        // Selecting another one leaves this tab off screen, and a card of it
        // is worth something again — without the pointer having moved.
        strip.workspace.select("b")
        assertSame(strip.workspace.tab("a"), strip.hoveredTab, "the tab left behind never got its card")
    }

    @Test
    fun `a card that would run off the window is slid back in`() {
        val nearTheEdge = Rect(700f, 0f, 800f, 40f)
        val position = TabHoverPreviewPosition(anchorPx = nearTheEdge, offsetPx = Below)

        val at =
            position.calculatePosition(
                anchorBounds = IntRect.Zero,
                windowSize = WindowSize,
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = CardSize,
            )

        assertEquals(IntOffset(x = WindowSize.width - CardSize.width, y = 44), at, "the card hung off the window")

        // And a card wider than the window keeps its leading edge visible.
        val wider =
            position.calculatePosition(
                anchorBounds = IntRect.Zero,
                windowSize = IntSize(width = 200, height = 600),
                layoutDirection = LayoutDirection.Ltr,
                popupContentSize = CardSize,
            )
        assertEquals(IntOffset(x = 0, y = 44), wider, "a card wider than the window lost its start")
    }
}
