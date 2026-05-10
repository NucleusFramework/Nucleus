package dev.nucleusframework.nucleus.systeminfo

import dev.nucleusframework.nucleus.systeminfo.model.BatteryInfo
import dev.nucleusframework.nucleus.systeminfo.model.ComponentInfo
import dev.nucleusframework.nucleus.systeminfo.model.ConnectivityInfo
import dev.nucleusframework.nucleus.systeminfo.model.CpuGlobalInfo
import dev.nucleusframework.nucleus.systeminfo.model.DiskInfo
import dev.nucleusframework.nucleus.systeminfo.model.GpuInfo
import dev.nucleusframework.nucleus.systeminfo.model.MemoryInfo
import dev.nucleusframework.nucleus.systeminfo.model.MotherboardInfo
import dev.nucleusframework.nucleus.systeminfo.model.NetworkInterfaceInfo
import dev.nucleusframework.nucleus.systeminfo.model.OsInfo
import dev.nucleusframework.nucleus.systeminfo.model.ProcessInfo
import dev.nucleusframework.nucleus.systeminfo.model.ProductInfo
import dev.nucleusframework.nucleus.systeminfo.model.UserInfo

@Suppress("TooManyFunctions")
internal interface PlatformSystemInfo {
    fun isAvailable(): Boolean

    fun osInfo(): OsInfo?

    fun memoryInfo(): MemoryInfo?

    fun cpuInfo(): CpuGlobalInfo?

    fun disks(): List<DiskInfo>

    fun components(): List<ComponentInfo>

    fun networks(): List<NetworkInterfaceInfo>

    fun users(): List<UserInfo>

    fun motherboard(): MotherboardInfo?

    fun product(): ProductInfo?

    fun processes(): List<ProcessInfo>

    fun process(pid: Long): ProcessInfo?

    fun gpus(): List<GpuInfo>

    fun batteryInfo(): BatteryInfo?

    fun idleTime(): Long

    fun connectivityInfo(): ConnectivityInfo?
}
