package dev.nucleusframework.lab.probes.system.energy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.energymanager.AwakeMode
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class EnergyProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Energy",
            domain = Domain.System,
            summary =
                "Do efficiency modes really deprioritise the process, and does " +
                    "keep-awake hold off sleep until released?",
            modules = listOf("energy-manager"),
            checks =
                listOf(
                    Check(
                        "efficiency-visible",
                        "Full efficiency shows in the OS: green leaf in Task Manager, nice 19 in ps, or a slower benchmark",
                    ),
                    Check(
                        "efficiency-restored",
                        "Back to Off, the scheduling read-back and the benchmark return to their baseline",
                    ),
                    Check(
                        "follow-window",
                        "With the window policy on, minimizing / unfocusing the Lab switches to Full / Light and back",
                    ),
                    Check("awake-holds", "With keep-awake on, the display does not dim past the OS idle timeout"),
                    Check(
                        "awake-readback",
                        "The OS lists the Lab's assertion while held, and nothing once every handle is closed",
                    ),
                    Check(
                        "handles-coexist",
                        "Releasing the keepAwake slot keeps the system awake while a handle is still open",
                    ),
                ),
            keywords = listOf("caffeine", "sleep", "ecoqos", "efficiency", "power"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<EnergyViewModel>()
        val state by vm.state.collectAsState()
        val window = LocalNucleusWindow.current
        val focused by window.focusFlow.collectAsState()
        val minimized by window.minimizedFlow.collectAsState()
        LaunchedEffect(focused, minimized) { vm.onIntent(EnergyIntent.WindowChanged(focused, minimized)) }
        var mode by remember { mutableStateOf(AwakeMode.SYSTEM_AND_DISPLAY) }
        val enabled = state.available.isAvailable

        ProbeLayout(
            capabilities = listOf(Capability("Energy manager", state.available)),
            controls = {
                SubHeading("Process efficiency")
                ChoiceRow(
                    "Level",
                    EfficiencyLevel.entries,
                    state.efficiency,
                ) { vm.onIntent(EnergyIntent.SetEfficiency(it)) }
                SwitchRow(
                    "Follow Lab window",
                    state.followWindow,
                    enabled = enabled,
                ) { vm.onIntent(EnergyIntent.SetFollowWindow(it)) }
                Hint(
                    "Full when minimized, Light when unfocused (applied while this probe is open). " +
                        "Picking a level turns it off.",
                )
                SubHeading("Benchmark")
                Hint("A fixed workload on a fresh thread.")
                Actions {
                    PrimaryAction(
                        "Run",
                        enabled = !state.benchmarking,
                    ) { vm.onIntent(EnergyIntent.RunBenchmark(threadEfficiency = false)) }
                    SecondaryAction("Run with thread efficiency", enabled = !state.benchmarking && enabled) {
                        vm.onIntent(EnergyIntent.RunBenchmark(threadEfficiency = true))
                    }
                }
                SubHeading("Keep awake")
                ChoiceRow(
                    "Mode",
                    AwakeMode.entries,
                    mode,
                    name = { it.name.lowercase().replace('_', ' ') },
                ) { mode = it }
                Actions {
                    PrimaryAction("keepAwake", enabled = enabled) { vm.onIntent(EnergyIntent.KeepAwake(mode)) }
                    SecondaryAction("releaseAwake", enabled = enabled) { vm.onIntent(EnergyIntent.ReleaseAwake) }
                    SecondaryAction(
                        "acquireAwake handle",
                        enabled = enabled,
                    ) { vm.onIntent(EnergyIntent.AcquireHandle(mode)) }
                }
                state.handles.forEach { handle ->
                    Actions {
                        SecondaryAction(
                            "Close handle #${handle.id} (${handle.mode})",
                        ) { vm.onIntent(EnergyIntent.CloseHandle(handle.id)) }
                    }
                }
                Actions { SecondaryAction("Read back from the OS") { vm.onIntent(EnergyIntent.ReadBack) } }
            },
            observed = { EnergyObserved(state) },
        )
    }

    companion object {
        val ID = ProbeId("system.energy")
    }
}
