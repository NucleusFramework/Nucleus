@file:Suppress("TooManyFunctions", "MagicNumber")

package systeminfodemo.viewmodel

import dev.nucleusframework.nucleus.systeminfo.SystemInfo
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class SystemInfoState(
    val osInfo: OsInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val cpuInfo: CpuGlobalInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val components: List<ComponentInfo> = emptyList(),
    val networks: List<NetworkInterfaceInfo> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val motherboard: MotherboardInfo? = null,
    val product: ProductInfo? = null,
    val processes: List<ProcessInfo> = emptyList(),
    val gpus: List<GpuInfo> = emptyList(),
    val batteryInfo: BatteryInfo? = null,
    val connectivityInfo: ConnectivityInfo? = null,
    val idleTime: Long = -1L,
    val cpuHistory: List<Float> = emptyList(),
    val cpuTempHistory: List<Float> = emptyList(),
    val coreTempHistory: Map<Int, List<Float>> = emptyMap(),
    val memoryHistory: List<Float> = emptyList(),
    val gpuUsageHistory: Map<Int, List<Float>> = emptyMap(),
    val gpuTempHistory: Map<Int, List<Float>> = emptyMap(),
)

private const val REFRESH_INTERVAL_MS = 1000L
private const val HISTORY_MAX_SIZE = 240

object SystemInfoViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SystemInfoState())
    val state: StateFlow<SystemInfoState> = _state.asStateFlow()

    init {
        scope.launch { collectLoop() }
    }

    private suspend fun collectLoop() {
        while (true) {
            refresh()
            delay(REFRESH_INTERVAL_MS.milliseconds)
        }
    }

    private fun refresh() {
        val os = SystemInfo.osInfo()
        val mem = SystemInfo.memoryInfo()
        val cpu = SystemInfo.cpuInfo()
        val disks = SystemInfo.disks()
        val components = SystemInfo.components()
        val networks = SystemInfo.networks()
        val users = SystemInfo.users()
        val mb = SystemInfo.motherboard()
        val product = SystemInfo.product()
        val procs = SystemInfo.processes()
        val gpus = SystemInfo.gpus()
        val battery = SystemInfo.batteryInfo()
        val connectivity = SystemInfo.connectivityInfo()
        val idleTime = SystemInfo.idleTime()

        val current = _state.value
        val cpuHist = (current.cpuHistory + (cpu?.globalCpuUsage ?: 0f)).takeLast(HISTORY_MAX_SIZE)

        // CPU Temperatures
        val cpuTemps = components.filter { it.label.contains("cpu", ignoreCase = true) && it.temperature != null }
        val avgCpuTemp = if (cpuTemps.isNotEmpty()) cpuTemps.mapNotNull { it.temperature }.average().toFloat() else 0f
        val cpuTempHist = (current.cpuTempHistory + avgCpuTemp).takeLast(HISTORY_MAX_SIZE)

        val coreTempHist = current.coreTempHistory.toMutableMap()
        cpuTemps.forEachIndexed { i, comp ->
            comp.temperature?.let { temp ->
                coreTempHist[i] = ((coreTempHist[i] ?: emptyList()) + temp).takeLast(HISTORY_MAX_SIZE)
            }
        }

        val memPercent =
            if (mem != null && mem.totalMemory > 0) {
                mem.usedMemory.toFloat() / mem.totalMemory.toFloat() * 100f
            } else {
                0f
            }
        val memHist = (current.memoryHistory + memPercent).takeLast(HISTORY_MAX_SIZE)

        val gpuUsageHist = current.gpuUsageHistory.toMutableMap()
        val gpuTempHist = current.gpuTempHistory.toMutableMap()
        gpus.forEachIndexed { i, gpu ->
            gpu.gpuUsage?.let { usage ->
                gpuUsageHist[i] = ((gpuUsageHist[i] ?: emptyList()) + usage).takeLast(HISTORY_MAX_SIZE)
            }
            gpu.temperature?.let { temp ->
                gpuTempHist[i] = ((gpuTempHist[i] ?: emptyList()) + temp).takeLast(HISTORY_MAX_SIZE)
            }
        }

        _state.value =
            SystemInfoState(
                osInfo = os,
                memoryInfo = mem,
                cpuInfo = cpu,
                disks = disks,
                components = components,
                networks = networks,
                users = users,
                motherboard = mb,
                product = product,
                processes = procs,
                gpus = gpus,
                batteryInfo = battery,
                connectivityInfo = connectivity,
                idleTime = idleTime,
                cpuHistory = cpuHist,
                cpuTempHistory = cpuTempHist,
                coreTempHistory = coreTempHist,
                memoryHistory = memHist,
                gpuUsageHistory = gpuUsageHist,
                gpuTempHistory = gpuTempHist,
            )
    }
}
