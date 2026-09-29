@file:OptIn(ExperimentalForeignApi::class)

package dev.nucleusframework.location

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLErrorDenied
import platform.CoreLocation.kCLErrorLocationUnknown
import platform.CoreLocation.kCLErrorNetwork
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyKilometer
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

internal actual fun platformLocationBackend(): LocationBackend = IosLocationBackend

/**
 * Core Location. Managers are created and driven on the main thread, whose run loop delivers
 * their delegate callbacks. `CLLocationManager.delegate` is weak, so live delegates are held in
 * [retained] until their manager is released.
 */
internal object IosLocationBackend : LocationBackend {
    // Only touched on the main thread.
    private val retained = mutableSetOf<Any>()

    override val isAvailable: Boolean
        get() = CLLocationManager.locationServicesEnabled()

    override fun authorization(): LocationAuthorization = CLLocationManager().authorizationStatus.toAuthorization()

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization =
        suspendCancellableCoroutine { continuation ->
            onMain {
                val manager = CLLocationManager()
                val status = manager.authorizationStatus
                val upgrade = access == LocationAccess.Background && status == kCLAuthorizationStatusAuthorizedWhenInUse
                if (status != kCLAuthorizationStatusNotDetermined && !upgrade) {
                    continuation.resume(status.toAuthorization())
                    return@onMain
                }
                val delegate = AuthorizationDelegate(manager) { continuation.resume(it) }
                retained += delegate
                manager.delegate = delegate
                manager.desiredAccuracy = accuracy.desired
                if (access == LocationAccess.Background) {
                    manager.requestAlwaysAuthorization()
                } else {
                    manager.requestWhenInUseAuthorization()
                }
                continuation.invokeOnCancellation { onMain { delegate.finish(null) } }
            }
        }

    override fun start(
        request: SessionRequest,
        listener: SessionListener,
    ): LocationSession {
        val delegate = SessionDelegate(request, listener)
        onMain(delegate::begin)
        return LocationSession { onMain(delegate::stop) }
    }

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private val LocationAccuracy.desired: Double
        get() = if (this == LocationAccuracy.Precise) kCLLocationAccuracyBest else kCLLocationAccuracyKilometer

    private fun CLAuthorizationStatus.toAuthorization(): LocationAuthorization =
        when (this) {
            kCLAuthorizationStatusNotDetermined -> LocationAuthorization.NotDetermined
            kCLAuthorizationStatusDenied -> LocationAuthorization.Denied
            kCLAuthorizationStatusAuthorizedAlways -> LocationAuthorization.Background
            kCLAuthorizationStatusAuthorizedWhenInUse -> LocationAuthorization.Foreground
            else -> LocationAuthorization.Restricted
        }

    /**
     * Answers with the first determined status — or, for a background upgrade the user declines
     * (which changes nothing, so Core Location calls nobody), with the status once the app is
     * active again after the prompt.
     */
    private class AuthorizationDelegate(
        private val manager: CLLocationManager,
        private val onResult: (LocationAuthorization) -> Unit,
    ) : NSObject(),
        CLLocationManagerDelegateProtocol {
        private var done = false
        private val initial = manager.authorizationStatus
        private val activation: NSObjectProtocol =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidBecomeActiveNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ -> finish(manager.authorizationStatus.toAuthorization()) }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status = manager.authorizationStatus
            // The first call reports the state at `delegate` assignment, not an answer.
            if (status != kCLAuthorizationStatusNotDetermined && status != initial) finish(status.toAuthorization())
        }

        fun finish(result: LocationAuthorization?) {
            if (done) return
            done = true
            NSNotificationCenter.defaultCenter.removeObserver(activation)
            manager.delegate = null
            retained -= this
            result?.let(onResult)
        }
    }

    private class SessionDelegate(
        private val request: SessionRequest,
        private val listener: SessionListener,
    ) : NSObject(),
        CLLocationManagerDelegateProtocol {
        private var manager: CLLocationManager? = null
        private var stopped = false
        private var awaitingAuthorization = false
        private var lastDelivered = Double.NEGATIVE_INFINITY

        fun begin() {
            if (stopped) return
            val manager = CLLocationManager()
            this.manager = manager
            retained += this
            manager.desiredAccuracy = request.accuracy.desired
            manager.distanceFilter =
                if (request.minDistanceMeters > 0.0) request.minDistanceMeters else kCLDistanceFilterNone
            manager.delegate = this
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> run(manager)
                kCLAuthorizationStatusNotDetermined -> {
                    // `requestLocation` fails at once while undetermined: ask, start on the answer.
                    awaitingAuthorization = true
                    manager.requestWhenInUseAuthorization()
                }
                else -> listener.onError(LocationError.AuthorizationDenied, "location access is denied")
            }
        }

        fun stop() {
            if (stopped) return
            stopped = true
            manager?.let {
                it.stopUpdatingLocation()
                it.delegate = null
            }
            manager = null
            retained -= this
        }

        private fun run(manager: CLLocationManager) {
            awaitingAuthorization = false
            if (request.continuous) {
                manager.startUpdatingLocation()
                return
            }
            if (request.maxAge.isPositive()) manager.location?.let { deliver(it, cached = true) }
            if (!stopped) manager.requestLocation()
        }

        private fun deliver(
            location: CLLocation,
            cached: Boolean,
        ) {
            if (stopped || location.horizontalAccuracy < 0.0) return
            val timestamp = location.timestamp.timeIntervalSince1970
            if (!request.continuous) {
                if (timestamp <= lastDelivered) return
                lastDelivered = timestamp
            }
            val (latitude, longitude) = location.coordinate.useContents { latitude to longitude }
            val verticalAccuracy = location.verticalAccuracy.takeIf { it > 0.0 }
            listener.onLocation(
                Location(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = verticalAccuracy?.let { location.altitude },
                    horizontalAccuracy = location.horizontalAccuracy,
                    verticalAccuracy = verticalAccuracy,
                    bearing = location.course.takeIf { it >= 0.0 },
                    speed = location.speed.takeIf { it >= 0.0 },
                    timestampMillis = (timestamp * MILLIS_PER_SECOND).toLong(),
                    isCached = cached,
                ),
            )
        }

        @ObjCSignatureOverride
        override fun locationManager(
            manager: CLLocationManager,
            didUpdateLocations: List<*>,
        ) {
            didUpdateLocations.forEach { (it as? CLLocation)?.let { location -> deliver(location, cached = false) } }
        }

        @ObjCSignatureOverride
        override fun locationManager(
            manager: CLLocationManager,
            didFailWithError: NSError,
        ) {
            if (stopped) return
            val error =
                when (didFailWithError.code) {
                    kCLErrorLocationUnknown -> LocationError.TemporarilyUnavailable
                    kCLErrorDenied -> LocationError.AuthorizationDenied
                    kCLErrorNetwork -> LocationError.Network
                    else -> LocationError.Unknown
                }
            listener.onError(error, didFailWithError.localizedDescription)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            if (stopped || !awaitingAuthorization) return
            when (manager.authorizationStatus) {
                kCLAuthorizationStatusNotDetermined -> Unit
                kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> run(manager)
                else -> {
                    awaitingAuthorization = false
                    listener.onError(LocationError.AuthorizationDenied, "location access was denied")
                }
            }
        }
    }

    private const val MILLIS_PER_SECOND = 1000.0
}
