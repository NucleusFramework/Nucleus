@file:OptIn(ExperimentalWasmJsInterop::class)
// The `js*` helpers' parameters are read by their `js()` bodies, which detekt cannot see.
@file:Suppress("UnusedParameter")

package dev.nucleusframework.location

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.js.ExperimentalWasmJsInterop

internal actual fun platformLocationBackend(): LocationBackend = WebLocationBackend

/**
 * The W3C Geolocation API, shared by the `js` and `wasmJs` targets.
 *
 * Browsers only expose it in a secure context (HTTPS or `localhost`) and prompt on the first
 * position request — there is no separate "ask" call, so [requestAuthorization] asks for a
 * position and reads the answer from how that request ends. The current state comes from the
 * Permissions API where the browser has one (all current engines), queried once and then kept up
 * to date by its `change` event.
 */
internal object WebLocationBackend : LocationBackend {
    private var permission = LocationAuthorization.NotDetermined

    init {
        if (jsHasGeolocation()) {
            jsQueryPermission { state -> permission = state.toAuthorization() ?: permission }
        }
    }

    override val isAvailable: Boolean
        get() = jsHasGeolocation()

    override fun authorization(): LocationAuthorization =
        if (jsHasGeolocation()) permission else LocationAuthorization.Restricted

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization {
        if (!jsHasGeolocation()) return LocationAuthorization.Restricted
        if (permission != LocationAuthorization.NotDetermined) return permission
        return suspendCancellableCoroutine { continuation ->
            // Any cached fix will do: only the permission outcome matters here.
            jsGetCurrentPosition(
                highAccuracy = accuracy == LocationAccuracy.Precise,
                maximumAge = Double.POSITIVE_INFINITY,
                onPosition = { _, _, _, _, _, _, _, _ ->
                    permission = LocationAuthorization.Foreground
                    continuation.resume(permission)
                },
                onError = { code, _ ->
                    // Only PERMISSION_DENIED says no; an unavailable position still means yes.
                    val denied = code == PERMISSION_DENIED
                    permission = if (denied) LocationAuthorization.Denied else LocationAuthorization.Foreground
                    continuation.resume(permission)
                },
            )
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    override fun start(
        request: SessionRequest,
        listener: SessionListener,
    ): LocationSession {
        if (!jsHasGeolocation()) {
            listener.onError(LocationError.PermanentlyUnavailable, "this browser has no Geolocation API")
            return LocationSession {}
        }
        val stopped = AtomicBoolean(false)
        val startedAt = nowMillis()
        val onPosition: PositionCallback = {
            latitude,
            longitude,
            altitude,
            accuracy,
            altitudeAccuracy,
            heading,
            speed,
            timestamp,
            ->
            if (!stopped.load()) {
                listener.onLocation(
                    Location(
                        latitude = latitude,
                        longitude = longitude,
                        altitude = altitude.orNull(),
                        horizontalAccuracy = accuracy.orNull(),
                        verticalAccuracy = altitudeAccuracy.orNull(),
                        bearing = heading.orNull(),
                        speed = speed.orNull(),
                        timestampMillis = timestamp.toLong(),
                        // The browser does not say; a fix older than the request came from its cache.
                        isCached = timestamp < startedAt,
                    ),
                )
            }
        }
        val onError: (Int, String) -> Unit = { code, message ->
            if (!stopped.load()) {
                if (code == PERMISSION_DENIED) permission = LocationAuthorization.Denied
                listener.onError(code.toLocationError(), message)
            }
        }
        val highAccuracy = request.accuracy == LocationAccuracy.Precise
        if (!request.continuous) {
            jsGetCurrentPosition(highAccuracy, request.maxAge.inWholeMilliseconds.toDouble(), onPosition, onError)
            return LocationSession { stopped.store(true) }
        }
        // watchPosition has no interval or distance knobs: the browser paces the updates itself.
        val watchId = jsWatchPosition(highAccuracy, onPosition, onError)
        return LocationSession {
            if (stopped.compareAndSet(expectedValue = false, newValue = true)) jsClearWatch(watchId)
        }
    }

    override fun nowMillis(): Long = jsNow().toLong()

    private fun String.toAuthorization(): LocationAuthorization? =
        when (this) {
            "granted" -> LocationAuthorization.Foreground
            "denied" -> LocationAuthorization.Denied
            "prompt" -> LocationAuthorization.NotDetermined
            else -> null
        }

    private fun Int.toLocationError(): LocationError =
        when (this) {
            PERMISSION_DENIED -> LocationError.AuthorizationDenied
            POSITION_UNAVAILABLE -> LocationError.TemporarilyUnavailable
            TIMEOUT -> LocationError.Timeout
            else -> LocationError.Unknown
        }

    private fun Double.orNull(): Double? = takeUnless { it.isNaN() }

    // GeolocationPositionError codes.
    private const val PERMISSION_DENIED = 1
    private const val POSITION_UNAVAILABLE = 2
    private const val TIMEOUT = 3
}

/** Latitude, longitude, altitude, accuracy, altitude accuracy, heading, speed, timestamp; `NaN` = not reported. */
private typealias PositionCallback = (Double, Double, Double, Double, Double, Double, Double, Double) -> Unit

// The browser is reached through `js()` bodies rather than external declarations, so the same
// source compiles for Kotlin/JS and Kotlin/Wasm; only primitives and lambdas cross the boundary.

private fun jsHasGeolocation(): Boolean = js("typeof navigator !== 'undefined' && !!navigator.geolocation")

private fun jsNow(): Double = js("Date.now()")

private fun jsQueryPermission(onState: (String) -> Unit): Unit =
    js(
        """{
        if (!navigator.permissions || !navigator.permissions.query) return;
        navigator.permissions.query({ name: 'geolocation' }).then(function (status) {
            onState(status.state);
            status.onchange = function () { onState(status.state); };
        }, function () {});
    }""",
    )

private fun jsGetCurrentPosition(
    highAccuracy: Boolean,
    maximumAge: Double,
    onPosition: PositionCallback,
    onError: (Int, String) -> Unit,
): Unit =
    js(
        """{
        navigator.geolocation.getCurrentPosition(function (p) {
            var c = p.coords;
            onPosition(c.latitude, c.longitude, c.altitude == null ? NaN : c.altitude, c.accuracy,
                c.altitudeAccuracy == null ? NaN : c.altitudeAccuracy, c.heading == null ? NaN : c.heading,
                c.speed == null ? NaN : c.speed, p.timestamp);
        }, function (e) { onError(e.code, e.message); },
        { enableHighAccuracy: highAccuracy, maximumAge: maximumAge });
    }""",
    )

private fun jsWatchPosition(
    highAccuracy: Boolean,
    onPosition: PositionCallback,
    onError: (Int, String) -> Unit,
): Int =
    js(
        """navigator.geolocation.watchPosition(function (p) {
            var c = p.coords;
            onPosition(c.latitude, c.longitude, c.altitude == null ? NaN : c.altitude, c.accuracy,
                c.altitudeAccuracy == null ? NaN : c.altitudeAccuracy, c.heading == null ? NaN : c.heading,
                c.speed == null ? NaN : c.speed, p.timestamp);
        }, function (e) { onError(e.code, e.message); }, { enableHighAccuracy: highAccuracy })""",
    )

private fun jsClearWatch(id: Int): Unit = js("navigator.geolocation.clearWatch(id)")
