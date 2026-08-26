package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsSystemInfoNativeTest {
    @Test
    fun `windows backend reports os memory cpu disks networks processes and self pid`() {
        if (Platform.Current != Platform.Windows) return
        assertTrue(WindowsSystemInfo.isAvailable())

        val os = WindowsSystemInfo.osInfo()
        assertNotNull(os)
        assertTrue(!os.name.isNullOrBlank())
        assertTrue(os.uptime > 0L)
        assertTrue(os.bootTime > 0L)

        val memory = WindowsSystemInfo.memoryInfo()
        assertNotNull(memory)
        assertTrue(memory.totalMemory > 0L)
        assertTrue(memory.usedMemory >= 0L)

        val cpu = WindowsSystemInfo.cpuInfo()
        assertNotNull(cpu)
        assertTrue(cpu.cpus.isNotEmpty())

        assertTrue(WindowsSystemInfo.disks().isNotEmpty())
        assertTrue(WindowsSystemInfo.networks().isNotEmpty())
        val users = WindowsSystemInfo.users()
        assertTrue(users.isEmpty() || users.any { it.name.isNotBlank() })

        WindowsSystemInfo.motherboard()
        WindowsSystemInfo.product()
        WindowsSystemInfo.components()
        WindowsSystemInfo.gpus()
        WindowsSystemInfo.batteryInfo()
        assertTrue(WindowsSystemInfo.idleTime() >= -1L)
        WindowsSystemInfo.connectivityInfo()

        val processes = WindowsSystemInfo.processes()
        assertTrue(processes.isNotEmpty())
        val selfPid = ProcessHandle.current().pid()
        val self = WindowsSystemInfo.process(selfPid)
        assertNotNull(self)
        assertEquals(selfPid, self.pid)
        assertTrue(self.name.isNotBlank())
        assertTrue(WindowsSystemInfo.process(-1L) == null)
    }
}
