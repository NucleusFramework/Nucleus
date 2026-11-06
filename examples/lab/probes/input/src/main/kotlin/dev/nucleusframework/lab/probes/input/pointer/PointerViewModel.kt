package dev.nucleusframework.lab.probes.input.pointer

import androidx.lifecycle.ViewModel
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
class PointerViewModel(
    timeline: Timeline,
) : MviViewModel<PointerState, PointerIntent, PointerEvent, Nothing>(
        PointerState(),
        PointerReducer,
        timeline,
        PointerProbe.ID,
    ) {
    /** Moves only touch the state; enter/exit/press/release reach the timeline, phantom exits as warnings. */
    fun onSample(sample: PointerSample) {
        val event = PointerEvent.Sampled(sample)
        when (sample.kind) {
            PointerKind.Move -> reduceSilently(event)
            PointerKind.Exit -> {
                val phantom = state.value.position != null && sample.inside
                dispatch(event, if (phantom) Severity.Warning else Severity.Info)
            }
            else -> dispatch(event)
        }
    }

    override suspend fun handle(intent: PointerIntent) {
        when (intent) {
            PointerIntent.Reset -> dispatch(PointerEvent.Cleared)
        }
    }

    override fun describe(value: Any): String =
        if (value is PointerEvent.Sampled) {
            with(value.sample) { "$kind${button?.let { " $it" } ?: ""} inside=$inside $pointerType" }
        } else {
            super.describe(value)
        }
}
