package dev.nucleusframework.lab.probes.system.energy

import androidx.compose.runtime.Composable
import dev.nucleusframework.lab.core.format.fmt
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.lab.probes.system.ToolReadBackView

/** What the OS says back: scheduling class, the benchmark, power assertions, every call's result. */
@Composable
fun EnergyObserved(state: EnergyState) {
    SubHeading("Efficiency")
    Readout("requested level", state.efficiency.name)
    val window =
        when {
            state.windowMinimized -> "minimized"
            state.windowFocused -> "focused"
            else -> "unfocused"
        }
    val policy =
        if (state.followWindow) "  → policy wants ${efficiencyFor(state.windowFocused, state.windowMinimized)}" else ""
    Readout("Lab window", window + policy, tone = if (state.followWindow) Tone.Neutral else Tone.Muted)
    ToolReadBackView("OS scheduling", state.osScheduling)

    SubHeading("Benchmark")
    BenchmarkList(state.benchmarks, state.benchmarking)

    SubHeading("Awake")
    Readout(
        "isAwakeActive()",
        state.awakeActive?.toString(),
        tone = if (state.awakeMismatch()) Tone.Warning else Tone.Neutral,
    )
    if (state.awakeMismatch()) {
        Readout(
            "mismatch",
            "differs from what this probe holds (another part of the app may hold a request)",
            tone = Tone.Warning,
        )
    }
    Readout("keepAwake slot", state.awakeSlot?.name ?: "released")
    Readout(
        "open handles",
        if (state.handles.isEmpty()) "none" else state.handles.joinToString { "#${it.id} ${it.mode}" },
    )
    ToolReadBackView("OS assertions", state.osAwake, empty = "None listed for this process.")

    SubHeading("Calls")
    EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
}

@Composable
private fun BenchmarkList(
    benchmarks: List<Benchmark>,
    running: Boolean,
) {
    if (running) EmptyState("Running…")
    val baseline = benchmarks.lastOrNull { it.efficiency == EfficiencyLevel.Off && !it.threadEfficiency }
    EventLog(
        benchmarks.map { run ->
            val ratio = baseline?.takeIf { it !== run && it.millis > 0 }?.let { run.millis.toFloat() / it.millis }
            LogEntry(
                text =
                    "${run.millis} ms  ${run.efficiency}${if (run.threadEfficiency) " + thread" else ""}" +
                        (ratio?.let { "  ×${it.fmt(2)} vs baseline" } ?: ""),
                epochMillis = run.epochMillis,
                tone = if (ratio != null && ratio > SLOWER) Tone.Ok else Tone.Neutral,
            )
        },
        empty = "Run it once at Off for a baseline, then again under Light / Full.",
    )
}

/** Above this ratio the mode visibly slowed the workload down: that is the expected outcome. */
private const val SLOWER = 1.15f
