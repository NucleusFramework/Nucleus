package dev.nucleusframework.nucleus.systeminfo.linux

import dev.nucleusframework.nucleus.systeminfo.PlatformSystemInfo
import dev.nucleusframework.nucleus.systeminfo.model.BatteryInfo
import dev.nucleusframework.nucleus.systeminfo.model.BatteryState
import dev.nucleusframework.nucleus.systeminfo.model.ComponentInfo
import dev.nucleusframework.nucleus.systeminfo.model.ConnectivityInfo
import dev.nucleusframework.nucleus.systeminfo.model.CpuGlobalInfo
import dev.nucleusframework.nucleus.systeminfo.model.CpuInfo
import dev.nucleusframework.nucleus.systeminfo.model.DiskInfo
import dev.nucleusframework.nucleus.systeminfo.model.GpuInfo
import dev.nucleusframework.nucleus.systeminfo.model.MemoryInfo
import dev.nucleusframework.nucleus.systeminfo.model.MeteredStatus
import dev.nucleusframework.nucleus.systeminfo.model.MotherboardInfo
import dev.nucleusframework.nucleus.systeminfo.model.NetworkInterfaceInfo
import dev.nucleusframework.nucleus.systeminfo.model.OsInfo
import dev.nucleusframework.nucleus.systeminfo.model.ProcessInfo
import dev.nucleusframework.nucleus.systeminfo.model.ProductInfo
import dev.nucleusframework.nucleus.systeminfo.model.UserInfo

@Suppress("TooManyFunctions", "ReturnCount")
internal object LinuxSystemInfo : PlatformSystemInfo {
    private val bridge = NativeLinuxSystemInfoBridge

    override fun isAvailable(): Boolean = bridge.isLoaded

    override fun osInfo(): OsInfo? {
        if (!bridge.isLoaded) return null
        return OsInfo(
            name = bridge.nativeOsName(),
            kernelVersion = bridge.nativeKernelVersion(),
            osVersion = bridge.nativeOsVersion(),
            longOsVersion = bridge.nativeLongOsVersion(),
            distributionId = bridge.nativeDistributionId(),
            hostName = bridge.nativeHostName(),
            cpuArch = bridge.nativeCpuArch(),
            uptime = bridge.nativeUptime(),
            bootTime = bridge.nativeBootTime(),
        )
    }

    override fun memoryInfo(): MemoryInfo? {
        if (!bridge.isLoaded) return null
        return MemoryInfo(
            totalMemory = bridge.nativeTotalMemory(),
            freeMemory = bridge.nativeFreeMemory(),
            availableMemory = bridge.nativeAvailableMemory(),
            usedMemory = bridge.nativeUsedMemory(),
            totalSwap = bridge.nativeTotalSwap(),
            freeSwap = bridge.nativeFreeSwap(),
            usedSwap = bridge.nativeUsedSwap(),
        )
    }

    override fun cpuInfo(): CpuGlobalInfo? {
        if (!bridge.isLoaded) return null
        val count = bridge.nativeCpuCount()
        val names = bridge.nativeCpuNames() ?: emptyArray()
        val vendorIds = bridge.nativeCpuVendorIds() ?: emptyArray()
        val brands = bridge.nativeCpuBrands() ?: emptyArray()
        val frequencies = bridge.nativeCpuFrequencies() ?: LongArray(0)
        val usages = bridge.nativeCpuUsages() ?: FloatArray(0)
        val cpus =
            (0 until count).map { i ->
                CpuInfo(
                    name = names.getOrElse(i) { "" },
                    vendorId = vendorIds.getOrElse(i) { "" },
                    brand = brands.getOrElse(i) { "" },
                    frequency = frequencies.getOrElse(i) { 0L },
                    cpuUsage = usages.getOrElse(i) { 0f },
                )
            }
        val physicalCores = bridge.nativePhysicalCoreCount()
        return CpuGlobalInfo(
            globalCpuUsage = bridge.nativeGlobalCpuUsage(),
            physicalCoreCount = if (physicalCores > 0) physicalCores else null,
            cpus = cpus,
        )
    }

