package dev.nucleusframework.lab.probes.shell.linux

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
class LinuxLauncherViewModel(
    private val gateway: LinuxLauncherGateway,
    timeline: Timeline,
) : MviViewModel<LinuxLauncherState, LinuxLauncherIntent, LinuxLauncherEvent, Nothing>(
        LinuxLauncherState(),
        LinuxLauncherReducer,
        timeline,
        LinuxLauncherProbe.ID,
    ) {
    init {
        val availability = gateway.availability()
        val detected = gateway.detectedDesktopFile()
        dispatch(LinuxLauncherEvent.Ready(availability, detected))
        if (availability.isAvailable && detected != null) selectDesktopFile(detected)
        launch {
            gateway.clicks.collect { stamped ->
                val delivery = stamped.map(QuicklistItems::label).toDelivery()
                dispatch(stamped.map { LinuxLauncherEvent.Clicked(it, delivery) })
                // The checkbox state lives in the app: republish so the dock shows the new tick.
                if (stamped.value == QuicklistItems.TOGGLE && state.value.quicklistActive) publish()
            }
        }
    }

    override suspend fun handle(intent: LinuxLauncherIntent) {
        when (intent) {
            is LinuxLauncherIntent.SetDesktopFile -> selectDesktopFile(intent.id.trim())
            is LinuxLauncherIntent.Apply -> apply(intent.properties)
            LinuxLauncherIntent.Reset -> apply(EntryProperties())
            LinuxLauncherIntent.PublishQuicklist -> publish()
            LinuxLauncherIntent.RemoveQuicklist -> {
                val outcome = gateway.removeQuicklist(state.value.desktopFile)
                dispatch(LinuxLauncherEvent.QuicklistChanged(false, outcome), severity(outcome))
            }
        }
    }

    private fun selectDesktopFile(id: String) {
        if (id.isEmpty()) return
        // The dock asks the Query handler for the current state when it (re)starts.
        val handler = gateway.registerQueryHandler(id)
        dispatch(LinuxLauncherEvent.DesktopFileChanged(id, handler), severity(handler))
    }

    private fun apply(properties: EntryProperties) {
        val outcome = gateway.update(state.value.desktopFile, properties.toLauncher())
        dispatch(LinuxLauncherEvent.Applied(properties, outcome), severity(outcome))
    }

    private fun publish() {
        val outcome = gateway.publishQuicklist(state.value.desktopFile, QuicklistItems.build(state.value.checkableOn))
        dispatch(LinuxLauncherEvent.QuicklistChanged(true, outcome), severity(outcome))
    }

    private fun severity(outcome: CallOutcome) = if (outcome.ok) Severity.Info else Severity.Error
}
