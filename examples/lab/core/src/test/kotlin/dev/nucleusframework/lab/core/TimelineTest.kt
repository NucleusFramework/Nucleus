package dev.nucleusframework.lab.core

import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.RingBufferTimeline
import dev.nucleusframework.lab.core.timeline.UiThread
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineTest {
    @Test
    fun `entries are tagged with whether they came from the UI thread`() {
        UiThread.capture()
        val timeline = RingBufferTimeline()
        timeline.record(null, EntryKind.Log, "here")
        thread(name = "os-callback") { timeline.record(null, EntryKind.Event, "there") }.join()

        val (here, there) = timeline.entries.value
        assertEquals(true, here.onUiThread)
        assertEquals(false, there.onUiThread)
        assertEquals("os-callback", there.thread)
    }

    @Test
    fun `the buffer keeps the most recent entries`() {
        val timeline = RingBufferTimeline()
        repeat(2_100) { timeline.record(null, EntryKind.Log, "#$it") }
        assertEquals(2_000, timeline.entries.value.size)
        assertEquals(
            "#2099",
            timeline.entries.value
                .last()
                .message,
        )
    }
}
