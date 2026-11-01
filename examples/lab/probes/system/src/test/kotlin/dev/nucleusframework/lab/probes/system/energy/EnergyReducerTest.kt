package dev.nucleusframework.lab.probes.system.energy

import dev.nucleusframework.energymanager.AwakeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyReducerTest {
    private fun EnergyState.on(event: EnergyEvent) = EnergyReducer.reduce(this, event)

    @Test
    fun `window policy is full when minimized, light when unfocused, off when focused`() {
        assertEquals(EfficiencyLevel.Full, efficiencyFor(focused = false, minimized = true))
        // Minimized wins even if the platform still reports focus.
        assertEquals(EfficiencyLevel.Full, efficiencyFor(focused = true, minimized = true))
        assertEquals(EfficiencyLevel.Light, efficiencyFor(focused = false, minimized = false))
        assertEquals(EfficiencyLevel.Off, efficiencyFor(focused = true, minimized = false))
    }

    @Test
    fun `handles open and close independently`() {
        val state =
            EnergyState()
                .on(EnergyEvent.HandleOpened(HandleView(1, AwakeMode.SYSTEM_ONLY), awakeActive = true))
                .on(EnergyEvent.HandleOpened(HandleView(2, AwakeMode.SYSTEM_AND_DISPLAY), awakeActive = true))
                .on(EnergyEvent.HandleClosed(1, awakeActive = true))
        assertEquals(listOf(2), state.handles.map { it.id })
        assertTrue(state.awakeActive == true)
    }

    @Test
    fun `awake mismatch flags the OS disagreeing with what the probe holds`() {
        val held = EnergyState().on(EnergyEvent.SlotChanged(AwakeMode.SYSTEM_ONLY, awakeActive = true))
        assertFalse(held.awakeMismatch())
        val leaked = held.on(EnergyEvent.SlotChanged(null, awakeActive = true))
        assertTrue(leaked.awakeMismatch())
        val released = held.on(EnergyEvent.SlotChanged(null, awakeActive = false))
        assertFalse(released.awakeMismatch())
    }

    @Test
    fun `a benchmark clears the busy flag and is kept in a capped history`() {
        var state = EnergyState().on(EnergyEvent.BenchmarkStarted)
        assertTrue(state.benchmarking)
        repeat(EnergyReducer.HISTORY + 3) {
            state = state.on(EnergyEvent.Benchmarked(Benchmark(it.toLong(), EfficiencyLevel.Off, false, 100)))
        }
        assertFalse(state.benchmarking)
        assertEquals(EnergyReducer.HISTORY, state.benchmarks.size)
    }
}
