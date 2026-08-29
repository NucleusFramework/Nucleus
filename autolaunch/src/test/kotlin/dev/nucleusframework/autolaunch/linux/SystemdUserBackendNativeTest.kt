package dev.nucleusframework.autolaunch.linux

import dev.nucleusframework.autolaunch.AutoLaunchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemdUserBackendNativeTest {
    @Test
    fun `systemd backend reports a real unit state without enabling login launch`() {
        if (!NativeAutoLaunchLinuxBridge.isLoaded) return

        val state = SystemdUserBackend.state()
        assertTrue(
            state == AutoLaunchState.ENABLED ||
                state == AutoLaunchState.DISABLED ||
                state == AutoLaunchState.UNSUPPORTED,
        )
        assertFalse(SystemdUserBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertFalse(SystemdUserBackend.wasStartedAtLogin(emptyArray()))

        val summary = SystemdUserBackend.diagnosticSummary()
        assertTrue(summary.contains("linuxBackend: systemd-user"))
        assertTrue(summary.contains("unitName:"))
        assertTrue(summary.contains(".service"))
        assertTrue(summary.contains("cgroup:"))
        NativeAutoLaunchLinuxBridge.getDiagnostic()
    }

    @Test
    fun `generated unit waits for the DE appearance settings before starting`() {
        val unit = SystemdUserBackend.buildUnitContent("/opt/demo/bin/demo")

        assertTrue(unit.contains("After=graphical-session.target"))
        // gsd-xsettings publishes Xft.dpi only once this target is reached; without it a
        // login-started app freezes sun.java2d.uiScale at 1.0 on HiDPI GNOME sessions.
        assertTrue(unit.contains("gnome-session-x11-services-ready.target"))
        assertTrue(unit.contains("PartOf=graphical-session.target"))
        assertTrue(unit.contains("WantedBy=graphical-session.target"))
        assertTrue(unit.contains("""ExecStart="/opt/demo/bin/demo""""))
    }

    @Test
    fun `generated unit quotes an executable path with spaces and quotes`() {
        val unit = SystemdUserBackend.buildUnitContent("""/opt/my app/bin/"demo"""")

        assertTrue(unit.contains("""ExecStart="/opt/my app/bin/\"demo\"""""))
    }

    @Test
    fun `native constants stay aligned with the jni contract`() {
        assertEquals(0, NativeAutoLaunchLinuxBridge.RC_OK)
        assertEquals(-1, NativeAutoLaunchLinuxBridge.RC_ERROR)
        assertEquals(-2, NativeAutoLaunchLinuxBridge.RC_USER_DENIED)
        assertEquals(-3, NativeAutoLaunchLinuxBridge.RC_NO_PORTAL)
        assertEquals(0, NativeAutoLaunchLinuxBridge.RC_STATE_DISABLED)
        assertEquals(1, NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED)
        assertEquals(2, NativeAutoLaunchLinuxBridge.RC_STATE_ENABLED_RUNTIME)
        assertEquals(-2, NativeAutoLaunchLinuxBridge.RC_STATE_NOT_INSTALLED)
    }
}
