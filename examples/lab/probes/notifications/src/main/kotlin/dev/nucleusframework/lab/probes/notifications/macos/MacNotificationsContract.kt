package dev.nucleusframework.lab.probes.notifications.macos

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.probes.notifications.Tracked
import dev.nucleusframework.lab.probes.notifications.plusTracked
import dev.nucleusframework.lab.probes.notifications.record
import dev.nucleusframework.lab.probes.notifications.update
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.NotificationSettings
import dev.nucleusframework.notification.PresentationOption

enum class SoundChoice { None, Default, Critical }

/** A UNNotificationRequest as the probe composes it. */
@Immutable
data class MacDraft(
    val title: String = "Nucleus Lab",
    val subtitle: String = "macOS probe",
    val body: String = "Reply, open or delete from the notification.",
    val sound: SoundChoice = SoundChoice.Default,
    val interruption: InterruptionLevel = InterruptionLevel.ACTIVE,
    val threadId: String = "lab-thread",
    val badge: Int? = null,
    val withActions: Boolean = true,
    /** 0 = deliver now, else a time-interval trigger. */
    val delaySeconds: Int = 0,
)

@Immutable
data class MacNotificationsState(
    val availability: Availability = Availability.Unknown,
    val settings: NotificationSettings? = null,
    val authorization: String? = null,
    val categoryInstalled: Boolean = false,
    val presentation: Set<PresentationOption> =
        setOf(PresentationOption.BANNER, PresentationOption.SOUND, PresentationOption.LIST),
    val sent: List<Tracked> = emptyList(),
    val pending: List<String> = emptyList(),
    val delivered: List<String> = emptyList(),
)

sealed interface MacNotificationsIntent {
    data class RequestAuthorization(
        val options: Set<AuthorizationOption>,
    ) : MacNotificationsIntent

    data object RefreshSettings : MacNotificationsIntent

    data class SetPresentation(
        val options: Set<PresentationOption>,
    ) : MacNotificationsIntent

    data class Send(
        val draft: MacDraft,
    ) : MacNotificationsIntent

    data object RefreshLists : MacNotificationsIntent

    data object RemoveAll : MacNotificationsIntent
}

sealed interface MacNotificationsEvent {
    data class Ready(
        val availability: Availability,
    ) : MacNotificationsEvent

    data class Authorized(
        val granted: Boolean,
        val error: String?,
    ) : MacNotificationsEvent

    data class SettingsRead(
        val settings: NotificationSettings,
    ) : MacNotificationsEvent

    data object CategoryInstalled : MacNotificationsEvent

    data class PresentationChanged(
        val options: Set<PresentationOption>,
    ) : MacNotificationsEvent

    data class Sending(
        val id: String,
        val title: String,
        val at: Long,
    ) : MacNotificationsEvent

    data class Added(
        val id: String,
        val error: String?,
    ) : MacNotificationsEvent

    /** A delegate callback about notification [id]; [terminal] when it was dismissed. */
    data class Reported(
        val id: String,
        val delivery: Delivery,
        val terminal: Boolean,
    ) : MacNotificationsEvent

    data class ListsRead(
        val pending: List<String>,
        val delivered: List<String>,
    ) : MacNotificationsEvent
}

object MacNotificationsReducer : Reducer<MacNotificationsState, MacNotificationsEvent> {
    override fun reduce(
        state: MacNotificationsState,
        event: MacNotificationsEvent,
    ): MacNotificationsState =
        when (event) {
            is MacNotificationsEvent.Ready -> state.copy(availability = event.availability)
            is MacNotificationsEvent.Authorized ->
                state.copy(
                    authorization =
                        if (event.error != null) {
                            "error: ${event.error}"
                        } else if (event.granted) {
                            "granted"
                        } else {
                            "denied"
                        },
                )
            is MacNotificationsEvent.SettingsRead -> state.copy(settings = event.settings)
            MacNotificationsEvent.CategoryInstalled -> state.copy(categoryInstalled = true)
            is MacNotificationsEvent.PresentationChanged -> state.copy(presentation = event.options)
            is MacNotificationsEvent.Sending ->
                state.copy(
                    sent = state.sent.plusTracked(Tracked(event.id, event.title, event.at)),
                )
            is MacNotificationsEvent.Added ->
                state.copy(
                    sent =
                        state.sent.update(event.id) {
                            if (event.error == null) {
                                it.copy(platformId = event.id)
                            } else {
                                it.copy(error = event.error, finished = true)
                            }
                        },
                )
            is MacNotificationsEvent.Reported ->
                state.copy(
                    sent = state.sent.record(event.id, event.delivery, event.terminal),
                )
            is MacNotificationsEvent.ListsRead -> state.copy(pending = event.pending, delivered = event.delivered)
        }
}
