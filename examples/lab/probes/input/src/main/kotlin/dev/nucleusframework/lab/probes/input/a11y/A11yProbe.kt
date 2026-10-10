package dev.nucleusframework.lab.probes.input.a11y

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SpecimenFrame
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceContent
import dev.nucleusframework.lab.probes.input.a11y.surface.SurfaceTabBar
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class A11yProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Accessibility",
            domain = Domain.Input,
            summary = "Is the Compose semantics tree what VoiceOver, Narrator and Orca actually see and drive?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "button",
                        "The screen reader reads 'Increment, button'; activating it with the AT moves the click counter",
                    ),
                    Check(
                        "states",
                        "'Tri-state checkbox' is announced off → checked → mixed; 'Cannot press' is announced dimmed/unavailable",
                    ),
                    Check(
                        "slider",
                        "'Volume' is a slider the AT can adjust (VO+Shift+arrows, Narrator, Orca), the new value read back",
                    ),
                    Check(
                        "custom",
                        "'Mark as read' and 'Archive' are offered as custom actions (VO+Cmd+. / Narrator actions) and both work",
                    ),
                    Check("live", "'Update status' is announced without moving focus (assertive live region)"),
                    Check(
                        "dialog",
                        "'Open dialog' traps the AT in the dialog; Escape / VO+Esc closes it and focus returns",
                    ),
                    Check("thread", "No AT-driven action in Observed is flagged off the UI thread"),
                ),
            keywords = listOf("a11y", "screen reader", "voiceover", "narrator", "orca", "accesskit", "uia", "at-spi"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<A11yViewModel>()
        val state by vm.state.collectAsState()
        val runs by vm.isolatedRuns.collectAsState(emptyList())

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("Platform bridge", Availability.Available, detail = bridgeName()),
                    Capability(
                        "Isolated surface (fixture)",
                        Availability.Available,
                        detail = "-Dlab.fixture=${A11ySurfaceFixture.ID}",
                    ),
                ),
            controls = {
                SpecimenFrame(height = 640.dp) {
                    // The surface is drawn for a dark page: its own background, as in the isolated fixture.
                    Column(Modifier.fillMaxSize().background(SURFACE_BACKGROUND)) {
                        SurfaceTabBar(state.tab, onSelect = { vm.onIntent(A11yIntent.SelectTab(it)) })
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            SurfaceContent(state.tab, Modifier.fillMaxSize(), onA11yEvent = vm::onSurfaceEvent)
                        }
                    }
                }
                Hint("The Lab's own chrome sits in the same tree. For a clean tree, open the surface alone:")
                Actions {
                    SecondaryAction("Open '${state.tab.label}' in an isolated process") {
                        vm.onIntent(A11yIntent.LaunchIsolated(state.tab))
                    }
                }
            },
            observed = {
                Readout("Actions observed", "${state.total}")
                Readout(
                    "Off the UI thread",
                    "${state.offUiThread}",
                    tone = if (state.offUiThread > 0) Tone.Error else Tone.Ok,
                )
                Actions { SecondaryAction("Clear") { vm.onIntent(A11yIntent.ClearActions) } }
                SubHeading("Surface state changes")
                Hint("Whoever caused them: pointer, keyboard or assistive technology.")
                EventLog(state.actions.map { it.toLogEntry() }, newestFirst = false, empty = "No action yet.")
                if (runs.isNotEmpty()) {
                    SubHeading("Isolated surface processes")
                    runs.asReversed().forEach { run ->
                        Readout(
                            "#${run.runId} ${run.variant}",
                            "pid=${run.pid} " + (run.exitCode?.let { "exited $it" } ?: "running"),
                            tone =
                                if (run.exitCode == null) {
                                    Tone.Ok
                                } else if (run.exitCode == 0) {
                                    Tone.Neutral
                                } else {
                                    Tone.Error
                                },
                        )
                        if (run.exitCode == null) {
                            Actions { SecondaryAction("Stop #${run.runId}") { vm.stop(run.runId) } }
                        }
                    }
                }
                SubHeading("Tools")
                CodeBlock(toolsHint())
            },
        )
    }

    companion object {
        val ID = ProbeId("input.a11y")
    }
}

/** Background the surface is designed on (the isolated fixture's window uses the same). */
private val SURFACE_BACKGROUND = Color(0xFF0F1115)

private fun bridgeName(): String =
    when (Platform.Current) {
        Platform.MacOS -> "NSAccessibility (AppKit)"
        Platform.Windows -> "UI Automation (AccessKit)"
        Platform.Linux -> "AT-SPI over D-Bus (AccessKit) — needs org.a11y.Status.IsEnabled"
        Platform.Unknown -> "none"
    }

private fun toolsHint(): String =
    when (Platform.Current) {
        Platform.MacOS ->
            "VoiceOver: Cmd+F5 · Accessibility Inspector (Xcode) · CI: swift scripts/ci/verify-ax.swift <pid>"
        Platform.Windows ->
            "Narrator: Ctrl+Win+Enter · Inspect.exe / Accessibility Insights · CI: scripts/ci/verify-uia*.ps1"
        Platform.Linux -> "Orca: Super+Alt+S · Accerciser · CI: scripts/ci/verify-atspi*.py"
        Platform.Unknown -> ""
    }
