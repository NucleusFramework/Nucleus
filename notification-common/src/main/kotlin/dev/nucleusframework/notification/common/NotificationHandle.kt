package dev.nucleusframework.notification.common

import dev.nucleusframework.notification.common.internal.PlatformDispatcher

/**
 * Opaque handle to a sent notification.
 *
 * Use [dismiss] to programmatically close the notification.
 */
public class NotificationHandle internal constructor(
    internal val platformId: String,
    private val dispatcher: PlatformDispatcher?,
) {
    /** Dismisses the notification if it is still visible. */
    public fun dismiss() {
        dispatcher?.dismiss(platformId)
    }

    override fun toString(): String = "NotificationHandle($platformId)"
}
