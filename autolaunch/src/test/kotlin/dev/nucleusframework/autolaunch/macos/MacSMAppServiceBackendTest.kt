package dev.nucleusframework.autolaunch.macos

import dev.nucleusframework.autolaunch.AutoLaunchState
import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacSMAppServiceBackendTest {
    @Test
    fun `state and diagnostic are readable without registering a login item`() {
        if (Platform.Current != Platform.MacOS) return

        val state = MacSMAppServiceBackend.state()
        assertTrue(
            state == AutoLaunchState.ENABLED ||
                state == AutoLaunchState.DISABLED ||
                state == AutoLaunchState.DISABLED_BY_USER ||
                state == AutoLaunchState.UNSUPPORTED,
            "unexpected state $state",
        )
        val diagnostic = MacSMAppServiceBackend.diagnosticSummary()
        assertTrue(diagnostic.contains("serviceManagement.isAvailable:"))
        assertTrue(diagnostic.contains("SMAppService.mainApp"))
        assertTrue(diagnostic.contains("LaunchInstanceID present:"))
        assertEquals(System.getenv("LaunchInstanceID") != null, MacSMAppServiceBackend.wasStartedAtLogin(emptyArray()))
        assertFalse(MacSMAppServiceBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
    }
}
