package dev.nucleusframework.lab.probes.notifications.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.probes.notifications.Tracked
import dev.nucleusframework.lab.probes.notifications.plusTracked
import dev.nucleusframework.lab.probes.notifications.record
import dev.nucleusframework.lab.probes.notifications.update

@Immutable
data class CommonState(
    val availability: Availability = Availability.Unknown,
    val sent: List<Tracked> = emptyList(),
)

sealed interface CommonIntent {
    data class Send(
        val draft: CommonDraft,
    ) : CommonIntent

    /** Sends [count] in a row: ordering, coalescing and callback routing under load. */
    data class Burst(
        val draft: CommonDraft,
        val count: Int,
    ) : CommonIntent

    data class Dismiss(
        val key: String,
    ) : CommonIntent
}

sealed interface CommonEvent {
    data class Ready(
        val availability: Availability,
    ) : CommonEvent

    data class Sending(
        val key: String,
        val title: String,
        val at: Long,
    ) : CommonEvent

    data class Sent(
        val key: String,
        val result: Result<String>,
    ) : CommonEvent

    data class Callback(
        val key: String,
        val delivery: Delivery,
        val terminal: Boolean,
    ) : CommonEvent

    data class Dismissed(
        val key: String,
        val found: Boolean,
    ) : CommonEvent
}

object CommonReducer : Reducer<CommonState, CommonEvent> {
    override fun reduce(
        state: CommonState,
        event: CommonEvent,
    ): CommonState =
        when (event) {
            is CommonEvent.Ready -> state.copy(availability = event.availability)
            is CommonEvent.Sending ->
                state.copy(
                    sent = state.sent.plusTracked(Tracked(event.key, event.title, event.at)),
                )
            is CommonEvent.Sent ->
                state.copy(
                    sent =
                        state.sent.update(event.key) {
                            event.result.fold(
                                onSuccess = { id -> it.copy(platformId = id) },
                                onFailure = { error -> it.copy(error = error.message ?: "unknown", finished = true) },
                            )
                        },
                )
            is CommonEvent.Callback ->
                state.copy(
                    sent = state.sent.record(event.key, event.delivery, finished = event.terminal),
                )
            is CommonEvent.Dismissed ->
                if (event.found) state else state.copy(sent = state.sent.update(event.key) { it.copy(finished = true) })
        }
}
