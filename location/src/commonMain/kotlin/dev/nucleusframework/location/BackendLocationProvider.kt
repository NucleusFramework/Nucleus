package dev.nucleusframework.location

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * What each platform implements: authorization plus *sessions* — a stream of fixes and errors
 * the platform produces between [LocationBackend.start] and [LocationSession.stop]. Everything
 * built on top (cached-fix acceptance, timeouts, the implicit authorization request, the flow)
 * is shared by [BackendLocationProvider].
 */
internal interface LocationBackend {
    val isAvailable: Boolean

    fun authorization(): LocationAuthorization

    suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization

    /** Starts a session. Must not call [listener] synchronously. */
    fun start(
        request: SessionRequest,
        listener: SessionListener,
    ): LocationSession

    /** Wall-clock milliseconds since the Unix epoch, to age cached fixes. */
    fun nowMillis(): Long
}

/**
 * @property continuous `false` for a one-shot: the platform's cached fix (when [maxAge] allows
 *     looking for one) followed by one fresh fix
 */
internal data class SessionRequest(
    val accuracy: LocationAccuracy,
    val continuous: Boolean,
    val minInterval: Duration = Duration.ZERO,
    val minDistanceMeters: Double = 0.0,
    val maxAge: Duration = Duration.ZERO,
)

internal interface SessionListener {
    fun onLocation(location: Location)

    fun onError(
        error: LocationError,
        message: String,
    )
}

internal fun interface LocationSession {
    /** Idempotent; no callback is delivered once it returns. */
    fun stop()
}

internal class BackendLocationProvider(
    private val backend: LocationBackend,
) : LocationProvider {
    override val isAvailable: Boolean
        get() = backend.isAvailable

    override fun authorization(): LocationAuthorization = backend.authorization()

    override suspend fun requestAuthorization(
        access: LocationAccess,
        accuracy: LocationAccuracy,
    ): LocationAuthorization = backend.requestAuthorization(access, accuracy)

    override suspend fun currentLocation(
        accuracy: LocationAccuracy,
        maxAge: Duration,
        timeout: Duration,
    ): Location {
        require(!maxAge.isNegative()) { "maxAge must not be negative" }
        require(timeout.isPositive()) { "timeout must be positive" }
        ensureAuthorized(accuracy)
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                val listener = OneShotListener(continuation, maxAge)
                val session = backend.start(SessionRequest(accuracy, continuous = false, maxAge = maxAge), listener)
                listener.attach(session)
                continuation.invokeOnCancellation { session.stop() }
            }
        } ?: throw LocationException(LocationError.Timeout, "no location within $timeout")
    }

    override fun locationUpdates(
        accuracy: LocationAccuracy,
        minInterval: Duration,
        minDistanceMeters: Double,
    ): Flow<Location> {
        require(!minInterval.isNegative()) { "minInterval must not be negative" }
        require(minDistanceMeters >= 0.0) { "minDistanceMeters must not be negative" }
        return callbackFlow {
            ensureAuthorized(accuracy)
            val request = SessionRequest(accuracy, continuous = true, minInterval, minDistanceMeters)
            val session =
                backend.start(
                    request,
                    object : SessionListener {
                        override fun onLocation(location: Location) {
                            trySend(location)
                        }

                        override fun onError(
                            error: LocationError,
                            message: String,
                        ) {
                            if (error.isTerminal) close(LocationException(error, message))
                        }
                    },
                )
            awaitClose { session.stop() }
        }
    }

    /** Prompts when nothing is decided yet; refuses early when the answer is already "no". */
    private suspend fun ensureAuthorized(accuracy: LocationAccuracy) {
        var status = backend.authorization()
        if (status == LocationAuthorization.NotDetermined) {
            status = backend.requestAuthorization(LocationAccess.Foreground, accuracy)
        }
        // Still undetermined (the platform cannot prompt, or does not report the answer): let
        // the session itself find out.
        if (status == LocationAuthorization.Denied || status == LocationAuthorization.Restricted) {
            throw LocationException(LocationError.AuthorizationDenied, "location access is $status")
        }
    }

    /** Resumes with the first acceptable fix or the first error, then stops the session. */
    @OptIn(ExperimentalAtomicApi::class)
    private inner class OneShotListener(
        private val continuation: CancellableContinuation<Location>,
        private val maxAge: Duration,
    ) : SessionListener {
        private val done = AtomicBoolean(false)
        private val session = AtomicReference<LocationSession?>(null)

        // The first callback may race `start` returning: whichever of the two sees the other's
        // write stops the session (stop is idempotent).
        fun attach(session: LocationSession) {
            this.session.store(session)
            if (done.load()) session.stop()
        }

        override fun onLocation(location: Location) {
            val age = backend.nowMillis() - location.timestampMillis
            if (location.isCached && age > maxAge.inWholeMilliseconds) return
            if (finish()) continuation.resume(location)
        }

        override fun onError(
            error: LocationError,
            message: String,
        ) {
            if (finish()) continuation.resumeWithException(LocationException(error, message))
        }

        private fun finish(): Boolean {
            if (!done.compareAndSet(expectedValue = false, newValue = true)) return false
            session.load()?.stop()
            return true
        }
    }
}

private val LocationError.isTerminal: Boolean
    get() = this == LocationError.AuthorizationDenied || this == LocationError.PermanentlyUnavailable
