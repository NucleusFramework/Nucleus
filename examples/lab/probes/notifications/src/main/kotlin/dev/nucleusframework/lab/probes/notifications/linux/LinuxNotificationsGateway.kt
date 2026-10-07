package dev.nucleusframework.lab.probes.notifications.linux

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.probes.notifications.NotificationCallback
import dev.nucleusframework.notification.linux.CloseReason
import dev.nucleusframework.notification.linux.LinuxNotificationCenter
import dev.nucleusframework.notification.linux.LinuxNotificationListener
import dev.nucleusframework.notification.linux.Notification
import dev.nucleusframework.notification.linux.ServerInformation
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Port over `notification-linux` (org.freedesktop.Notifications over D-Bus). */
interface LinuxNotificationsGateway {
    fun availability(): Availability

    fun serverInformation(): ServerInformation?

    fun capabilities(): List<String>

    /** The server's id, or 0 when the call failed. */
    fun notify(notification: Notification): Int

    fun close(id: Int)

    val signals: SharedFlow<Stamped<NotificationCallback<Int>>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusLinuxNotificationsGateway : LinuxNotificationsGateway {
    private val signalFlow = MutableSharedFlow<Stamped<NotificationCallback<Int>>>(extraBufferCapacity = 64)
    override val signals: SharedFlow<Stamped<NotificationCallback<Int>>> = signalFlow.asSharedFlow()
    private var listening = false

    override fun availability(): Availability {
        if (!LinuxNotificationCenter.isAvailable) {
            return Availability.Unavailable("libnotify bridge not loaded or no session bus")
        }
        if (!listening) {
            LinuxNotificationCenter.addListener(listener)
            listening = true
        }
        return Availability.Available
    }

    override fun serverInformation(): ServerInformation? = LinuxNotificationCenter.getServerInformation()

    override fun capabilities(): List<String> = LinuxNotificationCenter.getCapabilities()

    override fun notify(notification: Notification): Int = LinuxNotificationCenter.notify(notification)

    override fun close(id: Int) = LinuxNotificationCenter.closeNotification(id)

    private val listener =
        object : LinuxNotificationListener {
            override fun onClosed(
                notificationId: Int,
                reason: CloseReason,
            ) = emit(NotificationCallback(notificationId, "closed: $reason", terminal = true))

            override fun onActionInvoked(
                notificationId: Int,
                actionKey: String,
            ) = emit(NotificationCallback(notificationId, "action “$actionKey”", terminal = false))

            override fun onActivationToken(
                notificationId: Int,
                token: String,
            ) = emit(
                NotificationCallback(
                    notificationId,
                    "activation token ${token.take(TOKEN_PREVIEW)}…",
                    terminal = false,
                ),
            )
        }

    private fun emit(signal: NotificationCallback<Int>) {
        signalFlow.tryEmit(signal.stamped())
    }

    private companion object {
        const val TOKEN_PREVIEW = 12
    }
}
