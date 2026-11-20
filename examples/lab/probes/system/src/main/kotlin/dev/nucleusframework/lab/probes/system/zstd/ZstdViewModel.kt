package dev.nucleusframework.lab.probes.system.zstd

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
class ZstdViewModel(
    private val gateway: ZstdGateway,
    timeline: Timeline,
) : MviViewModel<ZstdState, ZstdIntent, ZstdEvent, Nothing>(ZstdState(), ZstdReducer, timeline, ZstdProbe.ID) {
    init {
        // Inspecting reads the JAR entry only; the library is loaded on the first round trip.
        launch { inspect() }
    }

    override suspend fun handle(intent: ZstdIntent) {
        when (intent) {
            ZstdIntent.Inspect -> inspect()
            is ZstdIntent.RunRoundTrip -> roundTrip(intent.sizeKib * 1024)
            ZstdIntent.ReadMappings -> dispatch(ZstdEvent.Mapped(io { gateway.mappedLibraries() }))
        }
    }

    private suspend fun inspect() {
        val (runtime, resource, manifest) =
            io {
                val resource = gateway.resource()
                Triple(gateway.runtime(), resource, gateway.manifest(resource))
            }
        val event = ZstdEvent.Inspected(runtime, resource, manifest)
        // A sandboxed build whose JAR still holds the real library means the pipeline did not run.
        val suspicious =
            resource.url == null ||
                (runtime.sandboxed && !resource.isMarker) ||
                (resource.isMarker && manifest.bundledPath == null)
        dispatch(event, if (suspicious) Severity.Warning else Severity.Info)
    }

    private suspend fun roundTrip(size: Int) {
        dispatch(ZstdEvent.Started)
        val loaded = io { runCatching { gateway.load() } }
        loaded
            .onSuccess { dispatch(ZstdEvent.Loaded(it)) }
            .onFailure {
                // UnsatisfiedLinkError is what a sandbox refusing a temp-extracted library looks like.
                dispatch(ZstdEvent.LoadFailed(it.summary), Severity.Error)
                return
            }
        val result = io { gateway.roundTrip(size) }
        dispatch(ZstdEvent.RoundTripped(result), if (result.identical) Severity.Info else Severity.Error)
        dispatch(ZstdEvent.Mapped(io { gateway.mappedLibraries() }))
    }
}
