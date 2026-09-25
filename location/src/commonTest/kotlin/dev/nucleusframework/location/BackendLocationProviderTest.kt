package dev.nucleusframework.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BackendLocationProviderTest {
    private val backend = FakeBackend()
    private val provider = BackendLocationProvider(backend)

    @Test
    fun currentLocationReturnsTheFirstFreshFixAndStopsTheSession() =
        runTest {
            val result = async { provider.currentLocation() }
            runCurrent()
            val session = backend.sessions.single()
            assertEquals(false, session.request.continuous)

            session.listener.onLocation(fix(latitude = 1.0))
            session.listener.onLocation(fix(latitude = 2.0))

            assertEquals(1.0, result.await().latitude)
            assertTrue(session.stopped)
        }

    @Test
    fun aCachedFixOlderThanMaxAgeIsSkipped() =
        runTest {
            val result = async { provider.currentLocation(maxAge = 1.minutes) }
            runCurrent()
            val session = backend.sessions.single()
            assertEquals(1.minutes, session.request.maxAge)

            session.listener.onLocation(fix(latitude = 1.0, ageMillis = 2.minutes.inWholeMilliseconds, cached = true))
            session.listener.onLocation(fix(latitude = 2.0))

            assertEquals(2.0, result.await().latitude)
        }

    @Test
    fun aCachedFixWithinMaxAgeIsAccepted() =
        runTest {
            val result = async { provider.currentLocation(maxAge = 5.minutes) }
            runCurrent()

            backend.sessions
                .single()
                .listener
                .onLocation(fix(latitude = 3.0, ageMillis = 60_000, cached = true))

            assertEquals(3.0, result.await().latitude)
        }

    @Test
    fun aSessionErrorFailsTheRequest() =
        runTest {
            val result = async { runCatching { provider.currentLocation() } }
            runCurrent()

            backend.sessions
                .single()
                .listener
                .onError(LocationError.Network, "offline")

            val error = assertLocationException(result.await())
            assertEquals(LocationError.Network, error.error)
        }

    @Test
    fun noFixWithinTheTimeoutIsATimeoutError() =
        runTest {
            val result = async { runCatching { provider.currentLocation(timeout = 10.seconds) } }
            runCurrent()
            advanceTimeBy(11.seconds)

            assertEquals(LocationError.Timeout, assertLocationException(result.await()).error)
            assertTrue(backend.sessions.single().stopped)
        }

    @Test
    fun anUndeterminedAuthorizationIsRequestedFirst() =
        runTest {
            backend.status = LocationAuthorization.NotDetermined
            backend.answer = LocationAuthorization.Foreground

            val result = async { provider.currentLocation(accuracy = LocationAccuracy.Approximate) }
            runCurrent()
            backend.sessions
                .single()
                .listener
                .onLocation(fix())
            result.await()

            assertEquals(listOf(LocationAccess.Foreground to LocationAccuracy.Approximate), backend.requests)
        }

    @Test
    fun aDeniedAuthorizationFailsWithoutStartingASession() =
        runTest {
            backend.status = LocationAuthorization.Denied

            val error = assertFailsWith<LocationException> { provider.currentLocation() }

            assertEquals(LocationError.AuthorizationDenied, error.error)
            assertTrue(backend.sessions.isEmpty())
            assertTrue(backend.requests.isEmpty())
        }

    @Test
    fun updatesRideOutTransientErrors() =
        runTest {
            val result = async { provider.locationUpdates(minInterval = 5.seconds).take(2).toList() }
            runCurrent()
            val session = backend.sessions.single()
            assertEquals(true, session.request.continuous)
            assertEquals(5.seconds, session.request.minInterval)

            session.listener.onLocation(fix(latitude = 1.0))
            session.listener.onError(LocationError.TemporarilyUnavailable, "tunnel")
            session.listener.onLocation(fix(latitude = 2.0))

            assertEquals(listOf(1.0, 2.0), result.await().map { it.latitude })
            runCurrent()
            assertTrue(session.stopped)
        }

    @Test
    fun updatesFailOnATerminalError() =
        runTest {
            val result = async { runCatching { provider.locationUpdates().toList() } }
            runCurrent()
            val session = backend.sessions.single()

            session.listener.onError(LocationError.AuthorizationDenied, "revoked")

            assertEquals(LocationError.AuthorizationDenied, assertLocationException(result.await()).error)
            assertTrue(session.stopped)
        }

    private fun TestScope.fix(
        latitude: Double = 0.0,
        ageMillis: Long = 0,
        cached: Boolean = false,
    ) = Location(
        latitude = latitude,
        longitude = 0.0,
        timestampMillis = backend.nowMillis() - ageMillis,
        isCached = cached,
    )

    private fun assertLocationException(result: Result<*>): LocationException {
        val error = result.exceptionOrNull()
        assertTrue(error is LocationException, "expected a LocationException, got $error")
        return error
    }

    private class FakeBackend : LocationBackend {
        var status = LocationAuthorization.Foreground
        var answer = LocationAuthorization.Foreground
        val requests = mutableListOf<Pair<LocationAccess, LocationAccuracy>>()
        val sessions = mutableListOf<FakeSession>()

        override val isAvailable: Boolean = true

        override fun authorization(): LocationAuthorization = status

        override suspend fun requestAuthorization(
            access: LocationAccess,
            accuracy: LocationAccuracy,
        ): LocationAuthorization {
            requests += access to accuracy
            status = answer
            return answer
        }

        override fun start(
            request: SessionRequest,
            listener: SessionListener,
        ): LocationSession = FakeSession(request, listener).also { sessions += it }

        override fun nowMillis(): Long = NOW
    }

    private class FakeSession(
        val request: SessionRequest,
        val listener: SessionListener,
    ) : LocationSession {
        var stopped = false

        override fun stop() {
            stopped = true
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
