package dev.nucleusframework.flatpak.internal

import dev.nucleusframework.flatpak.FlatpakErrorCode
import dev.nucleusframework.flatpak.FlatpakException
import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateState
import dev.nucleusframework.flatpak.FlatpakUpdateStatus

// `status` values of the portal's Progress signal.
private const val STATUS_RUNNING = 0
private const val STATUS_EMPTY = 1
private const val STATUS_DONE = 2
private const val STATUS_ERROR = 3

private const val PERCENT = 100.0

private const val DBUS_ACCESS_DENIED = "org.freedesktop.DBus.Error.AccessDenied"
private const val DBUS_NOT_SUPPORTED = "org.freedesktop.DBus.Error.NotSupported"
private val PORTAL_GONE =
    setOf(
        "org.freedesktop.DBus.Error.ServiceUnknown",
        "org.freedesktop.DBus.Error.NameHasNoOwner",
        "org.freedesktop.DBus.Error.UnknownMethod",
        "org.freedesktop.DBus.Error.UnknownObject",
        "org.freedesktop.DBus.Error.UnknownInterface",
    )

/** What one `UpdateAvailable` signal means for this process. */
internal fun statusOf(
    running: String,
    local: String,
    remote: String,
): FlatpakUpdateStatus =
    when {
        remote.isNotEmpty() && remote != local -> FlatpakUpdateStatus.Available(running, local, remote)
        local.isNotEmpty() && local != running -> FlatpakUpdateStatus.Installed(running, local)
        else -> FlatpakUpdateStatus.UpToDate(running)
    }

/** One `Progress` signal: a report, the last one when its state is not `Installing`, or a failure. */
internal sealed interface ProgressStep {
    data class Report(
        val progress: FlatpakUpdateProgress,
    ) : ProgressStep {
        val isFinal: Boolean get() = progress.state != FlatpakUpdateState.Installing
    }

    data class Failed(
        val error: FlatpakException,
    ) : ProgressStep
}

internal fun progressStepOf(signal: PortalProgress): ProgressStep {
    val count = signal.operationCount.coerceAtLeast(0)

    fun report(
        state: FlatpakUpdateState,
        fraction: Double,
    ) = ProgressStep.Report(FlatpakUpdateProgress(state, fraction, signal.operation, count))
    return when (signal.status) {
        STATUS_RUNNING -> {
            // `progress` is the percentage of the current operation, `op` its index.
            val fraction =
                if (count > 0) ((signal.operation + signal.percent / PERCENT) / count).coerceIn(0.0, 1.0) else 0.0
            report(FlatpakUpdateState.Installing, fraction)
        }
        STATUS_EMPTY -> report(FlatpakUpdateState.UpToDate, 1.0)
        STATUS_DONE -> report(FlatpakUpdateState.Completed, 1.0)
        STATUS_ERROR -> ProgressStep.Failed(errorOf(signal.error, signal.errorMessage, planned = count > 0))
        else ->
            ProgressStep.Failed(
                FlatpakException(FlatpakErrorCode.Internal, "Unknown progress status ${signal.status}"),
            )
    }
}

/**
 * Maps a portal error. The portal's messages are translated, so only the D-Bus name and whether
 * the transaction was planned ([planned]: the signal carried operations) tell errors apart. Two
 * failures share `NotSupported`: the new version needing more permissions is found while
 * planning, a missing Access portal before it.
 */
internal fun errorOf(
    name: String?,
    message: String?,
    planned: Boolean = false,
): FlatpakException {
    val text = message?.takeIf { it.isNotBlank() } ?: name ?: "The Flatpak portal failed"
    val code =
        when {
            name == DBUS_ACCESS_DENIED -> FlatpakErrorCode.UpdateDenied
            name == DBUS_NOT_SUPPORTED && planned -> FlatpakErrorCode.NewPermissionsRequired
            name == DBUS_NOT_SUPPORTED -> FlatpakErrorCode.PortalMissing
            name in PORTAL_GONE -> FlatpakErrorCode.PortalMissing
            else -> FlatpakErrorCode.Platform
        }
    return FlatpakException(code, text, portalError = name)
}

/** Parses the bridge's `"<D-Bus error name>\n<message>"`. */
internal fun parsePortalError(encoded: String): FlatpakException =
    errorOf(encoded.substringBefore('\n'), encoded.substringAfter('\n', missingDelimiterValue = ""))
