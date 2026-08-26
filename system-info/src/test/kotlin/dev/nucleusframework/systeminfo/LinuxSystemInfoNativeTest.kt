package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.linux.LinuxSystemInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxSystemInfoNativeTest {
    @Test
    fun `linux backend reports os memory cpu disks networks processes and self pid`() {
        if (Platform.Current != Platform.Linux) return
        assertTrue(LinuxSystemInfo.isAvailable())

        val os = LinuxSystemInfo.osInfo()
        assertNotNull(os)
        assertTrue(!os.name.isNullOrBlank())
        assertTrue(os.uptime > 0L)
        assertTrue(os.bootTime > 0L)

        val memory = LinuxSystemInfo.memoryInfo()
        assertNotNull(memory)
        assertTrue(memory.totalMemory > 0L)
        assertTrue(memory.usedMemory >= 0L)

        val cpu = LinuxSystemInfo.cpuInfo()
        assertNotNull(cpu)
        assertTrue(cpu.cpus.isNotEmpty())

        assertTrue(LinuxSystemInfo.disks().isNotEmpty())
        assertTrue(LinuxSystemInfo.networks().isNotEmpty())
        val users = LinuxSystemInfo.users()
        assertTrue(users.isEmpty() || users.any { it.name.isNotBlank() })

        LinuxSystemInfo.motherboard()
        LinuxSystemInfo.product()
        LinuxSystemInfo.components()
        LinuxSystemInfo.gpus()
        LinuxSystemInfo.batteryInfo()
        assertTrue(LinuxSystemInfo.idleTime() >= -1L)
        LinuxSystemInfo.connectivityInfo()

        val processes = LinuxSystemInfo.processes()
        assertTrue(processes.isNotEmpty())
        val selfPid = ProcessHandle.current().pid()
        val self = LinuxSystemInfo.process(selfPid)
        assertNotNull(self)
        assertEquals(selfPid, self.pid)
        assertTrue(self.name.isNotBlank())
        assertTrue(LinuxSystemInfo.process(-1L) == null)
    }
}
