package dev.nucleusframework.authentication

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real prompts, so opt-in: `NUCLEUS_AUTHENTICATION_SMOKE=1 ./gradlew :authentication:jvmTest`
 * on a desktop session. Nothing here needs the user to answer: every prompt it opens, it
 * dismisses itself.
 */
class AuthenticatorSmokeTest {
    @BeforeTest
    fun requireOptIn() {
        assumeTrue(System.getenv("NUCLEUS_AUTHENTICATION_SMOKE") == "1")
    }

    @Test
    fun bridgeLoads() {
        assertTrue(Authenticator.isSupported)
    }

    @Test
    fun availabilityAnswersWithoutUi() =
        runBlocking {
            val availability = Authenticator.availability()
            println("availability(DeviceOwner) = $availability")
            println("availability(Biometrics) = ${Authenticator.availability(AuthenticationPolicy.Biometrics)}")
        }

    @Test
    fun cancellingTheCallerDismissesThePrompt() =
        runBlocking {
            assumeTrue(Authenticator.availability().isAvailable)
            repeat(2) {
                val prompt = AuthenticationPrompt(reason = "run the smoke test #$it")
                val job = launch { Authenticator.authenticate(prompt) }
                delay(PROMPT_UP_MILLIS)
                assertTrue(job.isActive, "the prompt returned before anyone answered")
                job.cancel()
                withTimeout(DISMISS_MILLIS) { job.join() }
            }
        }

    @Test
    fun cancelledBeforeStartShowsNothing() =
        runBlocking {
            val job = launch { Authenticator.authenticate(AuthenticationPrompt(reason = "never shown")) }
            job.cancel()
            withTimeout(DISMISS_MILLIS) { job.join() }
        }

    @Test
    fun blankReasonNeverReachesThePlatform() =
        runBlocking {
            val e = assertFailsWith<AuthenticationException> { Authenticator.authenticate(AuthenticationPrompt(" ")) }
            assertEquals(AuthenticationError.InvalidPrompt, e.error)
        }

    @Test
    fun cancellationSurfacesAsCancellation() =
        runBlocking {
            assumeTrue(Authenticator.availability().isAvailable)
            var thrown: Throwable? = null
            val job =
                launch {
                    try {
                        Authenticator.authenticate(AuthenticationPrompt(reason = "check cancellation"))
                    } catch (e: Throwable) {
                        thrown = e
                        throw e
                    }
                }
            delay(PROMPT_UP_MILLIS)
            job.cancel()
            job.join()
            assertTrue(thrown is CancellationException, "expected CancellationException, got $thrown")
        }

    private companion object {
        const val PROMPT_UP_MILLIS = 1_500L
        const val DISMISS_MILLIS = 5_000L
    }
}
