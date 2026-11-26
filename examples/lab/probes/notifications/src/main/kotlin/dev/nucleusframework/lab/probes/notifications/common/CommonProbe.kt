package dev.nucleusframework.lab.probes.notifications.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.probes.notifications.toLogEntry
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastDuration
import dev.nucleusframework.notification.windows.ToastScenario
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class CommonProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Notifications (common DSL)",
            domain = Domain.Notifications,
            summary =
                "Does one `notification { }` call show a native notification everywhere and " +
                    "route every callback back?",
            modules = listOf("notification-common"),
            checks =
                listOf(
                    Check("shown", "Send shows a native notification with title, message and both buttons"),
                    Check("activated", "Clicking the body lists “activated” under that notification"),
                    Check("buttons", "Each button lists its own label, under the notification it belongs to"),
                    Check("dismissed", "Closing it lists “dismissed: USER_DISMISSED”; Dismiss here lists APPLICATION"),
                    Check("thread", "Every callback is on the UI thread (no ⚠) — the DSL's promise since #310"),
                    Check(
                        "burst",
                        "A burst of 5 shows 5 notifications (or a grouped stack) and callbacks reach the right one",
                    ),
                    Check("platform", "This OS's option block visibly applies (urgency / subtitle / scenario)"),
                ),
            keywords = listOf("toast", "banner", "DSL", "button", "dismiss"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<CommonViewModel>()
        val state by vm.state.collectAsState()
        // The draft is screen state: typing is not an intent worth a timeline line.
        var draft by remember { mutableStateOf(CommonDraft()) }
        val enabled = state.availability.isAvailable

        fun edit(next: CommonDraft) {
            draft = next
        }

        ProbeLayout(
            capabilities = listOf(Capability("Platform dispatcher", state.availability)),
            controls = {
                TextFieldRow("Title", draft.title) { edit(draft.copy(title = it)) }
                TextFieldRow("Message", draft.message) { edit(draft.copy(message = it)) }
                ChoiceRow("Buttons", listOf(0, 1, 2, 5), draft.buttons.size, name = { "$it" }) { n ->
                    edit(draft.copy(buttons = listOf("Accept", "Later", "Snooze", "Archive", "Delete").take(n)))
                }
                when (Platform.Current) {
                    Platform.Linux -> {
                        SubHeading("linux { }")
                        ChoiceRow("Urgency", listOf(null) + Urgency.entries, draft.linuxUrgency, ::optionName) {
                            edit(draft.copy(linuxUrgency = it))
                        }
                    }
                    Platform.MacOS -> {
                        SubHeading("macos { }")
                        TextFieldRow("Subtitle", draft.macSubtitle) { edit(draft.copy(macSubtitle = it)) }
                        ChoiceRow(
                            "Interruption",
                            listOf(null) + InterruptionLevel.entries,
                            draft.macInterruption,
                            ::optionName,
                        ) { edit(draft.copy(macInterruption = it)) }
                    }
                    Platform.Windows -> {
                        SubHeading("windows { }")
                        ChoiceRow(
                            "Scenario",
                            listOf(null) + ToastScenario.entries,
                            draft.windowsScenario,
                            ::optionName,
                        ) {
                            edit(draft.copy(windowsScenario = it))
                        }
                        ChoiceRow(
                            "Duration",
                            listOf(null) + ToastDuration.entries,
                            draft.windowsDuration,
                            ::optionName,
                        ) {
                            edit(draft.copy(windowsDuration = it))
                        }
                    }
                    else -> Unit
                }
                Actions {
                    PrimaryAction("Send", enabled = enabled) { vm.onIntent(CommonIntent.Send(draft)) }
                    SecondaryAction("Burst × 5", enabled = enabled) { vm.onIntent(CommonIntent.Burst(draft, 5)) }
                    state.sent.lastOrNull { !it.finished && it.platformId != null }?.let { last ->
                        SecondaryAction("Dismiss “${last.title}”") { vm.onIntent(CommonIntent.Dismiss(last.key)) }
                    }
                }
            },
            observed = {
                EventLog(state.sent.map { it.toLogEntry() }, empty = "Nothing sent yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("notifications.common")
    }
}

/** An optional platform setting: `null` leaves the platform default. */
private fun optionName(option: Enum<*>?): String = option?.name ?: "default"
