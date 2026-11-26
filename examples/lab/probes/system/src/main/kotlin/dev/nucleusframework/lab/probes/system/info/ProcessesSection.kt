package dev.nucleusframework.lab.probes.system.info

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.formatBytes
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import java.util.Locale

/**
 * The process list (only read while this section is shown), with the Lab's own process
 * pinned on top: its pid, memory and parent must match what the JDK says about itself.
 */
@Composable
fun ProcessesSection(
    state: SystemInfoState,
    onQuery: (String) -> Unit,
) {
    TextFieldRow("Filter (name, pid or executable)", state.processQuery, onValueChange = onQuery)
    SectionBody(sectionAvailability(InfoSection.Processes, state.sample)) {
        val all = state.sample?.processes.orEmpty()
        val self = ProcessHandle.current()
        val own = all.firstOrNull { it.pid == self.pid() }
        SubHeading("This process (pid ${self.pid()})")
        if (own == null) {
            Readout("in processes()", "missing: the list misses the caller itself", tone = Tone.Error)
        } else {
            val parent = self.parent().map { it.pid() }.orElse(null)
            Readout("name / exe", "${own.name}  ${own.exe ?: "exe not reported"}")
            Readout("memory (RSS)", formatBytes(own.memory))
            Readout(
                "parent pid",
                "${own.parentPid}  (JDK: $parent)",
                tone = if (parent != null && own.parentPid != parent) Tone.Warning else Tone.Neutral,
            )
            Readout("run time", formatDurationMillis(own.runTime * 1000))
        }
        val shown = filterProcesses(state.sample, state.processQuery)
        SubHeading("${shown.size} of ${all.size} processes, by CPU")
        if (state.samples <= 1) Hint("CPU % needs two samples: the first read is all zeros.")
        shown.forEach { p ->
            Readout(
                p.pid.toString(),
                "%-28s %6s %10s  %s".format(
                    Locale.ROOT,
                    p.name.take(28),
                    percentOf(p.cpuUsage),
                    formatBytes(p.memory),
                    p.status,
                ),
                tone = if (p.pid == self.pid()) Tone.Ok else Tone.Neutral,
            )
        }
    }
}
