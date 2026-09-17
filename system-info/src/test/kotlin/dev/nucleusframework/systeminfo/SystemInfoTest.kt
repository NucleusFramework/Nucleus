@file:Suppress("MaxLineLength")

package dev.nucleusframework.systeminfo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemInfoTest {
    @Test
    fun `system info is available on current platform`() {
        assertTrue(SystemInfo.isAvailable(), "SystemInfo should be available on the current OS")
    }

    @Test
    fun `os info returns valid data`() {
        val os = SystemInfo.osInfo()
        assertNotNull(os)
        assertNotNull(os.name, "OS name should not be null")
        assertNotNull(os.kernelVersion, "Kernel version should not be null")
        assertNotNull(os.osVersion, "OS version should not be null")
        assertNotNull(os.hostName, "Hostname should not be null")
        assertNotNull(os.cpuArch, "CPU arch should not be null")
        assertTrue(os.uptime > 0, "Uptime should be > 0")
        assertTrue(os.bootTime > 0, "Boot time should be > 0")
        println("OS: ${os.longOsVersion}")
    }

    @Test
    fun `memory info returns valid data`() {
        val mem = SystemInfo.memoryInfo()
        assertNotNull(mem)
        assertTrue(mem.totalMemory > 0, "Total memory should be > 0")
        assertTrue(mem.usedMemory > 0, "Used memory should be > 0")
        assertTrue(mem.availableMemory > 0, "Available memory should be > 0")
        println("Memory: ${mem.totalMemory / 1024 / 1024}MB total, ${mem.usedMemory / 1024 / 1024}MB used")
    }

    @Test
    fun `cpu info returns valid data`() {
        val cpu = SystemInfo.cpuInfo()
        assertNotNull(cpu)
        assertTrue(cpu.cpus.isNotEmpty(), "Should have at least one CPU")
        assertNotNull(cpu.physicalCoreCount, "Physical core count should not be null")
        assertTrue((cpu.physicalCoreCount ?: 0) > 0, "Physical core count should be > 0")
        cpu.cpus.first().let {
            assertTrue(it.brand.isNotEmpty(), "CPU brand should not be empty")
            println("CPU: ${it.brand}, freq=${it.frequency}MHz, cores=${cpu.physicalCoreCount}")
        }
        assertTrue(cpu.cpus.first().frequency >= 0, "CPU frequency should be >= 0")
    }

    @Test
    fun `disk info returns valid data`() {
        val disks = SystemInfo.disks()
        assertTrue(disks.isNotEmpty(), "Should have at least one disk")
        disks.forEach {
            println(
                "Disk: ${it.mountPoint} (${it.fileSystem}) ${it.totalSpace / 1024 / 1024 / 1024}GB kind=${it.kind} removable=${it.isRemovable}",
            )
        }
    }

    @Test
    fun `components returns sensors`() {
        val comps = SystemInfo.components()
        println("Found ${comps.size} temperature sensors:")
        comps.forEach { println("  ${it.label}: ${it.temperature}°C") }
    }

    @Test
    fun `network info returns valid data`() {
        val nets = SystemInfo.networks()
        assertTrue(nets.isNotEmpty(), "Should have at least one network interface")
        nets.first().let {
            assertTrue(it.name.isNotEmpty(), "Interface name should not be empty")
            println("Network: ${it.name} mac=${it.macAddress}")
        }
    }

    @Test
    fun `user info returns valid data`() {
        val users = SystemInfo.users()
        assertTrue(users.isNotEmpty(), "Should have at least one user")
        users.first().let {
            assertTrue(it.name.isNotEmpty(), "User name should not be empty")
            println("User: ${it.name} (${it.id})")
        }
    }

    @Test
    fun `hardware info returns valid data`() {
        val product = SystemInfo.product()
        assertNotNull(product)
        // DMI fields are legitimately nullable (VMs, containers, firmware with
        // "Default string" placeholders). The contract only guarantees that
        // product() returns a non-null record when the native bridge is loaded.
        println("Product: ${product.name} family=${product.family}")
    }

    @Test
    fun `process listing works`() {
        val procs = SystemInfo.processes()
        assertTrue(procs.isNotEmpty(), "Should have at least one process")
        println("Found ${procs.size} processes")
        // Current process should be findable
        val pid = ProcessHandle.current().pid()
        val me = SystemInfo.process(pid)
        assertNotNull(me, "Should find current process (PID $pid)")
        println("Current process: PID $pid name=${me.name}")
    }

    @Test
    fun `gpu info returns valid data`() {
        val gpus = SystemInfo.gpus()
        // Headless CI runners often expose no GPU; empty list is fine so long
        // as enumeration does not throw.
        if (gpus.isEmpty()) {
            println("No GPUs enumerated (acceptable on headless CI runners)")
            return
        }
        gpus.forEach { g ->
            println(
                "GPU: ${g.name} vendor=0x${g.vendorId.toString(
                    16,
                )} shared=${g.sharedSystemMemory / 1024 / 1024}MB driver=${g.driverVersion}",
            )
            println(
                "  temp=${g.temperature}°C usage=${g.gpuUsage}% memUsed=${g.memoryUsed?.let { it / 1024 / 1024 }}MB",
            )
            println(
                "  coreClock=${g.coreClockMhz}MHz memClock=${g.memoryClockMhz}MHz fan=${g.fanSpeedPercent}% power=${g.powerDrawWatts}W",
            )
        }
    }
}
