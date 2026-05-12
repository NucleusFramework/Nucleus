package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotaryToolApiKeyArgsTest {
    @Test
    fun `api key auth maps to notarytool flags`() {
        val (args, stdin) =
            NotarizationAuth.ApiKey(
                keyPath = "/tmp/AuthKey.p8",
                keyId = "KEY123",
                issuerId = "ISSUER456",
            ).toNotaryToolArgs()

        assertEquals(listOf("--key", "/tmp/AuthKey.p8", "--key-id", "KEY123", "--issuer", "ISSUER456"), args)
        assertNull(stdin)
    }
}
