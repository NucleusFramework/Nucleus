package dev.nucleusframework.lab.probes.notifications

import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.probes.notifications.common.CommonEvent
import dev.nucleusframework.lab.probes.notifications.common.CommonReducer
import dev.nucleusframework.lab.probes.notifications.common.CommonState
import dev.nucleusframework.lab.probes.notifications.linux.LinuxNotificationsEvent
import dev.nucleusframework.lab.probes.notifications.linux.LinuxNotificationsReducer
import dev.nucleusframework.lab.probes.notifications.linux.LinuxNotificationsState
import dev.nucleusframework.lab.probes.notifications.windows.WindowsToastEvent
import dev.nucleusframework.lab.probes.notifications.windows.WindowsToastReducer
import dev.nucleusframework.lab.probes.notifications.windows.WindowsToastState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationReducersTest {
    private fun delivery(what: String) = Delivery(0, what, "main", true)

    @Test
    fun `a common notification follows sending, shown, callback and dismissal`() {
        var state = CommonState()
        state = CommonReducer.reduce(state, CommonEvent.Sending("n1", "Hello", 1))
        assertNull(state.sent.single().platformId)
        state = CommonReducer.reduce(state, CommonEvent.Sent("n1", Result.success("id-1")))
        state = CommonReducer.reduce(state, CommonEvent.Callback("n1", delivery("button “Accept”"), terminal = false))
        state = CommonReducer.reduce(state, CommonEvent.Callback("n1", delivery("dismissed"), terminal = true))
        val tracked = state.sent.single()
        assertEquals("id-1", tracked.platformId)
        assertEquals(listOf("button “Accept”", "dismissed"), tracked.events.map { it.what })
        assertTrue(tracked.finished)
    }

    @Test
    fun `a failed send is finished with its reason`() {
        var state = CommonReducer.reduce(CommonState(), CommonEvent.Sending("n1", "Hello", 1))
        state = CommonReducer.reduce(state, CommonEvent.Sent("n1", Result.failure(IllegalStateException("no server"))))
        assertEquals("no server", state.sent.single().error)
        assertTrue(state.sent.single().finished)
    }

    @Test
    fun `callbacks for another notification do not leak into this one`() {
        var state = CommonReducer.reduce(CommonState(), CommonEvent.Sending("n1", "A", 1))
        state = CommonReducer.reduce(state, CommonEvent.Sending("n2", "B", 2))
        state = CommonReducer.reduce(state, CommonEvent.Callback("n2", delivery("activated"), terminal = false))
        assertTrue(
            state.sent
                .first { it.key == "n1" }
                .events
                .isEmpty(),
        )
        assertEquals(
            1,
            state.sent
                .first { it.key == "n2" }
                .events.size,
        )
    }

    @Test
    fun `linux - a zero id is a failure, and the last open id skips closed ones`() {
        var state =
            LinuxNotificationsReducer.reduce(
                LinuxNotificationsState(),
                LinuxNotificationsEvent.Notified("A", 0, null, 1),
            )
        assertEquals("Notify returned 0", state.sent.single().error)
        assertNull(state.lastOpenId)

        state = LinuxNotificationsReducer.reduce(state, LinuxNotificationsEvent.Notified("B", 41, null, 2))
        state = LinuxNotificationsReducer.reduce(state, LinuxNotificationsEvent.Notified("C", 42, null, 3))
        assertEquals(42, state.lastOpenId)
        state =
            LinuxNotificationsReducer.reduce(
                state,
                LinuxNotificationsEvent.Signal(42, delivery("closed: DISMISSED"), terminal = true),
            )
        assertEquals(41, state.lastOpenId)
    }

    @Test
    fun `linux - replacing in place keeps a single entry for the id`() {
        var state =
            LinuxNotificationsReducer.reduce(
                LinuxNotificationsState(),
                LinuxNotificationsEvent.Notified("A", 7, null, 1),
            )
        state = LinuxNotificationsReducer.reduce(state, LinuxNotificationsEvent.Notified("A2", 7, 7, 2))
        assertEquals(1, state.sent.size)
        assertEquals("A2 (replacing #7)", state.sent.single().title)
    }

    @Test
    fun `windows - progress advances per tag and clear all finishes everything`() {
        var state =
            WindowsToastReducer.reduce(
                WindowsToastState(),
                WindowsToastEvent.Sending("t1", "A", 1, withProgress = true),
            )
        state = WindowsToastReducer.reduce(state, WindowsToastEvent.Shown("t1", null))
        state = WindowsToastReducer.reduce(state, WindowsToastEvent.ProgressUpdated("t1", 0.5, null))
        assertEquals(0.5, state.progress["t1"])

        state = WindowsToastReducer.reduce(state, WindowsToastEvent.ProgressUpdated("t1", 0.75, "stale"))
        assertEquals(0.5, state.progress["t1"])
        assertEquals(
            "update failed: stale",
            state.sent
                .single()
                .events
                .single()
                .what,
        )

        state = WindowsToastReducer.reduce(state, WindowsToastEvent.Removed(null))
        assertTrue(state.progress.isEmpty())
        assertTrue(state.sent.all { it.finished })
    }
}
