package dev.nucleusframework.lab.probes.updater.feed

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.updater.testing.FeedFault
import kotlin.time.Duration.Companion.seconds

/** The misbehaviours a real release host, proxy or network produces, ready to inject. */
enum class FaultKind(
    val label: String,
    val fault: (sizeBytes: Long) -> FeedFault,
) {
    ServerError("HTTP 503", { FeedFault.Status(503) }),
    NotFound("HTTP 404", { FeedFault.Status(404) }),
    Slow("Delay 8 s", { FeedFault.Delay(8.seconds) }),
    Throttle("Throttle 512 KB/s", { FeedFault.Throttle(512L * 1024) }),
    Truncate("Cut at half", { size -> FeedFault.Truncate(size / 2) }),
    Corrupt("Flip byte 1024", { FeedFault.Corrupt(1024) }),
    NoRanges("Ignore Range", { FeedFault.IgnoreRange }),
}

enum class FaultTarget(
    val label: String,
    val glob: String,
) {
    Manifest("Manifest", "latest*.yml"),
    Artifact("Artifact", "NucleusLab-*"),
    Everything("Everything", "*"),
}

enum class FaultTimes(
    val label: String,
    val times: Int?,
) {
    Once("once", 1),
    Thrice("3 times", 3),
    Always("always", null),
}

@Immutable
data class FeedServerState(
    val status: FeedServerStatus = FeedServerStatus(),
    val version: String = "99.0.0",
    val sizeMb: Float = 16f,
    val faultKind: FaultKind = FaultKind.Throttle,
    val faultTarget: FaultTarget = FaultTarget.Artifact,
    val faultTimes: FaultTimes = FaultTimes.Once,
    val error: String? = null,
)

sealed interface FeedServerIntent {
    data object Start : FeedServerIntent

    data object Stop : FeedServerIntent

    data class EditVersion(
        val version: String,
    ) : FeedServerIntent

    data class SetSize(
        val megabytes: Float,
    ) : FeedServerIntent

    data object Publish : FeedServerIntent

    data class ConfigureFault(
        val kind: FaultKind? = null,
        val target: FaultTarget? = null,
        val times: FaultTimes? = null,
    ) : FeedServerIntent

    data object InjectFault : FeedServerIntent

    data object ClearFaults : FeedServerIntent

    data object ClearRequests : FeedServerIntent
}

sealed interface FeedServerEvent {
    data class StatusChanged(
        val status: FeedServerStatus,
    ) : FeedServerEvent {
        override fun toString(): String =
            "StatusChanged(running=${status.running}, published=${status.published?.version}, " +
                "faults=${status.faults.size})"
    }

    data class Edited(
        val version: String,
        val sizeMb: Float,
    ) : FeedServerEvent

    data class FaultConfigured(
        val kind: FaultKind,
        val target: FaultTarget,
        val times: FaultTimes,
    ) : FeedServerEvent

    /** The requests the server answered since the last look. */
    data class Served(
        val requests: List<ServedRequest>,
    ) : FeedServerEvent {
        override fun toString(): String = "Served(${requests.joinToString { it.line }})"
    }

    data class Failed(
        val reason: String,
    ) : FeedServerEvent
}

object FeedServerReducer : Reducer<FeedServerState, FeedServerEvent> {
    override fun reduce(
        state: FeedServerState,
        event: FeedServerEvent,
    ): FeedServerState =
        when (event) {
            is FeedServerEvent.StatusChanged -> state.copy(status = event.status, error = null)
            is FeedServerEvent.Edited -> state.copy(version = event.version, sizeMb = event.sizeMb)
            is FeedServerEvent.FaultConfigured ->
                state.copy(
                    faultKind = event.kind,
                    faultTarget = event.target,
                    faultTimes = event.times,
                )
            is FeedServerEvent.Served -> state
            is FeedServerEvent.Failed -> state.copy(error = event.reason)
        }
}
