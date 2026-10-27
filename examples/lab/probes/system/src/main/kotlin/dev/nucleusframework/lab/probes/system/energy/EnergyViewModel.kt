package dev.nucleusframework.lab.probes.system.energy

import androidx.lifecycle.ViewModel
import dev.nucleusframework.energymanager.AwakeHandle
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class EnergyViewModel(
    private val gateway: EnergyGateway,
    timeline: Timeline,
) : MviViewModel<EnergyState, EnergyIntent, EnergyEvent, Nothing>(
        EnergyState(),
        EnergyReducer,
        timeline,
        EnergyProbe.ID,
    ) {
    /** Live `acquireAwake` handles, by the id shown in the UI. */
    private val handles = mutableMapOf<Int, AwakeHandle>()
    private var nextHandle = 1

    init {
        dispatch(EnergyEvent.Ready(gateway.availability(), gateway.isAwakeActive()))
        launch { readBack() }
    }

    override suspend fun handle(intent: EnergyIntent) {
        when (intent) {
            is EnergyIntent.SetEfficiency -> {
                // A manual choice ends the window policy, or the next focus change would undo it.
                if (state.value.followWindow) dispatch(EnergyEvent.FollowWindowChanged(false))
                apply(intent.level, "manual")
            }
            is EnergyIntent.SetFollowWindow -> {
                dispatch(EnergyEvent.FollowWindowChanged(intent.enabled))
                if (intent.enabled) {
                    applyPolicy()
                } else {
                    apply(EfficiencyLevel.Off, "window policy off")
                }
            }
            is EnergyIntent.WindowChanged -> {
                reduceSilently(EnergyEvent.WindowObserved(intent.focused, intent.minimized))
                if (state.value.followWindow) applyPolicy()
            }
            is EnergyIntent.KeepAwake -> {
                record(
                    io {
                        gateway.keepAwake(intent.mode)
                    },
                )?.let { dispatch(EnergyEvent.SlotChanged(intent.mode, gateway.isAwakeActive())) }
                readBack()
            }
            EnergyIntent.ReleaseAwake -> {
                record(io { gateway.releaseAwake() })
                dispatch(EnergyEvent.SlotChanged(null, gateway.isAwakeActive()))
                readBack()
            }
            is EnergyIntent.AcquireHandle -> {
                val handle = io { gateway.acquireAwake(intent.mode) }
                val id = nextHandle++
                handles[id] = handle
                dispatch(EnergyEvent.HandleOpened(HandleView(id, intent.mode), gateway.isAwakeActive()))
                readBack()
            }
            is EnergyIntent.CloseHandle -> {
                handles.remove(intent.id)?.let { io { it.close() } }
                dispatch(EnergyEvent.HandleClosed(intent.id, gateway.isAwakeActive()))
                readBack()
            }
            EnergyIntent.ReadBack -> readBack()
            is EnergyIntent.RunBenchmark -> benchmark(intent.threadEfficiency)
        }
    }

    private suspend fun applyPolicy() {
        val target = efficiencyFor(state.value.windowFocused, state.value.windowMinimized)
        if (target != state.value.efficiency) {
            val cause =
                if (state.value.windowMinimized) {
                    "window minimized"
                } else if (!state.value.windowFocused) {
                    "window unfocused"
                } else {
                    "window focused"
                }
            apply(target, cause)
        }
    }

    private suspend fun apply(
        level: EfficiencyLevel,
        cause: String,
    ) {
        val results = io { gateway.applyEfficiency(level) }
        results.forEach { record(it) }
        dispatch(EnergyEvent.EfficiencyApplied(level, cause))
        readBack()
    }

    private suspend fun benchmark(threadEfficiency: Boolean) {
        dispatch(EnergyEvent.BenchmarkStarted)
        val (millis, threadCall) = io { gateway.benchmark(threadEfficiency) }
        threadCall?.let { record(it) }
        val result = Benchmark(System.currentTimeMillis(), state.value.efficiency, threadEfficiency, millis)
        dispatch(EnergyEvent.Benchmarked(result))
    }

    private suspend fun readBack() {
        val (awake, scheduling) = io { gateway.osAwakeReadBack() to gateway.osSchedulingReadBack() }
        dispatch(EnergyEvent.OsReadBack(awake, scheduling))
    }

    /** Logs [call]; returns it only when the call succeeded. */
    private fun record(call: CallRecord): CallRecord? {
        dispatch(EnergyEvent.Called(call), if (call.outcome.ok) Severity.Info else Severity.Error)
        return call.takeIf { it.outcome.ok }
    }

    /** Reset or app exit: leave the process the way the Lab found it. */
    override fun onCleared() {
        handles.values.forEach { runCatching { it.close() } }
        handles.clear()
        if (state.value.awakeSlot != null) runCatching { gateway.releaseAwake() }
        if (state.value.efficiency != EfficiencyLevel.Off) runCatching { gateway.applyEfficiency(EfficiencyLevel.Off) }
        super.onCleared()
    }
}
