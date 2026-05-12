package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class MacOSSigningBareIdentityTest {
    @Test
    fun `bare identity strips known certificate prefixes`() {
        val settings =
            ValidatedMacOSSigningSettings(
                bundleID = "dev.nucleusframework.app",
                identity = "Developer ID Installer: Nucleus Framework (TEAM123)",
                keychain = null,
                prefix = "dev.nucleusframework.",
                appStore = false,
            )

        assertEquals("Nucleus Framework (TEAM123)", settings.bareIdentityName)
    }
}
