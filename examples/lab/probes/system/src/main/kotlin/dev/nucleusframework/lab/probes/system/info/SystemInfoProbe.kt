package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class SystemInfoProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "System info",
            domain = Domain.System,
            summary =
                "Do CPU, memory, GPU, disk, sensor, network, process and battery " +
                    "readings match the OS's own monitor, live?",
            modules = listOf("system-info"),
            checks =
                listOf(
                    Check(
                        "cpu-live",
                        "CPU usage and the per-core meters track Activity Monitor / Task Manager / top within a few %",
                    ),
                    Check("memory", "Used memory matches the OS monitor, and opening a large app moves the chart"),
                    Check("self-process", "The Lab's own process is found, with the JDK's pid and parent pid"),
                    Check("network-rate", "Downloading something shows a matching ↓ rate on the active interface"),
                    Check("battery", "Unplugging the charger flips plugged-in and state within one sample (laptops)"),
                    Check(
                        "empty-explained",
                        "Every section without data says which call came back empty, never a blank area",
                    ),
                    Check(
                        "sample-cost",
                        "At 250 ms the last read stays well under the interval (no timeline warnings)",
                    ),
                ),
            keywords = listOf("cpu", "memory", "gpu", "disk", "temperature", "battery", "process", "network"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<SystemInfoViewModel>()
        val state by vm.state.collectAsState()
        val sample = state.sample

        ProbeLayout(
            capabilities =
                listOf(Capability("Native library", state.available)) +
                    InfoSection.entries.map { Capability(it.label, sectionAvailability(it, sample)) },
            controls = {
                ChoiceRow("Sampling", SamplingInterval.entries, state.interval, name = { it.label }) {
                    vm.onIntent(SystemInfoIntent.SetInterval(it))
                }
                ChoiceRow("Section", InfoSection.entries, state.section, name = { it.label }) {
                    vm.onIntent(SystemInfoIntent.ShowSection(it))
                }
                Actions {
                    SecondaryAction(
                        "Sample now",
                        enabled = state.available.isAvailable,
                    ) { vm.onIntent(SystemInfoIntent.SampleNow) }
                }
                SubHeading("Cost of one full read")
                Readout("samples", state.samples.toString())
                Readout("last / slowest", state.lastSampleMillis?.let { "$it ms / ${state.slowestSampleMillis} ms" })
                state.interval.millis?.let { period ->
                    val last = state.lastSampleMillis
                    if (last != null && last > period) {
                        Readout(
                            "warning",
                            "a read takes longer than the interval: samples are back to back",
                            tone = Tone.Warning,
                        )
                    }
                }
                if (state.section != InfoSection.Processes) {
                    Hint("processes() is only read while the Processes section is shown.")
                }
                state.error?.let { Readout("last error", it, tone = Tone.Error) }
            },
            observed = {
                SubHeading(state.section.label)
                when (state.section) {
                    InfoSection.Cpu -> CpuSection(state)
                    InfoSection.Memory -> MemorySection(state)
                    InfoSection.Gpu -> GpuSection(state)
                    InfoSection.Disks -> DisksSection(state)
                    InfoSection.Sensors -> SensorsSection(state)
                    InfoSection.Network -> NetworkSection(state)
                    InfoSection.Processes ->
                        ProcessesSection(
                            state,
                        ) { vm.onIntent(SystemInfoIntent.FilterProcesses(it)) }
                    InfoSection.Battery -> BatterySection(state)
                    InfoSection.Host -> HostSection(state)
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("system.info")
    }
}

val InfoSection.label: String
    get() =
        when (this) {
            InfoSection.Cpu -> "CPU"
            InfoSection.Gpu -> "GPU"
            else -> name
        }
