package dev.nucleusframework.window.tao.workspace

import dev.nucleusframework.window.tao.TaoWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Membership, focus recency and pinning of [WindowGroup], driven without any
 * native window: members are bare [TaoWindow] handles and focus is fed through
 * [WindowGroup.noteFocus].
 */
class WindowGroupTest {
    private val a = TaoWindow(handle = 1L)
    private val b = TaoWindow(handle = 2L)
    private val c = TaoWindow(handle = 3L)

    @Test
    fun `the owner is the pinned member, else the last focused, else the first joined`() {
        val group = WindowGroup(followFocus = true)
        assertNull(group.owner)

        group.join(a)
        group.join(b)
        assertSame(a, group.owner, "first joined")

        group.noteFocus(b)
        assertSame(b, group.owner, "last focused")

        group.pinTo(a)
        assertSame(a, group.owner, "pinned")

        group.pinTo(null)
        assertSame(b, group.owner, "back to focus")
    }

    @Test
    fun `a leaving owner hands over to the member focused before it`() {
        val group = WindowGroup(followFocus = true)
        group.join(a)
        group.join(b)
        group.join(c)
        group.noteFocus(b)
        group.noteFocus(c)

        group.leave(c)

        // Not the last joined (b happens to be both here), not the first: the
        // one the user was in before — so three members cannot fool it.
        assertSame(b, group.owner)
        group.leave(b)
        assertSame(a, group.owner, "no focus history left: the first member")
    }

    @Test
    fun `members by recency put the owner first and never-focused members last in join order`() {
        val group = WindowGroup(followFocus = true)
        group.join(a)
        group.join(b)
        group.join(c)
        assertEquals(listOf(a, b, c), group.membersByRecency, "no focus yet: join order")

        group.noteFocus(c)
        group.noteFocus(b)
        assertEquals(listOf(b, c, a), group.membersByRecency)

        // A pin puts its window first and leaves the recency of the rest alone.
        group.pinTo(a)
        assertEquals(listOf(a, b, c), group.membersByRecency)
    }

    @Test
    fun `a pin to a non-member is kept but ignored until it joins`() {
        val group = WindowGroup(followFocus = true)
        group.join(a)
        group.pinTo(b)

        assertSame(b, group.pinned)
        assertSame(a, group.owner, "a stranger cannot own the group")

        group.join(b)
        assertSame(b, group.owner)

        group.leave(b)
        assertNull(group.pinned, "a leaving member takes its pin with it")
        assertSame(a, group.owner)
    }

    @Test
    fun `join is idempotent, leaving a stranger is a no-op, and the hooks see both`() {
        val joined = mutableListOf<TaoWindow>()
        val left = mutableListOf<Pair<TaoWindow, TaoWindow?>>()
        val group = WindowGroup(followFocus = true, onJoined = joined::add, onLeft = { w, o -> left += w to o })

        group.join(a)
        group.join(a)
        group.join(b)
        assertEquals(listOf(a, b), group.members)
        assertEquals(listOf(a, b), joined)

        group.leave(c)
        assertTrue(left.isEmpty(), "a stranger leaving is nothing")

        group.noteFocus(b)
        group.leave(b)
        assertEquals(listOf<Pair<TaoWindow, TaoWindow?>>(b to a), left, "the hook sees the owner that remains")
        group.leave(a)
        assertEquals(listOf<Pair<TaoWindow, TaoWindow?>>(b to a, a to null), left)
        assertNull(group.owner)
    }

    @Test
    fun `without follow focus the owner ignores focus and takes the pin or the first member`() {
        val group = WindowGroup(followFocus = false)
        group.join(a)
        group.join(b)
        group.noteFocus(b)
        assertSame(a, group.owner)
        // Recency is still tracked for hit-testing, just not for ownership.
        assertEquals(listOf(a, b), group.membersByRecency)

        group.pinTo(b)
        assertSame(b, group.owner)
    }
}
