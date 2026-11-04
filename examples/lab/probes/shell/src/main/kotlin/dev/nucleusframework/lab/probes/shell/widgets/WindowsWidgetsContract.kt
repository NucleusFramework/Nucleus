package dev.nucleusframework.lab.probes.shell.widgets

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery

/** The widget host as `widgets { }` connected to it at startup. */
@Immutable
data class HostStatus(
    val host: Availability = Availability.Unknown,
    /** The process was started by the widgets board (COM), not by the user. */
    val launchedByHost: Boolean = false,
    val clsid: String? = null,
)

/** A pinned instance of the Lab counter. */
@Immutable
data class PinnedCounter(
    val id: String,
    val size: String,
    val isActive: Boolean,
    val count: Int,
)

@Immutable
data class WindowsWidgetsState(
    val manager: Availability = Availability.Unknown,
    val host: HostStatus = HostStatus(),
    val widgets: List<PinnedCounter> = emptyList(),
    /** NucleusWidgets.events: host callbacks, renders and failures. */
    val events: List<Delivery> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
)

sealed interface WindowsWidgetsIntent {
    data object Refresh : WindowsWidgetsIntent

    data class Push(
        val widgetId: String,
    ) : WindowsWidgetsIntent

    data class Delete(
        val widgetId: String,
    ) : WindowsWidgetsIntent
}

sealed interface WindowsWidgetsEvent {
    data class Ready(
        val manager: Availability,
        val host: HostStatus,
    ) : WindowsWidgetsEvent

    data class Received(
        val delivery: Delivery,
    ) : WindowsWidgetsEvent

    data class Listed(
        val widgets: List<PinnedCounter>?,
        val outcome: CallOutcome,
    ) : WindowsWidgetsEvent

    data class Called(
        val call: String,
        val outcome: CallOutcome,
    ) : WindowsWidgetsEvent
}

object WindowsWidgetsReducer : Reducer<WindowsWidgetsState, WindowsWidgetsEvent> {
    override fun reduce(
        state: WindowsWidgetsState,
        event: WindowsWidgetsEvent,
    ): WindowsWidgetsState =
        when (event) {
            is WindowsWidgetsEvent.Ready -> state.copy(manager = event.manager, host = event.host)
            is WindowsWidgetsEvent.Received -> state.copy(events = state.events.plusDelivery(event.delivery))
            is WindowsWidgetsEvent.Listed ->
                state.copy(
                    widgets = event.widgets ?: state.widgets,
                    calls = state.calls.plusCall("pinnedWidgets()", event.outcome, event.widgets?.size?.toString()),
                )
            is WindowsWidgetsEvent.Called -> state.copy(calls = state.calls.plusCall(event.call, event.outcome))
        }
}
