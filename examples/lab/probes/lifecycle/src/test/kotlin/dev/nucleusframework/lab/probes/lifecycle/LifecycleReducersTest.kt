package dev.nucleusframework.lab.probes.lifecycle

import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.lab.core.process.ProcessUpdate
import dev.nucleusframework.lab.probes.lifecycle.autolaunch.AutoLaunchEvent
import dev.nucleusframework.lab.probes.lifecycle.autolaunch.AutoLaunchProbeState
import dev.nucleusframework.lab.probes.lifecycle.autolaunch.AutoLaunchReducer
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchRecord
import dev.nucleusframework.lab.probes.lifecycle.watchdog.Freeze
import dev.nucleusframework.lab.probes.lifecycle.watchdog.WatchdogEvent
import dev.nucleusframework.lab.probes.lifecycle.watchdog.WatchdogReducer
import dev.nucleusframework.lab.probes.lifecycle.watchdog.WatchdogSignal
import dev.nucleusframework.lab.probes.lifecycle.watchdog.WatchdogState
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleReducersTest {
    @Test
    fun `a launch record folds start, output and exit`() {
        val record =
            LaunchRecord(1, "second instance", epochMillis = 0)
                .apply(ProcessUpdate.Started(42, listOf("java"), NoProcess))
                .apply(ProcessUpdate.Output("hello"))
                .apply(ProcessUpdate.Exited(0, 900))
        assertEquals(42, record.pid)
        assertEquals(listOf("hello"), record.output)
        assertFalse(record.running)
        assertEquals("pid 42 exited with 0 after 900 ms", record.summary)
    }

    @Test
    fun `a failed launch is not running`() {
        val record = LaunchRecord(1, "x", epochMillis = 0).apply(ProcessUpdate.Failed("no java"))
        assertFalse(record.running)
        assertTrue(record.summary.contains("no java"))
    }

    @Test
    fun `autolaunch records a state change only when the state changes`() {
        val reducer = AutoLaunchReducer("--nucleus-autostart", "DEV")

        fun read(
            state: AutoLaunchState,
            at: Long,
        ) = AutoLaunchEvent.Read(state, "Backend", "", startedAtLogin = false, at = at)
        val result =
            listOf(
                read(AutoLaunchState.DISABLED, 1),
                read(AutoLaunchState.DISABLED, 2),
                read(AutoLaunchState.ENABLED, 3),
            ).fold(AutoLaunchProbeState(), reducer::reduce)
        assertEquals(listOf(1L to AutoLaunchState.DISABLED, 3L to AutoLaunchState.ENABLED), result.stateChanges)
        assertEquals("--nucleus-autostart", result.autostartArgument)
    }

    @Test
    fun `a watchdog signal is timed from the freeze that caused it`() {
        val state =
            listOf(
                WatchdogEvent.FreezeStarted(Freeze(startedAt = 1_000, requestedMs = 8_000, declaredExpected = false)),
                WatchdogEvent.Signalled(WatchdogSignal.Unresponsive, "nucleus-tao-watchdog-events", at = 7_500),
                WatchdogEvent.FreezeEnded(8_010),
            ).fold(WatchdogState(), WatchdogReducer::reduce)
        assertEquals(6_500L, state.signals.single().sinceFreezeMs)
        assertEquals(8_010L, state.freezes.single().actualMs)
        assertFalse(state.freezing)
    }
}

/** A process that never ran: `Started` carries one, the record only reads its pid. */
private object NoProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}
