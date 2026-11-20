package dev.nucleusframework.lab.probes.rendering.partialredraw

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
class PartialRedrawViewModel(
    private val gateway: PartialRedrawGateway,
    timeline: Timeline,
) : MviViewModel<PartialRedrawState, PartialRedrawIntent, PartialRedrawEvent, PartialRedrawEffect>(
        PartialRedrawState(),
        PartialRedrawReducer,
        timeline,
        PartialRedrawProbe.ID,
    ) {
    init {
        read()
    }

    override suspend fun handle(intent: PartialRedrawIntent) {
        when (intent) {
            PartialRedrawIntent.Refresh -> read()
            is PartialRedrawIntent.SelectScene -> dispatch(PartialRedrawEvent.SceneSelected(intent.scene))
            is PartialRedrawIntent.SetInterval -> reduceSilently(PartialRedrawEvent.IntervalChanged(intent.millis))
            PartialRedrawIntent.CopyRelaunchFlags -> emit(PartialRedrawEffect.Copy(relaunchFlags()))
        }
    }

    private fun read() {
        val config = gateway.read()
        // Enabled without the patch means every frame repaints in full: worth flagging.
        val inconsistent = config.enabled && !config.composePatched
        dispatch(PartialRedrawEvent.ConfigRead(config), if (inconsistent) Severity.Warning else Severity.Info)
    }
}
