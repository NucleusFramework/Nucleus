package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.linux.LinuxSystemInfo
import dev.nucleusframework.systeminfo.model.BatteryState
import dev.nucleusframework.systeminfo.model.MeteredStatus
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemInfoPlatformFallbackTest {
    @Test
    fun `linux implementation falls back off linux`() {
        if (Platform.Current == Platform.Linux) return
        assertFalse(LinuxSystemInfo.isAvailable())
        assertNull(LinuxSystemInfo.osInfo())
        assertNull(LinuxSystemInfo.memoryInfo())
        assertNull(LinuxSystemInfo.cpuInfo())
        assertTrue(LinuxSystemInfo.disks().isEmpty())
        assertTrue(LinuxSystemInfo.components().isEmpty())
        assertTrue(LinuxSystemInfo.networks().isEmpty())
        assertTrue(LinuxSystemInfo.users().isEmpty())
        assertNull(LinuxSystemInfo.motherboard())
        assertNull(LinuxSystemInfo.product())
        assertTrue(LinuxSystemInfo.processes().isEmpty())
        assertNull(LinuxSystemInfo.process(1L))
        assertTrue(LinuxSystemInfo.gpus().isEmpty())
        assertNull(LinuxSystemInfo.batteryInfo())
        assertEquals(-1L, LinuxSystemInfo.idleTime())
        assertNull(LinuxSystemInfo.connectivityInfo())
    }

    @Test
    fun `windows implementation falls back off windows`() {
        if (Platform.Current == Platform.Windows) return
        assertFalse(WindowsSystemInfo.isAvailable())
        assertNull(WindowsSystemInfo.osInfo())
        assertNull(WindowsSystemInfo.memoryInfo())
        assertNull(WindowsSystemInfo.cpuInfo())
        assertTrue(WindowsSystemInfo.disks().isEmpty())
        assertTrue(WindowsSystemInfo.components().isEmpty())
        assertTrue(WindowsSystemInfo.networks().isEmpty())
        assertTrue(WindowsSystemInfo.users().isEmpty())
        assertNull(WindowsSystemInfo.motherboard())
        assertNull(WindowsSystemInfo.product())
        assertTrue(WindowsSystemInfo.processes().isEmpty())
        assertNull(WindowsSystemInfo.process(1L))
        assertTrue(WindowsSystemInfo.gpus().isEmpty())
        assertNull(WindowsSystemInfo.batteryInfo())
        assertEquals(-1L, WindowsSystemInfo.idleTime())
        assertNull(WindowsSystemInfo.connectivityInfo())
    }

    @Test
    fun `battery idle and connectivity are safe to query`() {
        val battery = SystemInfo.batteryInfo()
        if (battery != null) {
            assertTrue(battery.stateOfCharge in 0f..1f)
            assertTrue(BatteryState.entries.contains(battery.state))
            assertTrue(battery.health in 0f..1f)
        }
        val idle = SystemInfo.idleTime()
        assertTrue(idle >= -1L)
        val connectivity = SystemInfo.connectivityInfo()
        if (connectivity != null) {
            assertTrue(MeteredStatus.entries.contains(connectivity.meteredStatus))
            if (!connectivity.isConnected) {
                assertEquals(MeteredStatus.NOT_AVAILABLE, connectivity.meteredStatus)
            }
        }
    }
}
