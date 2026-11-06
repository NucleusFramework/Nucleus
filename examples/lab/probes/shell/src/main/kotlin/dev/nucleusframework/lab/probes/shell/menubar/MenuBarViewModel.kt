package dev.nucleusframework.lab.probes.shell.menubar

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class MenuBarViewModel(
    private val gateway: MenuBarGateway,
    host: SessionHost,
    timeline: Timeline,
) : MviViewModel<MenuBarState, MenuBarIntent, MenuBarEvent, Nothing>(
        MenuBarState(),
        MenuBarReducer,
        timeline,
        MenuBarProbe.ID,
    ) {
    private val sessions = sessions(host)

    init {
        dispatch(MenuBarEvent.Ready(gateway.availability()))
    }

    override suspend fun handle(intent: MenuBarIntent) {
        when (intent) {
            MenuBarIntent.Install -> sessions.open("Lab menu bar") { MenuBarSession(this@MenuBarViewModel) }
            MenuBarIntent.Remove -> sessions.close()
            is MenuBarIntent.Installed -> dispatch(MenuBarEvent.InstalledChanged(intent.installed))
            is MenuBarIntent.Picked -> picked(intent.action)
            MenuBarIntent.PopUp -> {
                // The item callback runs inside the menu's tracking loop: stamp it there.
                val selected = gateway.popUp { label -> picked(MenuAction.Popup(label).stamped()) }
                dispatch(MenuBarEvent.PopupClosed(selected))
            }
        }
    }

    private fun picked(action: Stamped<MenuAction>) {
        val delivery = action.map { it.label }.toDelivery()
        dispatch(action.map { MenuBarEvent.Picked(it, delivery) })
    }
}
