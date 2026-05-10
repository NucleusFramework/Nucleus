package dev.nucleusframework.nucleus.systeminfo

import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.systeminfo.linux.LinuxSystemInfo
import dev.nucleusframework.nucleus.systeminfo.macos.MacOsSystemInfo
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
import dev.nucleusframework.nucleus.systeminfo.windows.WindowsSystemInfo

@Suppress("TooManyFunctions")
object SystemInfo {
    private val delegate: PlatformSystemInfo? =
        when (Platform.Current) {
            Platform.Windows -> WindowsSystemInfo
            Platform.MacOS -> MacOsSystemInfo
            Platform.Linux -> LinuxSystemInfo
            else -> null
        }

    fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    fun osInfo(): OsInfo? = delegate?.osInfo()

    fun memoryInfo(): MemoryInfo? = delegate?.memoryInfo()

    fun cpuInfo(): CpuGlobalInfo? = delegate?.cpuInfo()

    fun disks(): List<DiskInfo> = delegate?.disks() ?: emptyList()

    fun components(): List<ComponentInfo> = delegate?.components() ?: emptyList()

    fun networks(): List<NetworkInterfaceInfo> = delegate?.networks() ?: emptyList()

    fun users(): List<UserInfo> = delegate?.users() ?: emptyList()

    fun motherboard(): MotherboardInfo? = delegate?.motherboard()

    fun product(): ProductInfo? = delegate?.product()

    fun processes(): List<ProcessInfo> = delegate?.processes() ?: emptyList()

    fun process(pid: Long): ProcessInfo? = delegate?.process(pid)

    fun gpus(): List<GpuInfo> = delegate?.gpus() ?: emptyList()

    fun batteryInfo(): BatteryInfo? = delegate?.batteryInfo()

    fun idleTime(): Long = delegate?.idleTime() ?: -1L

    fun connectivityInfo(): ConnectivityInfo? = delegate?.connectivityInfo()
}
