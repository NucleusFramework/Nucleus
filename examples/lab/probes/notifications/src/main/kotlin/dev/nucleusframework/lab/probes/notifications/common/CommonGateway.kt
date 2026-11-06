package dev.nucleusframework.lab.probes.notifications.common

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.probes.notifications.NotificationCallback
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.common.NotificationHandle
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/** Everything the common DSL lets the app say, platform blocks included. */
@Immutable
data class CommonDraft(
    val title: String = "Nucleus Lab",
    val message: String = "Hello from the common DSL",
    val buttons: List<String> = listOf("Accept", "Later"),
    val linuxUrgency: Urgency? = null,
    val macSubtitle: String = "",
    val macInterruption: InterruptionLevel? = null,
    val windowsScenario: ToastScenario? = null,
    val windowsDuration: ToastDuration? = null,
)

/** Port over `notification-common`. */
interface CommonGateway {
    fun availability(): Availability

    /** Returns the platform id on success, or the failure reason. */
    fun send(
        key: String,
        draft: CommonDraft,
    ): Result<String>

    fun dismiss(key: String): Boolean

    /** DSL callbacks, stamped where they ran: the DSL promises the UI thread on every platform (#310). */
    val callbacks: SharedFlow<Stamped<NotificationCallback<String>>>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NucleusCommonGateway : CommonGateway {
    private val callbackFlow = MutableSharedFlow<Stamped<NotificationCallback<String>>>(extraBufferCapacity = 64)
    override val callbacks: SharedFlow<Stamped<NotificationCallback<String>>> = callbackFlow.asSharedFlow()
    private val handles = ConcurrentHashMap<String, NotificationHandle>()

    override fun availability(): Availability {
        NotificationManager.initialize()
        return Availability.of(NotificationManager.isAvailable()) {
            when (Platform.Current) {
                Platform.MacOS -> Availability.NEEDS_APP_BUNDLE
                Platform.Windows -> "no dispatcher: check the AUMID / Start-menu shortcut"
                else -> "no dispatcher: no notification server on the session bus"
            }
        }
    }

    override fun send(
        key: String,
        draft: CommonDraft,
    ): Result<String> {
        fun emit(
            what: String,
            terminal: Boolean = false,
        ) = callbackFlow.tryEmit(NotificationCallback(key, what, terminal).stamped())

        val built =
            notification(
                title = draft.title,
                message = draft.message,
                onActivated = { emit("activated (body clicked)") },
                onDismissed = { reason -> emit("dismissed: $reason", terminal = true) },
                onFailed = { emit("failed", terminal = true) },
            ) {
                draft.buttons.forEach { label -> button(label) { emit("button “$label”") } }
                linux { urgency = draft.linuxUrgency }
                macos {
                    subtitle = draft.macSubtitle.ifBlank { null }
                    interruptionLevel = draft.macInterruption
                }
                windows {
                    scenario = draft.windowsScenario
                    duration = draft.windowsDuration
                }
            }
        return when (val result = built.send()) {
            is NotificationResult.Success -> {
                handles[key] = result.handle
                // The platform id is internal; the handle prints it.
                Result.success(result.handle.toString())
            }
            is NotificationResult.Failure -> Result.failure(IllegalStateException(result.reason))
        }
    }

    override fun dismiss(key: String): Boolean {
        val handle = handles.remove(key) ?: return false
        handle.dismiss()
        return true
    }
}
