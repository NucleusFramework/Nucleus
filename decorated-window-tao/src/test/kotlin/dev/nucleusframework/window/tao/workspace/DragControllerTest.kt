package dev.nucleusframework.window.tao.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One live drag at a time, and feedback cleared exactly when a drag ends. */
class DragControllerTest {
    private class Session

    @Test
    fun `begin supersedes the live session and clears the feedback once`() {
        var cleared = 0
        val controller = DragController<Session> { cleared++ }
        val first = Session()
        val second = Session()

        controller.begin(first)
        assertEquals(0, cleared, "nothing to clear before the first drag")
        assertTrue(controller.isLive(first))

        controller.begin(second)
        assertEquals(1, cleared, "the superseded drag's feedback is gone")
        assertFalse(controller.isLive(first))
        assertTrue(controller.isLive(second))
        assertSame(second, controller.active)
    }

    @Test
    fun `release ignores a session that is not live and is idempotent for the live one`() {
        var cleared = 0
        val controller = DragController<Session> { cleared++ }
        val live = Session()
        val stale = Session()
        controller.begin(live)

        controller.release(stale)
        assertEquals(0, cleared)
        assertTrue(controller.isLive(live), "a stale release cannot end the live drag")

        controller.release(live)
        controller.release(live)
        assertEquals(1, cleared, "the second release finds nothing live and clears again harmlessly")
        assertNull(controller.active)
    }

    @Test
    fun `release of null ends whichever session is live`() {
        var cleared = 0
        val controller = DragController<Session> { cleared++ }
        val live = Session()
        controller.begin(live)

        controller.release(null)

        assertNull(controller.active)
        assertFalse(controller.isLive(live))
        assertEquals(1, cleared)
    }
}
