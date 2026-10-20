package dev.nucleusframework.lab.probes.fixtures.runner

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
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class FixturesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Fixtures",
            domain = Domain.Fixtures,
            summary = "Do the programs that must own their process run clean in a child JVM of their own?",
            modules = listOf("nucleus-application", "decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "rect-stress-60s",
                        "rect-stress [default] runs 60 s without exit 55; [timed 60 s] ends with exit 0 — pass",
                    ),
                    Check(
                        "swing-tao-edt",
                        "swing-tao: the Swing frame's EDT tick keeps counting while the native Tao window opens and closes from its buttons",
                    ),
                    Check(
                        "swing-tao-quit",
                        "swing-tao: Quit and the frame's close button both end the run with exit 0",
                    ),
                    Check(
                        "partial-tint",
                        "partial-redraw [blink + tint]: once the window is static only the 8×8 box is tinted",
                    ),
                    Check(
                        "partial-verify",
                        "partial-redraw [tour + verify] and [torture + verify]: the output reports no verify failure",
                    ),
                    Check(
                        "partial-idle",
                        "partial-redraw [main-tick]: nothing is repainted while the main-thread loop rewrites the same state",
                    ),
                    Check("stop", "Stop ends a running fixture and its history row reads stopped, not a failure"),
                    Check(
                        "output",
                        "Output streams while the fixture runs, and the exit code shows with its meaning when it ends",
                    ),
                ),
            keywords = listOf("fixture", "process", "rect-stress", "swing", "partial redraw", "torture", "exit code"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<FixturesViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Relaunch this JVM", state.relaunch),
                    Capability(
                        "Partial redraw Compose patch",
                        state.partialRedrawPatch,
                        detail = "nucleusOptimization { partialRedraw = true } on the Lab app",
                    ),
                ),
            controls = {
                if (state.fixtures.isEmpty()) EmptyState("No fixture contributed to the graph.")
                state.fixtures.forEach { fixture ->
                    FixtureCard(
                        fixture = fixture,
                        selected = state.variantOf(fixture),
                        running = state.runningOf(fixture.id),
                        canRun = state.relaunch.isAvailable,
                        onIntent = vm::onIntent,
                    )
                }
            },
            observed = {
                val focused = state.focusedRun
                if (focused == null) {
                    EmptyState("Run a fixture to see its output here.")
                } else {
                    RunDetails(focused, state.outcomeOf(focused), state.now)
                    RunOutput(focused)
                }
                SubHeading("History")
                Actions {
                    SecondaryAction("Clear finished", enabled = state.runs.any { it.exitCode != null }) {
                        vm.onIntent(FixturesIntent.ClearFinished)
                    }
                }
                RunHistory(state, onSelect = { vm.onIntent(FixturesIntent.ShowOutput(it)) })
            },
        )
    }

    companion object {
        val ID = ProbeId("fixtures.runner")
    }
}
