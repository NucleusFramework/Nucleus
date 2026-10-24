package dev.nucleusframework.energymanager

import dev.nucleusframework.energymanager.linux.LinuxEnergyManager
import dev.nucleusframework.energymanager.windows.WindowsEnergyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyManagerApiTest {
    @After
    fun tearDown() {
        EnergyManager.resetAwakeForTests()
    }

    @Test
    fun `deprecated screen-awake aliases delegate to keepAwake`() {
        assumeAvailable()
        try {
            val start = System.nanoTime()
            val kept = EnergyManager.keepScreenAwake()
            val elapsedMs = (System.nanoTime() - start) / NANOS_PER_MILLI
            assertTrue(kept.success, "keepScreenAwake failed: ${awakeDiagnostics(kept, elapsedMs)}")
            assertTrue(EnergyManager.isScreenAwakeActive())
            assertTrue(EnergyManager.isAwakeActive())
        } finally {
            val released = EnergyManager.releaseScreenAwake()
            assertTrue(released.success, "releaseScreenAwake failed: $released")
        }
        assertFalse(EnergyManager.isScreenAwakeActive())
    }

    /**
     * What a failed keep-awake leaves to go on. The Linux bridge only says that no backend took
     * the request, so this adds which backends the environment offers (GNOME needs a session bus,
     * X11 a display, logind the system bus) and how long the attempt took: each D-Bus call gives
     * up after 2 s, so a multiple of that is a timeout, not a refusal.
     */
    private fun awakeDiagnostics(
        result: EnergyManager.Result,
        elapsedMs: Long,
    ): String =
        "$result after ${elapsedMs}ms; " +
            "DBUS_SESSION_BUS_ADDRESS=${System.getenv("DBUS_SESSION_BUS_ADDRESS") != null} " +
            "DISPLAY=${System.getenv("DISPLAY")} WAYLAND_DISPLAY=${System.getenv("WAYLAND_DISPLAY")} " +
            "system bus socket=${File("/run/dbus/system_bus_socket").exists()} " +
            "XDG_SESSION_ID=${System.getenv("XDG_SESSION_ID")}"

    @Test
    fun `withLightEfficiencyMode returns the block value`() =
        runBlocking {
            assumeAvailable()
            val value =
                EnergyManager.withLightEfficiencyMode {
                    7
                }
            assertEquals(7, value)
        }

    @Test
    fun `result carries success code and message`() {
        val ok = EnergyManager.Result(true)
        assertTrue(ok.success)
        assertEquals(0, ok.errorCode)
        assertEquals("", ok.message)
        val fail = EnergyManager.Result(false, -1, "nope")
        assertFalse(fail.success)
        assertEquals(-1, fail.errorCode)
        assertEquals("nope", fail.message)
    }

    @Test
    fun `linux energy manager falls back off linux`() {
        if (isLinux()) return
        assertFalse(LinuxEnergyManager.isAvailable())
        assertFalse(LinuxEnergyManager.enableEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.disableEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.enableLightEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.disableLightEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.enableThreadEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.disableThreadEfficiencyMode().success)
        assertFalse(LinuxEnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY).success)
        assertFalse(LinuxEnergyManager.releaseAwake().success)
        assertFalse(LinuxEnergyManager.isAwakeActive())
    }

    @Test
    fun `windows energy manager falls back off windows`() {
        if (isWindows()) return
        assertFalse(WindowsEnergyManager.isAvailable())
        assertFalse(WindowsEnergyManager.enableEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.disableEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.enableLightEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.disableLightEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.enableThreadEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.disableThreadEfficiencyMode().success)
        assertFalse(WindowsEnergyManager.keepAwake(AwakeMode.SYSTEM_AND_DISPLAY).success)
        assertFalse(WindowsEnergyManager.releaseAwake().success)
        assertFalse(WindowsEnergyManager.isAwakeActive())
    }

    private fun assumeAvailable() {
        assumeTrue("Energy manager not available", EnergyManager.isAvailable())
    }

    private fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