    override fun disks(): List<DiskInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeDiskCount()
        if (count <= 0) return emptyList()
        val names = bridge.nativeDiskNames() ?: return emptyList()
        val fileSystems = bridge.nativeDiskFileSystems() ?: return emptyList()
        val mountPoints = bridge.nativeDiskMountPoints() ?: return emptyList()
        val totalSpaces = bridge.nativeDiskTotalSpaces() ?: return emptyList()
        val availableSpaces = bridge.nativeDiskAvailableSpaces() ?: return emptyList()
        val kinds = bridge.nativeDiskKinds() ?: return emptyList()
        val removable = bridge.nativeDiskRemovable() ?: return emptyList()
        val readOnly = bridge.nativeDiskReadOnly() ?: return emptyList()
        return (0 until count).map { i ->
            DiskInfo(
                name = names.getOrElse(i) { "" },
                fileSystem = fileSystems.getOrElse(i) { "" },
                mountPoint = mountPoints.getOrElse(i) { "" },
                totalSpace = totalSpaces.getOrElse(i) { 0L },
                availableSpace = availableSpaces.getOrElse(i) { 0L },
                kind = kinds.getOrElse(i) { "Unknown" },
                isRemovable = removable.getOrElse(i) { false },
                isReadOnly = readOnly.getOrElse(i) { false },
            )
        }
    }

    override fun components(): List<ComponentInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeComponentCount()
        if (count <= 0) return emptyList()
        val labels = bridge.nativeComponentLabels() ?: return emptyList()
        val temps = bridge.nativeComponentTemperatures() ?: return emptyList()
        val maxTemps = bridge.nativeComponentMaxTemperatures() ?: return emptyList()
        val critTemps = bridge.nativeComponentCriticalTemperatures() ?: return emptyList()
        return (0 until count).map { i ->
            val temp = temps.getOrElse(i) { Float.NaN }
            val max = maxTemps.getOrElse(i) { Float.NaN }
            val crit = critTemps.getOrElse(i) { Float.NaN }
            ComponentInfo(
                label = labels.getOrElse(i) { "" },
                temperature = if (temp.isNaN()) null else temp,
                max = if (max.isNaN()) null else max,
                critical = if (crit.isNaN()) null else crit,
            )
        }
    }

    override fun networks(): List<NetworkInterfaceInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeNetworkCount()
        if (count <= 0) return emptyList()
        val names = bridge.nativeNetworkNames() ?: return emptyList()
        val rxBytes = bridge.nativeNetworkReceivedBytes() ?: return emptyList()
        val txBytes = bridge.nativeNetworkTransmittedBytes() ?: return emptyList()
        val rxPackets = bridge.nativeNetworkReceivedPackets() ?: return emptyList()
        val txPackets = bridge.nativeNetworkTransmittedPackets() ?: return emptyList()
        val rxErrors = bridge.nativeNetworkErrorsReceived() ?: return emptyList()
        val txErrors = bridge.nativeNetworkErrorsTransmitted() ?: return emptyList()
        val macs = bridge.nativeNetworkMacAddresses() ?: return emptyList()
        val mtus = bridge.nativeNetworkMtus() ?: return emptyList()
        return (0 until count).map { i ->
            NetworkInterfaceInfo(
                name = names.getOrElse(i) { "" },
                receivedBytes = rxBytes.getOrElse(i) { 0L },
                transmittedBytes = txBytes.getOrElse(i) { 0L },
                receivedPackets = rxPackets.getOrElse(i) { 0L },
                transmittedPackets = txPackets.getOrElse(i) { 0L },
                errorsOnReceived = rxErrors.getOrElse(i) { 0L },
                errorsOnTransmitted = txErrors.getOrElse(i) { 0L },
                macAddress = macs.getOrElse(i) { "" },
                mtu = mtus.getOrElse(i) { 0L },
            )
        }
    }

    override fun users(): List<UserInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeUserCount()
        if (count <= 0) return emptyList()
        val names = bridge.nativeUserNames() ?: return emptyList()
        val ids = bridge.nativeUserIds() ?: return emptyList()
        val groupIds = bridge.nativeUserGroupIds() ?: return emptyList()
        val groups = bridge.nativeUserGroups() ?: return emptyList()
        return (0 until count).map { i ->
            UserInfo(
                name = names.getOrElse(i) { "" },
                id = ids.getOrElse(i) { "" },
                groupId = groupIds.getOrElse(i) { "" },
                groups = groups.getOrElse(i) { "" }.split(",").filter { it.isNotEmpty() },
            )
        }
    }

    override fun motherboard(): MotherboardInfo? {
        if (!bridge.isLoaded) return null
        return MotherboardInfo(
            name = bridge.nativeMotherboardName(),
            vendorName = bridge.nativeMotherboardVendor(),
            version = bridge.nativeMotherboardVersion(),
            serialNumber = bridge.nativeMotherboardSerial(),
            assetTag = bridge.nativeMotherboardAssetTag(),
        )
    }

    override fun product(): ProductInfo? {
        if (!bridge.isLoaded) return null
        return ProductInfo(
            name = bridge.nativeProductName(),
            family = bridge.nativeProductFamily(),
            serialNumber = bridge.nativeProductSerial(),
            sku = bridge.nativeProductSku(),
            uuid = bridge.nativeProductUuid(),
            version = bridge.nativeProductVersion(),
            vendorName = bridge.nativeProductVendor(),
        )
    }

    override fun processes(): List<ProcessInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeProcessCount()
        if (count <= 0) return emptyList()
        val pids = bridge.nativeProcessPids() ?: return emptyList()
        val names = bridge.nativeProcessNames() ?: return emptyList()
        val exes = bridge.nativeProcessExes() ?: return emptyList()
        val memories = bridge.nativeProcessMemories() ?: return emptyList()
        val virtualMemories = bridge.nativeProcessVirtualMemories() ?: return emptyList()
        val cpuUsages = bridge.nativeProcessCpuUsages() ?: return emptyList()
        val statuses = bridge.nativeProcessStatuses() ?: return emptyList()
        val startTimes = bridge.nativeProcessStartTimes() ?: return emptyList()
        val runTimes = bridge.nativeProcessRunTimes() ?: return emptyList()
        val parentPids = bridge.nativeProcessParentPids() ?: return emptyList()
        val cmds = bridge.nativeProcessCmds() ?: return emptyList()
        val cwds = bridge.nativeProcessCwds() ?: return emptyList()
        val roots = bridge.nativeProcessRoots() ?: return emptyList()
        return (0 until count).map { i ->
            val ppid = parentPids.getOrElse(i) { -1L }
            ProcessInfo(
                pid = pids.getOrElse(i) { 0L },
                name = names.getOrElse(i) { "" },
                exe = exes.getOrElse(i) { "" }.ifEmpty { null },
                memory = memories.getOrElse(i) { 0L },
                virtualMemory = virtualMemories.getOrElse(i) { 0L },
                cpuUsage = cpuUsages.getOrElse(i) { 0f },
                status = statuses.getOrElse(i) { "Unknown" },
                startTime = startTimes.getOrElse(i) { 0L },
                runTime = runTimes.getOrElse(i) { 0L },
                parentPid = if (ppid >= 0) ppid else null,
                cmd = cmds.getOrElse(i) { "" }.split("\u0000").filter { it.isNotEmpty() },
                cwd = cwds.getOrElse(i) { "" }.ifEmpty { null },
                root = roots.getOrElse(i) { "" }.ifEmpty { null },
            )
        }
    }

    override fun process(pid: Long): ProcessInfo? {
        if (!bridge.isLoaded) return null
        val name = bridge.nativeProcessByPidName(pid) ?: return null
        val ppid = bridge.nativeProcessByPidParentPid(pid)
        return ProcessInfo(
            pid = pid,
            name = name,
            exe = bridge.nativeProcessByPidExe(pid)?.ifEmpty { null },
            memory = bridge.nativeProcessByPidMemory(pid),
            virtualMemory = bridge.nativeProcessByPidVirtualMemory(pid),
            cpuUsage = bridge.nativeProcessByPidCpuUsage(pid),
            status = bridge.nativeProcessByPidStatus(pid) ?: "Unknown",
            startTime = bridge.nativeProcessByPidStartTime(pid),
            runTime = bridge.nativeProcessByPidRunTime(pid),
            parentPid = if (ppid >= 0) ppid else null,
            cmd = (bridge.nativeProcessByPidCmd(pid) ?: "").split("\u0000").filter { it.isNotEmpty() },
            cwd = bridge.nativeProcessByPidCwd(pid)?.ifEmpty { null },
            root = bridge.nativeProcessByPidRoot(pid)?.ifEmpty { null },
        )
    }

    override fun idleTime(): Long {
        if (!bridge.isLoaded) return -1L
        return bridge.nativeIdleTimeSeconds()
    }

    @Suppress("CyclomaticComplexMethod")
    override fun batteryInfo(): BatteryInfo? {
        if (!bridge.isLoaded) return null
        if (!bridge.nativeBatteryPresent()) return null
        val currentCapacity = bridge.nativeBatteryCurrentCapacity()
        val maxCapacity = bridge.nativeBatteryMaxCapacity()
        val designCapacity = bridge.nativeBatteryDesignCapacity()
        val isCharging = bridge.nativeBatteryIsCharging()
        val fullyCharged = bridge.nativeBatteryFullyCharged()
        val isPluggedIn = bridge.nativeBatteryExternalConnected()
        val timeRemaining = bridge.nativeBatteryTimeRemaining()
        val temperature = bridge.nativeBatteryTemperature()
        val state =
            when {
                fullyCharged -> BatteryState.Full
                isCharging -> BatteryState.Charging
                !isPluggedIn -> BatteryState.Discharging
                else -> BatteryState.Unknown
            }
        val stateOfCharge =
            if (maxCapacity > 0) {
                (currentCapacity.toFloat() / maxCapacity).coerceIn(0f, 1f)
            } else {
                0f
            }
        val health =
            if (designCapacity > 0) {
                (maxCapacity.toFloat() / designCapacity).coerceIn(0f, 1f)
            } else {
                0f
            }
        return BatteryInfo(
            stateOfCharge = stateOfCharge,
            state = state,
            isPluggedIn = isPluggedIn,
            currentCapacity = currentCapacity,
            maxCapacity = maxCapacity,
            designCapacity = designCapacity,
            cycleCount = bridge.nativeBatteryCycleCount(),
            voltage = bridge.nativeBatteryVoltage(),
            amperage = bridge.nativeBatteryAmperage(),
            temperature = if (temperature.isNaN()) null else temperature,
            health = health,
            timeToFull = if (state == BatteryState.Charging && timeRemaining >= 0) timeRemaining else null,
            timeToEmpty = if (state == BatteryState.Discharging && timeRemaining >= 0) timeRemaining else null,
            manufacturer = bridge.nativeBatteryManufacturer(),
            modelName = bridge.nativeBatteryModelName(),
            serialNumber = bridge.nativeBatterySerialNumber(),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    override fun gpus(): List<GpuInfo> {
        if (!bridge.isLoaded) return emptyList()
        val count = bridge.nativeGpuCount()
        if (count <= 0) return emptyList()
        val names = bridge.nativeGpuNames() ?: return emptyList()
        val vendorIds = bridge.nativeGpuVendorIds() ?: return emptyList()
        val deviceIds = bridge.nativeGpuDeviceIds() ?: return emptyList()
        val dedicatedVideo = bridge.nativeGpuDedicatedVideoMemories() ?: return emptyList()
        val dedicatedSystem = bridge.nativeGpuDedicatedSystemMemories() ?: return emptyList()
        val sharedSystem = bridge.nativeGpuSharedSystemMemories() ?: return emptyList()
        val driverVersions = bridge.nativeGpuDriverVersions() ?: return emptyList()
        val temperatures = bridge.nativeGpuTemperatures()
        val usages = bridge.nativeGpuUsages()
        val memoryUsed = bridge.nativeGpuMemoryUsed()
        val coreClocks = bridge.nativeGpuCoreClocks()
        val memoryClocks = bridge.nativeGpuMemoryClocks()
        val fanSpeeds = bridge.nativeGpuFanSpeeds()
        val powerDraws = bridge.nativeGpuPowerDraws()
        return (0 until count).map { i ->
            val temp = temperatures?.getOrElse(i) { Float.NaN } ?: Float.NaN
            val usage = usages?.getOrElse(i) { Float.NaN } ?: Float.NaN
            val memUsed = memoryUsed?.getOrElse(i) { -1L } ?: -1L
            val coreClock = coreClocks?.getOrElse(i) { -1 } ?: -1
            val memClock = memoryClocks?.getOrElse(i) { -1 } ?: -1
            val fan = fanSpeeds?.getOrElse(i) { Float.NaN } ?: Float.NaN
            val power = powerDraws?.getOrElse(i) { Float.NaN } ?: Float.NaN
            GpuInfo(
                name = names.getOrElse(i) { "" },
                vendorId = vendorIds.getOrElse(i) { 0L },
                deviceId = deviceIds.getOrElse(i) { 0L },
                dedicatedVideoMemory = dedicatedVideo.getOrElse(i) { 0L },
                dedicatedSystemMemory = dedicatedSystem.getOrElse(i) { 0L },
                sharedSystemMemory = sharedSystem.getOrElse(i) { 0L },
                driverVersion = driverVersions.getOrElse(i) { "" }.ifEmpty { null },
                temperature = if (temp.isNaN()) null else temp,
                gpuUsage = if (usage.isNaN()) null else usage,
                memoryUsed = if (memUsed < 0) null else memUsed,
                coreClockMhz = if (coreClock < 0) null else coreClock,
                memoryClockMhz = if (memClock < 0) null else memClock,
                fanSpeedPercent = if (fan.isNaN()) null else fan,
                powerDrawWatts = if (power.isNaN()) null else power,
            )
        }
    }

    override fun connectivityInfo(): ConnectivityInfo? {
        if (!bridge.isLoaded) return null
        val connected = bridge.nativeIsNetworkConnected()
        return ConnectivityInfo(
            isConnected = connected,
            meteredStatus =
                if (!connected) {
                    MeteredStatus.NOT_AVAILABLE
                } else {
                    when (bridge.nativeGetMeteredStatus()) {
                        1 -> MeteredStatus.UNMETERED
                        2 -> MeteredStatus.METERED
                        else -> MeteredStatus.UNKNOWN
                    }
                },
        )
    }
}
