package dev.nucleusframework.lab.probes.system.fswatcher

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FsWatcherReducerTest {
    private fun FsWatcherState.on(event: FsWatcherEvent) = FsWatcherReducer.reduce(this, event)

    private fun action(
        id: Int,
        atNanos: Long,
    ) = FsWatcherEvent.ActionStarted(ActionRecord(id, FsAction.CreateFile, 0, atNanos))

    private fun observed(atNanos: Long) =
        FsWatcherEvent.Observed(FsObservation("Created", "probe.txt", false, false, atNanos, 0), "fs-thread", false)

    @Test
    fun `an event is attributed to the latest action before it, with its latency`() {
        val state =
            FsWatcherState()
                .on(action(1, 1_000_000))
                .on(action(2, 50_000_000))
                .on(observed(62_000_000))
        val event = state.events.single()
        assertEquals(2, event.actionId)
        assertEquals(12, event.latencyMillis)
        assertEquals("fs-thread", event.thread)
        assertEquals(1, state.actions.single { it.id == 2 }.events)
        assertEquals(0, state.actions.single { it.id == 1 }.events)
    }

    @Test
    fun `only the first event sets an action's first latency`() {
        val state =
            FsWatcherState()
                .on(action(1, 0))
                .on(observed(5_000_000))
                .on(observed(40_000_000))
        val record = state.actions.single()
        assertEquals(2, record.events)
        assertEquals(5, record.firstLatencyMillis)
    }

    @Test
    fun `an event before any action is an outside change`() {
        val state = FsWatcherState().on(observed(10)).on(action(1, 20))
        val event = state.events.single()
        assertNull(event.actionId)
        assertNull(event.latencyMillis)
    }

    @Test
    fun `a failed action keeps its record and carries the error`() {
        val state = FsWatcherState().on(action(1, 0)).on(FsWatcherEvent.ActionFailed(1, "no probe file"))
        assertEquals("no probe file", state.actions.single().error)
    }

    @Test
    fun `the event list is capped`() {
        var state = FsWatcherState().on(action(1, 0))
        repeat(FsWatcherReducer.EVENTS + 5) { state = state.on(observed(it.toLong())) }
        assertEquals(FsWatcherReducer.EVENTS, state.events.size)
        assertEquals(FsWatcherReducer.EVENTS + 5, state.actions.single().events)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `scratch actions toggle and refuse a missing precondition`() {
        val root = createTempDirectory("lab-fs")
        try {
            val actions = ScratchActions(root)
            assertFailsWith<IllegalStateException> { actions.perform(FsAction.DeleteFile) }
            actions.perform(FsAction.CreateFile)
            actions.perform(FsAction.RenameFile)
            assertTrue(root.resolve("probe-renamed.txt").exists())
            actions.perform(FsAction.RenameFile)
            assertTrue(root.resolve("probe.txt").exists())
            actions.perform(FsAction.CreateNested)
            actions.perform(FsAction.RenameDir)
            assertTrue(root.resolve("nested-moved/deep/leaf.txt").exists())
            actions.perform(FsAction.DeleteTree)
            assertFalse(root.resolve("nested-moved").exists())
            actions.perform(FsAction.Clean)
            assertFalse(root.resolve("probe.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
