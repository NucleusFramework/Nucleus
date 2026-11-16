package dev.nucleusframework.lab.probes.shell.widgets

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
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
class WindowsWidgetsViewModel(
    private val gateway: WindowsWidgetsGateway,
    timeline: Timeline,
) : MviViewModel<WindowsWidgetsState, WindowsWidgetsIntent, WindowsWidgetsEvent, Nothing>(
        WindowsWidgetsState(),
        WindowsWidgetsReducer,
        timeline,
        widgetsProbeId,
    ) {
    init {
        dispatch(WindowsWidgetsEvent.Ready(gateway.managerAvailability(), gateway.hostStatus()))
        launch {
            gateway.events.collect { stamped ->
                dispatch(stamped.map { WindowsWidgetsEvent.Received(stamped.toDelivery()) })
                // Every event may have changed a widget: keep the list current.
                if (state.value.host.host.isAvailable) refresh()
            }
        }
        if (state.value.host.host.isAvailable) launch { refresh() }
    }

    override suspend fun handle(intent: WindowsWidgetsIntent) {
        when (intent) {
            WindowsWidgetsIntent.Refresh -> refresh()
            is WindowsWidgetsIntent.Push -> {
                called(
                    "updateWidgetState + update(${intent.widgetId})",
                    io { gateway.push(intent.widgetId, intent.definitionId) },
                )
                refresh()
            }
            is WindowsWidgetsIntent.Delete -> {
                called("deleteWidget(${intent.widgetId})", io { gateway.delete(intent.widgetId) })
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        val result = io { gateway.list() }
        dispatch(WindowsWidgetsEvent.Listed(result.getOrNull(), CallOutcome.of(result)), severity(result.isSuccess))
    }

    private fun called(
        call: String,
        outcome: CallOutcome,
    ) = dispatch(WindowsWidgetsEvent.Called(call, outcome), severity(outcome.ok))

    private fun severity(ok: Boolean) = if (ok) Severity.Info else Severity.Error
}
