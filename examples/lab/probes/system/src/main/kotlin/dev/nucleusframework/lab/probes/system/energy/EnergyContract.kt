package dev.nucleusframework.lab.probes.system.energy

import androidx.compose.runtime.Immutable
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.probes.system.ToolReadBack

/** Process-level efficiency, as the Lab requested it (the OS has no getter for it). */
enum class EfficiencyLevel { Off, Light, Full }

@Immutable
data class HandleView(
    val id: Int,
    val mode: AwakeMode,
)

/** A fixed CPU-bound workload, timed: efficiency mode must make it measurably slower. */
@Immutable
data class Benchmark(
    val epochMillis: Long,
    val efficiency: EfficiencyLevel,
    val threadEfficiency: Boolean,
    val millis: Long,
)

@Immutable
data class EnergyState(
    val available: Availability = Availability.Unknown,
    val efficiency: EfficiencyLevel = EfficiencyLevel.Off,
    /** Efficiency follows the Lab window: full when minimized, light when unfocused. */
    val followWindow: Boolean = false,
    val windowFocused: Boolean = true,
    val windowMinimized: Boolean = false,
    /** The explicit `keepAwake` slot, `null` once released. */
    val awakeSlot: AwakeMode? = null,
    val handles: List<HandleView> = emptyList(),
    /** `isAwakeActive()` after the last awake call. */
    val awakeActive: Boolean? = null,
    val osAwake: ToolReadBack? = null,
    val osScheduling: ToolReadBack? = null,
    val benchmarks: List<Benchmark> = emptyList(),
    val benchmarking: Boolean = false,
    val calls: List<CallRecord> = emptyList(),
)

sealed interface EnergyIntent {
    data class SetEfficiency(
        val level: EfficiencyLevel,
    ) : EnergyIntent

    data class SetFollowWindow(
        val enabled: Boolean,
    ) : EnergyIntent

    data class WindowChanged(
        val focused: Boolean,
        val minimized: Boolean,
    ) : EnergyIntent

    data class KeepAwake(
        val mode: AwakeMode,
    ) : EnergyIntent

    data object ReleaseAwake : EnergyIntent

    data class AcquireHandle(
        val mode: AwakeMode,
    ) : EnergyIntent

    data class CloseHandle(
        val id: Int,
    ) : EnergyIntent

    data object ReadBack : EnergyIntent

    data class RunBenchmark(
        val threadEfficiency: Boolean,
    ) : EnergyIntent
}

sealed interface EnergyEvent {
    data class Ready(
        val available: Availability,
        val awakeActive: Boolean,
    ) : EnergyEvent

    data class Called(
        val call: CallRecord,
    ) : EnergyEvent

    data class EfficiencyApplied(
        val level: EfficiencyLevel,
        val cause: String,
    ) : EnergyEvent

    data class FollowWindowChanged(
        val enabled: Boolean,
    ) : EnergyEvent

    data class WindowObserved(
        val focused: Boolean,
        val minimized: Boolean,
    ) : EnergyEvent

    data class SlotChanged(
        val mode: AwakeMode?,
        val awakeActive: Boolean,
    ) : EnergyEvent

    data class HandleOpened(
        val handle: HandleView,
        val awakeActive: Boolean,
    ) : EnergyEvent

    data class HandleClosed(
        val id: Int,
        val awakeActive: Boolean,
    ) : EnergyEvent

    data class OsReadBack(
        val awake: ToolReadBack,
        val scheduling: ToolReadBack,
    ) : EnergyEvent

    data object BenchmarkStarted : EnergyEvent

    data class Benchmarked(
        val result: Benchmark,
    ) : EnergyEvent
}

/** The policy `nucleus-demo` applies implicitly: full when minimized, light when merely unfocused. */
fun efficiencyFor(
    focused: Boolean,
    minimized: Boolean,
): EfficiencyLevel =
    when {
        minimized -> EfficiencyLevel.Full
        !focused -> EfficiencyLevel.Light
        else -> EfficiencyLevel.Off
    }

object EnergyReducer : Reducer<EnergyState, EnergyEvent> {
    /** Benchmarks kept: enough to compare every level, with and without the thread mode. */
    const val HISTORY = 30

    override fun reduce(
        state: EnergyState,
        event: EnergyEvent,
    ): EnergyState =
        when (event) {
            is EnergyEvent.Ready -> state.copy(available = event.available, awakeActive = event.awakeActive)
            is EnergyEvent.Called -> state.copy(calls = state.calls.append(event.call))
            is EnergyEvent.EfficiencyApplied -> state.copy(efficiency = event.level)
            is EnergyEvent.FollowWindowChanged -> state.copy(followWindow = event.enabled)
            is EnergyEvent.WindowObserved ->
                state.copy(
                    windowFocused = event.focused,
                    windowMinimized = event.minimized,
                )
            is EnergyEvent.SlotChanged -> state.copy(awakeSlot = event.mode, awakeActive = event.awakeActive)
            is EnergyEvent.HandleOpened ->
                state.copy(
                    handles = state.handles + event.handle,
                    awakeActive = event.awakeActive,
                )
            is EnergyEvent.HandleClosed ->
                state.copy(handles = state.handles.filterNot { it.id == event.id }, awakeActive = event.awakeActive)
            is EnergyEvent.OsReadBack -> state.copy(osAwake = event.awake, osScheduling = event.scheduling)
            EnergyEvent.BenchmarkStarted -> state.copy(benchmarking = true)
            is EnergyEvent.Benchmarked ->
                state.copy(benchmarking = false, benchmarks = state.benchmarks.append(event.result, HISTORY))
        }
}

/** `true` when the OS still reports awake while nothing in the Lab holds it, or the reverse. */
fun EnergyState.awakeMismatch(): Boolean {
    val held = awakeSlot != null || handles.isNotEmpty()
    return awakeActive != null && awakeActive != held
}
