package dev.nucleusframework.autolaunch

import dev.nucleusframework.autolaunch.windows.NativeAutoLaunchBridge
import dev.nucleusframework.autolaunch.windows.Win32RegistryBackend
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the Win32 registry backend. Writes under a unique
 * test value name that is always cleaned up in [afterEach].
 *
 * Gated on Windows by the Gradle `test` task configuration.
 */
class Win32RegistryBackendTest {
    private val testValueName = "NucleusAutoLaunchTest-${System.nanoTime()}"

    @BeforeTest
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name").lowercase().contains("win"),
            "Win32 registry tests only run on Windows",
        )
        assertTrue(NativeAutoLaunchBridge.isLoaded, "native bridge must load on Windows")
        AutoLaunchConfig.registryValueName = testValueName
        AutoLaunchConfig.executablePath = "C:\\Windows\\System32\\notepad.exe"
        AutoLaunchConfig.autostartArgument = "--nucleus-autostart"
        cleanup()
    }

    @AfterTest
    fun tearDown() {
        if (!System.getProperty("os.name").lowercase().contains("win")) return
        cleanup()
        AutoLaunchConfig.registryValueName = null
        AutoLaunchConfig.executablePath = null
    }

    private fun cleanup() {
        NativeAutoLaunchBridge.regDeleteRun(testValueName, alsoStartupApproved = true)
    }

    @Test
    fun `state is DISABLED when no Run entry`() {
        assertEquals(AutoLaunchState.DISABLED, Win32RegistryBackend.state())
    }

    @Test
    fun `enable writes Run value and state becomes ENABLED`() {
        assertEquals(AutoLaunchResult.OK, Win32RegistryBackend.enable())
        assertEquals(AutoLaunchState.ENABLED, Win32RegistryBackend.state())
    }

    @Test
    fun `enable twice returns UNCHANGED the second time`() {
        assertEquals(AutoLaunchResult.OK, Win32RegistryBackend.enable())
        assertEquals(AutoLaunchResult.UNCHANGED, Win32RegistryBackend.enable())
    }

    @Test
    fun `disable removes Run entry and state is DISABLED`() {
        Win32RegistryBackend.enable()
        assertEquals(AutoLaunchResult.OK, Win32RegistryBackend.disable())
        assertEquals(AutoLaunchState.DISABLED, Win32RegistryBackend.state())
    }

    @Test
    fun `disable when absent returns UNCHANGED`() {
        assertEquals(AutoLaunchResult.UNCHANGED, Win32RegistryBackend.disable())
    }
}
