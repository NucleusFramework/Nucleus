package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LiveChart
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.systeminfo.model.GpuInfo
import java.util.Locale

/** One block per adapter: identity, memory, and the live counters the driver exposes. */
@Composable
fun GpuSection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Gpu, state.sample)) {
        val gpus = state.sample?.gpus.orEmpty()
        gpus.forEachIndexed { index, gpu ->
            SubHeading("#$index ${gpu.name}")
            Readout("vendor / device", "0x%04X / 0x%04X".format(Locale.ROOT, gpu.vendorId, gpu.deviceId))
            Readout("driver", gpu.driverVersion ?: "not reported")
            Readout("dedicated video", formatBytes(gpu.dedicatedVideoMemory))
            Readout(
                "dedicated / shared system",
                "${formatBytes(gpu.dedicatedSystemMemory)} / ${formatBytes(gpu.sharedSystemMemory)}",
            )
            GpuLive(state, index, gpu)
        }
    }
}

@Composable
private fun GpuLive(
    state: SystemInfoState,
    index: Int,
    gpu: GpuInfo,
) {
    val usage = gpu.gpuUsage
    if (usage == null) {
        // Live counters come from vendor libraries (NVML, IOKit performance statistics, sysfs).
        EmptyState("No live counters: the driver exposes no utilisation for this adapter.")
        return
    }
    LiveChart("usage", state.gpuHistory[gpuKey(index, gpu)].orEmpty(), percentOf(usage), max = 100f)
    gpu.memoryUsed?.let { used ->
        val total = gpu.dedicatedVideoMemory
        if (total > 0) {
            Meter("VRAM used", used.toFloat() / total, formatBytes(used))
        } else {
            Readout("VRAM used", formatBytes(used))
        }
    }
    Readout("temperature", celsius(gpu.temperature))
    Readout(
        "clocks core / memory",
        "${gpu.coreClockMhz?.let { "$it MHz" } ?: "n/a"} / ${gpu.memoryClockMhz?.let { "$it MHz" } ?: "n/a"}",
    )
    Readout(
        "fan / power",
        "${gpu.fanSpeedPercent?.let(::percentOf) ?: "n/a"} / ${gpu.powerDrawWatts?.let { "${it.fmt(1)} W" } ?: "n/a"}",
    )
}
