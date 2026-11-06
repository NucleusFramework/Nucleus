package dev.nucleusframework.lab.probes.system.authentication

import dev.nucleusframework.authentication.AuthenticationError
import dev.nucleusframework.authentication.AuthenticationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationContractTest {
    private val invalid = AuthOutcome.Failed(AuthenticationError.InvalidPrompt, "The reason is blank")

    @Test
    fun `only the scripted modes have an expectation`() {
        assertNull(AuthMode.Answer.isMetBy(AuthOutcome.Succeeded))
        assertTrue(AuthMode.AppCancel.isMetBy(AuthOutcome.Cancelled) == true)
        // The user answering before the Lab cancels is a finding: the cancel was not exercised.
        assertFalse(AuthMode.AppCancel.isMetBy(AuthOutcome.Succeeded) == true)
        // No prompt to cancel (Touch ID not paired): not exercised, not a failure.
        assertNull(AuthMode.AppCancel.isMetBy(AuthOutcome.Failed(AuthenticationError.Unavailable, "not paired")))
        assertTrue(AuthMode.BlankReason.isMetBy(invalid) == true)
        assertFalse(AuthMode.BlankReason.isMetBy(AuthOutcome.Failed(AuthenticationError.Platform, "")) == true)
    }

    @Test
    fun `a finished attempt fills in its own row only`() {
        val state =
            listOf(
                AuthEvent.Started(
                    AuthAttempt(1, 0, AuthenticationPolicy.DeviceOwner, AuthParentChoice.LabWindow, AuthMode.Answer),
                ),
                AuthEvent.Started(
                    AuthAttempt(2, 0, AuthenticationPolicy.Biometrics, AuthParentChoice.Auto, AuthMode.BlankReason),
                ),
                AuthEvent.Finished(2, invalid, 3),
            ).fold(AuthState(), AuthReducer::reduce)
        assertNull(state.attempts[0].outcome)
        assertEquals(invalid, state.attempts[1].outcome)
        assertEquals(3, state.attempts[1].millis)
    }
}
