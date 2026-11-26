package dev.nucleusframework.lab.probes.shell.menubar

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.plusDelivery

enum class MenuMode { Compact, Regular, Wide }

/** What a menu item does to the probe's state when picked. */
sealed interface MenuAction {
    val label: String

    data object Ping : MenuAction {
        override val label = "Lab ▸ Ping"
    }

    data object ToggleCheckable : MenuAction {
        override val label = "Lab ▸ Checkable"
    }

    data class SelectMode(
        val mode: MenuMode,
    ) : MenuAction {
        override val label = "Lab ▸ Mode ▸ $mode"
    }

    data object BumpBadge : MenuAction {
        override val label = "Lab ▸ Inbox (badge +1)"
    }

    data object Nested : MenuAction {
        override val label = "Lab ▸ More ▸ Nested item"
    }

    data class Popup(
        override val label: String,
    ) : MenuAction
}

@Immutable
data class MenuBarState(
    val availability: Availability = Availability.Unknown,
    /** The menu bar composition is live (it is installed while composed). */
    val installed: Boolean = false,
    val checkable: Boolean = false,
    val mode: MenuMode = MenuMode.Regular,
    val inboxBadge: Int = 3,
    val picks: List<Delivery> = emptyList(),
    val lastPopup: String? = null,
)

sealed interface MenuBarIntent {
    data object Install : MenuBarIntent

    data object Remove : MenuBarIntent

    /** Reported by the menu bar composition itself: true on enter, false on dispose. */
    data class Installed(
        val installed: Boolean,
    ) : MenuBarIntent

    data class Picked(
        val action: Stamped<MenuAction>,
    ) : MenuBarIntent

    data object PopUp : MenuBarIntent
}

sealed interface MenuBarEvent {
    data class Ready(
        val availability: Availability,
    ) : MenuBarEvent

    data class InstalledChanged(
        val installed: Boolean,
    ) : MenuBarEvent

    data class Picked(
        val action: MenuAction,
        val delivery: Delivery,
    ) : MenuBarEvent

    data class PopupClosed(
        val selected: Boolean,
    ) : MenuBarEvent
}

object MenuBarReducer : Reducer<MenuBarState, MenuBarEvent> {
    override fun reduce(
        state: MenuBarState,
        event: MenuBarEvent,
    ): MenuBarState =
        when (event) {
            is MenuBarEvent.Ready -> state.copy(availability = event.availability)
            is MenuBarEvent.InstalledChanged -> state.copy(installed = event.installed)
            is MenuBarEvent.Picked -> {
                val delivered = state.copy(picks = state.picks.plusDelivery(event.delivery))
                when (val action = event.action) {
                    MenuAction.ToggleCheckable -> delivered.copy(checkable = !state.checkable)
                    is MenuAction.SelectMode -> delivered.copy(mode = action.mode)
                    MenuAction.BumpBadge -> delivered.copy(inboxBadge = state.inboxBadge + 1)
                    is MenuAction.Popup -> delivered.copy(lastPopup = action.label)
                    MenuAction.Ping, MenuAction.Nested -> delivered
                }
            }
            is MenuBarEvent.PopupClosed ->
                if (event.selected) state else state.copy(lastPopup = "dismissed without a pick")
        }
}
