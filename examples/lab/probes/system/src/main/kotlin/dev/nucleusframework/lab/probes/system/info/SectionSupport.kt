package dev.nucleusframework.lab.probes.system.info

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.systeminfo.model.ProcessInfo

/**
 * Whether [section] has anything to show in [sample], and if not, which call came back empty.
 * An empty list is a legitimate answer of `SystemInfo` (no battery, no sensor), so the reason
 * names the call and the usual cause rather than guessing at a bug.
 */
fun sectionAvailability(
    section: InfoSection,
    sample: SystemSample?,
): Availability {
    if (sample == null) return Availability.Unknown
    return when (section) {
        InfoSection.Cpu -> Availability.of(sample.cpu != null) { "cpuInfo() returned null" }
        InfoSection.Memory ->
            Availability.of(
                sample.memory?.let { it.totalMemory > 0 } == true,
            ) { "memoryInfo() returned null or a total of 0" }
        InfoSection.Gpu ->
            Availability.of(
                sample.gpus.isNotEmpty(),
            ) { "gpus() found no adapter (headless session, VM without a virtual GPU)" }
        InfoSection.Disks -> Availability.of(sample.disks.isNotEmpty()) { "disks() returned no volume" }
        InfoSection.Sensors ->
            Availability.of(sample.components.isNotEmpty()) {
                "components() returned no sensor (usual in VMs, and on Windows without a sensor driver exposed to user mode)"
            }
        InfoSection.Network -> Availability.of(sample.networks.isNotEmpty()) { "networks() returned no interface" }
        InfoSection.Processes ->
            when {
                sample.processes == null -> Availability.Unknown
                sample.processes.isEmpty() -> Availability.Unavailable("processes() returned an empty list")
                else -> Availability.Available
            }
        InfoSection.Battery ->
            Availability.of(sample.battery != null) {
                "batteryInfo() returned null: no battery (desktop, VM) or no power-supply class"
            }
        InfoSection.Host -> Availability.of(sample.os != null) { "osInfo() returned null" }
    }
}

/** Process list after the text filter (name, pid or executable), heaviest CPU first. */
fun filterProcesses(
    sample: SystemSample?,
    query: String,
    limit: Int = 40,
): List<ProcessInfo> {
    val all = sample?.processes.orEmpty()
    val q = query.trim()
    val matching =
        if (q.isEmpty()) {
            all
        } else {
            all.filter { p ->
                p.name.contains(q, ignoreCase = true) ||
                    p.pid.toString() == q ||
                    p.exe?.contains(q, ignoreCase = true) == true
            }
        }
    return matching.sortedByDescending { it.cpuUsage }.take(limit)
}

/** A sensor reading: `54.0 °C`, `n/a` when the sensor gives none. */
fun celsius(value: Float?): String = value?.let { "${it.fmt(1)} °C" } ?: "n/a"

/** A usage the OS reports in percent (0..100), not as a fraction. */
fun percentOf(value: Float): String = percent(value / 100.0)
