package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.notification.common.DismissReason
import dev.nucleusframework.notification.common.Notification
import dev.nucleusframework.notification.common.NotificationHandle
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.NotificationUrgency
import dev.nucleusframework.notification.windows.DismissalReason
import dev.nucleusframework.notification.windows.ToastNotificationListener
import dev.nucleusframework.notification.windows.ToastNotificationPriority
import dev.nucleusframework.notification.windows.ToastScenario
import dev.nucleusframework.notification.windows.WindowsNotificationCenter
import dev.nucleusframework.notification.windows.toast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

private const val TAG_MAX_LENGTH = 16

internal class WindowsDispatcher private constructor() : PlatformDispatcher {
    private val logger = Logger.getLogger(WindowsDispatcher::class.java.simpleName)
    private val initialized = AtomicBoolean(false)
    private val tagCounter = AtomicLong(0)

    @Volatile
    private var listenerRegistered = false

    private val listener =
        object : ToastNotificationListener {
            override fun onActivated(
                tag: String,
                group: String,
                arguments: String,
                userInputs: Map<String, String>,
            ) {
                val platformId = toPlatformId(tag, group)
                val callbacks = CallbackRegistry.get(platformId) ?: return
                try {
                    if (arguments.startsWith("btn_")) {
                        callbacks.buttonCallbacks[arguments]?.invoke()
                    } else {
                        callbacks.onActivated?.invoke()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification callback", e)
                }
            }

            override fun onDismissed(
                tag: String,
                group: String,
                reason: DismissalReason,
            ) {
                val platformId = toPlatformId(tag, group)
                val callbacks = CallbackRegistry.remove(platformId) ?: return
                try {
                    callbacks.onDismissed?.invoke(reason.toCommon())
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification dismiss callback", e)
                }
            }

            override fun onFailed(
                tag: String,
                group: String,
                errorCode: Int,
            ) {
                val platformId = toPlatformId(tag, group)
                val callbacks = CallbackRegistry.remove(platformId) ?: return
                try {
                    callbacks.onFailed?.invoke()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification failed callback", e)
                }
            }
        }

    companion object {
        private const val GROUP = "ncm"

        fun createIfAvailable(): WindowsDispatcher? =
            try {
                Class.forName("dev.nucleusframework.notification.windows.WindowsNotificationCenter")
                WindowsDispatcher()
            } catch (_: ClassNotFoundException) {
                null
            }

        private fun toPlatformId(
            tag: String,
            group: String,
        ): String = "$tag:$group"
    }

    override fun isAvailable(): Boolean = WindowsNotificationCenter.isAvailable

    override fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            WindowsNotificationCenter.initialize()
        }
    }

    override fun send(notification: Notification): NotificationResult {
        initialize()
        ensureListenerRegistered()

        val tag = generateTag()
        val platformId = toPlatformId(tag, GROUP)

        // CRITICAL uses the "urgent" scenario: distinct visual treatment that breaks through
        // Focus Assist / Do Not Disturb (Windows 10 build 19041+, ignored on older builds).
        // put_Priority(High) below only affects connected-standby screen wake and Action Center
        // ordering, so the scenario is what makes CRITICAL visibly different on desktop.
        val toastContent =
            toast {
                if (notification.urgency == NotificationUrgency.CRITICAL) {
                    scenario = ToastScenario.URGENT
                }
                visual {
                    text(notification.title)
                    if (notification.message.isNotEmpty()) {
                        text(notification.message)
                    }
                    if (notification.largeImage != null) {
                        heroImage(notification.largeImage)
                    }
                    if (notification.smallIcon != null) {
                        appLogo(notification.smallIcon)
                    }
                }

                if (notification.buttons.isNotEmpty()) {
                    actions {
                        notification.buttons.forEachIndexed { index, button ->
                            button(content = button.title, arguments = "btn_$index")
                        }
                    }
                }
            }

        val buttonCallbacks = mutableMapOf<String, () -> Unit>()
        notification.buttons.forEachIndexed { index, button ->
            buttonCallbacks["btn_$index"] = button.onClick
        }

        CallbackRegistry.register(
            platformId,
            NotificationCallbacks(
                onActivated = notification.onActivated,
                onDismissed = notification.onDismissed,
                onFailed = notification.onFailed,
                buttonCallbacks = buttonCallbacks,
            ),
        )

        var sendError: String? = null
        val priority =
            if (notification.urgency == NotificationUrgency.CRITICAL) {
                ToastNotificationPriority.HIGH
            } else {
                ToastNotificationPriority.DEFAULT
            }

        WindowsNotificationCenter.show(
            content = toastContent,
            tag = tag,
            group = GROUP,
            priority = priority,
        ) { error ->
            if (error != null) {
                sendError = error
                CallbackRegistry.remove(platformId)
                notification.onFailed?.invoke()
            }
        }

        return if (sendError != null) {
            NotificationResult.Failure(sendError!!)
        } else {
            NotificationResult.Success(NotificationHandle(platformId, this))
        }
    }

    override fun dismiss(platformId: String) {
        val parts = platformId.split(":", limit = 2)
        if (parts.size == 2) {
            WindowsNotificationCenter.remove(tag = parts[0], group = parts[1])
        }
    }

    private fun generateTag(): String {
        val counter = tagCounter.incrementAndGet()
        return "n$counter".take(TAG_MAX_LENGTH)
    }

    private fun ensureListenerRegistered() {
        if (!listenerRegistered) {
            synchronized(this) {
                if (!listenerRegistered) {
                    WindowsNotificationCenter.addListener(listener)
                    listenerRegistered = true
                }
            }
        }
    }
}

private fun DismissalReason.toCommon(): DismissReason =
    when (this) {
        DismissalReason.USER_CANCELED -> DismissReason.USER_DISMISSED
        DismissalReason.TIMED_OUT -> DismissReason.TIMED_OUT
        DismissalReason.APPLICATION_HIDDEN -> DismissReason.APPLICATION
    }
