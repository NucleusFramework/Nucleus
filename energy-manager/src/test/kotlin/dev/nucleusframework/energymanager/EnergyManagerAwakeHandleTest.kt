package dev.nucleusframework.energymanager

import dev.nucleusframework.energymanager.windows.NativeWindowsEnergyBridge
import dev.nucleusframework.energymanager.windows.WindowsEnergyManager
import org.junit.After
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnergyManagerAwakeHandleTest {
    @After
    fun tearDown() {
        EnergyManager.resetAwakeForTests()
    }

    @Test
    fun `acquireAwake round trips`() {
        assumeAvailable()
        val handle = EnergyManager.acquireAwake()
        try {
            assertTrue(handle.isActive)
            assertTrue(EnergyManager.isAwakeActive())
        } finally {
            handle.close()
        }
        assertFalse(handle.isActive)
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `closing one of two handles leaves the request active`() {
        assumeAvailable()
        val first = EnergyManager.acquireAwake()
        val second = EnergyManager.acquireAwake()
        first.close()
        assertTrue(EnergyManager.isAwakeActive())
        assertFalse(first.isActive)
        assertTrue(second.isActive)
        second.close()
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `releaseAwake does not drop a live acquireAwake handle`() {
        assumeAvailable()
        EnergyManager.keepAwake()
        val handle = EnergyManager.acquireAwake()
        assertTrue(EnergyManager.releaseAwake().success)
        assertTrue(EnergyManager.isAwakeActive(), "handle must keep the OS request alive")
        handle.close()
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `close is idempotent`() {
        assumeAvailable()
        val handle = EnergyManager.acquireAwake()
        handle.close()
        handle.close()
        assertFalse(handle.isActive)
        assertFalse(EnergyManager.isAwakeActive())
    }

    @Test
    fun `windows display handle wins over a system-only keepAwake`() {
        assumeWindows()
        assumeAvailable()

        EnergyManager.keepAwake(AwakeMode.SYSTEM_ONLY)
        val handle = EnergyManager.acquireAwake(AwakeMode.SYSTEM_AND_DISPLAY)
        val withDisplay = queryAwakeFlags()
        handle.close()
        val systemOnly = queryAwakeFlags()
        EnergyManager.releaseAwake()

        assertTrue(withDisplay and ES_SYSTEM_REQUIRED != 0)
        assertTrue(withDisplay and ES_DISPLAY_REQUIRED != 0)
        assertTrue(systemOnly and ES_SYSTEM_REQUIRED != 0)
        assertEquals(0, systemOnly and ES_DISPLAY_REQUIRED)
    }

    private fun assumeAvailable() {
        assumeTrue("Energy manager not available", EnergyManager.isAvailable())
    }

    private fun assumeWindows() {
        assumeTrue(
            "Test requires Windows",
            System.getProperty("os.name").lowercase().contains("windows"),
        )
    }

    private fun queryAwakeFlags(): Int =
        WindowsEnergyManager.onAwakeThread { NativeWindowsEnergyBridge.nativeQueryAwakeFlags() }

    private companion object {
        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002
    }
}
