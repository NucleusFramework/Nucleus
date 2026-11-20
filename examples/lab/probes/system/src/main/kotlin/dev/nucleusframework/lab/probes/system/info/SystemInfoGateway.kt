package dev.nucleusframework.lab.probes.system.info

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.systeminfo.SystemInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/** Port over `system-info`. Blocking: call it off the UI thread. */
interface SystemInfoGateway {
    fun availability(): Availability

    fun sample(includeProcesses: Boolean): SystemSample
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusSystemInfoGateway : SystemInfoGateway {
    override fun availability(): Availability =
        Availability.of(SystemInfo.isAvailable()) { "native library nucleus_system_info not loaded on this platform" }

    override fun sample(includeProcesses: Boolean): SystemSample =
        SystemSample(
            epochMillis = System.currentTimeMillis(),
            os = SystemInfo.osInfo(),
            memory = SystemInfo.memoryInfo(),
            cpu = SystemInfo.cpuInfo(),
            disks = SystemInfo.disks(),
            components = SystemInfo.components(),
            networks = SystemInfo.networks(),
            gpus = SystemInfo.gpus(),
            battery = SystemInfo.batteryInfo(),
            connectivity = SystemInfo.connectivityInfo(),
            idleMillis = SystemInfo.idleTime(),
            motherboard = SystemInfo.motherboard(),
            product = SystemInfo.product(),
            processes = if (includeProcesses) SystemInfo.processes() else null,
        )
}
