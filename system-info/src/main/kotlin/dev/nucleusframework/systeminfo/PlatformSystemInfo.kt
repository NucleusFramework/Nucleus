package dev.nucleusframework.systeminfo

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
import dev.nucleusframework.systeminfo.model.RenderTier
import dev.nucleusframework.systeminfo.model.UserInfo
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    fun renderTier(): RenderTier

    fun batteryInfo(): BatteryInfo?

    fun idleTime(): Long

    fun connectivityInfo(): ConnectivityInfo?

    // Platforms without a native change monitor report the current state once.
    fun connectivityChanges(): Flow<ConnectivityInfo> =
        flow {
            val current = connectivityInfo() ?: return@flow
            emit(current)
            awaitCancellation()
        }

    // Platforms without a native volume monitor report the current volumes once.
    fun diskChanges(): Flow<List<DiskInfo>> =
        flow {
            emit(synchronized(diskScanLock) { disks() })
            awaitCancellation()
        }

    // Platforms without a pre-eject hook never call the handler.
    fun ejectRequests(handler: (DiskInfo) -> Boolean): AutoCloseable = AutoCloseable { }
}

/**
 * Splits a native command line, where every argument is NUL-terminated, into argv.
 * Empty arguments are kept; only the trailing NULs (terminator, `setproctitle` padding) are dropped.
 */
internal fun splitCommandLine(raw: String?): List<String> {
    val args = raw.orEmpty().trimEnd('\u0000')
    return if (args.isEmpty()) emptyList() else args.split('\u0000')
}
