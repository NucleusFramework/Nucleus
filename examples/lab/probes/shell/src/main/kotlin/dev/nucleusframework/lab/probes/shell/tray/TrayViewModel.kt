package dev.nucleusframework.lab.probes.shell.tray

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

/** The tray is pure composition (ComposeNativeTray), so there is no gateway: the session reports what it saw. */
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class TrayViewModel(
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<TrayState, TrayIntent, TrayEvent, Nothing>(TrayState(), TrayReducer, timeline, TrayProbe.ID) {
    private val sessions = sessions(host)

    override suspend fun handle(intent: TrayIntent) {
        when (intent) {
            TrayIntent.Show -> sessions.open("Lab tray icon") { close -> TraySession(this@TrayViewModel, close) }
            TrayIntent.Remove -> sessions.close()
            is TrayIntent.Installed -> dispatch(TrayEvent.InstalledChanged(intent.installed))
            is TrayIntent.SetTooltip -> dispatch(TrayEvent.TooltipChanged(intent.tooltip))
            is TrayIntent.Delivered -> {
                val delivery = intent.action.map { it.label }.toDelivery()
                dispatch(intent.action.map { TrayEvent.Delivered(it, delivery) })
            }
        }
    }
}
