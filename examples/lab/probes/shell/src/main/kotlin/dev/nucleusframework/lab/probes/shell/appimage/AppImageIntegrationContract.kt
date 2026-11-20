package dev.nucleusframework.lab.probes.shell.appimage

import androidx.compose.runtime.Immutable
import dev.nucleusframework.core.runtime.AppImageIntegrationResult
import dev.nucleusframework.core.runtime.AppImageIntegrationStatus
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall

@Immutable
data class AppImageIntegrationState(
    val status: AppImageIntegrationStatus? = null,
    val calls: List<CallRecord> = emptyList(),
)

sealed interface AppImageIntegrationIntent {
    data object Refresh : AppImageIntegrationIntent

    data object Integrate : AppImageIntegrationIntent
}

sealed interface AppImageIntegrationEvent {
    data class StatusRead(
        val status: AppImageIntegrationStatus,
    ) : AppImageIntegrationEvent

    data class IntegrateCalled(
        val result: AppImageIntegrationResult,
        val status: AppImageIntegrationStatus,
    ) : AppImageIntegrationEvent
}

object AppImageIntegrationReducer : Reducer<AppImageIntegrationState, AppImageIntegrationEvent> {
    override fun reduce(
        state: AppImageIntegrationState,
        event: AppImageIntegrationEvent,
    ): AppImageIntegrationState =
        when (event) {
            is AppImageIntegrationEvent.StatusRead ->
                state.copy(status = event.status, calls = state.calls.plusCall("status()", CallOutcome.Ok))
            is AppImageIntegrationEvent.IntegrateCalled ->
                state.copy(status = event.status, calls = state.calls.plusCall("integrate()", event.result.toOutcome()))
        }
}

fun AppImageIntegrationResult.toOutcome(): CallOutcome =
    when (this) {
        is AppImageIntegrationResult.Integrated -> CallOutcome.Ok
        is AppImageIntegrationResult.Failed ->
            CallOutcome(
                false,
                listOfNotNull(reason, cause?.message).joinToString(": "),
            )
    }

/** One line for the status readout. */
fun AppImageIntegrationStatus.label(): String =
    when (this) {
        AppImageIntegrationStatus.NotAppImage -> "not an AppImage"
        AppImageIntegrationStatus.NotIntegrated -> "not integrated"
        is AppImageIntegrationStatus.Integrated -> "integrated"
        is AppImageIntegrationStatus.Stale -> "stale (image moved or updated)"
        is AppImageIntegrationStatus.ExternallyManaged -> "managed elsewhere"
    }

/** The entry the status is about, when there is one. */
val AppImageIntegrationStatus.desktopFile: String?
    get() =
        when (this) {
            is AppImageIntegrationStatus.Integrated -> desktopFile.path
            is AppImageIntegrationStatus.Stale -> desktopFile.path
            is AppImageIntegrationStatus.ExternallyManaged -> desktopFile.path
            else -> null
        }

/** Integration may proceed: nothing is installed yet, or the Lab's own entry is out of date. */
val AppImageIntegrationStatus.canIntegrate: Boolean
    get() = this == AppImageIntegrationStatus.NotIntegrated || this is AppImageIntegrationStatus.Stale
