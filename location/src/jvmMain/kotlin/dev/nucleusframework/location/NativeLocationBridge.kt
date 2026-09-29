package dev.nucleusframework.location

import dev.nucleusframework.core.runtime.NativeLibraryLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

private const val LIBRARY_NAME = "nucleus_location"

// Mirrors `AUTH_*` and `ErrorKind` in src/main/native/src/lib.rs.
internal const val AUTH_NOT_DETERMINED = 0
internal const val AUTH_DENIED = 1
internal const val AUTH_RESTRICTED = 2
internal const val AUTH_FOREGROUND = 3
internal const val AUTH_BACKGROUND = 4

internal const val ERROR_AUTHORIZATION_DENIED = 1
internal const val ERROR_TEMPORARILY_UNAVAILABLE = 2
internal const val ERROR_PERMANENTLY_UNAVAILABLE = 3
internal const val ERROR_NETWORK = 4

/**
 * JNI surface of `nucleus_location`. Sessions and authorization requests are identified by ids
 * the Kotlin side allocates, so a callback that races a stop finds nothing registered and is
 * dropped here rather than in native code.
 */
@Suppress("TooManyFunctions")
internal object NativeLocationBridge {
    private val logger = Logger.getLogger(NativeLocationBridge::class.java.name)

    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, NativeLocationBridge::class.java)

    private val sessions = ConcurrentHashMap<Long, SessionListener>()
    private val authorizations = ConcurrentHashMap<Long, (LocationAuthorization) -> Unit>()

    fun registerSession(
        id: Long,
        listener: SessionListener,
    ) {
        sessions[id] = listener
    }

    fun unregisterSession(id: Long) {
        sessions.remove(id)
    }

    fun registerAuthorization(
        id: Long,
        callback: (LocationAuthorization) -> Unit,
    ) {
        authorizations[id] = callback
    }

    fun unregisterAuthorization(id: Long) {
        authorizations.remove(id)
    }

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    @JvmStatic
    external fun nativeAuthorizationStatus(): Int

    @JvmStatic
    external fun nativeRequestAuthorization(
        requestId: Long,
        background: Boolean,
        precise: Boolean,
        desktopId: String,
    )

    @Suppress("LongParameterList")
    @JvmStatic
    external fun nativeStart(
        sessionId: Long,
        precise: Boolean,
        continuous: Boolean,
        intervalMillis: Long,
        distanceMeters: Double,
        maxCachedAgeMillis: Long,
        desktopId: String,
    )

    @JvmStatic
    external fun nativeStop(sessionId: Long)

    /** Called from native code; `NaN` marks a value the platform did not report. */
    @Suppress("LongParameterList")
    @JvmStatic
    fun onLocation(
        sessionId: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        horizontalAccuracy: Double,
        verticalAccuracy: Double,
        bearing: Double,
        speed: Double,
        timestampMillis: Long,
        cached: Boolean,
    ) {
        val listener = sessions[sessionId] ?: return
        val location =
            Location(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude.orNull(),
                horizontalAccuracy = horizontalAccuracy.orNull(),
                verticalAccuracy = verticalAccuracy.orNull(),
                bearing = bearing.orNull(),
                speed = speed.orNull(),
                timestampMillis = timestampMillis,
                isCached = cached,
            )
        guarded { listener.onLocation(location) }
    }

    /** Called from native code. */
    @JvmStatic
    fun onError(
        sessionId: Long,
        kind: Int,
        message: String,
    ) {
        val listener = sessions[sessionId] ?: return
        guarded { listener.onError(errorOf(kind), message) }
    }

    /** Called from native code. */
    @JvmStatic
    fun onAuthorization(
        requestId: Long,
        status: Int,
    ) {
        val callback = authorizations.remove(requestId) ?: return
        guarded { callback(authorizationOf(status)) }
    }

    fun authorizationOf(status: Int): LocationAuthorization =
        when (status) {
            AUTH_NOT_DETERMINED -> LocationAuthorization.NotDetermined
            AUTH_DENIED -> LocationAuthorization.Denied
            AUTH_FOREGROUND -> LocationAuthorization.Foreground
            AUTH_BACKGROUND -> LocationAuthorization.Background
            else -> LocationAuthorization.Restricted
        }

    private fun errorOf(kind: Int): LocationError =
        when (kind) {
            ERROR_AUTHORIZATION_DENIED -> LocationError.AuthorizationDenied
            ERROR_TEMPORARILY_UNAVAILABLE -> LocationError.TemporarilyUnavailable
            ERROR_PERMANENTLY_UNAVAILABLE -> LocationError.PermanentlyUnavailable
            ERROR_NETWORK -> LocationError.Network
            else -> LocationError.Unknown
        }

    // A listener that throws must not unwind into native code.
    @Suppress("TooGenericExceptionCaught")
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Location callback failed", e)
        }
    }

    private fun Double.orNull(): Double? = takeUnless { it.isNaN() }
}
