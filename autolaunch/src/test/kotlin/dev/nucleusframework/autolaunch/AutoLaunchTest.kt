package dev.nucleusframework.autolaunch

import dev.nucleusframework.autolaunch.linux.LinuxAutoLaunch
import dev.nucleusframework.autolaunch.linux.SystemdUserBackend
import dev.nucleusframework.autolaunch.linux.UnsupportedLinuxBackend
import dev.nucleusframework.autolaunch.windows.WindowsAutoLaunch
import dev.nucleusframework.autolaunch.windows.Win32RegistryBackend
import dev.nucleusframework.core.runtime.ExecutableRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoLaunchTest {
    private val previousType = System.getProperty(ExecutableRuntime.TYPE_PROPERTY)
    private val previousMarker = AutoLaunchConfig.autostartArgument
    private val previousTaskId = AutoLaunchConfig.taskId
    private val previousExe = AutoLaunchConfig.executablePath
    private val previousRegistry = AutoLaunchConfig.registryValueName
    private val previousReason = AutoLaunchConfig.backgroundReason

    @AfterTest
    fun restore() {
        restoreProperty(ExecutableRuntime.TYPE_PROPERTY, previousType)
        AutoLaunchConfig.autostartArgument = previousMarker
        AutoLaunchConfig.taskId = previousTaskId
        AutoLaunchConfig.executablePath = previousExe
        AutoLaunchConfig.registryValueName = previousRegistry
        AutoLaunchConfig.backgroundReason = previousReason
    }

    @Test
    fun `dev mode short-circuits wasStartedAtLogin`() {
        restoreProperty(ExecutableRuntime.TYPE_PROPERTY, null)
        assertTrue(ExecutableRuntime.isDev())
        assertFalse(AutoLaunch.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertFalse(AutoLaunch.wasStartedAtLogin(emptyArray()))
    }

    @Test
    fun `state helpers do not throw and match the resolved state`() {
        AutoLaunch.preload()
        val state = AutoLaunch.state()
        assertTrue(AutoLaunchState.entries.contains(state))
        assertEquals(
            state == AutoLaunchState.ENABLED || state == AutoLaunchState.ENABLED_BY_POLICY,
            AutoLaunch.isEnabled(),
        )
        assertEquals(state == AutoLaunchState.DISABLED_BY_USER, AutoLaunch.isUserLocked())
    }

    @Test
    fun `enable and disable report unsupported or leave a disabled host unchanged`() {
        when (val state = AutoLaunch.state()) {
            AutoLaunchState.UNSUPPORTED -> {
                assertEquals(AutoLaunchResult.UNSUPPORTED, AutoLaunch.enable())
                val disable = AutoLaunch.disable()
                assertTrue(
                    disable == AutoLaunchResult.UNSUPPORTED || disable == AutoLaunchResult.UNCHANGED,
                    "disable on UNSUPPORTED must not mutate: $disable",
                )
            }
            AutoLaunchState.DISABLED, AutoLaunchState.DISABLED_BY_POLICY -> {
                assertEquals(AutoLaunchResult.UNCHANGED, AutoLaunch.disable())
                assertFalse(AutoLaunch.isEnabled())
            }
            AutoLaunchState.DISABLED_BY_USER -> {
                assertEquals(AutoLaunchResult.BLOCKED_BY_USER, AutoLaunch.enable())
                assertFalse(AutoLaunch.isEnabled())
            }
            AutoLaunchState.ENABLED, AutoLaunchState.ENABLED_BY_POLICY -> {
                assertTrue(AutoLaunch.isEnabled())
            }
        }
    }

    @Test
    fun `diagnostic summarizes backend and os`() {
        val text = AutoLaunch.diagnostic()
        assertTrue(text.contains("backend:"))
        assertTrue(text.contains("os.name:"))
        assertTrue(text.contains("executableType:"))
    }

    @Test
    fun `containsAutostartMarker respects the configured argument`() {
        AutoLaunchConfig.autostartArgument = "--nucleus-autostart"
        assertTrue(containsAutostartMarker(arrayOf("--foo", "--nucleus-autostart")))
        assertFalse(containsAutostartMarker(arrayOf("--foo")))

        AutoLaunchConfig.autostartArgument = null
        assertFalse(containsAutostartMarker(arrayOf("--nucleus-autostart")))

        AutoLaunchConfig.autostartArgument = "   "
        assertFalse(containsAutostartMarker(arrayOf("   ")))
    }

    @Test
    fun `noop backend is a hard unsupported fallback`() {
        assertEquals(AutoLaunchState.UNSUPPORTED, NoOpAutoLaunchBackend.state())
        assertEquals(AutoLaunchResult.UNSUPPORTED, NoOpAutoLaunchBackend.enable())
        assertEquals(AutoLaunchResult.UNSUPPORTED, NoOpAutoLaunchBackend.disable())
        assertFalse(NoOpAutoLaunchBackend.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
        assertFalse(NoOpAutoLaunchBackend.openSystemSettings())
        assertEquals("", NoOpAutoLaunchBackend.diagnosticSummary())
    }

    @Test
    fun `linux and windows backends fall back off their host OS`() {
        if (!isWindows()) {
            assertEquals(AutoLaunchState.UNSUPPORTED, WindowsAutoLaunch.state())
            assertEquals(AutoLaunchResult.UNSUPPORTED, WindowsAutoLaunch.enable())
            assertEquals(AutoLaunchResult.UNSUPPORTED, WindowsAutoLaunch.disable())
            assertEquals(AutoLaunchState.UNSUPPORTED, Win32RegistryBackend.state())
            assertEquals(AutoLaunchResult.UNSUPPORTED, Win32RegistryBackend.enable())
            assertEquals(AutoLaunchResult.UNSUPPORTED, Win32RegistryBackend.disable())
        }
        if (!isLinux()) {
            assertEquals(AutoLaunchState.UNSUPPORTED, LinuxAutoLaunch.state())
            assertEquals(AutoLaunchResult.UNSUPPORTED, LinuxAutoLaunch.enable())
            assertEquals(AutoLaunchResult.UNSUPPORTED, LinuxAutoLaunch.disable())
            assertFalse(LinuxAutoLaunch.wasStartedAtLogin(arrayOf("--nucleus-autostart")))
            assertEquals(AutoLaunchState.UNSUPPORTED, UnsupportedLinuxBackend.state())
            assertEquals(AutoLaunchResult.UNSUPPORTED, UnsupportedLinuxBackend.enable())
            assertEquals(AutoLaunchResult.UNSUPPORTED, UnsupportedLinuxBackend.disable())
            assertFalse(UnsupportedLinuxBackend.wasStartedAtLogin(arrayOf("x")))
            assertTrue(UnsupportedLinuxBackend.diagnosticSummary().contains("native library"))
            assertFalse(SystemdUserBackend.wasStartedAtLogin(emptyArray()))
            assertTrue(SystemdUserBackend.diagnosticSummary().contains("linuxBackend"))
        }
    }

    @Test
    fun `config overrides are writable and restorable`() {
        AutoLaunchConfig.taskId = "com.example.Startup"
        AutoLaunchConfig.executablePath = "/tmp/app"
        AutoLaunchConfig.registryValueName = "KoverTest"
        AutoLaunchConfig.backgroundReason = "tests"
        AutoLaunchConfig.autostartArgument = "--kover-autostart"
        assertEquals("com.example.Startup", AutoLaunchConfig.taskId)
        assertEquals("/tmp/app", AutoLaunchConfig.executablePath)
        assertEquals("KoverTest", AutoLaunchConfig.registryValueName)
        assertEquals("tests", AutoLaunchConfig.backgroundReason)
        assertEquals("--kover-autostart", AutoLaunchConfig.autostartArgument)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun isLinux(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")

    private fun restoreProperty(
        name: String,
        value: String?,
    ) {
        if (value == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, value)
        }
    }
}
