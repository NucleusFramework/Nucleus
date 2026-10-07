package dev.nucleusframework.lab.probes.updater.feed

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.format.summary
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
class FeedServerViewModel(
    private val host: FeedServerHost,
    timeline: Timeline,
) : MviViewModel<FeedServerState, FeedServerIntent, FeedServerEvent, Nothing>(
        FeedServerState(),
        FeedServerReducer,
        timeline,
        FeedServerProbe.ID,
    ) {
    init {
        launch { host.status.collect { reduceSilently(FeedServerEvent.StatusChanged(it)) } }
        // Every request the updater makes, wherever it comes from, lands in the timeline.
        poll(
            POLL_MS,
            read = { host.pollRequests().map { ServedRequest(it.toString(), it.status) } },
            toEvent = { FeedServerEvent.Served(it) },
            isChange = { _, next -> next.isNotEmpty() },
            severity = { served -> if (served.any { it.failed }) Severity.Warning else Severity.Info },
        )
    }

    override suspend fun handle(intent: FeedServerIntent) {
        val current = state.value
        when (intent) {
            FeedServerIntent.Start -> attempt { host.start() }
            FeedServerIntent.Stop -> attempt { host.stop() }
            is FeedServerIntent.EditVersion -> reduceSilently(FeedServerEvent.Edited(intent.version, current.sizeMb))
            is FeedServerIntent.SetSize -> reduceSilently(FeedServerEvent.Edited(current.version, intent.megabytes))
            FeedServerIntent.Publish -> attempt { host.publish(current.version.trim(), (current.sizeMb * MB).toLong()) }
            is FeedServerIntent.ConfigureFault ->
                dispatch(
                    FeedServerEvent.FaultConfigured(
                        intent.kind ?: current.faultKind,
                        intent.target ?: current.faultTarget,
                        intent.times ?: current.faultTimes,
                    ),
                )
            FeedServerIntent.InjectFault -> {
                val size = current.status.published?.sizeBytes ?: (current.sizeMb * MB).toLong()
                attempt {
                    host.fault(
                        current.faultKind.fault(size),
                        current.faultTarget.glob,
                        current.faultTimes.times,
                    )
                }
            }
            FeedServerIntent.ClearFaults -> attempt { host.clearFaults() }
            FeedServerIntent.ClearRequests -> attempt { host.clearRequests() }
        }
    }

    private suspend fun attempt(block: () -> Any) {
        runCatching { io(block) }
            .onSuccess { dispatch(FeedServerEvent.StatusChanged(host.status.value)) }
            .onFailure { dispatch(FeedServerEvent.Failed(it.summary), Severity.Error) }
    }

    private companion object {
        const val MB = 1024 * 1024
        const val POLL_MS = 500L
    }
}
