package dev.nucleusframework.nucleus.notification.common

import dev.nucleusframework.nucleus.notification.common.internal.PlatformDispatcher

/**
 * Opaque handle to a sent notification.
 *
 * Use [dismiss] to programmatically close the notification.
 */
class NotificationHandle internal constructor(
    internal val platformId: String,
    private val dispatcher: PlatformDispatcher?,
) {
    /** Dismisses the notification if it is still visible. */
    fun dismiss() {
        dispatcher?.dismiss(platformId)
    }

    override fun toString(): String = "NotificationHandle($platformId)"
}
