package dev.nucleusframework.lab.probes.notifications.linux

import androidx.lifecycle.ViewModel
import dev.nucleusframework.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.notification.linux.Notification
import dev.nucleusframework.notification.linux.NotificationAction
import dev.nucleusframework.notification.linux.NotificationHints
import dev.nucleusframework.notification.linux.NotificationSound
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class LinuxNotificationsViewModel(
    private val gateway: LinuxNotificationsGateway,
    timeline: Timeline,
) : MviViewModel<LinuxNotificationsState, LinuxNotificationsIntent, LinuxNotificationsEvent, Nothing>(
        LinuxNotificationsState(),
        LinuxNotificationsReducer,
        timeline,
        LinuxNotificationsProbe.ID,
    ) {
    init {
        val availability = gateway.availability()
        dispatch(LinuxNotificationsEvent.Ready(availability))
        if (availability.isAvailable) readServer()
        launch {
            gateway.signals.collect { stamped ->
                val signal = stamped.value
                val delivery = stamped.map { it.what }.toDelivery()
                dispatch(stamped.map { LinuxNotificationsEvent.Signal(signal.key, delivery, signal.terminal) })
            }
        }
    }

    override suspend fun handle(intent: LinuxNotificationsIntent) {
        when (intent) {
            LinuxNotificationsIntent.ReadServer -> readServer()
            is LinuxNotificationsIntent.Send -> send(intent.draft)
            is LinuxNotificationsIntent.Close -> gateway.close(intent.id)
        }
    }

    private fun readServer() {
        val info = gateway.serverInformation()
        dispatch(
            LinuxNotificationsEvent.ServerRead(
                info?.let { "${it.name} ${it.version} by ${it.vendor} · spec ${it.specVersion}" },
                gateway.capabilities().sorted(),
            ),
            if (info == null) Severity.Error else Severity.Info,
        )
    }

    private fun send(draft: LinuxDraft) {
        val replaces = if (draft.replaceLast) state.value.lastOpenId else null
        val notification =
            Notification(
                appName = "Nucleus Lab",
                replacesId = replaces ?: 0,
                appIcon = if (draft.icon) FreedesktopIcon.Status.DIALOG_INFORMATION else null,
                summary = draft.summary,
                body = draft.body,
                actions =
                    if (draft.actions) {
                        listOf(
                            // The "default" key is what a click on the body invokes.
                            NotificationAction(NotificationAction.DEFAULT_KEY, "Open"),
                            NotificationAction("reply", "Reply"),
                            NotificationAction("mute", "Mute"),
                        )
                    } else {
                        emptyList()
                    },
                hints =
                    NotificationHints(
                        urgency = draft.urgency,
                        category = draft.category.ifBlank { null },
                        soundName = if (draft.sound) NotificationSound.Notification.MESSAGE_NEW_INSTANT else null,
                        resident = draft.resident.takeIf { it },
                        transient = draft.transient.takeIf { it },
                    ),
                expireTimeout = draft.expireMs,
            )
        val id = gateway.notify(notification)
        dispatch(
            LinuxNotificationsEvent.Notified(draft.summary, id, replaces, System.currentTimeMillis()),
            if (id == 0) Severity.Error else Severity.Info,
        )
    }
}
