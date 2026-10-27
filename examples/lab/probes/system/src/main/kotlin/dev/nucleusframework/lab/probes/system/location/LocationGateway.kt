package dev.nucleusframework.lab.probes.system.location

import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.requestLocationAuthorization
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.location.Geolocation
import dev.nucleusframework.location.LocationAccess
import dev.nucleusframework.location.LocationAccuracy
import dev.nucleusframework.location.LocationAuthorization
import dev.nucleusframework.location.LocationProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `location`: the provider itself, plus a crash-proof availability read. */
interface LocationGateway {
    val provider: LocationProvider

    fun availability(): Availability

    /** Through [window] when there is one, so the Linux portal dialog belongs to the Lab. */
    suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
        window: NucleusWindow?,
    ): LocationAuthorization
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusLocationGateway : LocationGateway {
    override val provider: LocationProvider = Geolocation

    // A broken libnucleus_location throws a LinkageError from here: the capability goes
    // unavailable, the Lab stays up.
    override fun availability(): Availability =
        Availability.catching({ "no reachable location service" }) { Geolocation.isAvailable }

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
        window: NucleusWindow?,
    ): LocationAuthorization =
        window?.requestLocationAuthorization(access, accuracy) ?: Geolocation.requestAuthorization(access, accuracy)
}
