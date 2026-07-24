package dev.nucleusframework.notification.common.internal

import dev.nucleusframework.notification.ActionOption
import dev.nucleusframework.notification.CategoryOption
import dev.nucleusframework.notification.DeliveredNotification
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.NotificationAction
import dev.nucleusframework.notification.NotificationAttachment
import dev.nucleusframework.notification.NotificationCategory
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.NotificationCenterDelegate
import dev.nucleusframework.notification.NotificationContent
import dev.nucleusframework.notification.NotificationRequest
import dev.nucleusframework.notification.NotificationResponse
import dev.nucleusframework.notification.PresentationOption
import dev.nucleusframework.notification.common.DismissReason
import dev.nucleusframework.notification.common.Notification
import dev.nucleusframework.notification.common.NotificationHandle
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.NotificationUrgency
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

internal class MacOsDispatcher private constructor() : PlatformDispatcher {
    private val logger = Logger.getLogger(MacOsDispatcher::class.java.simpleName)

    @Volatile
    private var delegateRegistered = false

    // Cache category registrations: button-titles-signature -> categoryId
    private val categoryCache = ConcurrentHashMap<String, String>()

    private val delegate =
        object : NotificationCenterDelegate {
            override fun willPresent(notification: DeliveredNotification): Set<PresentationOption> =
                setOf(PresentationOption.BANNER, PresentationOption.SOUND)

            override fun didReceive(response: NotificationResponse) {
                val id = response.notification.identifier
                val actionId = response.actionIdentifier
                val callbacks =
                    when (actionId) {
                        NotificationAction.DISMISS_ACTION_IDENTIFIER -> CallbackRegistry.remove(id)
                        else -> CallbackRegistry.get(id)
                    }
                callbacks ?: return

                try {
                    when {
                        actionId == NotificationAction.DEFAULT_ACTION_IDENTIFIER ->
                            callbacks.onActivated?.invoke()
                        actionId == NotificationAction.DISMISS_ACTION_IDENTIFIER ->
                            callbacks.onDismissed?.invoke(DismissReason.USER_DISMISSED)
                        actionId.startsWith("btn_") ->
                            callbacks.buttonCallbacks[actionId]?.invoke()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                ) {
                    logger.log(Level.WARNING, "Error in notification callback", e)
                }
            }
        }

    companion object {
        fun createIfAvailable(): MacOsDispatcher? =
            try {
                Class.forName("dev.nucleusframework.notification.NotificationCenter")
                MacOsDispatcher()
            } catch (_: ClassNotFoundException) {
                null
            }
    }

    override fun isAvailable(): Boolean = NotificationCenter.isAvailable

    override fun initialize() {
        // macOS does not require explicit initialization beyond delegate
    }

    override fun send(notification: Notification): NotificationResult {
        ensureDelegateRegistered()

        val identifier = UUID.randomUUID().toString()

        // Register category for buttons if needed
        val categoryId =
            if (notification.buttons.isNotEmpty()) {
                registerCategoryForButtons(notification)
            } else {
                // Even without buttons, register a category with CUSTOM_DISMISS_ACTION
                // so onDismissed fires
                if (notification.onDismissed != null) {
                    registerDismissOnlyCategory()
                } else {
                    ""
                }
            }

        val attachments =
            if (notification.largeImage != null) {
                listOf(NotificationAttachment(identifier = "largeImage", url = notification.largeImage))
            } else {
                emptyList()
            }

        val content =
            NotificationContent(
                title = notification.title,
                body = notification.message,
                categoryIdentifier = categoryId,
                attachments = attachments,
                interruptionLevel = notification.urgency.toInterruptionLevel(),
            )

        val request =
            NotificationRequest(
                identifier = identifier,
                content = content,
            )

        val buttonCallbacks = mutableMapOf<String, () -> Unit>()
        notification.buttons.forEachIndexed { index, button ->
            buttonCallbacks["btn_$index"] = button.onClick
        }

        CallbackRegistry.register(
            identifier,
            NotificationCallbacks(
                onActivated = notification.onActivated,
                onDismissed = notification.onDismissed,
                onFailed = notification.onFailed,
                buttonCallbacks = buttonCallbacks,
            ),
        )

        NotificationCenter.add(request) { error ->
            if (error != null) {
                CallbackRegistry.remove(identifier)
                notification.onFailed?.invoke()
            }
        }

        return NotificationResult.Success(NotificationHandle(identifier, this))
    }

    override fun dismiss(platformId: String) {
        NotificationCenter.removeDeliveredNotifications(listOf(platformId))
    }

    // CRITICAL maps to TIME_SENSITIVE, not the OS CRITICAL level: the latter needs Apple's
    // restricted critical-alerts entitlement (granted manually per App ID, and requires an
    // embedded provisioning profile even for non-App-Store apps), so it is not exposed here.
    // interruptionLevel requires macOS 12+ and is ignored on older systems.
    private fun NotificationUrgency.toInterruptionLevel(): InterruptionLevel =
        when (this) {
            NotificationUrgency.LOW -> InterruptionLevel.PASSIVE
            NotificationUrgency.NORMAL -> InterruptionLevel.ACTIVE
            NotificationUrgency.CRITICAL -> InterruptionLevel.TIME_SENSITIVE
        }

    private fun registerCategoryForButtons(notification: Notification): String {
        val signature = notification.buttons.joinToString("|") { it.title }
        return categoryCache.getOrPut(signature) {
            val categoryId = "ncm_${categoryCache.size}"
            val actions =
                notification.buttons.mapIndexed { index, button ->
                    NotificationAction(
                        identifier = "btn_$index",
                        title = button.title,
                        options = setOf(ActionOption.FOREGROUND),
                    )
                }
            val category =
                NotificationCategory(
                    identifier = categoryId,
                    actions = actions,
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                )
            registerAllCategories(category)
            categoryId
        }
    }

    private fun registerDismissOnlyCategory(): String {
        val categoryId = "ncm_dismiss"
        return categoryCache.getOrPut(categoryId) {
            val category =
                NotificationCategory(
                    identifier = categoryId,
                    actions = emptyList(),
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                )
            registerAllCategories(category)
            categoryId
        }
    }

    private fun registerAllCategories(newCategory: NotificationCategory) {
        // Collect all registered categories and add the new one
        val allCategories =
            categoryCache.values
                .map { id ->
                    // We only have the ID here; rebuild from what we know
                    // For simplicity, re-register everything via a fresh set call
                    NotificationCategory(identifier = id, options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION))
                }.toMutableSet()
        allCategories.add(newCategory)
        NotificationCenter.setNotificationCategories(allCategories)
    }

    private fun ensureDelegateRegistered() {
        if (!delegateRegistered) {
            synchronized(this) {
                if (!delegateRegistered) {
                    NotificationCenter.setDelegate(delegate)
                    delegateRegistered = true
                }
            }
        }
    }
}
