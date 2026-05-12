package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotaryToolKeychainArgsTest {
    @Test
    fun `keychain profile auth includes optional keychain path`() {
        val (args, stdin) =
            NotarizationAuth.KeychainProfile(
                profileName = "nucleus-profile",
                keychainPath = "/tmp/login.keychain-db",
            ).toNotaryToolArgs()

        assertEquals(listOf("--keychain-profile", "nucleus-profile", "--keychain", "/tmp/login.keychain-db"), args)
        assertNull(stdin)
    }
}
