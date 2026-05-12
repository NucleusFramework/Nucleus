package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class MacOSSigningDeveloperIdTest {
    @Test
    fun `developer id prefix is added for non app store signing`() {
        val settings =
            ValidatedMacOSSigningSettings(
                bundleID = "dev.nucleusframework.app",
                identity = "Nucleus Framework (TEAM123)",
                keychain = null,
                prefix = "dev.nucleusframework.",
                appStore = false,
            )

        assertEquals("Developer ID Application: Nucleus Framework (TEAM123)", settings.fullDeveloperID)
    }
}
