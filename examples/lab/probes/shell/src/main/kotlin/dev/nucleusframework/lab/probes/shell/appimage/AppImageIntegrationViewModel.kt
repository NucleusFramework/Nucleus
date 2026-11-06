package dev.nucleusframework.lab.probes.shell.appimage

import androidx.lifecycle.ViewModel
import dev.nucleusframework.core.runtime.AppImageIntegrationResult
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
class AppImageIntegrationViewModel(
    private val gateway: AppImageIntegrationGateway,
    timeline: Timeline,
) : MviViewModel<AppImageIntegrationState, AppImageIntegrationIntent, AppImageIntegrationEvent, Nothing>(
        AppImageIntegrationState(),
        AppImageIntegrationReducer,
        timeline,
        AppImageIntegrationProbe.ID,
    ) {
    init {
        dispatch(AppImageIntegrationEvent.StatusRead(gateway.status()))
    }

    override suspend fun handle(intent: AppImageIntegrationIntent) {
        when (intent) {
            AppImageIntegrationIntent.Refresh -> dispatch(AppImageIntegrationEvent.StatusRead(gateway.status()))
            AppImageIntegrationIntent.Integrate -> {
                val result = gateway.integrate()
                val severity = if (result is AppImageIntegrationResult.Integrated) Severity.Info else Severity.Error
                dispatch(AppImageIntegrationEvent.IntegrateCalled(result, gateway.status()), severity)
            }
        }
    }
}
