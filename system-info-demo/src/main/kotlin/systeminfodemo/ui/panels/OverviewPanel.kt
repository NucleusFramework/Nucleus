@file:Suppress("MagicNumber", "MaxLineLength")

package systeminfodemo.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.nucleus.systeminfo.model.MeteredStatus
import systeminfodemo.ui.InfoRow
import systeminfodemo.ui.LineChart
import systeminfodemo.ui.ProgressBar
import systeminfodemo.ui.SectionCard
import systeminfodemo.ui.formatBytes
import systeminfodemo.ui.formatDuration
import systeminfodemo.viewmodel.SystemInfoState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewPanel(state: SystemInfoState) {
    // CPU + Memory side by side with live charts
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionCard("CPU", modifier = Modifier.weight(1f)) {
            val usage = state.cpuInfo?.globalCpuUsage ?: 0f
            InfoRow("Usage", "%.1f%%".format(usage))
            ProgressBar(progress = usage / 100f, color = cpuColor(usage))
            InfoRow(
                "Model",
                state.cpuInfo
                    ?.cpus
                    ?.firstOrNull()
                    ?.brand,
            )
            InfoRow(
                "Cores",
                "${state.cpuInfo?.physicalCoreCount ?: "?"} physical / ${state.cpuInfo?.cpus?.size ?: "?"} logical",
            )
            if (state.cpuHistory.size >= 2) {
                LineChart(data = state.cpuHistory, lineColor = Color(0xFF5AB869))
            }
        }

        SectionCard("Memory", modifier = Modifier.weight(1f)) {
            val mem = state.memoryInfo
            val pct = if (mem != null && mem.totalMemory > 0) mem.usedMemory.toFloat() / mem.totalMemory * 100f else 0f
            InfoRow("Usage", "%.1f%%".format(pct))
            ProgressBar(progress = pct / 100f, color = memoryColor(pct))
            InfoRow("Used / Total", mem?.let { "${formatBytes(it.usedMemory)} / ${formatBytes(it.totalMemory)}" })
            InfoRow("Available", mem?.let { formatBytes(it.availableMemory) })
            if (state.memoryHistory.size >= 2) {
                LineChart(data = state.memoryHistory, lineColor = Color(0xFF3574F0))
            }
        }
    }

    // GPU + System
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.gpus.isNotEmpty()) {
            SectionCard("GPU", modifier = Modifier.weight(1f)) {
                state.gpus.forEach { gpu ->
                    InfoRow("Name", gpu.name)
                    gpu.gpuUsage?.let { usage ->
                        InfoRow("Usage", "%.1f%%".format(usage))
                        ProgressBar(progress = usage / 100f, color = gpuColor(usage))
                    }
                    gpu.temperature?.let { temp ->
                        InfoRow("Temperature", "%.0f\u00B0C".format(temp))
                        ProgressBar(
                            progress = (temp / 100f).coerceIn(0f, 1f),
                            color = gpuTempColor(temp),
                        )
                    }
                    gpu.memoryUsed?.let { used ->
                        if (gpu.dedicatedVideoMemory > 0) {
                            InfoRow("VRAM", "${formatBytes(used)} / ${formatBytes(gpu.dedicatedVideoMemory)}")
                        }
                    } ?: run {
                        if (gpu.dedicatedVideoMemory > 0) InfoRow("VRAM", formatBytes(gpu.dedicatedVideoMemory))
                    }
                    gpu.coreClockMhz?.let { InfoRow("Core Clock", "$it MHz") }
                    gpu.powerDrawWatts?.let { InfoRow("Power", "%.1f W".format(it)) }
                    gpu.driverVersion?.let { InfoRow("Driver", it) }
                }
                val gpuHistory = state.gpuUsageHistory[0]
                if (gpuHistory != null && gpuHistory.size >= 2) {
                    LineChart(data = gpuHistory, lineColor = Color(0xFF9C6ADE))
                }
            }
        }

        SectionCard("System", modifier = Modifier.weight(1f)) {
            InfoRow("OS", state.osInfo?.longOsVersion)
            InfoRow("Kernel", state.osInfo?.kernelVersion)
            InfoRow("Hostname", state.osInfo?.hostName)
            InfoRow("Architecture", state.osInfo?.cpuArch)
            InfoRow("Uptime", state.osInfo?.let { formatDuration(it.uptime) })
            InfoRow("Idle", if (state.idleTime >= 0) formatDuration(state.idleTime) else "N/A")
        }
    }

    // Storage + Sensors/Network
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SectionCard("Storage", modifier = Modifier.weight(1f)) {
            state.disks.forEach { disk ->
                val used = disk.totalSpace - disk.availableSpace
                val usedFraction = if (disk.totalSpace > 0) used.toFloat() / disk.totalSpace else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(disk.mountPoint, "${formatBytes(used)} / ${formatBytes(disk.totalSpace)}")
                    ProgressBar(progress = usedFraction, color = diskColor(usedFraction))
                }
            }
        }
    }

    // Sensors + Network
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.components.isNotEmpty()) {
            SectionCard("Sensors", modifier = Modifier.weight(1f)) {
                state.components.take(6).forEach { comp ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        org.jetbrains.jewel.ui.component
                            .Text(comp.label)
                        org.jetbrains.jewel.ui.component.Text(
                            comp.temperature?.let { "%.1f\u00B0C".format(it) } ?: "N/A",
                        )
                    }
                }
            }
        }

        if (state.networks.any { it.name != "lo" } || state.connectivityInfo != null) {
            SectionCard("Network", modifier = Modifier.weight(1f)) {
                state.connectivityInfo?.let { conn ->
                    InfoRow("Connected", if (conn.isConnected) "Yes" else "No")
                    InfoRow(
                        "Metered",
                        when (conn.meteredStatus) {
                            MeteredStatus.NOT_AVAILABLE -> "N/A"
                            MeteredStatus.UNMETERED -> "No"
                            MeteredStatus.METERED -> "Yes"
                            MeteredStatus.UNKNOWN -> "Unknown"
                        },
                    )
                }
                state.networks.filter { it.name != "lo" }.take(4).forEach { net ->
                    InfoRow(
                        net.name,
                        "TX: ${formatBytes(net.transmittedBytes)} / RX: ${formatBytes(net.receivedBytes)}",
                    )
                }
            }
        }
    }
}

internal fun cpuColor(usage: Float): Color =
    when {
        usage < 30f -> Color(0xFF5AB869)
        usage < 70f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

internal fun memoryColor(percent: Float): Color =
    when {
        percent < 50f -> Color(0xFF3574F0)
        percent < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

internal fun diskColor(fraction: Float): Color =
    when {
        fraction < 0.7f -> Color(0xFF5AB869)
        fraction < 0.9f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

internal fun gpuColor(usage: Float): Color =
    when {
        usage < 30f -> Color(0xFF9C6ADE)
        usage < 70f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

private fun gpuTempColor(temp: Float): Color =
    when {
        temp < 60f -> Color(0xFF5AB869)
        temp < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }
