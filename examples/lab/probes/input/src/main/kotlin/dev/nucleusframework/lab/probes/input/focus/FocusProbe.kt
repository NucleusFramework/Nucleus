package dev.nucleusframework.lab.probes.input.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class FocusProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Focus & windows",
            domain = Domain.Input,
            summary = "Does keyboard focus move, survive window switches and respect disabled / non-focusable windows?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "tab",
                        "Tab from Name visits Name → Email → Subscribe → Notes → Cancel → Submit; Shift+Tab walks back ('Sequential' stays yes)",
                    ),
                    Check(
                        "restore",
                        "Focus Notes, switch to another app and back: Notes still has focus and the caret blinks ('Restore failures' 0)",
                    ),
                    Check("window", "Window focus flips false/true exactly once per app switch"),
                    Check("programmatic", "'Focus Email', 'Next' and 'Clear focus' move focus as labelled"),
                    Check(
                        "disabled",
                        "A child opened with enabled=off receives no click and no key (no error line in Observed)",
                    ),
                    Check(
                        "nonfocusable",
                        "A child opened with focusable=off takes clicks but never becomes the key window: typing still goes to the Lab",
                    ),
                ),
            keywords = listOf("focus", "tab order", "traversal", "child window", "focusable"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<FocusViewModel>()
        val state by vm.state.collectAsState()
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { vm.onWindowFocus(windowFocused) }

        ProbeLayout(
            capabilities = emptyList(),
            controls = {
                SubHeading("Focus targets, in their declared order")
                FocusForm(vm)
                SubHeading("Child window")
                SwitchRow("enabled", state.child.enabled, enabled = !state.childOpen) {
                    vm.onIntent(FocusIntent.SetChild(state.child.copy(enabled = it)))
                }
                SwitchRow("focusable", state.child.focusable, enabled = !state.childOpen) {
                    vm.onIntent(FocusIntent.SetChild(state.child.copy(focusable = it)))
                }
                Actions {
                    if (state.childOpen) {
                        SecondaryAction("Close child") { vm.onIntent(FocusIntent.CloseChild) }
                    } else {
                        PrimaryAction("Open child") { vm.onIntent(FocusIntent.OpenChild) }
                    }
                    SecondaryAction("Reset log") { vm.onIntent(FocusIntent.Reset) }
                }
            },
            observed = {
                Readout("Focused", state.focused ?: "nothing tracked")
                Readout(
                    "Window focused",
                    state.windowFocused?.toString(),
                    tone = if (state.windowFocused == false) Tone.Warning else Tone.Neutral,
                )
                Readout("Window focus changes", "${state.windowFocusChanges}")
                Readout("Visit order", state.order.joinToString(" → ").ifEmpty { "—" })
                Readout(
                    "Sequential",
                    if (isSequentialOrder(state.order)) "yes" else "no — skipped or jumped",
                    tone = if (isSequentialOrder(state.order)) Tone.Ok else Tone.Error,
                )
                Readout(
                    "Restore failures",
                    "${state.restoreFailures}",
                    tone = if (state.restoreFailures > 0) Tone.Error else Tone.Ok,
                )
                SubHeading("Focus log")
                EventLog(state.lines.map(::LogEntry), newestFirst = false, max = 25)
                SubHeading("Input received by the child")
                EventLog(
                    state.childInputs.map(::LogEntry),
                    newestFirst = false,
                    max = 15,
                    empty = if (state.childOpen) "Nothing yet." else "Child closed.",
                )
            },
        )
    }

    companion object {
        val ID = ProbeId("input.focus")
    }
}
