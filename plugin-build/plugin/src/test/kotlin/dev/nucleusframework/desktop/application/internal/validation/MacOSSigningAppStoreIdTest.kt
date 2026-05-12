package dev.nucleusframework.desktop.application.internal.validation

import org.junit.Assert.assertEquals
import org.junit.Test

class MacOSSigningAppStoreIdTest {
    @Test
    fun `app store signing uses third party mac developer prefix`() {
        val settings =
            ValidatedMacOSSigningSettings(
                bundleID = "dev.nucleusframework.app",
                identity = "Nucleus Framework (TEAM123)",
                keychain = null,
                prefix = "dev.nucleusframework.",
                appStore = true,
            )

        assertEquals("3rd Party Mac Developer Application: Nucleus Framework (TEAM123)", settings.fullDeveloperID)
    }
}
