package dev.nucleusframework.nucleus.notification.common.internal

import dev.nucleusframework.nucleus.notification.ActionOption
import dev.nucleusframework.nucleus.notification.CategoryOption
import dev.nucleusframework.nucleus.notification.DeliveredNotification
import dev.nucleusframework.nucleus.notification.NotificationAction
import dev.nucleusframework.nucleus.notification.NotificationAttachment
import dev.nucleusframework.nucleus.notification.NotificationCategory
import dev.nucleusframework.nucleus.notification.NotificationCenter
import dev.nucleusframework.nucleus.notification.NotificationCenterDelegate
import dev.nucleusframework.nucleus.notification.NotificationContent
import dev.nucleusframework.nucleus.notification.NotificationRequest
import dev.nucleusframework.nucleus.notification.NotificationResponse
import dev.nucleusframework.nucleus.notification.PresentationOption
import dev.nucleusframework.nucleus.notification.common.DismissReason
import dev.nucleusframework.nucleus.notification.common.Notification
import dev.nucleusframework.nucleus.notification.common.NotificationHandle
import dev.nucleusframework.nucleus.notification.common.NotificationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        private const val SEND_TIMEOUT_SECONDS = 5L

        fun createIfAvailable(): MacOsDispatcher? =
            try {
                Class.forName("dev.nucleusframework.nucleus.notification.NotificationCenter")
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

        val latch = CountDownLatch(1)
        val errorHolder =
            java.util.concurrent.atomic
                .AtomicReference<String?>(null)
        NotificationCenter.add(request) { error ->
            if (error != null) {
                errorHolder.set(error)
                CallbackRegistry.remove(identifier)
                notification.onFailed?.invoke()
            }
            latch.countDown()
        }

        // Wait for the native completion handler so the process does not exit
        // before macOS has accepted the notification request.
        if (!latch.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            logger.warning("Notification send timed out after $SEND_TIMEOUT_SECONDS s")
        }

        val sendError = errorHolder.get()
        return if (sendError != null) {
            NotificationResult.Failure(sendError)
        } else {
            NotificationResult.Success(NotificationHandle(identifier, this))
        }
    }

    override fun dismiss(platformId: String) {
        NotificationCenter.removeDeliveredNotifications(listOf(platformId))
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
