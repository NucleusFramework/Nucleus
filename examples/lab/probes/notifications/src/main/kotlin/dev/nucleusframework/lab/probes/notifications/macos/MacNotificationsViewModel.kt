package dev.nucleusframework.lab.probes.notifications.macos

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.notification.NotificationAction
import dev.nucleusframework.notification.NotificationContent
import dev.nucleusframework.notification.NotificationRequest
import dev.nucleusframework.notification.NotificationSound
import dev.nucleusframework.notification.NotificationTrigger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import java.util.concurrent.atomic.AtomicInteger

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class MacNotificationsViewModel(
    private val gateway: MacNotificationsGateway,
    timeline: Timeline,
) : MviViewModel<MacNotificationsState, MacNotificationsIntent, MacNotificationsEvent, Nothing>(
        MacNotificationsState(),
        MacNotificationsReducer,
        timeline,
        MacNotificationsProbe.ID,
    ) {
    private val counter = AtomicInteger()

    init {
        val availability = gateway.availability()
        dispatch(MacNotificationsEvent.Ready(availability))
        if (availability.isAvailable) {
            gateway.installCategory()
            dispatch(MacNotificationsEvent.CategoryInstalled)
            gateway.claimDelegate { state.value.presentation }
            launch { dispatch(MacNotificationsEvent.SettingsRead(gateway.settings())) }
        }
        // UNUserNotificationCenter makes no thread promise: the thread is shown, not judged.
        launch {
            gateway.delegateEvents.collect { stamped ->
                when (val event = stamped.value) {
                    is MacDelegateEvent.WillPresent ->
                        report(
                            stamped,
                            event.notification.identifier,
                            "willPresent → ${event.answered.joinToString { it.name }}",
                            terminal = false,
                        )
                    is MacDelegateEvent.DidReceive -> {
                        val response = event.response
                        val (what, terminal) =
                            when (response.actionIdentifier) {
                                NotificationAction.DEFAULT_ACTION_IDENTIFIER -> "activated (body clicked)" to false
                                NotificationAction.DISMISS_ACTION_IDENTIFIER -> "dismissed" to true
                                else ->
                                    "action “${response.actionIdentifier}”" +
                                        (response.userText?.let { " text=“$it”" } ?: "") to
                                        false
                            }
                        report(stamped, response.notification.identifier, what, terminal)
                    }
                }
            }
        }
    }

    override suspend fun handle(intent: MacNotificationsIntent) {
        when (intent) {
            is MacNotificationsIntent.RequestAuthorization -> {
                val (granted, error) = gateway.requestAuthorization(intent.options)
                dispatch(
                    MacNotificationsEvent.Authorized(granted, error),
                    if (error != null) Severity.Error else Severity.Info,
                )
                dispatch(MacNotificationsEvent.SettingsRead(gateway.settings()))
            }
            MacNotificationsIntent.RefreshSettings -> dispatch(MacNotificationsEvent.SettingsRead(gateway.settings()))
            is MacNotificationsIntent.SetPresentation ->
                dispatch(MacNotificationsEvent.PresentationChanged(intent.options))
            is MacNotificationsIntent.Send -> send(intent.draft)
            MacNotificationsIntent.RefreshLists -> refreshLists()
            MacNotificationsIntent.RemoveAll -> {
                gateway.removeAllPending()
                gateway.removeAllDelivered()
                refreshLists()
            }
        }
    }

    private suspend fun send(draft: MacDraft) {
        // The common DSL claims the delegate when it sends; take it back so responses land here.
        gateway.claimDelegate { state.value.presentation }
        val id = "lab-mac-${counter.incrementAndGet()}"
        dispatch(MacNotificationsEvent.Sending(id, draft.title, System.currentTimeMillis()))
        val request =
            NotificationRequest(
                identifier = id,
                content =
                    NotificationContent(
                        title = draft.title,
                        subtitle = draft.subtitle,
                        body = draft.body,
                        badge = draft.badge,
                        sound =
                            when (draft.sound) {
                                SoundChoice.None -> null
                                SoundChoice.Default -> NotificationSound.Default
                                SoundChoice.Critical -> NotificationSound.DefaultCritical
                            },
                        threadIdentifier = draft.threadId,
                        categoryIdentifier = if (draft.withActions) LAB_CATEGORY else "",
                        interruptionLevel = draft.interruption,
                    ),
                trigger =
                    if (draft.delaySeconds >
                        0
                    ) {
                        NotificationTrigger.TimeInterval(draft.delaySeconds.toDouble())
                    } else {
                        null
                    },
            )
        val error = gateway.add(request)
        dispatch(MacNotificationsEvent.Added(id, error), if (error != null) Severity.Error else Severity.Info)
        refreshLists()
    }

    private suspend fun refreshLists() {
        val pending = gateway.pending().map { "${it.identifier} · ${it.title} · in ${it.triggerInterval.toInt()} s" }
        val delivered = gateway.delivered().map { "${it.identifier} · ${it.title}" }
        dispatch(MacNotificationsEvent.ListsRead(pending, delivered))
    }

    private fun report(
        origin: Stamped<MacDelegateEvent>,
        id: String,
        what: String,
        terminal: Boolean,
    ) {
        val delivery = origin.map { what }.toDelivery()
        dispatch(origin.map { MacNotificationsEvent.Reported(id, delivery, terminal) })
    }
}
