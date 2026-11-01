package dev.nucleusframework.lab.probes.notifications.linux

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.probes.notifications.Tracked
import dev.nucleusframework.lab.probes.notifications.plusTracked
import dev.nucleusframework.lab.probes.notifications.record
import dev.nucleusframework.notification.linux.Urgency

/** A Notify call as the probe composes it. */
@Immutable
data class LinuxDraft(
    val summary: String = "Nucleus Lab",
    val body: String = "<b>Bold</b> body with a <a href=\"https://nucleusframework.dev\">link</a>",
    val urgency: Urgency = Urgency.NORMAL,
    val category: String = "im.received",
    val actions: Boolean = true,
    val icon: Boolean = true,
    val sound: Boolean = false,
    val resident: Boolean = false,
    val transient: Boolean = false,
    /** -1 = server default, 0 = never expires. */
    val expireMs: Int = -1,
    /** Replace the last notification instead of adding one. */
    val replaceLast: Boolean = false,
)

@Immutable
data class LinuxNotificationsState(
    val availability: Availability = Availability.Unknown,
    val server: String? = null,
    val capabilities: List<String> = emptyList(),
    val sent: List<Tracked> = emptyList(),
) {
    /** Server id of the newest notification still open, for “replace” and “close”. */
    val lastOpenId: Int? get() = sent.lastOrNull { !it.finished && it.platformId != null }?.platformId?.toIntOrNull()

    fun supports(capability: String): Boolean = capability in capabilities
}

sealed interface LinuxNotificationsIntent {
    data object ReadServer : LinuxNotificationsIntent

    data class Send(
        val draft: LinuxDraft,
    ) : LinuxNotificationsIntent

    data class Close(
        val id: Int,
    ) : LinuxNotificationsIntent
}

sealed interface LinuxNotificationsEvent {
    data class Ready(
        val availability: Availability,
    ) : LinuxNotificationsEvent

    data class ServerRead(
        val server: String?,
        val capabilities: List<String>,
    ) : LinuxNotificationsEvent

    /** [id] is the server's answer: 0 = refused; equal to [replaced] when it replaced in place. */
    data class Notified(
        val title: String,
        val id: Int,
        val replaced: Int?,
        val at: Long,
    ) : LinuxNotificationsEvent

    data class Signal(
        val id: Int,
        val delivery: Delivery,
        val terminal: Boolean,
    ) : LinuxNotificationsEvent
}

object LinuxNotificationsReducer : Reducer<LinuxNotificationsState, LinuxNotificationsEvent> {
    override fun reduce(
        state: LinuxNotificationsState,
        event: LinuxNotificationsEvent,
    ): LinuxNotificationsState =
        when (event) {
            is LinuxNotificationsEvent.Ready -> state.copy(availability = event.availability)
            is LinuxNotificationsEvent.ServerRead ->
                state.copy(
                    server = event.server,
                    capabilities = event.capabilities,
                )
            is LinuxNotificationsEvent.Notified -> {
                val key = if (event.id == 0) "failed-${event.at}" else event.id.toString()
                val title = if (event.replaced != null) "${event.title} (replacing #${event.replaced})" else event.title
                val tracked =
                    Tracked(
                        key,
                        title,
                        event.at,
                        platformId = event.id.takeIf { it != 0 }?.toString(),
                        error =
                            if (event.id == 0) {
                                "Notify returned 0"
                            } else {
                                null
                            },
                    )
                state.copy(sent = state.sent.plusTracked(tracked))
            }
            is LinuxNotificationsEvent.Signal ->
                state.copy(
                    sent = state.sent.record(event.id.toString(), event.delivery, event.terminal),
                )
        }
}
