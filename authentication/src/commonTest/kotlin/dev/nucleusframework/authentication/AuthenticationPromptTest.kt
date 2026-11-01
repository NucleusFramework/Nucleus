package dev.nucleusframework.authentication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticationPromptTest {
    @Test
    fun blankReasonIsRejected() {
        for (reason in listOf("", "   ")) {
            val e = assertFailsWith<AuthenticationException> { AuthenticationPrompt(reason).validate() }
            assertEquals(AuthenticationError.InvalidPrompt, e.error)
        }
    }

    @Test
    fun reasonAloneIsEnough() {
        AuthenticationPrompt("unlock the vault").validate()
    }

    @Test
    fun exceptionMessageDefaultsToTheError() {
        assertEquals("Lockout", AuthenticationException(AuthenticationError.Lockout).message)
    }
}
