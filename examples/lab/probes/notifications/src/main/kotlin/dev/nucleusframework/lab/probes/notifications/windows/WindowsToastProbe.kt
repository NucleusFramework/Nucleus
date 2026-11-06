package dev.nucleusframework.lab.probes.notifications.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.format.percent
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.notifications.toLogEntry
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class WindowsToastProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Windows toasts",
            domain = Domain.Notifications,
            summary =
                "Do rich toasts render, update in place, carry user input back, and show up " +
                    "in the Action Center history?",
            modules = listOf("notification-windows"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check("shown", "Send shows a toast attributed to “Nucleus Lab” with the attribution line"),
                    Check(
                        "inputs",
                        "With a text box and selection, Send / a button returns the typed text and the picked item",
                    ),
                    Check("progress", "A progress toast advances in place (25 % steps) without a new toast or sound"),
                    Check("scenario", "Reminder / Alarm scenarios stay on screen until acted on"),
                    Check("dismissed", "Closing lists USER_CANCELED; letting it expire lists TIMED_OUT"),
                    Check("history", "History lists the Lab's toasts still in the Action Center; Clear all empties it"),
                    Check("thread", "Every activation and dismissal is on the UI thread (no ⚠)"),
                ),
            keywords = listOf("toast", "WinRT", "Action Center", "AUMID", "progress bar", "data binding"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WindowsToastViewModel>()
        val state by vm.state.collectAsState()
        var draft by remember { mutableStateOf(ToastDraft()) }
        val enabled = state.availability.isAvailable && state.initialized == true

        ProbeLayout(
            capabilities =
                listOfNotNull(
                    Capability("ToastNotificationManager", state.availability, detail = state.identity),
                    state.initialized?.let {
                        Capability(
                            "initialize()",
                            Availability.of(it) { "refused — check the AUMID / Start-menu shortcut" },
                        )
                    },
                ),
            controls = {
                TextFieldRow("Title", draft.title) { draft = draft.copy(title = it) }
                TextFieldRow("Body", draft.body) { draft = draft.copy(body = it) }
                TextFieldRow("Attribution", draft.attribution) { draft = draft.copy(attribution = it) }
                SwitchRow("Buttons (Open / Later)", draft.buttons) { draft = draft.copy(buttons = it) }
                SwitchRow("Text box + Send", draft.textBox) { draft = draft.copy(textBox = it) }
                SwitchRow("Selection box", draft.selection) { draft = draft.copy(selection = it) }
                SwitchRow("Header (groups toasts)", draft.header) { draft = draft.copy(header = it) }
                SwitchRow("Bound progress bar", draft.progress) { draft = draft.copy(progress = it) }
                SwitchRow("Silent", draft.silent) { draft = draft.copy(silent = it) }
                ChoiceRow("Scenario", ToastScenario.entries, draft.scenario) { draft = draft.copy(scenario = it) }
                ChoiceRow("Duration", ToastDuration.entries, draft.duration) { draft = draft.copy(duration = it) }
                Actions {
                    PrimaryAction("Send", enabled = enabled) { vm.onIntent(WindowsToastIntent.Send(draft)) }
                    SecondaryAction("History", enabled = enabled) { vm.onIntent(WindowsToastIntent.ReadHistory) }
                    SecondaryAction("Clear all", enabled = enabled) { vm.onIntent(WindowsToastIntent.ClearAll) }
                }
                state.progress.keys.forEach { tag ->
                    Actions {
                        SecondaryAction(
                            "Advance $tag (${percent(state.progress[tag] ?: 0.0, decimals = 0)})",
                            enabled = enabled,
                        ) {
                            vm.onIntent(WindowsToastIntent.AdvanceProgress(tag))
                        }
                        SecondaryAction(
                            "Remove $tag",
                            enabled = enabled,
                        ) { vm.onIntent(WindowsToastIntent.Remove(tag)) }
                    }
                }
            },
            observed = {
                SubHeading("Sent")
                EventLog(state.sent.map { it.toLogEntry() }, empty = "Nothing sent yet.")
                SubHeading("Action Center history")
                val history = state.history
                when {
                    state.historyError != null -> Readout("Error", state.historyError, tone = Tone.Error)
                    history == null -> EmptyState("Not read yet.")
                    history.isEmpty() -> EmptyState("Empty.")
                    else -> CodeBlock(history.joinToString("\n"))
                }
            },
        )
    }

    companion object {
        val ID = ProbeId("notifications.windows")
    }
}
