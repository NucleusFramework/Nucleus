package dev.nucleusframework.location

import android.Manifest
import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import android.location.Location as AndroidLocation

internal actual fun platformLocationBackend(): LocationBackend = AndroidLocationBackend

/**
 * The framework `LocationManager` — no Google Play services dependency. The fused provider is
 * used where the platform has one (API 31+), GPS / network otherwise.
 */
@Suppress("TooManyFunctions")
internal object AndroidLocationBackend : LocationBackend {
    private const val PREFERENCES = "dev.nucleusframework.location"
    private const val KEY_ASKED = "asked"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val context: Context
        get() = AndroidLocationContext.requireApplication

    private val manager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val isAvailable: Boolean
        get() {
            if (AndroidLocationContext.applicationOrNull == null) return false
            val manager = manager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                // Not getProviders(true): it only lists the providers the app already holds a
                // permission for, so it reads "off" until the user grants access.
                manager.allProviders
                    .filter { it != LocationManager.PASSIVE_PROVIDER }
                    .any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            }
        }

    override fun authorization(): LocationAuthorization =
        when {
            AndroidLocationContext.applicationOrNull == null -> LocationAuthorization.Restricted
            hasForeground() && hasBackground() -> LocationAuthorization.Background
            hasForeground() -> LocationAuthorization.Foreground
            // Android does not tell "never asked" from "denied" without an activity at hand.
            preferences().getBoolean(KEY_ASKED, false) -> LocationAuthorization.Denied
            else -> LocationAuthorization.NotDetermined
        }

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization {
        if (AndroidLocationContext.applicationOrNull == null) return LocationAuthorization.Restricted
        // A precise request with only approximate access granted asks for the upgrade (Android 12+
        // shows a dedicated dialog; a refused upgrade leaves the approximate grant in place).
        if (!hasForeground() || (accuracy == LocationAccuracy.Precise && !hasFine())) {
            // Android 12+ shows one dialog with a precise / approximate choice when both are asked.
            val permissions =
                if (accuracy == LocationAccuracy.Precise) {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                } else {
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            prompt(permissions)
        }
        // Background access must be asked on its own, after a foreground grant (API 30+).
        if (access == LocationAccess.Background && hasForeground() && !hasBackground()) {
            prompt(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
        return authorization()
    }

    override fun start(
        request: SessionRequest,
        listener: SessionListener,
    ): LocationSession {
        val session = AndroidSession(request, listener)
        mainHandler.post(session::begin)
        return session
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun openSettings(): Boolean {
        val application = AndroidLocationContext.applicationOrNull ?: return false
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", application.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (AndroidLocationContext.resumedActivity ?: application).startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ||
            PermissionRequests.wasGranted(permission)

    private fun hasFine(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasForeground(): Boolean = hasFine() || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    // Before API 29 a foreground grant covers the background as well.
    private fun hasBackground(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Shows the permission dialog over the resumed activity and suspends until it is answered. */
    private suspend fun prompt(permissions: Array<String>) {
        val activity = AndroidLocationContext.resumedActivity ?: return
        PermissionRequests.request(activity, permissions)
        // Recorded once answered, and only then: while the dialog is up, or when the request is
        // cancelled before the user answers, `authorization()` must not read "asked" as Denied.
        preferences().edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** One session. Every platform call happens on the main looper. */
    private class AndroidSession(
        private val request: SessionRequest,
        private val listener: SessionListener,
    ) : LocationSession {
        private val stopped = AtomicBoolean(false)
        private var cancellation: CancellationSignal? = null
        private var updates: LocationListener? = null

        fun begin() {
            if (stopped.get()) return
            if (!hasForeground()) {
                listener.onError(LocationError.AuthorizationDenied, "no location permission is granted")
                return
            }
            val provider = provider()
            if (provider == null) {
                listener.onError(LocationError.PermanentlyUnavailable, "location is turned off")
                return
            }
            try {
                if (request.continuous) startUpdates(provider) else startOneShot(provider)
            } catch (e: SecurityException) {
                listener.onError(LocationError.AuthorizationDenied, e.message ?: "location permission revoked")
            } catch (e: IllegalArgumentException) {
                listener.onError(LocationError.PermanentlyUnavailable, e.message ?: "no such provider: $provider")
            }
        }

        override fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            mainHandler.post {
                cancellation?.cancel()
                updates?.let(manager::removeUpdates)
            }
        }

        private fun deliver(
            location: AndroidLocation?,
            cached: Boolean,
        ) {
            if (stopped.get()) return
            if (location == null) {
                if (!cached) listener.onError(LocationError.TemporarilyUnavailable, "no fix could be obtained")
                return
            }
            listener.onLocation(location.toLocation(cached))
        }

        private fun startUpdates(provider: String) {
            val listener = SessionLocationListener()
            updates = listener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.requestLocationUpdates(
                    provider,
                    locationRequest(request.minInterval.inWholeMilliseconds),
                    context.mainExecutor,
                    listener,
                )
            } else {
                manager.requestLocationUpdates(
                    provider,
                    request.minInterval.inWholeMilliseconds,
                    request.minDistanceMeters.toFloat(),
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }

        /**
         * The fused provider defaults to a balanced (Wi-Fi / cell) quality that never turns GNSS on:
         * a precise request must ask for high accuracy explicitly.
         */
        @TargetApi(Build.VERSION_CODES.S)
        private fun locationRequest(intervalMillis: Long): LocationRequest =
            LocationRequest
                .Builder(intervalMillis)
                .setQuality(
                    if (request.accuracy == LocationAccuracy.Precise && hasFine()) {
                        LocationRequest.QUALITY_HIGH_ACCURACY
                    } else {
                        LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                    },
                ).setMinUpdateDistanceMeters(request.minDistanceMeters.toFloat())
                .build()

        @Suppress("DEPRECATION")
        private fun startOneShot(provider: String) {
            if (request.maxAge.isPositive()) {
                val cached =
                    manager
                        .getProviders(true)
                        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                        .maxByOrNull { it.time }
                if (cached != null) deliver(cached, cached = true)
                if (stopped.get()) return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val signal = CancellationSignal()
                cancellation = signal
                // No platform deadline (30 s by default): the caller's timeout bounds the request.
                val oneShot =
                    LocationRequest
                        .Builder(locationRequest(0))
                        .setDurationMillis(Long.MAX_VALUE)
                        .build()
                manager.getCurrentLocation(provider, oneShot, signal, context.mainExecutor) {
                    deliver(it, cached = false)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cancellation = signal
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { deliver(it, cached = false) }
            } else {
                val listener = SessionLocationListener()
                updates = listener
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

        /** Picks the provider matching the accuracy, among those enabled and permitted. */
        private fun provider(): String? {
            val precise = request.accuracy == LocationAccuracy.Precise && hasFine()
            val candidates =
                buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                    if (precise) add(LocationManager.GPS_PROVIDER)
                    add(LocationManager.NETWORK_PROVIDER)
                    if (hasFine()) add(LocationManager.GPS_PROVIDER)
                }
            val enabled = manager.getProviders(true)
            return candidates.firstOrNull { it in enabled }
        }

        // Every method overridden: before API 30 the framework interface has no default bodies.
        private inner class SessionLocationListener : LocationListener {
            override fun onLocationChanged(location: AndroidLocation) = deliver(location, cached = false)

            override fun onProviderDisabled(provider: String) {
                if (!stopped.get()) listener.onError(LocationError.TemporarilyUnavailable, "$provider was turned off")
            }

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit
        }
    }

    private fun AndroidLocation.toLocation(cached: Boolean): Location =
        Location(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude.takeIf { hasAltitude() },
            horizontalAccuracy = accuracy.toDouble().takeIf { hasAccuracy() },
            verticalAccuracy =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) {
                    verticalAccuracyMeters.toDouble()
                } else {
                    null
                },
            bearing = bearing.toDouble().takeIf { hasBearing() },
            speed = speed.toDouble().takeIf { hasSpeed() },
            timestampMillis = time,
            isCached = cached,
        )
}
