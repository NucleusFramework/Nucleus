package dev.nucleusframework.lab.probes.shell.tray

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.plusDelivery

/** What the tray reported back. */
sealed interface TrayAction {
    val label: String

    /** Left click on the icon (`primaryAction`). */
    data object Primary : TrayAction {
        override val label = "primary click on the icon"
    }

    data object MenuOpened : TrayAction {
        override val label = "menu opened"
    }

    data object Ping : TrayAction {
        override val label = "menu ▸ Ping the Lab"
    }

    data class Toggle(
        val checked: Boolean,
    ) : TrayAction {
        override val label = "menu ▸ Checkable → $checked"
    }

    data object Nested : TrayAction {
        override val label = "menu ▸ More ▸ Nested item"
    }
}

@Immutable
data class TrayState(
    /** The tray composition is live: the icon should be in the tray / menu bar extras. */
    val installed: Boolean = false,
    val tooltip: String = "Nucleus Lab",
    val checked: Boolean = false,
    val pings: Int = 0,
    val primaryClicks: Int = 0,
    val menuOpens: Int = 0,
    val deliveries: List<Delivery> = emptyList(),
)

sealed interface TrayIntent {
    data object Show : TrayIntent

    data object Remove : TrayIntent

    data class Installed(
        val installed: Boolean,
    ) : TrayIntent

    data class SetTooltip(
        val tooltip: String,
    ) : TrayIntent

    data class Delivered(
        val action: Stamped<TrayAction>,
    ) : TrayIntent
}

sealed interface TrayEvent {
    data class InstalledChanged(
        val installed: Boolean,
    ) : TrayEvent

    data class TooltipChanged(
        val tooltip: String,
    ) : TrayEvent

    data class Delivered(
        val action: TrayAction,
        val delivery: Delivery,
    ) : TrayEvent
}

object TrayReducer : Reducer<TrayState, TrayEvent> {
    override fun reduce(
        state: TrayState,
        event: TrayEvent,
    ): TrayState =
        when (event) {
            is TrayEvent.InstalledChanged -> state.copy(installed = event.installed)
            is TrayEvent.TooltipChanged -> state.copy(tooltip = event.tooltip)
            is TrayEvent.Delivered -> {
                val logged = state.copy(deliveries = state.deliveries.plusDelivery(event.delivery))
                when (val action = event.action) {
                    TrayAction.Primary -> logged.copy(primaryClicks = state.primaryClicks + 1)
                    TrayAction.MenuOpened -> logged.copy(menuOpens = state.menuOpens + 1)
                    TrayAction.Ping -> logged.copy(pings = state.pings + 1)
                    is TrayAction.Toggle -> logged.copy(checked = action.checked)
                    TrayAction.Nested -> logged
                }
            }
        }
}
