package dev.nucleusframework.lab.probes.shell.widgets

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery

/** The COM class object the widget host activates, as registered at startup. */
@Immutable
data class ProviderStatus(
    val registration: Availability = Availability.Unknown,
    /** The process was started by COM for the widget host, not by the user. */
    val launchedByCom: Boolean = false,
    val processStartMillis: Long = 0,
    /** First host callback, to measure how long a COM cold start keeps the host waiting. */
    val firstCallbackMillis: Long? = null,
)

/** A pinned widget as [dev.nucleusframework.widget.windows.WidgetManager] reports it. */
@Immutable
data class PinnedWidget(
    val id: String,
    val definitionId: String,
    val size: String,
    val isActive: Boolean,
    val count: Int,
    val lastUpdateMillis: Long,
)

@Immutable
data class WindowsWidgetsState(
    val manager: Availability = Availability.Unknown,
    val provider: ProviderStatus = ProviderStatus(),
    val widgets: List<PinnedWidget> = emptyList(),
    /** Every host callback, stamped with the COM thread it arrived on. */
    val callbacks: List<Delivery> = emptyList(),
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
    ) : WindowsWidgetsEvent

    data class ProviderChanged(
        val provider: ProviderStatus,
    ) : WindowsWidgetsEvent

    data class Callback(
        val delivery: Delivery,
    ) : WindowsWidgetsEvent

    data class Listed(
        val widgets: List<PinnedWidget>?,
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
            is WindowsWidgetsEvent.Ready -> state.copy(manager = event.manager)
            is WindowsWidgetsEvent.ProviderChanged -> state.copy(provider = event.provider)
            is WindowsWidgetsEvent.Callback -> state.copy(callbacks = state.callbacks.plusDelivery(event.delivery))
            is WindowsWidgetsEvent.Listed ->
                state.copy(
                    widgets = event.widgets ?: state.widgets,
                    calls = state.calls.plusCall("getWidgetInfos()", event.outcome, event.widgets?.size?.toString()),
                )
            is WindowsWidgetsEvent.Called -> state.copy(calls = state.calls.plusCall(event.call, event.outcome))
        }
}
