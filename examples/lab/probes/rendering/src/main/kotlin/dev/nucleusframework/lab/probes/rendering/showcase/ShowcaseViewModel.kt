package dev.nucleusframework.lab.probes.rendering.showcase

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ShowcaseViewModel(
    timeline: Timeline,
) : MviViewModel<ShowcaseState, ShowcaseIntent, ShowcaseEvent, Nothing>(
        ShowcaseState(),
        ShowcaseReducer,
        timeline,
        ShowcaseProbe.ID,
    ) {
    override suspend fun handle(intent: ShowcaseIntent) {
        when (intent) {
            ShowcaseIntent.Click -> reduceSilently(ShowcaseEvent.Clicked)
            is ShowcaseIntent.ToggleBlob -> dispatch(ShowcaseEvent.BlobToggled(intent.index))
            ShowcaseIntent.ToggleBlur -> dispatch(ShowcaseEvent.BlurToggled)
            ShowcaseIntent.ToggleAnimation -> dispatch(ShowcaseEvent.AnimationToggled)
            ShowcaseIntent.ToggleCursorGlow -> dispatch(ShowcaseEvent.CursorGlowToggled)
        }
    }
}
