package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.LiveChart
import dev.nucleusframework.lab.designsystem.Meter
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone

/** RAM chart and breakdown, swap, and the JVM's own heap for comparison. */
@Composable
fun MemorySection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Memory, state.sample)) {
        val memory = state.sample?.memory ?: return@SectionBody
        val usedFraction = memory.usedMemory.toFloat() / memory.totalMemory
        LiveChart(
            title = "used / total",
            values = state.memoryHistory,
            current = "${formatBytes(memory.usedMemory)} / ${formatBytes(memory.totalMemory)}",
            max = 100f,
        )
        Meter("used", usedFraction, percent(usedFraction.toDouble()))
        Readout("free", formatBytes(memory.freeMemory))
        Readout("available", formatBytes(memory.availableMemory))
        // used + available should land near total; a big gap means one of them is mis-sourced.
        val gap = memory.totalMemory - memory.usedMemory - memory.availableMemory
        Readout(
            "total − used − available",
            formatBytes(kotlin.math.abs(gap)) + if (gap < 0) " (negative)" else "",
            tone = if (kotlin.math.abs(gap) > memory.totalMemory / 10) Tone.Warning else Tone.Muted,
        )
        SubHeading("Swap")
        if (memory.totalSwap == 0L) {
            EmptyState("No swap configured.")
        } else {
            Meter("swap used", memory.usedSwap.toFloat() / memory.totalSwap, formatBytes(memory.usedSwap))
            Readout("swap total", formatBytes(memory.totalSwap))
        }
        SubHeading("This JVM")
        val runtime = Runtime.getRuntime()
        Readout(
            "heap used / max",
            "${formatBytes(runtime.totalMemory() - runtime.freeMemory())} / ${formatBytes(runtime.maxMemory())}",
        )
    }
}
