@file:Suppress("MaxLineLength")

package dev.nucleusframework.nucleus.systeminfo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemInfoSmokeTest {
    @Test
    fun `native library loads and isAvailable returns true`() {
        assertTrue(SystemInfo.isAvailable(), "SystemInfo should be available on this platform")
    }

    @Test
    fun `osInfo returns non-null with expected fields`() {
        val os = SystemInfo.osInfo()
        assertNotNull(os, "osInfo should not be null")
        assertNotNull(os.name, "OS name should not be null")
        assertNotNull(os.hostName, "hostname should not be null")
        assertNotNull(os.cpuArch, "CPU arch should not be null")
        assertTrue(os.uptime > 0, "uptime should be positive")
        assertTrue(os.bootTime > 0, "bootTime should be positive")
        println("OS: ${os.longOsVersion} (${os.cpuArch}), hostname=${os.hostName}, uptime=${os.uptime}s")
    }

    @Test
    fun `memoryInfo returns non-null with sane values`() {
        val mem = SystemInfo.memoryInfo()
        assertNotNull(mem, "memoryInfo should not be null")
        assertTrue(mem.totalMemory > 0, "totalMemory should be positive")
        assertTrue(mem.availableMemory > 0, "availableMemory should be positive")
        assertTrue(mem.usedMemory > 0, "usedMemory should be positive")
        assertTrue(mem.totalMemory >= mem.availableMemory, "total >= available")
        println("Memory: total=${mem.totalMemory / 1024 / 1024}MB, used=${mem.usedMemory / 1024 / 1024}MB")
    }

    @Test
    fun `cpuInfo returns non-null with at least one CPU`() {
        val cpu = SystemInfo.cpuInfo()
        assertNotNull(cpu, "cpuInfo should not be null")
        assertTrue(cpu.cpus.isNotEmpty(), "should have at least one logical CPU")
        assertTrue(cpu.cpus[0].brand.isNotEmpty(), "CPU brand should not be empty")
        println("CPU: ${cpu.cpus[0].brand}, ${cpu.cpus.size} logical, physical=${cpu.physicalCoreCount}")
    }

    @Test
    fun `disks returns non-empty list`() {
        val disks = SystemInfo.disks()
        assertTrue(disks.isNotEmpty(), "should have at least one disk")
        assertTrue(disks[0].totalSpace > 0, "disk total space should be positive")
        disks.forEach { d ->
            println("Disk: ${d.mountPoint} (${d.fileSystem}) ${d.totalSpace / 1024 / 1024 / 1024}GB, kind=${d.kind}")
        }
    }

    @Test
    fun `networks returns non-empty list`() {
        val nets = SystemInfo.networks()
        assertTrue(nets.isNotEmpty(), "should have at least one network interface")
        nets.forEach { n ->
            println("Net: ${n.name} MAC=${n.macAddress} MTU=${n.mtu}")
        }
    }

    @Test
    fun `users returns non-empty list`() {
        val users = SystemInfo.users()
        assertTrue(users.isNotEmpty(), "should have at least one user")
        users.forEach { u ->
            println("User: ${u.name} SID=${u.id} groups=${u.groups}")
        }
    }

    @Test
    fun `motherboard returns non-null`() {
        val mb = SystemInfo.motherboard()
        assertNotNull(mb, "motherboard should not be null")
        println("Motherboard: ${mb.name} by ${mb.vendorName}")
    }

    @Test
    fun `product returns non-null`() {
        val prod = SystemInfo.product()
        assertNotNull(prod, "product should not be null")
        println("Product: ${prod.name} by ${prod.vendorName}, UUID=${prod.uuid}")
    }

    @Test
    fun `processes returns non-empty list`() {
        val procs = SystemInfo.processes()
        assertTrue(procs.isNotEmpty(), "should have at least one process")
        println("Process count: ${procs.size}")
        // Check we can find our own process
        val pid = ProcessHandle.current().pid()
        val self = SystemInfo.process(pid)
        assertNotNull(self, "should find our own process by PID")
        println("Self: PID=$pid name=${self.name} memory=${self.memory / 1024}KB")
    }

    @Test
    fun `gpus returns non-empty list with VRAM`() {
        val gpus = SystemInfo.gpus()
        assertTrue(gpus.isNotEmpty(), "should have at least one GPU")
        gpus.forEach { gpu ->
            assertTrue(gpu.name.isNotEmpty(), "GPU name should not be empty")
            assertTrue(gpu.dedicatedVideoMemory > 0 || gpu.sharedSystemMemory > 0, "GPU should have some memory")
            println(
                "GPU: ${gpu.name} VRAM=${gpu.dedicatedVideoMemory / 1024 / 1024}MB vendor=0x${gpu.vendorId.toString(
                    16,
                )} driver=${gpu.driverVersion}",
            )
            println(
                "  temp=${gpu.temperature?.let { "%.0f°C".format(it) } ?: "N/A"} " +
                    "usage=${gpu.gpuUsage?.let { "%.1f%%".format(it) } ?: "N/A"} " +
                    "vramUsed=${gpu.memoryUsed?.let { "${it / 1024 / 1024}MB" } ?: "N/A"}",
            )
            println(
                "  coreClock=${gpu.coreClockMhz?.let { "${it}MHz" } ?: "N/A"} " +
                    "memClock=${gpu.memoryClockMhz?.let { "${it}MHz" } ?: "N/A"} " +
                    "fan=${gpu.fanSpeedPercent?.let { "%.0f%%".format(it) } ?: "N/A"} " +
                    "power=${gpu.powerDrawWatts?.let { "%.1fW".format(it) } ?: "N/A"}",
            )
        }
    }
}
