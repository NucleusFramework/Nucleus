package dev.nucleusframework.lab.probes.rendering.textures

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

/** Owns the external producers: they keep their devices across navigation and close with the store. */
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class TexturesViewModel(
    timeline: Timeline,
) : MviViewModel<TexturesState, TexturesIntent, TexturesEvent, Nothing>(
        TexturesState(),
        TexturesReducer,
        timeline,
        TexturesProbe.ID,
    ) {
    val bench = TextureBench()

    init {
        val producers =
            listOfNotNull(
                bench.primary?.let { ProducerInfo("primary", it.kind, it.syncMode) },
                bench.secondary?.let { ProducerInfo("secondary", it.kind, it.syncMode) },
                bench.planar?.let { ProducerInfo("planar", it.kind, it.syncMode) },
                bench.swappedPlanar?.let { ProducerInfo("planar, swapped", it.kind, it.syncMode) },
            )
        dispatch(TexturesEvent.ProducersCreated(producers))
    }

    override suspend fun handle(intent: TexturesIntent) {
        when (intent) {
            TexturesIntent.ToggleAnimation -> dispatch(TexturesEvent.AnimationToggled)
            TexturesIntent.ToggleTrayPanel -> dispatch(TexturesEvent.TrayPanelToggled)
            is TexturesIntent.ContextResolved -> dispatch(TexturesEvent.ContextResolved(intent.surface, intent.info))
        }
    }

    override fun onCleared() = bench.close()
}
