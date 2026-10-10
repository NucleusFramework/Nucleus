package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.mvi.Reducer
import dev.nucleusframework.lab.core.mvi.append
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

enum class InfoSection { Cpu, Memory, Gpu, Disks, Sensors, Network, Processes, Battery, Host }

/** Sampling period; `null` = paused. */
enum class SamplingInterval(
    val millis: Long?,
    val label: String,
) {
    Fast(250, "250 ms"),
    Normal(1_000, "1 s"),
    Slow(5_000, "5 s"),
    Paused(null, "paused"),
}

/** One read of every `SystemInfo` call, as returned. */
@Immutable
data class SystemSample(
    val epochMillis: Long,
    val os: OsInfo?,
    val memory: MemoryInfo?,
    val cpu: CpuGlobalInfo?,
    val disks: List<DiskInfo>,
    val components: List<ComponentInfo>,
    val networks: List<NetworkInterfaceInfo>,
    val gpus: List<GpuInfo>,
    val battery: BatteryInfo?,
    val connectivity: ConnectivityInfo?,
    val idleMillis: Long,
    val motherboard: MotherboardInfo?,
    val product: ProductInfo?,
    /** Only read while the Processes section is shown: it is the expensive call. */
    val processes: List<ProcessInfo>?,
)

/** Receive/transmit throughput of one interface, from two consecutive cumulative counters. */
@Immutable
data class NetRate(
    val rxBytesPerSec: Float,
    val txBytesPerSec: Float,
)

@Immutable
data class SystemInfoState(
    val available: Availability = Availability.Unknown,
    val interval: SamplingInterval = SamplingInterval.Normal,
    val section: InfoSection = InfoSection.Cpu,
    val processQuery: String = "",
    val sample: SystemSample? = null,
    val samples: Int = 0,
    /** Wall time of the last full read, i.e. what the API costs the caller. */
    val lastSampleMillis: Long? = null,
    val slowestSampleMillis: Long = 0,
    val cpuHistory: List<Float> = emptyList(),
    val memoryHistory: List<Float> = emptyList(),
    val gpuHistory: Map<String, List<Float>> = emptyMap(),
    val netRates: Map<String, NetRate> = emptyMap(),
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList(),
    val error: String? = null,
)

sealed interface SystemInfoIntent {
    data class SetInterval(
        val interval: SamplingInterval,
    ) : SystemInfoIntent

    data class ShowSection(
        val section: InfoSection,
    ) : SystemInfoIntent

    data class FilterProcesses(
        val query: String,
    ) : SystemInfoIntent

    data object SampleNow : SystemInfoIntent
}

sealed interface SystemInfoEvent {
    data class AvailabilityResolved(
        val available: Availability,
    ) : SystemInfoEvent

    data class IntervalChanged(
        val interval: SamplingInterval,
    ) : SystemInfoEvent

    data class SectionChanged(
        val section: InfoSection,
    ) : SystemInfoEvent

    data class QueryChanged(
        val query: String,
    ) : SystemInfoEvent

    data class Sampled(
        val sample: SystemSample,
        val durationMillis: Long,
    ) : SystemInfoEvent {
        override fun toString(): String = "Sampled($durationMillis ms)"
    }

    data class SampleFailed(
        val message: String,
    ) : SystemInfoEvent
}

object SystemInfoReducer : Reducer<SystemInfoState, SystemInfoEvent> {
    const val HISTORY = 120

    override fun reduce(
        state: SystemInfoState,
        event: SystemInfoEvent,
    ): SystemInfoState =
        when (event) {
            is SystemInfoEvent.AvailabilityResolved -> state.copy(available = event.available)
            is SystemInfoEvent.IntervalChanged -> state.copy(interval = event.interval)
            is SystemInfoEvent.SectionChanged -> state.copy(section = event.section)
            is SystemInfoEvent.QueryChanged -> state.copy(processQuery = event.query)
            is SystemInfoEvent.SampleFailed -> state.copy(error = event.message)
            is SystemInfoEvent.Sampled -> sampled(state, event)
        }

    private fun sampled(
        state: SystemInfoState,
        event: SystemInfoEvent.Sampled,
    ): SystemInfoState {
        val sample = event.sample
        val previous = state.sample
        val memoryPercent = sample.memory?.takeIf { it.totalMemory > 0 }?.let { it.usedMemory * 100f / it.totalMemory }
        val rates = previous?.let { netRates(it, sample) } ?: state.netRates
        val gpuHistory =
            sample.gpus.foldIndexed(state.gpuHistory) { index, acc, gpu ->
                val usage = gpu.gpuUsage ?: return@foldIndexed acc
                val key = gpuKey(index, gpu)
                acc + (key to acc[key].orEmpty().append(usage, HISTORY))
            }
        // Rates need a previous sample; the first one adds no point to the throughput charts.
        val rxTotal = rates.values.sumOf { it.rxBytesPerSec.toDouble() }.toFloat()
        val txTotal = rates.values.sumOf { it.txBytesPerSec.toDouble() }.toFloat()
        return state.copy(
            sample = sample.copy(processes = sample.processes ?: previous?.processes),
            samples = state.samples + 1,
            lastSampleMillis = event.durationMillis,
            slowestSampleMillis = maxOf(state.slowestSampleMillis, event.durationMillis),
            cpuHistory = sample.cpu?.let { state.cpuHistory.append(it.globalCpuUsage, HISTORY) } ?: state.cpuHistory,
            memoryHistory = memoryPercent?.let { state.memoryHistory.append(it, HISTORY) } ?: state.memoryHistory,
            gpuHistory = gpuHistory,
            netRates = rates,
            rxHistory = if (previous == null) state.rxHistory else state.rxHistory.append(rxTotal, HISTORY),
            txHistory = if (previous == null) state.txHistory else state.txHistory.append(txTotal, HISTORY),
            error = null,
        )
    }

    /**
     * Interface counters are cumulative since boot: a rate is the difference over the time
     * between the two samples. A counter going backwards (interface reset) reads as 0.
     */
    fun netRates(
        before: SystemSample,
        after: SystemSample,
    ): Map<String, NetRate> {
        val seconds = (after.epochMillis - before.epochMillis) / 1000f
        if (seconds <= 0f) return emptyMap()
        val old = before.networks.associateBy { it.name }
        return after.networks.associate { now ->
            val then = old[now.name]
            now.name to
                if (then == null) {
                    NetRate(0f, 0f)
                } else {
                    NetRate(
                        rxBytesPerSec = (now.receivedBytes - then.receivedBytes).coerceAtLeast(0) / seconds,
                        txBytesPerSec = (now.transmittedBytes - then.transmittedBytes).coerceAtLeast(0) / seconds,
                    )
                }
        }
    }
}

/** Two identical adapters share a name; the index keeps their histories apart. */
fun gpuKey(
    index: Int,
    gpu: GpuInfo,
): String = "#$index ${gpu.name}"
