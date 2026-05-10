package dev.nucleusframework.nucleus.notification.common.internal

import dev.nucleusframework.nucleus.notification.common.Notification
import dev.nucleusframework.nucleus.notification.common.NotificationResult

internal interface PlatformDispatcher {
    fun isAvailable(): Boolean

    fun initialize()

    fun send(notification: Notification): NotificationResult

    fun dismiss(platformId: String)
}
