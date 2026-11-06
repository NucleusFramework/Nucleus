package dev.nucleusframework.lab.probes.notifications.windows

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.probes.notifications.NotificationCallback
import dev.nucleusframework.notification.windows.DismissalReason
import dev.nucleusframework.notification.windows.HistoryEntry
import dev.nucleusframework.notification.windows.ToastContent
import dev.nucleusframework.notification.windows.ToastNotificationData
import dev.nucleusframework.notification.windows.ToastNotificationListener
import dev.nucleusframework.notification.windows.WindowsNotificationCenter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Port over `notification-windows` (WinRT ToastNotificationManager). */
interface WindowsToastGateway {
    fun availability(): Availability

    /** AUMID the toasts are attributed to, and how its Start-menu shortcut is handled. */
    fun identity(): String

    fun initialize(): Boolean

    suspend fun show(
        content: ToastContent,
        tag: String,
        group: String,
        initialData: ToastNotificationData?,
    ): String?

    suspend fun update(
        tag: String,
        group: String,
        data: ToastNotificationData,
    ): String?

    fun remove(
        tag: String,
        group: String,
    )

    fun clearAll()

    suspend fun history(): Pair<List<HistoryEntry>, String?>

    val callbacks: SharedFlow<Stamped<NotificationCallback<String>>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusWindowsToastGateway : WindowsToastGateway {
    private val callbackFlow = MutableSharedFlow<Stamped<NotificationCallback<String>>>(extraBufferCapacity = 64)
    override val callbacks: SharedFlow<Stamped<NotificationCallback<String>>> = callbackFlow.asSharedFlow()
    private var listening = false

    override fun availability(): Availability =
        Availability.of(WindowsNotificationCenter.isAvailable) { "nucleus_notification_windows not loaded" }

    override fun identity(): String =
        buildString {
            append("AUMID ${NucleusApp.aumid}")
            append(
                when {
                    ExecutableRuntime.isAppX() -> " · package identity (MSIX)"
                    ExecutableRuntime.isDev() -> " · dev run: no Start-menu shortcut is created (REQUIRE_NO_CREATE)"
                    else -> " · Start-menu shortcut required (REQUIRE_CREATE)"
                },
            )
        }

    override fun initialize(): Boolean {
        val ok = WindowsNotificationCenter.initialize()
        if (ok && !listening) {
            WindowsNotificationCenter.addListener(listener)
            listening = true
        }
        return ok
    }

    override suspend fun show(
        content: ToastContent,
        tag: String,
        group: String,
        initialData: ToastNotificationData?,
    ): String? =
        suspendCancellableCoroutine { cont ->
            WindowsNotificationCenter.show(
                content,
                tag = tag,
                group = group,
                initialData = initialData,
            ) { cont.resume(it) }
        }

    override suspend fun update(
        tag: String,
        group: String,
        data: ToastNotificationData,
    ): String? =
        suspendCancellableCoroutine { cont ->
            WindowsNotificationCenter.update(tag, group, data) { cont.resume(it) }
        }

    override fun remove(
        tag: String,
        group: String,
    ) = WindowsNotificationCenter.remove(tag, group)

    override fun clearAll() = WindowsNotificationCenter.clearAll()

    override suspend fun history(): Pair<List<HistoryEntry>, String?> =
        suspendCancellableCoroutine { cont ->
            WindowsNotificationCenter.getHistory { entries, error ->
                cont.resume(entries to error)
            }
        }

    private val listener =
        object : ToastNotificationListener {
            override fun onActivated(
                tag: String,
                group: String,
                arguments: String,
                userInputs: Map<String, String>,
            ) {
                val inputs = if (userInputs.isEmpty()) "" else " inputs=$userInputs"
                emit(tag, "activated args=“$arguments”$inputs", terminal = false)
            }

            override fun onDismissed(
                tag: String,
                group: String,
                reason: DismissalReason,
            ) = emit(tag, "dismissed: $reason", terminal = reason != DismissalReason.APPLICATION_HIDDEN)

            override fun onFailed(
                tag: String,
                group: String,
                errorCode: Int,
            ) = emit(tag, "failed: HRESULT 0x%08X".format(Locale.ROOT, errorCode), terminal = true)
        }

    private fun emit(
        tag: String,
        what: String,
        terminal: Boolean,
    ) {
        callbackFlow.tryEmit(NotificationCallback(tag, what, terminal).stamped())
    }
}
