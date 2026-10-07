package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LiveChart
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading

/** Global usage chart, then one meter per logical CPU. */
@Composable
fun CpuSection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Cpu, state.sample)) {
        val cpu = state.sample?.cpu ?: return@SectionBody
        LiveChart(
            title = "globalCpuUsage",
            values = state.cpuHistory,
            current = percentOf(cpu.globalCpuUsage),
            max = 100f,
        )
        val first = cpu.cpus.firstOrNull()
        Readout("brand", first?.brand?.ifBlank { null } ?: "not reported")
        Readout("vendor", first?.vendorId?.ifBlank { null } ?: "not reported")
        Readout("logical / physical", "${cpu.cpus.size} / ${cpu.physicalCoreCount ?: "not reported"}")
        // The first read has no previous tick to diff against: every core reads 0 %.
        if (state.samples <= 1) EmptyState("Per-core usage needs two samples.")
        SubHeading("Per core")
        cpu.cpus.forEach { core ->
            Meter(
                label = core.name,
                fraction = core.cpuUsage / 100f,
                text = "${percentOf(core.cpuUsage)} ${core.frequency} MHz",
            )
        }
    }
}
