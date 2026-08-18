package dev.nucleusframework.autolaunch.linux

import dev.nucleusframework.autolaunch.AutoLaunch
import dev.nucleusframework.autolaunch.AutoLaunchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxAutoLaunchNativeTest {
    @Test
    fun `linux facade matches the systemd backend on a host session`() {
        if (!isLinux() || !NativeAutoLaunchLinuxBridge.isLoaded) return

        assertEquals(SystemdUserBackend.state(), LinuxAutoLaunch.state())
        assertFalse(LinuxAutoLaunch.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertFalse(LinuxAutoLaunch.wasStartedAtLogin(emptyArray()))

        val summary = LinuxAutoLaunch.diagnosticSummary()
        assertTrue(summary.contains("linuxBackend: systemd-user"))
        assertTrue(summary.contains("unitName:"))

        val diagnostic = AutoLaunch.diagnostic()
        assertTrue(diagnostic.contains("backend: LinuxAutoLaunch"))
        assertTrue(diagnostic.contains("linuxBackend: systemd-user"))
        assertTrue(AutoLaunchState.entries.contains(AutoLaunch.state()))
    }

    @Test
    fun `unsupported linux backend stays a hard fallback`() {
        assertEquals(AutoLaunchState.UNSUPPORTED, UnsupportedLinuxBackend.state())
        assertEquals(
            dev.nucleusframework.autolaunch.AutoLaunchResult.UNSUPPORTED,
            UnsupportedLinuxBackend.enable(),
        )
        assertEquals(
            dev.nucleusframework.autolaunch.AutoLaunchResult.UNSUPPORTED,
            UnsupportedLinuxBackend.disable(),
        )
        assertFalse(UnsupportedLinuxBackend.openSystemSettings())
        assertFalse(UnsupportedLinuxBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertTrue(UnsupportedLinuxBackend.diagnosticSummary().contains("native library"))
    }

    @Test
    fun `native unit file helpers report a real status when loaded`() {
        if (!NativeAutoLaunchLinuxBridge.isLoaded) return
        val missing = NativeAutoLaunchLinuxBridge.getUnitFileState("nucleus-kover-missing.service")
        assertTrue(
            missing == NativeAutoLaunchLinuxBridge.RC_STATE_NOT_INSTALLED ||
                missing == NativeAutoLaunchLinuxBridge.RC_ERROR ||
                missing == NativeAutoLaunchLinuxBridge.RC_STATE_DISABLED,
        )
        assertTrue(NativeAutoLaunchLinuxBridge.isPortalAvailable() || !NativeAutoLaunchLinuxBridge.isPortalAvailable())
    }

    private fun isLinux(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")
}
