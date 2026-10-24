package dev.nucleusframework.lab.probes.shell.dock

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.launcher.macos.DockMenuItem

/** Ids of the items the probe puts in the Dock menu. */
object DockItems {
    const val PING = 1
    const val COUNTER = 2
    const val DISABLED = 3
    const val SEPARATOR = 4
    const val SUBMENU = 10
    const val BADGE_5 = 11
    const val BADGE_CLEAR = 12
    const val OVERVIEW = 20

    /** The menu as it should be after [clicks] clicks on the counter: proves the menu can be rebuilt live. */
    fun build(clicks: Int): List<DockMenuItem> =
        listOf(
            DockMenuItem(PING, "Ping the Lab"),
            DockMenuItem(COUNTER, "Counter: $clicks"),
            DockMenuItem(DISABLED, "Disabled item", enabled = false),
            DockMenuItem.separator(SEPARATOR),
            DockMenuItem(
                SUBMENU,
                "Badge",
                children = listOf(DockMenuItem(BADGE_5, "Set badge to 5"), DockMenuItem(BADGE_CLEAR, "Clear badge")),
            ),
            DockMenuItem(OVERVIEW, "Open Lab overview"),
        )

    fun label(id: Int): String =
        when (id) {
            PING -> "Ping the Lab"
            COUNTER -> "Counter"
            DISABLED -> "Disabled item (must never fire)"
            BADGE_5 -> "Badge ▸ Set badge to 5"
            BADGE_CLEAR -> "Badge ▸ Clear badge"
            OVERVIEW -> "Open Lab overview"
            else -> "unknown id $id"
        }
}

@Immutable
data class DockMenuState(
    val availability: Availability = Availability.Unknown,
    val installed: Boolean = false,
    val counter: Int = 0,
    val clicks: List<Delivery> = emptyList(),
)

sealed interface DockMenuIntent {
    data object Install : DockMenuIntent

    data object Clear : DockMenuIntent
}

sealed interface DockMenuEvent {
    data class Ready(
        val availability: Availability,
    ) : DockMenuEvent

    data class Installed(
        val installed: Boolean,
    ) : DockMenuEvent

    data class Clicked(
        val id: Int,
        val delivery: Delivery,
    ) : DockMenuEvent
}

object DockMenuReducer : Reducer<DockMenuState, DockMenuEvent> {
    override fun reduce(
        state: DockMenuState,
        event: DockMenuEvent,
    ): DockMenuState =
        when (event) {
            is DockMenuEvent.Ready -> state.copy(availability = event.availability)
            is DockMenuEvent.Installed -> state.copy(installed = event.installed)
            is DockMenuEvent.Clicked ->
                state.copy(
                    counter = if (event.id == DockItems.COUNTER) state.counter + 1 else state.counter,
                    clicks = state.clicks.plusDelivery(event.delivery),
                )
        }
}
