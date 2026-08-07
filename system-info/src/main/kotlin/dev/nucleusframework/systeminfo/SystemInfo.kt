package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.systeminfo.linux.LinuxSystemInfo
import dev.nucleusframework.systeminfo.macos.MacOsSystemInfo
import dev.nucleusframework.systeminfo.model.BatteryInfo
import dev.nucleusframework.systeminfo.model.ComponentInfo
import dev.nucleusframework.systeminfo.model.ConnectivityInfo
import dev.nucleusframework.systeminfo.model.CpuGlobalInfo
import dev.nucleusframework.systeminfo.model.DiskInfo
import dev.nucleusframework.systeminfo.model.GpuInfo
import dev.nucleusframework.systeminfo.model.MemoryInfo
import dev.nucleusframework.systeminfo.model.MotherboardInfo
import dev.nucleusframework.systeminfo.model.NetworkInterfaceInfo
import dev.nucleusframework.systeminfo.model.OsInfo
import dev.nucleusframework.systeminfo.model.ProcessInfo
import dev.nucleusframework.systeminfo.model.ProductInfo
import dev.nucleusframework.systeminfo.model.UserInfo
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo

@Suppress("TooManyFunctions")
public object SystemInfo {
    private val delegate: PlatformSystemInfo? =
        when (Platform.Current) {
            Platform.Windows -> WindowsSystemInfo
            Platform.MacOS -> MacOsSystemInfo
            Platform.Linux -> LinuxSystemInfo
            else -> null
        }

    public fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    public fun osInfo(): OsInfo? = delegate?.osInfo()

    public fun memoryInfo(): MemoryInfo? = delegate?.memoryInfo()

    public fun cpuInfo(): CpuGlobalInfo? = delegate?.cpuInfo()

    public fun disks(): List<DiskInfo> = delegate?.disks() ?: emptyList()

    public fun components(): List<ComponentInfo> = delegate?.components() ?: emptyList()

    public fun networks(): List<NetworkInterfaceInfo> = delegate?.networks() ?: emptyList()

    public fun users(): List<UserInfo> = delegate?.users() ?: emptyList()

    public fun motherboard(): MotherboardInfo? = delegate?.motherboard()

    public fun product(): ProductInfo? = delegate?.product()

    public fun processes(): List<ProcessInfo> = delegate?.processes() ?: emptyList()

    public fun process(pid: Long): ProcessInfo? = delegate?.process(pid)

    public fun gpus(): List<GpuInfo> = delegate?.gpus() ?: emptyList()

    public fun batteryInfo(): BatteryInfo? = delegate?.batteryInfo()

    public fun idleTime(): Long = delegate?.idleTime() ?: -1L

    public fun connectivityInfo(): ConnectivityInfo? = delegate?.connectivityInfo()
}
