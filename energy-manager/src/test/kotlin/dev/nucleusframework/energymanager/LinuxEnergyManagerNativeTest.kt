package dev.nucleusframework.energymanager

import dev.nucleusframework.energymanager.linux.LinuxEnergyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxEnergyManagerNativeTest {
    @Test
    fun `linux energy manager is available and light mode raises nice`() {
        if (!isLinux()) return
        assertTrue(LinuxEnergyManager.isAvailable())
        assertTrue(EnergyManager.isAvailable())

        val enable = EnergyManager.enableLightEfficiencyMode()
        val niceAfter = EnergyManagerTest.readNice()
        val disable = EnergyManager.disableLightEfficiencyMode()

        assertTrue(enable.success, enable.message)
        assertEquals(10, niceAfter, "Expected nice = 10 after light enable")
        assertTrue(disable.success, disable.message)
    }

    @Test
    fun `full efficiency mode on a dedicated thread reports success`() {
        if (!isLinux()) return
        var enable = EnergyManager.Result(false)
        var disable = EnergyManager.Result(false)
        var nice = -1
        val thread =
            Thread {
                enable = EnergyManager.enableEfficiencyMode()
                nice = EnergyManagerTest.readNice()
                disable = EnergyManager.disableEfficiencyMode()
            }
        thread.start()
        thread.join()
        assertTrue(enable.success, enable.message)
        assertEquals(19, nice)
        assertTrue(disable.success, disable.message)
    }

    @Test
    fun `thread efficiency mode on a dedicated thread reports success`() {
        if (!isLinux()) return
        var enable = EnergyManager.Result(false)
        var disable = EnergyManager.Result(false)
        val thread =
            Thread {
                enable = EnergyManager.enableThreadEfficiencyMode()
                disable = EnergyManager.disableThreadEfficiencyMode()
            }
        thread.start()
        thread.join()
        assertTrue(enable.success, enable.message)
        assertTrue(disable.success, disable.message)
    }

    private fun isLinux(): Boolean = System.getProperty("os.name").lowercase().contains("linux")
}
