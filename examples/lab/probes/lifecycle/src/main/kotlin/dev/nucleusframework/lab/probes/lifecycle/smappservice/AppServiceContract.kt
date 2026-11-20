package dev.nucleusframework.lab.probes.lifecycle.smappservice

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.servicemanagement.AppServiceStatus

@Immutable
data class AppServiceState(
    val available: Boolean? = null,
    val statuses: Map<LabService, AppServiceStatus> = emptyMap(),
    val calls: List<CallRecord> = emptyList(),
    val heartbeats: List<String> = emptyList(),
    val loginItemsOpened: Boolean? = null,
)

sealed interface AppServiceIntent {
    data class Register(
        val service: LabService,
    ) : AppServiceIntent

    data class Unregister(
        val service: LabService,
    ) : AppServiceIntent

    data object OpenLoginItems : AppServiceIntent

    data object Refresh : AppServiceIntent
}

sealed interface AppServiceEvent {
    data class Read(
        val available: Boolean,
        val statuses: Map<LabService, AppServiceStatus>,
        val heartbeats: List<String>,
    ) : AppServiceEvent {
        override fun toString(): String =
            "Read(available=$available, ${statuses.entries.joinToString { "${it.key}=${it.value}" }})"
    }

    /** A register / unregister call, with the status read right after it. */
    data class Completed(
        val service: LabService,
        val call: CallRecord,
        val statusAfter: AppServiceStatus?,
    ) : AppServiceEvent

    data class LoginItemsOpened(
        val opened: Boolean,
    ) : AppServiceEvent
}

object AppServiceReducer : Reducer<AppServiceState, AppServiceEvent> {
    override fun reduce(
        state: AppServiceState,
        event: AppServiceEvent,
    ): AppServiceState =
        when (event) {
            is AppServiceEvent.Read ->
                state.copy(
                    available = event.available,
                    statuses = event.statuses,
                    heartbeats = event.heartbeats,
                )
            is AppServiceEvent.Completed ->
                state.copy(
                    calls = state.calls.append(event.call),
                    statuses = event.statusAfter?.let { state.statuses + (event.service to it) } ?: state.statuses,
                )
            is AppServiceEvent.LoginItemsOpened -> state.copy(loginItemsOpened = event.opened)
        }
}
