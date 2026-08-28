package dev.nucleusframework.window.tao.popup

import dev.nucleusframework.window.tao.dispatch.TaoMainDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneFramePumpTest {
    @Test
    fun scheduleOnMainRunsInline() {
        withPinnedMain {
            var renders = 0
            val pump = StandaloneFramePump { renders++ }
            pump.schedule()
            assertEquals(1, renders)
        }
    }

    @Test
    fun nestedScheduleFromRenderDoesNotReenter() {
        withPinnedMain {
            var renders = 0
            lateinit var pump: StandaloneFramePump
            pump =
                StandaloneFramePump {
                    renders++
                    if (renders == 1) pump.schedule()
                }
            pump.schedule()
            assertEquals(1, renders)
            // Nested [schedule] posted a follow-up; drop it so it cannot
            // run on the dispatcher fallback thread after this test.
            pump.disposed = true
        }
    }

    @Test
    fun extraSchedulesWhileRenderingCoalesce() {
        withPinnedMain {
            var renders = 0
            lateinit var pump: StandaloneFramePump
            pump =
                StandaloneFramePump {
                    renders++
                    if (renders == 1) {
                        pump.schedule()
                        pump.schedule()
                    }
                }
            pump.schedule()
            assertEquals(1, renders)
            pump.disposed = true
        }
    }

    @Test
    fun scheduleAfterDisposeIsNoOp() {
        withPinnedMain {
            var renders = 0
            val pump = StandaloneFramePump { renders++ }
            pump.schedule()
            pump.disposed = true
            pump.schedule()
            assertEquals(1, renders)
        }
    }

    private fun withPinnedMain(block: () -> Unit) {
        val previous = TaoMainDispatcher.taoMainThread
        TaoMainDispatcher.taoMainThread = Thread.currentThread()
        try {
            block()
        } finally {
            TaoMainDispatcher.taoMainThread = previous
        }
    }
}
