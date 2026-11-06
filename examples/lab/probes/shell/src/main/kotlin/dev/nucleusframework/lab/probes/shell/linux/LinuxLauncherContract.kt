package dev.nucleusframework.lab.probes.shell.linux

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.CallOutcome
import dev.nucleusframework.lab.core.mvi.CallRecord
import dev.nucleusframework.lab.core.mvi.Delivery
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.plusCall
import dev.nucleusframework.lab.core.mvi.plusDelivery
import dev.nucleusframework.launcher.linux.LauncherProperties

/** The full LauncherEntry property set, always sent whole so the dock never keeps a stale field. */
@Immutable
data class EntryProperties(
    val count: Long = 0,
    val countVisible: Boolean = false,
    val progress: Double = 0.0,
    val progressVisible: Boolean = false,
    val urgent: Boolean = false,
    val updating: Boolean = false,
) {
    fun toLauncher(): LauncherProperties =
        LauncherProperties(
            count = count,
            countVisible = countVisible,
            progress = progress,
            progressVisible = progressVisible,
            urgent = urgent,
            updating = updating,
        )
}

@Immutable
data class LinuxLauncherState(
    val availability: Availability = Availability.Unknown,
    val detectedDesktopFile: String? = null,
    val desktopFile: String = "",
    val queryHandler: CallOutcome? = null,
    /** Last property set the dock accepted. */
    val applied: EntryProperties = EntryProperties(),
    val quicklistActive: Boolean = false,
    val checkableOn: Boolean = false,
    val clicks: List<Delivery> = emptyList(),
    val calls: List<CallRecord> = emptyList(),
)

sealed interface LinuxLauncherIntent {
    data class SetDesktopFile(
        val id: String,
    ) : LinuxLauncherIntent

    data class Apply(
        val properties: EntryProperties,
    ) : LinuxLauncherIntent

    data object Reset : LinuxLauncherIntent

    data object PublishQuicklist : LinuxLauncherIntent

    data object RemoveQuicklist : LinuxLauncherIntent
}

sealed interface LinuxLauncherEvent {
    data class Ready(
        val availability: Availability,
        val detected: String?,
    ) : LinuxLauncherEvent

    data class DesktopFileChanged(
        val id: String,
        val queryHandler: CallOutcome?,
    ) : LinuxLauncherEvent

    data class Applied(
        val properties: EntryProperties,
        val outcome: CallOutcome,
    ) : LinuxLauncherEvent

    data class QuicklistChanged(
        val active: Boolean,
        val outcome: CallOutcome,
    ) : LinuxLauncherEvent

    data class Clicked(
        val id: Int,
        val delivery: Delivery,
    ) : LinuxLauncherEvent
}

object LinuxLauncherReducer : Reducer<LinuxLauncherState, LinuxLauncherEvent> {
    override fun reduce(
        state: LinuxLauncherState,
        event: LinuxLauncherEvent,
    ): LinuxLauncherState =
        when (event) {
            is LinuxLauncherEvent.Ready ->
                state.copy(
                    availability = event.availability,
                    detectedDesktopFile = event.detected,
                    desktopFile = event.detected.orEmpty(),
                )
            is LinuxLauncherEvent.DesktopFileChanged ->
                state.copy(
                    desktopFile = event.id,
                    queryHandler = event.queryHandler,
                    calls =
                        event.queryHandler?.let { state.calls.plusCall("registerQueryHandler(${event.id})", it) }
                            ?: state.calls,
                )
            is LinuxLauncherEvent.Applied ->
                state.copy(
                    applied = if (event.outcome.ok) event.properties else state.applied,
                    calls = state.calls.plusCall("update(${event.properties.summary()})", event.outcome),
                )
            is LinuxLauncherEvent.QuicklistChanged ->
                state.copy(
                    quicklistActive = if (event.outcome.ok) event.active else state.quicklistActive,
                    calls =
                        state.calls.plusCall(
                            if (event.active) "publish quicklist" else "remove quicklist",
                            event.outcome,
                        ),
                )
            is LinuxLauncherEvent.Clicked ->
                state.copy(
                    checkableOn = if (event.id == QuicklistItems.TOGGLE) !state.checkableOn else state.checkableOn,
                    clicks = state.clicks.plusDelivery(event.delivery),
                )
        }
}

fun EntryProperties.summary(): String =
    buildList {
        add("count=${if (countVisible) count.toString() else "hidden"}")
        add("progress=${if (progressVisible) "${(progress * 100).toInt()}%" else "hidden"}")
        if (urgent) add("urgent")
        if (updating) add("updating")
    }.joinToString(" ")
