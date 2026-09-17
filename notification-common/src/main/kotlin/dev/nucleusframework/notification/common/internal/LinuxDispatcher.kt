package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.notification.common.DismissReason
import dev.nucleusframework.notification.common.Notification
import dev.nucleusframework.notification.common.NotificationHandle
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.linux.CloseReason
import dev.nucleusframework.notification.linux.LinuxNotificationCenter
import dev.nucleusframework.notification.linux.LinuxNotificationListener
import dev.nucleusframework.notification.linux.NotificationAction
import dev.nucleusframework.notification.linux.NotificationHints
import java.util.logging.Level
import java.util.logging.Logger
import dev.nucleusframework.notification.linux.Notification as LinuxNotification

internal class LinuxDispatcher private constructor() : PlatformDispatcher {
    private val logger = Logger.getLogger(LinuxDispatcher::class.java.name)

    @Volatile
    private var listenerRegistered = false

    private val listener =
        object : LinuxNotificationListener {
            override fun onActionInvoked(
                notificationId: Int,
                actionKey: String,
            ) {
                val id = notificationId.toString()
                val callbacks = CallbackRegistry.get(id) ?: return
                try {
                    if (actionKey == NotificationAction.DEFAULT_KEY) {
                        callbacks.onActivated?.invoke()
                    } else {
                        callbacks.buttonCallbacks[actionKey]?.invoke()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification callback", e)
                }
            }

            override fun onClosed(
                notificationId: Int,
                reason: CloseReason,
            ) {
                val id = notificationId.toString()
                val callbacks = CallbackRegistry.remove(id) ?: return
                try {
                    callbacks.onDismissed?.invoke(reason.toCommon())
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification dismiss callback", e)
                }
            }
        }

    companion object {
        fun createIfAvailable(): LinuxDispatcher? =
            try {
                Class.forName("dev.nucleusframework.notification.linux.LinuxNotificationCenter")
                LinuxDispatcher()
            } catch (_: ClassNotFoundException) {
                null
            }
    }

    override fun isAvailable(): Boolean = LinuxNotificationCenter.isAvailable

    override fun initialize() {
        // Linux does not require explicit initialization
    }

    override fun send(notification: Notification): NotificationResult {
        ensureListenerRegistered()

        val actions = mutableListOf<NotificationAction>()
        // Add default action so body clicks trigger onActivated
        if (notification.onActivated != null) {
            actions.add(NotificationAction(key = NotificationAction.DEFAULT_KEY, label = ""))
        }
        // Add button actions
        val buttonCallbacks = mutableMapOf<String, () -> Unit>()
        notification.buttons.forEachIndexed { index, button ->
            val key = "btn_$index"
            actions.add(NotificationAction(key = key, label = button.title))
            buttonCallbacks[key] = button.onClick
        }

        val opts = notification.linux
        val hints =
            NotificationHints(
                urgency = opts?.urgency,
                category = opts?.category,
                imagePath = notification.largeImage?.let { FreedesktopIcon.Custom(it) },
                resident = opts?.resident,
                transient = opts?.transient,
            )

        val linuxNotification =
            LinuxNotification(
                summary = notification.title,
                body = notification.message,
                appIcon = notification.smallIcon?.let { FreedesktopIcon.Custom(it) },
                actions = actions,
                hints = hints,
                expireTimeout = opts?.expireTimeout ?: SERVER_DEFAULT_EXPIRE_TIMEOUT,
            )

        val id = LinuxNotificationCenter.notify(linuxNotification)
        if (id == 0) {
            notification.onFailed?.invoke()
            return NotificationResult.Failure("Linux notification server returned 0")
        }

        val platformId = id.toString()
        CallbackRegistry.register(
            platformId,
            NotificationCallbacks(
                onActivated = notification.onActivated,
                onDismissed = notification.onDismissed,
                onFailed = notification.onFailed,
                buttonCallbacks = buttonCallbacks,
            ),
        )

        return NotificationResult.Success(NotificationHandle(platformId, this))
    }

    override fun dismiss(platformId: String) {
        val id = platformId.toIntOrNull() ?: return
        LinuxNotificationCenter.closeNotification(id)
    }

    private fun ensureListenerRegistered() {
        if (!listenerRegistered) {
            synchronized(this) {
                if (!listenerRegistered) {
                    LinuxNotificationCenter.addListener(listener)
                    listenerRegistered = true
                }
            }
        }
    }
}

/** freedesktop `expire_timeout` sentinel meaning "let the server decide". */
private const val SERVER_DEFAULT_EXPIRE_TIMEOUT = -1

private fun CloseReason.toCommon(): DismissReason =
    when (this) {
        CloseReason.DISMISSED -> DismissReason.USER_DISMISSED
        CloseReason.EXPIRED -> DismissReason.TIMED_OUT
        CloseReason.CLOSED -> DismissReason.APPLICATION
        CloseReason.UNDEFINED -> DismissReason.UNKNOWN
    }
