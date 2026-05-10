package dev.nucleusframework.nucleus.desktop.application.internal.validation

import dev.nucleusframework.nucleus.desktop.application.dsl.MacOSNotarizationSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ValidatedMacOSNotarizationSettingsTest {
    private fun newSettings(): MacOSNotarizationSettings {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(MacOSNotarizationSettings::class.java)
    }

    private fun assertFailsWith(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalStateException containing: $message")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Expected message to contain '$message', got: ${e.message}",
                e.message?.contains(message) == true,
            )
        }
    }

    @Test
    fun `null settings reject with not-provided error`() {
        val settings: MacOSNotarizationSettings? = null
        assertFailsWith("notarization settings are not provided") {
            settings.validate()
        }
    }

    @Test
    fun `no mode configured fails with explicit guidance`() {
        val settings = newSettings()
        assertFailsWith("no authentication mode configured") {
            settings.validate()
        }
    }

    @Test
    fun `complete apple-id mode validates as AppleId auth`() {
        val settings = newSettings()
        settings.appleID.set("dev@example.com")
        settings.password.set("@keychain:AC_PASSWORD")
        settings.teamID.set("TEAMID")

        val validated = settings.validate()
        val auth = validated.auth as NotarizationAuth.AppleId
        assertEquals("dev@example.com", auth.appleID)
        assertEquals("@keychain:AC_PASSWORD", auth.password)
        assertEquals("TEAMID", auth.teamID)
    }

    @Test
    fun `apple-id missing password fails`() {
        val settings = newSettings()
        settings.appleID.set("dev@example.com")
        settings.teamID.set("TEAMID")

        assertFailsWith("password is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `apple-id missing teamID fails`() {
        val settings = newSettings()
        settings.appleID.set("dev@example.com")
        settings.password.set("pwd")

        assertFailsWith("teamID is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `apple-id missing appleID fails`() {
        val settings = newSettings()
        settings.password.set("pwd")
        settings.teamID.set("TEAMID")

        assertFailsWith("appleID is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `keychainProfile alone validates with null path`() {
        val settings = newSettings()
        settings.keychainProfile.set("AC_PASSWORD")

        val validated = settings.validate()
        val auth = validated.auth as NotarizationAuth.KeychainProfile
        assertEquals("AC_PASSWORD", auth.profileName)
        assertNull(auth.keychainPath)
    }

    @Test
    fun `keychainProfile with explicit keychainPath validates`() {
        val settings = newSettings()
        settings.keychainProfile.set("AC_PASSWORD")
        settings.keychainPath.set("/Users/me/Library/Keychains/login.keychain-db")

        val validated = settings.validate()
        val auth = validated.auth as NotarizationAuth.KeychainProfile
        assertEquals("AC_PASSWORD", auth.profileName)
        assertEquals("/Users/me/Library/Keychains/login.keychain-db", auth.keychainPath)
    }

    @Test
    fun `apple-id and keychainProfile together fail with mutual-exclusivity error`() {
        val settings = newSettings()
        settings.appleID.set("dev@example.com")
        settings.password.set("pwd")
        settings.teamID.set("TEAMID")
        settings.keychainProfile.set("AC_PASSWORD")

        assertFailsWith("mutually exclusive") {
            settings.validate()
        }
    }

    @Test
    fun `empty strings are treated as not-set`() {
        val settings = newSettings()
        settings.appleID.set("")
        settings.password.set("")
        settings.teamID.set("")
        settings.keychainProfile.set("")
        settings.apiKey.set("")
        settings.apiKeyId.set("")
        settings.apiIssuer.set("")

        assertFailsWith("no authentication mode configured") {
            settings.validate()
        }
    }

    @Test
    fun `complete api-key mode validates as ApiKey auth`() {
        val settings = newSettings()
        settings.apiKey.set("/path/to/AuthKey_ABC123.p8")
        settings.apiKeyId.set("ABC123")
        settings.apiIssuer.set("12345678-90ab-cdef-1234-567890abcdef")

        val validated = settings.validate()
        val auth = validated.auth as NotarizationAuth.ApiKey
        assertEquals("/path/to/AuthKey_ABC123.p8", auth.keyPath)
        assertEquals("ABC123", auth.keyId)
        assertEquals("12345678-90ab-cdef-1234-567890abcdef", auth.issuerId)
    }

    @Test
    fun `api-key missing apiKey fails`() {
        val settings = newSettings()
        settings.apiKeyId.set("ABC123")
        settings.apiIssuer.set("issuer-uuid")

        assertFailsWith("apiKey is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `api-key missing apiKeyId fails`() {
        val settings = newSettings()
        settings.apiKey.set("/path/to/AuthKey.p8")
        settings.apiIssuer.set("issuer-uuid")

        assertFailsWith("apiKeyId is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `api-key missing apiIssuer fails`() {
        val settings = newSettings()
        settings.apiKey.set("/path/to/AuthKey.p8")
        settings.apiKeyId.set("ABC123")

        assertFailsWith("apiIssuer is null or empty") {
            settings.validate()
        }
    }

    @Test
    fun `api-key combined with apple-id fails with mutual-exclusivity error`() {
        val settings = newSettings()
        settings.appleID.set("dev@example.com")
        settings.password.set("pwd")
        settings.teamID.set("TEAMID")
        settings.apiKey.set("/path/to/AuthKey.p8")
        settings.apiKeyId.set("ABC123")
        settings.apiIssuer.set("issuer-uuid")

        assertFailsWith("mutually exclusive") {
            settings.validate()
        }
    }

    @Test
    fun `api-key combined with keychainProfile fails with mutual-exclusivity error`() {
        val settings = newSettings()
        settings.keychainProfile.set("AC_PASSWORD")
        settings.apiKey.set("/path/to/AuthKey.p8")
        settings.apiKeyId.set("ABC123")
        settings.apiIssuer.set("issuer-uuid")

        assertFailsWith("mutually exclusive") {
            settings.validate()
        }
    }

    @Test
    fun `toNotaryToolArgs builds apple-id args with password as stdin`() {
        val auth = NotarizationAuth.AppleId("dev@example.com", "pwd", "TEAMID")
        val (args, stdin) = auth.toNotaryToolArgs()
        assertEquals(listOf("--apple-id", "dev@example.com", "--team-id", "TEAMID"), args)
        assertEquals("pwd", stdin)
    }

    @Test
    fun `toNotaryToolArgs builds keychain-profile args with no stdin`() {
        val auth = NotarizationAuth.KeychainProfile("AC_PASSWORD", null)
        val (args, stdin) = auth.toNotaryToolArgs()
        assertEquals(listOf("--keychain-profile", "AC_PASSWORD"), args)
        assertNull(stdin)
    }

    @Test
    fun `toNotaryToolArgs appends keychain path when set`() {
        val auth = NotarizationAuth.KeychainProfile("AC_PASSWORD", "/path/to/login.keychain-db")
        val (args, stdin) = auth.toNotaryToolArgs()
        assertEquals(
            listOf("--keychain-profile", "AC_PASSWORD", "--keychain", "/path/to/login.keychain-db"),
            args,
        )
        assertNull(stdin)
    }

    @Test
    fun `toNotaryToolArgs builds api-key args with no stdin`() {
        val auth =
            NotarizationAuth.ApiKey(
                keyPath = "/path/to/AuthKey.p8",
                keyId = "ABC123",
                issuerId = "issuer-uuid",
            )
        val (args, stdin) = auth.toNotaryToolArgs()
        assertEquals(
            listOf(
                "--key", "/path/to/AuthKey.p8",
                "--key-id", "ABC123",
                "--issuer", "issuer-uuid",
            ),
            args,
        )
        assertNull(stdin)
    }
}
