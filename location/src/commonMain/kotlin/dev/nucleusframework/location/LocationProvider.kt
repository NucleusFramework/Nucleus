package dev.nucleusframework.location

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The device's geolocation.
 *
 * [Geolocation] is the platform's instance; depend on this interface to substitute a fake in
 * tests. Every member is safe to call from any thread.
 *
 * Location requests ([currentLocation], [locationUpdates]) ask for authorization themselves
 * when it has not been determined yet, and fail with [LocationError.AuthorizationDenied] when
 * it is refused. Call [requestAuthorization] beforehand to choose the moment of the prompt, or
 * to ask for [LocationAccess.Background].
 *
 * Platform requirements:
 * - **Android**: declare `ACCESS_COARSE_LOCATION` (and `ACCESS_FINE_LOCATION` for
 *   [LocationAccuracy.Precise], `ACCESS_BACKGROUND_LOCATION` for [LocationAccess.Background])
 *   in the app manifest. Prompts need a resumed activity.
 * - **iOS / macOS**: `NSLocationWhenInUseUsageDescription` (plus
 *   `NSLocationAlwaysAndWhenInUseUsageDescription` for background) in the `Info.plist`. Without
 *   one — an unpackaged run from Gradle or the IDE — Core Location never prompts.
 * - **Windows**: the "Let desktop apps access your location" privacy setting.
 * - **Linux**: xdg-desktop-portal with a Location backend, or GeoClue 2.
 */
public interface LocationProvider {
    /** Whether this platform has a location service the app can reach at all. */
    public val isAvailable: Boolean

    /** The current authorization, without prompting. */
    public fun authorization(): LocationAuthorization

    /**
     * Asks the user for access, prompting only when the platform has not recorded an answer yet,
     * and returns the resulting authorization. Suspends while the prompt is shown.
     */
    public suspend fun requestAuthorization(
        access: LocationAccess = LocationAccess.Foreground,
        accuracy: LocationAccuracy = LocationAccuracy.Precise,
    ): LocationAuthorization

    /**
     * One fix.
     *
     * @param maxAge the oldest already-acquired fix acceptable; [Duration.ZERO] always measures
     *     a new one. A cached fix comes back without waiting for the hardware.
     * @param timeout how long to wait for a fix before failing with [LocationError.Timeout]
     * @throws LocationException when no fix can be obtained
     */
    public suspend fun currentLocation(
        accuracy: LocationAccuracy = LocationAccuracy.Precise,
        maxAge: Duration = Duration.ZERO,
        timeout: Duration = 30.seconds,
    ): Location

    /**
     * Fixes as the device moves. Cold: each collector starts its own platform session, which
     * stops when the collection is cancelled. Transient outages are ridden out silently; the flow
     * fails with [LocationException] when access is denied or location becomes unavailable.
     *
     * @param minInterval the pace the platform is asked for; a hint, not a guarantee
     * @param minDistanceMeters movement below which no fix is reported; a hint as well
     */
    public fun locationUpdates(
        accuracy: LocationAccuracy = LocationAccuracy.Precise,
        minInterval: Duration = 1.seconds,
        minDistanceMeters: Double = 0.0,
    ): Flow<Location>
}

/** The platform's [LocationProvider]. */
public object Geolocation : LocationProvider by BackendLocationProvider(platformLocationBackend())
