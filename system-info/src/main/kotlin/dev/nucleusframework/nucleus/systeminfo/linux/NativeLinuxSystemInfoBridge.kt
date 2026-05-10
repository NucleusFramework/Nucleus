package dev.nucleusframework.nucleus.systeminfo.linux

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_system_info"

@Suppress("TooManyFunctions")
internal object NativeLinuxSystemInfoBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLinuxSystemInfoBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // OS Info
    @JvmStatic external fun nativeOsName(): String?

    @JvmStatic external fun nativeKernelVersion(): String?

    @JvmStatic external fun nativeOsVersion(): String?

    @JvmStatic external fun nativeLongOsVersion(): String?

    @JvmStatic external fun nativeDistributionId(): String?

    @JvmStatic external fun nativeHostName(): String?

    @JvmStatic external fun nativeCpuArch(): String?

    @JvmStatic external fun nativeUptime(): Long

    @JvmStatic external fun nativeBootTime(): Long

    // Memory
    @JvmStatic external fun nativeTotalMemory(): Long

    @JvmStatic external fun nativeFreeMemory(): Long

    @JvmStatic external fun nativeAvailableMemory(): Long

    @JvmStatic external fun nativeUsedMemory(): Long

    @JvmStatic external fun nativeTotalSwap(): Long

    @JvmStatic external fun nativeFreeSwap(): Long

    @JvmStatic external fun nativeUsedSwap(): Long

    // CPU
    @JvmStatic external fun nativeGlobalCpuUsage(): Float

    @JvmStatic external fun nativePhysicalCoreCount(): Int

    @JvmStatic external fun nativeCpuCount(): Int

    @JvmStatic external fun nativeCpuNames(): Array<String>?

    @JvmStatic external fun nativeCpuVendorIds(): Array<String>?

    @JvmStatic external fun nativeCpuBrands(): Array<String>?

    @JvmStatic external fun nativeCpuFrequencies(): LongArray?

    @JvmStatic external fun nativeCpuUsages(): FloatArray?

    // Disks
    @JvmStatic external fun nativeDiskCount(): Int

    @JvmStatic external fun nativeDiskNames(): Array<String>?

    @JvmStatic external fun nativeDiskFileSystems(): Array<String>?

    @JvmStatic external fun nativeDiskMountPoints(): Array<String>?

    @JvmStatic external fun nativeDiskTotalSpaces(): LongArray?

    @JvmStatic external fun nativeDiskAvailableSpaces(): LongArray?

    @JvmStatic external fun nativeDiskKinds(): Array<String>?

    @JvmStatic external fun nativeDiskRemovable(): BooleanArray?

    @JvmStatic external fun nativeDiskReadOnly(): BooleanArray?

    // Components (temperature sensors)
    @JvmStatic external fun nativeComponentCount(): Int

    @JvmStatic external fun nativeComponentLabels(): Array<String>?

    @JvmStatic external fun nativeComponentTemperatures(): FloatArray?

    @JvmStatic external fun nativeComponentMaxTemperatures(): FloatArray?

    @JvmStatic external fun nativeComponentCriticalTemperatures(): FloatArray?

    // Network interfaces
    @JvmStatic external fun nativeNetworkCount(): Int

    @JvmStatic external fun nativeNetworkNames(): Array<String>?

    @JvmStatic external fun nativeNetworkReceivedBytes(): LongArray?

    @JvmStatic external fun nativeNetworkTransmittedBytes(): LongArray?

    @JvmStatic external fun nativeNetworkReceivedPackets(): LongArray?

    @JvmStatic external fun nativeNetworkTransmittedPackets(): LongArray?

    @JvmStatic external fun nativeNetworkErrorsReceived(): LongArray?

    @JvmStatic external fun nativeNetworkErrorsTransmitted(): LongArray?

    @JvmStatic external fun nativeNetworkMacAddresses(): Array<String>?

    @JvmStatic external fun nativeNetworkMtus(): LongArray?

    // Users
    @JvmStatic external fun nativeUserCount(): Int

    @JvmStatic external fun nativeUserNames(): Array<String>?

    @JvmStatic external fun nativeUserIds(): Array<String>?

    @JvmStatic external fun nativeUserGroupIds(): Array<String>?

    @JvmStatic external fun nativeUserGroups(): Array<String>?

    // Hardware — Motherboard
    @JvmStatic external fun nativeMotherboardName(): String?

    @JvmStatic external fun nativeMotherboardVendor(): String?

    @JvmStatic external fun nativeMotherboardVersion(): String?

    @JvmStatic external fun nativeMotherboardSerial(): String?

    @JvmStatic external fun nativeMotherboardAssetTag(): String?

    // Hardware �� Product
    @JvmStatic external fun nativeProductName(): String?

    @JvmStatic external fun nativeProductFamily(): String?

    @JvmStatic external fun nativeProductSerial(): String?

    @JvmStatic external fun nativeProductSku(): String?

    @JvmStatic external fun nativeProductUuid(): String?

    @JvmStatic external fun nativeProductVersion(): String?

    @JvmStatic external fun nativeProductVendor(): String?

    // Processes
    @JvmStatic external fun nativeProcessCount(): Int

    @JvmStatic external fun nativeProcessPids(): LongArray?

    @JvmStatic external fun nativeProcessNames(): Array<String>?

    @JvmStatic external fun nativeProcessExes(): Array<String>?

    @JvmStatic external fun nativeProcessMemories(): LongArray?

    @JvmStatic external fun nativeProcessVirtualMemories(): LongArray?

    @JvmStatic external fun nativeProcessCpuUsages(): FloatArray?

    @JvmStatic external fun nativeProcessStatuses(): Array<String>?

    @JvmStatic external fun nativeProcessStartTimes(): LongArray?

    @JvmStatic external fun nativeProcessRunTimes(): LongArray?

    @JvmStatic external fun nativeProcessParentPids(): LongArray?

    @JvmStatic external fun nativeProcessCmds(): Array<String>?

    @JvmStatic external fun nativeProcessCwds(): Array<String>?

    @JvmStatic external fun nativeProcessRoots(): Array<String>?

    // GPU
    @JvmStatic external fun nativeGpuCount(): Int

    @JvmStatic external fun nativeGpuNames(): Array<String>?

    @JvmStatic external fun nativeGpuVendorIds(): LongArray?

    @JvmStatic external fun nativeGpuDeviceIds(): LongArray?

    @JvmStatic external fun nativeGpuDedicatedVideoMemories(): LongArray?

    @JvmStatic external fun nativeGpuDedicatedSystemMemories(): LongArray?

    @JvmStatic external fun nativeGpuSharedSystemMemories(): LongArray?

    @JvmStatic external fun nativeGpuDriverVersions(): Array<String>?

    // GPU live metrics
    @JvmStatic external fun nativeGpuTemperatures(): FloatArray?

    @JvmStatic external fun nativeGpuUsages(): FloatArray?

    @JvmStatic external fun nativeGpuMemoryUsed(): LongArray?

    @JvmStatic external fun nativeGpuCoreClocks(): IntArray?

    @JvmStatic external fun nativeGpuMemoryClocks(): IntArray?

    @JvmStatic external fun nativeGpuFanSpeeds(): FloatArray?

    @JvmStatic external fun nativeGpuPowerDraws(): FloatArray?

    // Single process by PID
    @JvmStatic external fun nativeProcessByPidName(pid: Long): String?

    @JvmStatic external fun nativeProcessByPidExe(pid: Long): String?

    @JvmStatic external fun nativeProcessByPidMemory(pid: Long): Long

    @JvmStatic external fun nativeProcessByPidVirtualMemory(pid: Long): Long

    @JvmStatic external fun nativeProcessByPidCpuUsage(pid: Long): Float

    @JvmStatic external fun nativeProcessByPidStatus(pid: Long): String?

    @JvmStatic external fun nativeProcessByPidStartTime(pid: Long): Long

    @JvmStatic external fun nativeProcessByPidRunTime(pid: Long): Long

    @JvmStatic external fun nativeProcessByPidParentPid(pid: Long): Long

    @JvmStatic external fun nativeProcessByPidCmd(pid: Long): String?

    @JvmStatic external fun nativeProcessByPidCwd(pid: Long): String?

    @JvmStatic external fun nativeProcessByPidRoot(pid: Long): String?

    // Battery
    @JvmStatic external fun nativeBatteryPresent(): Boolean

    @JvmStatic external fun nativeBatteryExternalConnected(): Boolean

    @JvmStatic external fun nativeBatteryIsCharging(): Boolean

    @JvmStatic external fun nativeBatteryFullyCharged(): Boolean

    @JvmStatic external fun nativeBatteryCurrentCapacity(): Int

    @JvmStatic external fun nativeBatteryMaxCapacity(): Int

    @JvmStatic external fun nativeBatteryDesignCapacity(): Int

    @JvmStatic external fun nativeBatteryCycleCount(): Int

    @JvmStatic external fun nativeBatteryVoltage(): Int

    @JvmStatic external fun nativeBatteryAmperage(): Int

    @JvmStatic external fun nativeBatteryTemperature(): Float

    @JvmStatic external fun nativeBatteryTimeRemaining(): Int

    @JvmStatic external fun nativeBatteryManufacturer(): String?

    @JvmStatic external fun nativeBatteryModelName(): String?

    @JvmStatic external fun nativeBatterySerialNumber(): String?

    // Idle time
    @JvmStatic external fun nativeIdleTimeSeconds(): Long

    // Connectivity
    @JvmStatic external fun nativeIsNetworkConnected(): Boolean

    @JvmStatic external fun nativeGetMeteredStatus(): Int
}
