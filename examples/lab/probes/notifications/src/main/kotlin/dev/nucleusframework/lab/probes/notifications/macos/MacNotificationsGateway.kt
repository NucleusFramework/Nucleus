package dev.nucleusframework.lab.probes.notifications.macos

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.notification.ActionOption
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.CategoryOption
import dev.nucleusframework.notification.DeliveredNotification
import dev.nucleusframework.notification.NotificationAction
import dev.nucleusframework.notification.NotificationCategory
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.NotificationCenterDelegate
import dev.nucleusframework.notification.NotificationRequest
import dev.nucleusframework.notification.NotificationResponse
import dev.nucleusframework.notification.NotificationSettings
import dev.nucleusframework.notification.PendingNotificationInfo
import dev.nucleusframework.notification.PresentationOption
import dev.nucleusframework.notification.TextInputNotificationAction
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val LAB_CATEGORY = "lab.actions"

/** A UNUserNotificationCenter delegate callback. */
sealed interface MacDelegateEvent {
    data class WillPresent(
        val notification: DeliveredNotification,
        val answered: Set<PresentationOption>,
    ) : MacDelegateEvent

    data class DidReceive(
        val response: NotificationResponse,
    ) : MacDelegateEvent
}

/** Port over `notification-macos` (UNUserNotificationCenter). */
interface MacNotificationsGateway {
    fun availability(): Availability

    suspend fun requestAuthorization(options: Set<AuthorizationOption>): Pair<Boolean, String?>

    suspend fun settings(): NotificationSettings

    fun installCategory()

    /** Becomes the process-wide delegate; the common DSL takes it back on its next send. */
    fun claimDelegate(presentation: () -> Set<PresentationOption>)

    suspend fun add(request: NotificationRequest): String?

    suspend fun pending(): List<PendingNotificationInfo>

    suspend fun delivered(): List<DeliveredNotification>

    fun removeAllDelivered()

    fun removeAllPending()

    /** Delegate callbacks with the thread they ran on; the API promises none, so none is judged. */
    val delegateEvents: SharedFlow<Stamped<MacDelegateEvent>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusMacNotificationsGateway : MacNotificationsGateway {
    private val events = MutableSharedFlow<Stamped<MacDelegateEvent>>(extraBufferCapacity = 64)
    override val delegateEvents: SharedFlow<Stamped<MacDelegateEvent>> = events.asSharedFlow()

    override fun availability(): Availability =
        Availability.of(NotificationCenter.isAvailable) { Availability.NEEDS_APP_BUNDLE }

    override suspend fun requestAuthorization(options: Set<AuthorizationOption>): Pair<Boolean, String?> =
        suspendCancellableCoroutine { cont ->
            NotificationCenter.requestAuthorization(options) { granted, error ->
                cont.resume(granted to error)
            }
        }

    override suspend fun settings(): NotificationSettings =
        suspendCancellableCoroutine { cont -> NotificationCenter.getNotificationSettings { cont.resume(it) } }

    override fun installCategory() {
        NotificationCenter.setNotificationCategories(
            setOf(
                NotificationCategory(
                    identifier = LAB_CATEGORY,
                    actions =
                        listOf(
                            TextInputNotificationAction(
                                "reply",
                                "Reply",
                                textInputButtonTitle = "Send",
                                textInputPlaceholder = "Type a reply",
                            ),
                            NotificationAction("open", "Open", setOf(ActionOption.FOREGROUND)),
                            NotificationAction("delete", "Delete", setOf(ActionOption.DESTRUCTIVE)),
                        ),
                    // Without it, closing a notification reports nothing.
                    options = setOf(CategoryOption.CUSTOM_DISMISS_ACTION),
                ),
            ),
        )
    }

    override fun claimDelegate(presentation: () -> Set<PresentationOption>) {
        NotificationCenter.setDelegate(
            object : NotificationCenterDelegate {
                override fun willPresent(notification: DeliveredNotification): Set<PresentationOption> {
                    val answer = presentation()
                    emit(MacDelegateEvent.WillPresent(notification, answer))
                    return answer
                }

                override fun didReceive(response: NotificationResponse) {
                    emit(MacDelegateEvent.DidReceive(response))
                }
            },
        )
    }

    override suspend fun add(request: NotificationRequest): String? =
        suspendCancellableCoroutine { cont -> NotificationCenter.add(request) { error -> cont.resume(error) } }

    override suspend fun pending(): List<PendingNotificationInfo> =
        suspendCancellableCoroutine { cont -> NotificationCenter.getPendingNotifications { cont.resume(it) } }

    override suspend fun delivered(): List<DeliveredNotification> =
        suspendCancellableCoroutine { cont -> NotificationCenter.getDeliveredNotifications { cont.resume(it) } }

    override fun removeAllDelivered() = NotificationCenter.removeAllDeliveredNotifications()

    override fun removeAllPending() = NotificationCenter.removeAllPendingNotifications()

    private fun emit(event: MacDelegateEvent) {
        events.tryEmit(Stamped(event, Thread.currentThread().name, onUiThread = null))
    }
}
