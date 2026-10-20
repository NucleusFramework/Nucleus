package dev.nucleusframework.lab.probes.lifecycle.singleinstance

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.lab.core.mvi.replaceWhere
import dev.nucleusframework.lab.core.process.ProcessUpdate
import dev.nucleusframework.lab.probes.lifecycle.launch.LaunchRecord

@Immutable
data class SingleInstanceState(
    val lockTakenByApp: Boolean,
    val lockFile: String,
    val restoreRequestFile: String,
    val lockIdentifier: String,
    val holder: LockHolder? = null,
    val holderError: String? = null,
    val checkedAt: Long? = null,
    val restoreSignals: List<Delivery> = emptyList(),
    val launches: List<LaunchRecord> = emptyList(),
)

sealed interface SingleInstanceIntent {
    data object CheckLock : SingleInstanceIntent

    data object LaunchSecond : SingleInstanceIntent

    data object KillLaunched : SingleInstanceIntent
}

sealed interface SingleInstanceEvent {
    data class LockChecked(
        val holder: LockHolder,
        val at: Long,
    ) : SingleInstanceEvent

    data class LockCheckFailed(
        val reason: String,
    ) : SingleInstanceEvent

    data class RestoreFileChanged(
        val signal: Delivery,
    ) : SingleInstanceEvent

    data class LaunchStarted(
        val record: LaunchRecord,
    ) : SingleInstanceEvent

    data class LaunchProgress(
        val id: Int,
        val update: ProcessUpdate,
    ) : SingleInstanceEvent
}

object SingleInstanceReducer : Reducer<SingleInstanceState, SingleInstanceEvent> {
    override fun reduce(
        state: SingleInstanceState,
        event: SingleInstanceEvent,
    ): SingleInstanceState =
        when (event) {
            is SingleInstanceEvent.LockChecked ->
                state.copy(
                    holder = event.holder,
                    holderError = null,
                    checkedAt = event.at,
                )
            is SingleInstanceEvent.LockCheckFailed -> state.copy(holderError = event.reason)
            is SingleInstanceEvent.RestoreFileChanged ->
                state.copy(restoreSignals = state.restoreSignals.plusDelivery(event.signal))
            is SingleInstanceEvent.LaunchStarted -> state.copy(launches = state.launches.append(event.record))
            is SingleInstanceEvent.LaunchProgress ->
                state.copy(
                    launches = state.launches.replaceWhere(LaunchRecord::id, event.id) { it.apply(event.update) },
                )
        }
}
