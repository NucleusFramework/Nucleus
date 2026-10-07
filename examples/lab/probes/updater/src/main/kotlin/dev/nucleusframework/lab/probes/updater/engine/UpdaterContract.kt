package dev.nucleusframework.lab.probes.updater.engine

import androidx.compose.runtime.Immutable
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateSimulation

enum class SourceKind(
    val label: String,
) {
    Simulation("Simulation"),
    FeedServer("Fault-injecting feed"),
    Directory("Local directory"),
    GitHub("GitHub"),
}

@Immutable
data class SetupForm(
    val kind: SourceKind = SourceKind.Simulation,
    val scenario: UpdateSimulation.Scenario = UpdateSimulation.Scenario.UPDATE_AVAILABLE,
    val justUpdatedFrom: String = "",
    val directory: String = "",
    val gitHubRepo: String = "NucleusFramework/Nucleus",
    val currentVersion: String = "",
    val channel: String = "latest",
    val allowPrerelease: Boolean = false,
    val differential: Boolean = true,
)

/** The result of the last check, flattened for display. */
@Immutable
sealed interface CheckOutcome {
    data class Available(
        val info: UpdateInfo,
        val level: String,
    ) : CheckOutcome

    data object NotAvailable : CheckOutcome

    data class Failed(
        val type: String,
        val message: String,
        val cause: String?,
    ) : CheckOutcome
}

@Immutable
data class DownloadState(
    val startedAt: Long,
    val bytes: Long = 0,
    val total: Long = 0,
    val percent: Double = 0.0,
    val differential: Boolean = false,
    val file: String? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
) {
    val running: Boolean get() = file == null && error == null

    fun bytesPerSecond(now: Long): Long = ((finishedAt ?: now) - startedAt).coerceAtLeast(1).let { bytes * 1000 / it }
}

@Immutable
data class UpdaterState(
    val form: SetupForm = SetupForm(),
    val facts: UpdaterFacts? = null,
    val configError: String? = null,
    val launchEvent: String? = null,
    val consumedEvent: String? = null,
    val checking: Boolean = false,
    val outcome: CheckOutcome? = null,
    val download: DownloadState? = null,
    val armInstall: Boolean = false,
    val install: String? = null,
    val pendingRestart: String? = null,
)

sealed interface UpdaterIntent {
    data class Edit(
        val form: SetupForm,
    ) : UpdaterIntent

    data object Apply : UpdaterIntent

    data object Check : UpdaterIntent

    data object Download : UpdaterIntent

    data class ArmInstall(
        val armed: Boolean,
    ) : UpdaterIntent

    data object Install : UpdaterIntent

    data object ConsumeEvent : UpdaterIntent
}

sealed interface UpdaterEvent {
    data class Edited(
        val form: SetupForm,
    ) : UpdaterEvent

    data class Configured(
        val facts: UpdaterFacts,
    ) : UpdaterEvent

    data class ConfigFailed(
        val reason: String,
    ) : UpdaterEvent

    data class LaunchEventRead(
        val description: String,
    ) : UpdaterEvent

    data class EventConsumed(
        val description: String,
    ) : UpdaterEvent

    data object CheckStarted : UpdaterEvent

    data class Checked(
        val outcome: CheckOutcome,
    ) : UpdaterEvent

    data class DownloadStarted(
        val at: Long,
    ) : UpdaterEvent

    data class Progressed(
        val bytes: Long,
        val total: Long,
        val percent: Double,
        val differential: Boolean,
    ) : UpdaterEvent

    data class Downloaded(
        val file: String,
        val at: Long,
    ) : UpdaterEvent

    data class DownloadFailed(
        val reason: String,
        val at: Long,
    ) : UpdaterEvent

    data class Armed(
        val armed: Boolean,
    ) : UpdaterEvent

    data class InstallReported(
        val description: String,
    ) : UpdaterEvent

    data class PendingRestart(
        val version: String?,
    ) : UpdaterEvent
}
