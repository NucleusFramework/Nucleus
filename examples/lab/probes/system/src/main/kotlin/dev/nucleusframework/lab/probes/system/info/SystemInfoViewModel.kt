package dev.nucleusframework.lab.probes.system.info

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.commands.LabCommands
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.time.timedMillis
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SystemInfoViewModel(
    private val gateway: SystemInfoGateway,
    commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<SystemInfoState, SystemInfoIntent, SystemInfoEvent, Nothing>(
        SystemInfoState(),
        SystemInfoReducer,
        timeline,
        SystemInfoProbe.ID,
    ) {
    init {
        val available = gateway.availability()
        dispatch(SystemInfoEvent.AvailabilityResolved(available))
        if (available.isAvailable) {
            // The sampling loop restarts whenever the interval changes; a pause simply ends it.
            launch {
                state.map { it.interval }.distinctUntilChanged().collectLatest { interval ->
                    val period = interval.millis ?: return@collectLatest
                    while (true) {
                        sampleOnce()
                        delay(period)
                    }
                }
            }
        }
        // nucleus-lab://probe/system.info?section=gpu&interval=fast
        onParams(commands) { params ->
            params.enum<InfoSection>("section")?.let { onIntent(SystemInfoIntent.ShowSection(it)) }
            params.enum<SamplingInterval>("interval")?.let { onIntent(SystemInfoIntent.SetInterval(it)) }
        }
    }

    override suspend fun handle(intent: SystemInfoIntent) {
        when (intent) {
            is SystemInfoIntent.SetInterval -> dispatch(SystemInfoEvent.IntervalChanged(intent.interval))
            is SystemInfoIntent.ShowSection -> {
                dispatch(SystemInfoEvent.SectionChanged(intent.section))
                if (intent.section == InfoSection.Processes) sampleOnce()
            }
            is SystemInfoIntent.FilterProcesses -> reduceSilently(SystemInfoEvent.QueryChanged(intent.query))
            SystemInfoIntent.SampleNow -> sampleOnce()
        }
    }

    private suspend fun sampleOnce() {
        if (!state.value.available.isAvailable) return
        val processes = state.value.section == InfoSection.Processes
        val (result, millis) = io { timedMillis { runCatching { gateway.sample(includeProcesses = processes) } } }
        result
            .onSuccess {
                // Sampling is periodic: keep it out of the timeline unless it is slow enough to matter.
                if (millis >= SLOW_SAMPLE_MILLIS) {
                    dispatch(SystemInfoEvent.Sampled(it, millis), Severity.Warning)
                } else {
                    reduceSilently(SystemInfoEvent.Sampled(it, millis))
                }
            }.onFailure { dispatch(SystemInfoEvent.SampleFailed(it.summary), Severity.Error) }
    }

    private companion object {
        const val SLOW_SAMPLE_MILLIS = 200L
    }
}
