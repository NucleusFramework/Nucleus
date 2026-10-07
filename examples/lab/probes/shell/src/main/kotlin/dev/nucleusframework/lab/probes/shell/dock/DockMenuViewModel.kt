package dev.nucleusframework.lab.probes.shell.dock

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.commands.LabCommands
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
class DockMenuViewModel(
    private val gateway: DockMenuGateway,
    private val commands: LabCommands,
    timeline: Timeline,
) : MviViewModel<DockMenuState, DockMenuIntent, DockMenuEvent, Nothing>(
        DockMenuState(),
        DockMenuReducer,
        timeline,
        DockMenuProbe.ID,
    ) {
    init {
        dispatch(DockMenuEvent.Ready(gateway.availability()))
        launch {
            gateway.clicks.collect { stamped ->
                val delivery = stamped.map(DockItems::label).toDelivery()
                dispatch(stamped.map { DockMenuEvent.Clicked(it, delivery) })
                react(stamped.value)
            }
        }
    }

    override suspend fun handle(intent: DockMenuIntent) {
        when (intent) {
            DockMenuIntent.Install -> {
                gateway.setMenu(DockItems.build(state.value.counter))
                dispatch(DockMenuEvent.Installed(true))
            }
            DockMenuIntent.Clear -> {
                gateway.clear()
                dispatch(DockMenuEvent.Installed(false))
            }
        }
    }

    /** Items act on the Lab, so a click is visible beyond this screen. */
    private fun react(id: Int) {
        when (id) {
            DockItems.COUNTER -> gateway.setMenu(DockItems.build(state.value.counter))
            DockItems.BADGE_5 -> commands.open(ProbeId("shell.badge"), mapOf("count" to "5"))
            DockItems.BADGE_CLEAR -> commands.open(ProbeId("shell.badge"), mapOf("clear" to ""))
            DockItems.OVERVIEW -> commands.open(ProbeId("lab.overview"))
        }
    }
}
