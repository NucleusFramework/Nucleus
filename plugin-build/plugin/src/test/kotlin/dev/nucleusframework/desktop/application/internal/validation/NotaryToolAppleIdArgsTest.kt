package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class NotaryToolAppleIdArgsTest {
    @Test
    fun `apple id auth keeps password in stdin`() {
        val (args, stdin) =
            NotarizationAuth.AppleId(
                appleID = "developer@example.com",
                password = "app-specific-password",
                teamID = "TEAM123",
            ).toNotaryToolArgs()

        assertEquals(listOf("--apple-id", "developer@example.com", "--team-id", "TEAM123"), args)
        assertEquals("app-specific-password", stdin)
    }
}
