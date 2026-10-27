package dev.nucleusframework.location

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.tools.LinuxDesktopFileDetector
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal actual fun platformLocationBackend(): LocationBackend = DesktopLocationBackend

/** Windows, macOS and Linux, through the Rust bridge in `src/main/native`. */
internal object DesktopLocationBackend : LocationBackend {
    private val nextId = AtomicLong(1)

    /**
     * The `.desktop` id GeoClue attributes a direct request to on Linux (the portal identifies
     * the app itself). Unused elsewhere.
     */
    private val desktopId: String by lazy {
        if (Platform.Current == Platform.Linux) {
            LinuxDesktopFileDetector.desktopFilename?.removeSuffix(".desktop") ?: NucleusApp.appId
        } else {
            ""
        }
    }

    override val isAvailable: Boolean
        get() = NativeLocationBridge.isLoaded && NativeLocationBridge.nativeIsAvailable()

    override fun authorization(): LocationAuthorization =
        if (NativeLocationBridge.isLoaded) {
            NativeLocationBridge.authorizationOf(NativeLocationBridge.nativeAuthorizationStatus())
        } else {
            LocationAuthorization.Restricted
        }

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization {
        if (!NativeLocationBridge.isLoaded) return LocationAuthorization.Restricted
        val id = nextId.getAndIncrement()
        return suspendCancellableCoroutine { continuation ->
            NativeLocationBridge.registerAuthorization(id) { continuation.resume(it) }
            continuation.invokeOnCancellation { NativeLocationBridge.unregisterAuthorization(id) }
            NativeLocationBridge.nativeRequestAuthorization(
                requestId = id,
                background = access == LocationAccess.Background,
                precise = accuracy == LocationAccuracy.Precise,
                desktopId = desktopId,
            )
        }
    }

    override fun start(
        request: SessionRequest,
        listener: SessionListener,
    ): LocationSession {
        val id = nextId.getAndIncrement()
        if (!NativeLocationBridge.isLoaded) {
            val stopped = AtomicBoolean(false)
            Thread {
                if (!stopped.get()) {
                    listener.onError(
                        LocationError.PermanentlyUnavailable,
                        "the nucleus_location library is not available",
                    )
                }
            }.apply { isDaemon = true }.start()
            return LocationSession { stopped.set(true) }
        }
        NativeLocationBridge.registerSession(id, listener)
        NativeLocationBridge.nativeStart(
            sessionId = id,
            precise = request.accuracy == LocationAccuracy.Precise,
            continuous = request.continuous,
            intervalMillis = request.minInterval.inWholeMilliseconds,
            distanceMeters = request.minDistanceMeters,
            maxCachedAgeMillis = request.maxAge.inWholeMilliseconds,
            desktopId = desktopId,
        )
        val stopped = AtomicBoolean(false)
        return LocationSession {
            if (stopped.compareAndSet(false, true)) {
                NativeLocationBridge.unregisterSession(id)
                NativeLocationBridge.nativeStop(id)
            }
        }
    }

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun openSettings(): Boolean {
        val command =
            when (Platform.Current) {
                Platform.Windows -> listOf("explorer.exe", "ms-settings:privacy-location")
                Platform.MacOS ->
                    listOf("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")
                // GNOME is the only desktop with a location panel; elsewhere (and inside a
                // Flatpak sandbox) the launch fails and there is nothing to open.
                Platform.Linux -> listOf("gnome-control-center", "location")
                Platform.Unknown -> return false
            }
        return try {
            ProcessBuilder(command).start()
            true
        } catch (_: IOException) {
            false
        }
    }
}
