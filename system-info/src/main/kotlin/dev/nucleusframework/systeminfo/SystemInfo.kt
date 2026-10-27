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
import dev.nucleusframework.systeminfo.model.RenderTier
import dev.nucleusframework.systeminfo.model.UserInfo
import dev.nucleusframework.systeminfo.windows.WindowsSystemInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

    /**
     * The mounted volumes: local disks and partitions, removable media, disk images and network
     * shares. Pseudo file systems (proc, tmpfs, overlay, snap squashfs, …) are left out.
     *
     * The free space of a volume that does not answer within about a second (a network share whose
     * server is gone) is reported as `0` instead of blocking. Call it off the UI thread.
     */
    public fun disks(): List<DiskInfo> = synchronized(diskScanLock) { delegate?.disks() } ?: emptyList()

    public fun components(): List<ComponentInfo> = delegate?.components() ?: emptyList()

    public fun networks(): List<NetworkInterfaceInfo> = delegate?.networks() ?: emptyList()

    public fun users(): List<UserInfo> = delegate?.users() ?: emptyList()

    public fun motherboard(): MotherboardInfo? = delegate?.motherboard()

    public fun product(): ProductInfo? = delegate?.product()

    public fun processes(): List<ProcessInfo> = delegate?.processes() ?: emptyList()

    public fun process(pid: Long): ProcessInfo? = delegate?.process(pid)

    public fun gpus(): List<GpuInfo> = delegate?.gpus() ?: emptyList()

    /**
     * How well the default GPU handles 3D content: [RenderTier.SOFTWARE] or [RenderTier.LEGACY] mean a 3D
     * engine should be turned off. Probed once (Windows: the default DXGI adapter's Direct3D feature level;
     * Linux: a throwaway GL ES context; macOS: the default Metal device), then cached. The probe loads the GPU
     * driver, which can take tens of milliseconds the first time: call it off the UI thread.
     */
    public fun renderTier(): RenderTier = delegate?.renderTier() ?: RenderTier.UNKNOWN

    public fun batteryInfo(): BatteryInfo? = delegate?.batteryInfo()

    public fun idleTime(): Long = delegate?.idleTime() ?: -1L

    public fun connectivityInfo(): ConnectivityInfo? = delegate?.connectivityInfo()

    /**
     * Observes network connectivity: emits the current [ConnectivityInfo] when collected,
     * then every change, until the collection is cancelled.
     *
     * All collectors share a single native monitor, started with the first one and
     * stopped with the last. Consecutive identical states are never emitted.
     *
     * Linux watches the xdg-desktop-portal NetworkMonitor, falling back to NetworkManager;
     * when neither is reachable the flow emits a single state with
     * [dev.nucleusframework.systeminfo.model.ConnectivityLevel.UNKNOWN]. macOS watches
     * `NWPathMonitor`, which does not probe the internet: a usable path reports
     * [dev.nucleusframework.systeminfo.model.ConnectivityLevel.FULL], and Low Data Mode counts
     * as metered. Windows watches the Network List Manager, whose NCSI probe tells a
     * routed network without internet
     * ([dev.nucleusframework.systeminfo.model.ConnectivityLevel.LIMITED]) from a captive
     * portal; metered comes from `INetworkCostManager`. The flow is empty on unsupported
     * platforms.
     */
    public fun connectivityFlow(): Flow<ConnectivityInfo> = delegate?.connectivityChanges() ?: emptyFlow()

    /**
     * Observes the mounted volumes: emits the current [disks] when collected, then the new list
     * every time a volume is mounted, unmounted or remounted, until the collection is cancelled.
     *
     * The OS notifies the change, nothing is polled: Linux watches `/proc/self/mountinfo`, Windows
     * `WM_DEVICECHANGE` and the volume device interface, macOS DiskArbitration. All collectors share
     * a single native monitor, started with the first one and stopped with the last. Bursts of
     * changes are coalesced, and a change of free space alone is not emitted. Find a volume again
     * across emissions through [DiskInfo.volumeId]. The flow is empty on unsupported platforms.
     */
    public fun disksFlow(): Flow<List<DiskInfo>> = delegate?.diskChanges() ?: emptyFlow()

    /**
     * Asks [handler] before a volume is ejected or unmounted, so the app can close the files it keeps
     * open there. Return `true` to let the eject proceed, `false` to refuse it: the OS then tells the
     * user the volume is in use.
     *
     * The handler runs on a background thread and must answer within 3 seconds; a slower handler, or
     * one that throws, lets the eject proceed. When several handlers are registered, any refusal wins.
     *
     * Supported on Windows (an Explorer eject or "Safely Remove Hardware" of a removable or external
     * drive) and macOS (an unmount or eject through Finder, `diskutil` or DiskArbitration). Forced
     * unmounts, a drive pulled out without ejecting it, and a direct `umount` are never asked; on
     * Windows that includes detaching a disk image (ISO, VHD), which only ever dismounts by force.
     * Linux has no such hook: the handler is never called there, observe [disksFlow] to react after
     * the fact.
     *
     * Close the returned registration to remove the handler.
     */
    public fun onEjectRequest(handler: (DiskInfo) -> Boolean): AutoCloseable =
        delegate?.ejectRequests(handler) ?: AutoCloseable { }
}
