package dev.nucleusframework.notification.common

import dev.nucleusframework.notification.common.internal.DispatcherFactory
import dev.nucleusframework.notification.common.internal.PlatformDispatcher
import java.util.logging.Logger

/**
 * Cross-platform notification manager.
 *
 * Automatically detects the current platform and delegates to the appropriate
 * notification backend (Linux, Windows, or macOS).
 *
 * The corresponding platform module must be on the runtime classpath:
 * - `nucleus.notification-linux` on Linux
 * - `nucleus.notification-windows` on Windows
 * - `nucleus.notification-macos` on macOS
 */
public object NotificationManager {
    private val logger = Logger.getLogger(NotificationManager::class.java.simpleName)
    private val dispatcher: PlatformDispatcher? by lazy {
        DispatcherFactory.create().also {
            if (it == null) logger.fine("No notification dispatcher available for this platform")
        }
    }

    /** Whether the notification system is available on the current platform. */
    public fun isAvailable(): Boolean = dispatcher?.isAvailable() == true

    /**
     * Eagerly initializes the notification subsystem.
     *
     * On Windows this calls `WindowsNotificationCenter.initialize()`.
     * On other platforms this is a no-op. Initialization also happens
     * lazily on first [send] if not called explicitly.
     */
    public fun initialize() {
        dispatcher?.initialize()
    }

    /** Sends a notification and returns a [NotificationResult]. */
    public fun send(notification: Notification): NotificationResult {
        val d =
            dispatcher
                ?: return NotificationResult.Failure("No notification support on this platform")
        if (!d.isAvailable()) {
            return NotificationResult.Failure("Notifications not available")
        }
        return d.send(notification)
    }
}
