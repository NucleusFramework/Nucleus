package dev.nucleusframework.lab.probes.system.location

import androidx.compose.runtime.Immutable
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.location.Location
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationAuthorization

/** How a log line reads: an observation, a result as expected, or a failure. */
enum class LocationTone { Info, Ok, Failed }

@Immutable
data class LocationLine(
    val epochMillis: Long,
    val text: String,
    val tone: LocationTone,
)

@Immutable
data class LocationState(
    val available: Availability = Availability.Unknown,
    val accuracy: LocationAccuracy = LocationAccuracy.Precise,
    /** Last value of `authorizationUpdates()`; `null` until the first one. */
    val authorization: LocationAuthorization? = null,
    val tracking: Boolean = false,
    val lastFix: Location? = null,
    val updates: Int = 0,
    val log: List<LocationLine> = emptyList(),
)

sealed interface LocationIntent {
    data class SetAccuracy(
        val accuracy: LocationAccuracy,
    ) : LocationIntent

    /** [window] parents the Linux portal dialog; `null` leaves it unparented. */
    data class RequestAuthorization(
        val access: LocationAccess,
        val window: NucleusWindow? = null,
    ) : LocationIntent {
        override fun toString(): String = "RequestAuthorization($access, parented=${window != null})"
    }

    /** One fix; [cached] accepts one already acquired in the last 10 minutes. */
    data class CurrentLocation(
        val cached: Boolean,
    ) : LocationIntent

    data class SetTracking(
        val tracking: Boolean,
    ) : LocationIntent

    data object OpenSettings : LocationIntent

    data object Clear : LocationIntent
}

sealed interface LocationEvent {
    data class Ready(
        val available: Availability,
    ) : LocationEvent

    data class AccuracyChanged(
        val accuracy: LocationAccuracy,
    ) : LocationEvent

    data class AuthorizationChanged(
        val authorization: LocationAuthorization,
    ) : LocationEvent

    /** [live] counts it as a `locationUpdates` fix. */
    data class Fixed(
        val location: Location,
        val live: Boolean,
    ) : LocationEvent

    data class TrackingChanged(
        val tracking: Boolean,
    ) : LocationEvent

    data class Logged(
        val line: LocationLine,
    ) : LocationEvent

    data object Cleared : LocationEvent
}

object LocationReducer : Reducer<LocationState, LocationEvent> {
    override fun reduce(
        state: LocationState,
        event: LocationEvent,
    ): LocationState =
        when (event) {
            is LocationEvent.Ready -> state.copy(available = event.available)
            is LocationEvent.AccuracyChanged -> state.copy(accuracy = event.accuracy)
            is LocationEvent.AuthorizationChanged -> state.copy(authorization = event.authorization)
            is LocationEvent.Fixed ->
                state.copy(lastFix = event.location, updates = state.updates + if (event.live) 1 else 0)
            is LocationEvent.TrackingChanged -> state.copy(tracking = event.tracking)
            is LocationEvent.Logged -> state.copy(log = state.log.append(event.line))
            LocationEvent.Cleared -> state.copy(log = emptyList(), lastFix = null, updates = 0)
        }
}
