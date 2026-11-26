package dev.nucleusframework.lab.probes.rendering.conformance

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
class ConformanceViewModel(
    timeline: Timeline,
) : MviViewModel<ConformanceState, ConformanceIntent, ConformanceEvent, Nothing>(
        ConformanceState(),
        ConformanceReducer,
        timeline,
        ConformanceProbe.ID,
    ) {
    override suspend fun handle(intent: ConformanceIntent) {
        val levers = state.value.levers
        when (intent) {
            is ConformanceIntent.SelectCategory -> dispatch(ConformanceEvent.CategorySelected(intent.category))
            is ConformanceIntent.Search -> reduceSilently(ConformanceEvent.Searched(intent.query))
            ConformanceIntent.ToggleRtl -> dispatch(ConformanceEvent.LeversChanged(levers.copy(rtl = !levers.rtl)))
            is ConformanceIntent.SetFontScale ->
                dispatch(
                    ConformanceEvent.LeversChanged(levers.copy(fontScale = intent.scale)),
                )
            is ConformanceIntent.SetDensityScale ->
                dispatch(
                    ConformanceEvent.LeversChanged(levers.copy(densityScale = intent.scale)),
                )
            ConformanceIntent.ToggleOutlines ->
                dispatch(
                    ConformanceEvent.LeversChanged(levers.copy(outlines = !levers.outlines)),
                )
            ConformanceIntent.ResetLevers -> dispatch(ConformanceEvent.LeversChanged(Levers()))
        }
    }
}
