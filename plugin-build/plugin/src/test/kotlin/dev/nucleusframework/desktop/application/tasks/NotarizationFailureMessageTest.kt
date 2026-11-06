package dev.nucleusframework.desktop.application.tasks

import dev.nucleusframework.desktop.application.internal.validation.NotarizationAuth
import dev.nucleusframework.desktop.application.internal.validation.toNotaryToolArgs
import dev.nucleusframework.desktop.application.internal.validation.toNotaryToolCredentialValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build logs outlive the build and CI retains them, so no credential may reach a failure report. */
class NotarizationFailureMessageTest {
    private val auths =
        listOf(
            NotarizationAuth.AppleId(appleID = "dev@example.com", password = "secret", teamID = "TEAM123"),
            NotarizationAuth.KeychainProfile(profileName = "ci-profile", keychainPath = "/tmp/ci.keychain"),
            NotarizationAuth.ApiKey(keyPath = "/tmp/AuthKey.p8", keyId = "KEY123", issuerId = "ISSUER456"),
        )

    @Test
    fun `failure message never carries a credential`() {
        for (auth in auths) {
            val message =
                notarizationFailureMessage(
                    packageFileName = "App.dmg",
                    submissionId = "2efe2717-52ef-43a5-96dc-0797e4ca1041",
                    exitCode = 1,
                    appleLog = null,
                )

            assertTrue(message, "xcrun notarytool log 2efe2717-52ef-43a5-96dc-0797e4ca1041" in message)
            for (credential in auth.toNotaryToolCredentialValues()) {
                assertFalse(message, credential in message)
            }
        }
    }

    @Test
    fun `every notarytool auth value is masked as a credential`() {
        for (auth in auths) {
            val values = auth.toNotaryToolArgs().first.filterNot { it.startsWith("--") }

            assertEquals(emptyList<String>(), values - auth.toNotaryToolCredentialValues())
        }
    }
}
