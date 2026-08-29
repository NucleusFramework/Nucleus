package dev.nucleusframework.window.tao.popup

import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneFramePumpTest {
    @Test
    fun scheduleOnMainRunsInline() {
        val probe = Probe()
        probe.pump.schedule()
        assertEquals(1, probe.renders)
        assertTrue(probe.posted.isEmpty())
    }

    @Test
    fun nestedScheduleFromRenderDoesNotReenter() {
        val probe =
            Probe { p ->
                p.renders++
                if (p.renders == 1) p.pump.schedule()
            }
        probe.pump.schedule()
        assertEquals(1, probe.renders)
        assertEquals(1, probe.posted.size)
        probe.drainPosted()
        assertEquals(2, probe.renders)
        assertTrue(probe.posted.isEmpty())
    }

    @Test
    fun extraSchedulesWhileRenderingCoalesce() {
        val probe =
            Probe { p ->
                p.renders++
                if (p.renders == 1) {
                    p.pump.schedule()
                    p.pump.schedule()
                }
            }
        probe.pump.schedule()
        assertEquals(1, probe.renders)
        assertEquals(1, probe.posted.size)
        probe.drainPosted()
        assertEquals(2, probe.renders)
    }

    @Test
    fun scheduleAfterDisposeIsNoOp() {
        val probe = Probe()
        probe.pump.schedule()
        probe.pump.disposed = true
        probe.pump.schedule()
        assertEquals(1, probe.renders)
        assertTrue(probe.posted.isEmpty())
    }

    @Test
    fun disposedPostedFrameIsDropped() {
        val probe =
            Probe { p ->
                p.renders++
                if (p.renders == 1) p.pump.schedule()
            }
        probe.pump.schedule()
        probe.pump.disposed = true
        probe.drainPosted()
        assertEquals(1, probe.renders)
    }

    /**
     * In-memory pump: [isOnMain] is always true and [post] records runnables
     * instead of touching [dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher].
     */
    private class Probe(
        onRender: (Probe) -> Unit = { it.renders++ },
    ) {
        val posted = ArrayDeque<Runnable>()
        var renders = 0
        val pump: StandaloneFramePump =
            StandaloneFramePump(
                isOnMain = { true },
                post = { posted.addLast(it) },
            ) { onRender(this) }

        fun drainPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
        }
    }
}
