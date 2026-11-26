package dev.nucleusframework.lab.probes.notifications.windows

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.unjudgedDelivery
import dev.nucleusframework.lab.probes.notifications.Tracked
import dev.nucleusframework.lab.probes.notifications.plusTracked
import dev.nucleusframework.lab.probes.notifications.record
import dev.nucleusframework.lab.probes.notifications.update
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario

/** The toast features the probe can stack on one notification. */
@Immutable
data class ToastDraft(
    val title: String = "Nucleus Lab",
    val body: String = "A rich toast from the Windows probe",
    val attribution: String = "via notification-windows",
    val buttons: Boolean = true,
    val textBox: Boolean = false,
    val selection: Boolean = false,
    val header: Boolean = false,
    val progress: Boolean = false,
    val silent: Boolean = false,
    val scenario: ToastScenario = ToastScenario.DEFAULT,
    val duration: ToastDuration = ToastDuration.DEFAULT,
)

const val TOAST_GROUP = "lab"

@Immutable
data class WindowsToastState(
    val availability: Availability = Availability.Unknown,
    val identity: String = "",
    val initialized: Boolean? = null,
    val sent: List<Tracked> = emptyList(),
    /** Tag → progress (0..1) of toasts sent with a bound progress bar. */
    val progress: Map<String, Double> = emptyMap(),
    val history: List<String>? = null,
    val historyError: String? = null,
)

sealed interface WindowsToastIntent {
    data class Send(
        val draft: ToastDraft,
    ) : WindowsToastIntent

    /** Advances the bound progress bar of [tag] in place (data binding, no new toast). */
    data class AdvanceProgress(
        val tag: String,
    ) : WindowsToastIntent

    data class Remove(
        val tag: String,
    ) : WindowsToastIntent

    data object ClearAll : WindowsToastIntent

    data object ReadHistory : WindowsToastIntent
}

sealed interface WindowsToastEvent {
    data class Ready(
        val availability: Availability,
        val identity: String,
        val initialized: Boolean?,
    ) : WindowsToastEvent

    data class Sending(
        val tag: String,
        val title: String,
        val at: Long,
        val withProgress: Boolean,
    ) : WindowsToastEvent

    data class Shown(
        val tag: String,
        val error: String?,
    ) : WindowsToastEvent

    data class ProgressUpdated(
        val tag: String,
        val value: Double,
        val error: String?,
    ) : WindowsToastEvent

    data class Reported(
        val tag: String,
        val delivery: Delivery,
        val terminal: Boolean,
    ) : WindowsToastEvent

    data class Removed(
        val tag: String?,
    ) : WindowsToastEvent

    data class HistoryRead(
        val entries: List<String>,
        val error: String?,
    ) : WindowsToastEvent
}

object WindowsToastReducer : Reducer<WindowsToastState, WindowsToastEvent> {
    override fun reduce(
        state: WindowsToastState,
        event: WindowsToastEvent,
    ): WindowsToastState =
        when (event) {
            is WindowsToastEvent.Ready ->
                state.copy(
                    availability = event.availability,
                    identity = event.identity,
                    initialized = event.initialized,
                )
            is WindowsToastEvent.Sending ->
                state.copy(
                    sent = state.sent.plusTracked(Tracked(event.tag, event.title, event.at)),
                    progress = if (event.withProgress) state.progress + (event.tag to 0.0) else state.progress,
                )
            is WindowsToastEvent.Shown ->
                state.copy(
                    sent =
                        state.sent.update(event.tag) {
                            if (event.error == null) {
                                it.copy(platformId = event.tag)
                            } else {
                                it.copy(error = event.error, finished = true)
                            }
                        },
                )
            is WindowsToastEvent.ProgressUpdated ->
                if (event.error == null) {
                    state.copy(progress = state.progress + (event.tag to event.value))
                } else {
                    state.copy(
                        sent =
                            state.sent.record(
                                event.tag,
                                unjudgedDelivery("update failed: ${event.error}"),
                            ),
                    )
                }
            is WindowsToastEvent.Reported ->
                state.copy(
                    sent = state.sent.record(event.tag, event.delivery, event.terminal),
                )
            is WindowsToastEvent.Removed ->
                state.copy(
                    sent =
                        state.sent.map {
                            if (event.tag == null ||
                                it.key == event.tag
                            ) {
                                it.copy(finished = true)
                            } else {
                                it
                            }
                        },
                    progress = if (event.tag == null) emptyMap() else state.progress - event.tag,
                )
            is WindowsToastEvent.HistoryRead -> state.copy(history = event.entries, historyError = event.error)
        }
}
