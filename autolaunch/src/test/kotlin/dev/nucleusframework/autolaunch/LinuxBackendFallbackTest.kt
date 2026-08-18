package dev.nucleusframework.autolaunch

import dev.nucleusframework.autolaunch.linux.FlatpakPortalBackend
import dev.nucleusframework.autolaunch.linux.NativeAutoLaunchLinuxBridge
import dev.nucleusframework.autolaunch.linux.SystemdUserBackend
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxBackendFallbackTest {
    private val previousMarker = AutoLaunchConfig.autostartArgument
    private val previousExe = AutoLaunchConfig.executablePath
    private val previousReason = AutoLaunchConfig.backgroundReason

    @AfterTest
    fun restore() {
        AutoLaunchConfig.autostartArgument = previousMarker
        AutoLaunchConfig.executablePath = previousExe
        AutoLaunchConfig.backgroundReason = previousReason
    }

    @Test
    fun `native linux bridge reports unloaded fallbacks off linux`() {
        if (isLinux() && NativeAutoLaunchLinuxBridge.isLoaded) return
        assertFalse(NativeAutoLaunchLinuxBridge.isLoaded)
        assertFalse(NativeAutoLaunchLinuxBridge.isPortalAvailable())
        assertEquals(
            NativeAutoLaunchLinuxBridge.RC_NO_PORTAL,
            NativeAutoLaunchLinuxBridge.requestBackground(true, arrayOf("app"), "reason"),
        )
        assertEquals(
            NativeAutoLaunchLinuxBridge.RC_ERROR,
            NativeAutoLaunchLinuxBridge.writeUnitFile("x.service", "[Unit]"),
        )
        assertEquals(NativeAutoLaunchLinuxBridge.RC_ERROR, NativeAutoLaunchLinuxBridge.deleteUnitFile("x.service"))
        assertEquals(NativeAutoLaunchLinuxBridge.RC_ERROR, NativeAutoLaunchLinuxBridge.enableUnit("x.service"))
        assertEquals(NativeAutoLaunchLinuxBridge.RC_ERROR, NativeAutoLaunchLinuxBridge.disableUnit("x.service"))
        assertEquals(NativeAutoLaunchLinuxBridge.RC_ERROR, NativeAutoLaunchLinuxBridge.getUnitFileState("x.service"))
        assertEquals("(native not loaded)", NativeAutoLaunchLinuxBridge.getDiagnostic())
        assertEquals(0, NativeAutoLaunchLinuxBridge.RC_OK)
        assertEquals(-1, NativeAutoLaunchLinuxBridge.RC_ERROR)
        assertEquals(-2, NativeAutoLaunchLinuxBridge.RC_USER_DENIED)
        assertEquals(-3, NativeAutoLaunchLinuxBridge.RC_NO_PORTAL)
        assertEquals(0, NativeAutoLaunchLinuxBridge.RC_STATE_DISABLED)
        assertEquals(1, NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED)
        assertEquals(2, NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED_RUNTIME)
        assertEquals(-2, NativeAutoLaunchLinuxBridge.RC_STATE_NOT_INSTALLED)
    }

    @Test
    fun `flatpak portal is unsupported without the session bus`() {
        AutoLaunchConfig.autostartArgument = "--nucleus-autostart"
        AutoLaunchConfig.executablePath = "/app/bin/App"
        AutoLaunchConfig.backgroundReason = "coverage"
        if (!NativeAutoLaunchLinuxBridge.isPortalAvailable()) {
            assertEquals(AutoLaunchState.UNSUPPORTED, FlatpakPortalBackend.state())
            assertEquals(AutoLaunchResult.UNSUPPORTED, FlatpakPortalBackend.enable())
            assertEquals(AutoLaunchResult.UNSUPPORTED, FlatpakPortalBackend.disable())
        }
        assertTrue(FlatpakPortalBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertFalse(FlatpakPortalBackend.wasStartedAtLogin(arrayOf("--other")))
        val summary = FlatpakPortalBackend.diagnosticSummary()
        assertTrue(summary.contains("linuxBackend: flatpak-portal"))
        assertTrue(summary.contains("portalAvailable:"))
        assertTrue(summary.contains("markerFile:"))
        if (!isLinux()) {
            assertFalse(FlatpakPortalBackend.openSystemSettings())
        }
    }

    @Test
    fun `systemd user backend enable fails without the native library`() {
        if (NativeAutoLaunchLinuxBridge.isLoaded) return
        AutoLaunchConfig.executablePath = "/opt/My App/bin/App"
        assertEquals(AutoLaunchState.UNSUPPORTED, SystemdUserBackend.state())
        assertEquals(AutoLaunchResult.ERROR, SystemdUserBackend.enable())
        // state() is UNSUPPORTED, so disable still attempts native cleanup and reports OK.
        assertEquals(AutoLaunchResult.OK, SystemdUserBackend.disable())
        assertFalse(SystemdUserBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        val summary = SystemdUserBackend.diagnosticSummary()
        assertTrue(summary.contains("linuxBackend: systemd-user"))
        assertTrue(summary.contains("unitName:"))
        assertTrue(summary.contains(".service"))
        if (!isLinux()) {
            assertFalse(SystemdUserBackend.openSystemSettings())
        }
    }

    private fun isLinux(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")
}
