package dev.nucleusframework.lab.probes.system.appearance

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class AppearanceViewModel(
    private val gateway: AppearanceGateway,
    timeline: Timeline,
) : MviViewModel<AppearanceState, AppearanceIntent, AppearanceEvent, Nothing>(
        AppearanceState(),
        AppearanceReducer,
        timeline,
        AppearanceProbe.ID,
    ) {
    init {
        dispatch(AppearanceEvent.Capabilities(gateway.darkModeDetector(), gateway.accentSupport()))
        dispatch(AppearanceEvent.Polled(gateway.isDark()))
        launch {
            gateway.darkModeChanges().collect { stamped ->
                val delivery = stamped.map { "dark=$it" }.toDelivery()
                dispatch(stamped.map { AppearanceEvent.DarkPushed(it, delivery) })
            }
        }
    }

    override suspend fun handle(intent: AppearanceIntent) {
        val now = System.currentTimeMillis()
        when (intent) {
            AppearanceIntent.Poll -> dispatch(AppearanceEvent.Polled(gateway.isDark()))
            is AppearanceIntent.ComposableReport -> {
                dispatch(AppearanceEvent.AccentChanged(intent.accentArgb, now))
                dispatch(AppearanceEvent.ContrastChanged(intent.highContrast, now))
            }
            AppearanceIntent.ClearHistory -> dispatch(AppearanceEvent.HistoryCleared)
        }
    }
}
