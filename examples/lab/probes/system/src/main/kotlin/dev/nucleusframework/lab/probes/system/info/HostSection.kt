package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone

/** OS identity, uptime, user idle time and the machine's SMBIOS strings. */
@Composable
fun HostSection(state: SystemInfoState) {
    SectionBody(sectionAvailability(InfoSection.Host, state.sample)) {
        val sample = state.sample ?: return@SectionBody
        val os = sample.os ?: return@SectionBody
        Readout("name / version", listOfNotNull(os.name, os.osVersion).joinToString(" ").ifBlank { null })
        Readout("long version", os.longOsVersion)
        Readout("kernel", os.kernelVersion)
        Readout("distribution id", os.distributionId)
        Readout(
            "arch",
            "${os.cpuArch}  (JVM os.arch: ${System.getProperty("os.arch")})",
        )
        Readout("host name", os.hostName)
        Readout("uptime", formatDurationMillis(os.uptime * 1000))
        // Idle time resets on any input: it should drop to ~0 while you move the mouse.
        Readout(
            "idleTime()",
            if (sample.idleMillis < 0) "not supported here" else "${sample.idleMillis} ms",
            tone = if (sample.idleMillis < 0) Tone.Muted else Tone.Neutral,
        )
        SubHeading("Hardware")
        val product = sample.product
        val board = sample.motherboard
        Readout(
            "product",
            product?.let { listOfNotNull(it.vendorName, it.name, it.version).joinToString(" ") } ?: "not reported",
        )
        Readout("family / sku", product?.let { "${it.family ?: "—"} / ${it.sku ?: "—"}" } ?: "not reported")
        Readout(
            "motherboard",
            board?.let { listOfNotNull(it.vendorName, it.name, it.version).joinToString(" ") } ?: "not reported",
        )
    }
}
