package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacOSSigningTeamIdTest {
    @Test
    fun `team id is extracted from identity suffix`() {
        val settings =
            ValidatedMacOSSigningSettings(
                bundleID = "dev.nucleusframework.app",
                identity = "Developer ID Application: Nucleus Framework (TEAM123)",
                keychain = null,
                prefix = "dev.nucleusframework.",
                appStore = false,
            )

        assertEquals("TEAM123", settings.teamID)
    }

    @Test
    fun `team id is null when identity has no suffix`() {
        val settings =
            ValidatedMacOSSigningSettings(
                bundleID = "dev.nucleusframework.app",
                identity = "Nucleus Framework",
                keychain = null,
                prefix = "dev.nucleusframework.",
                appStore = false,
            )

        assertNull(settings.teamID)
    }
}
