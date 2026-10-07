package dev.nucleusframework.lab.probes.notifications.macos

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
import dev.nucleusframework.lab.designsystem.CodeBlock
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.NumberFieldRow
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.Tone
import dev.nucleusframework.lab.probes.notifications.toLogEntry
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.InterruptionLevel
import dev.nucleusframework.notification.PresentationOption
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class MacNotificationsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "macOS notifications",
            domain = Domain.Notifications,
            summary =
                "Does UNUserNotificationCenter grant, deliver, schedule and report every " +
                    "response — with the app in front too?",
            modules = listOf("notification-macos"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "auth",
                        "Request authorization shows the system prompt once; the status below matches System Settings",
                    ),
                    Check(
                        "foreground",
                        "With the Lab focused, a notification still shows as a banner (willPresent is listed)",
                    ),
                    Check("actions", "Reply / Open / Delete appear; Reply's text arrives below as text=“…”"),
                    Check("dismiss", "Closing a notification lists “dismissed” (CUSTOM_DISMISS_ACTION)"),
                    Check("scheduled", "A 10 s delayed notification is listed as pending, then delivered on time"),
                    Check("threads", "Notifications with the same thread id are grouped in Notification Center"),
                    Check("badge", "Badge 7 sets the Dock badge (and the Badge probe reads 7 back)"),
                ),
            keywords = listOf("UNUserNotificationCenter", "authorization", "category", "text input", "time sensitive"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<MacNotificationsViewModel>()
        val state by vm.state.collectAsState()
        var draft by remember { mutableStateOf(MacDraft()) }
        val enabled = state.availability.isAvailable
        val settings = state.settings

        ProbeLayout(
            capabilities = listOf(Capability("UNUserNotificationCenter", state.availability)),
            controls = {
                SubHeading("Authorization")
                Actions {
                    PrimaryAction("Request alert + sound + badge", enabled = enabled) {
                        vm.onIntent(
                            MacNotificationsIntent.RequestAuthorization(
                                setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND, AuthorizationOption.BADGE),
                            ),
                        )
                    }
                    SecondaryAction("Request provisional", enabled = enabled) {
                        vm.onIntent(MacNotificationsIntent.RequestAuthorization(setOf(AuthorizationOption.PROVISIONAL)))
                    }
                    SecondaryAction(
                        "Re-read settings",
                        enabled = enabled,
                    ) { vm.onIntent(MacNotificationsIntent.RefreshSettings) }
                }
                SubHeading("Request")
                TextFieldRow("Title", draft.title) { draft = draft.copy(title = it) }
                TextFieldRow("Subtitle", draft.subtitle) { draft = draft.copy(subtitle = it) }
                TextFieldRow("Body", draft.body) { draft = draft.copy(body = it) }
                TextFieldRow("Thread id", draft.threadId) { draft = draft.copy(threadId = it) }
                ChoiceRow("Sound", SoundChoice.entries, draft.sound) { draft = draft.copy(sound = it) }
                ChoiceRow("Interruption", InterruptionLevel.entries, draft.interruption) {
                    draft = draft.copy(interruption = it)
                }
                ChoiceRow("Deliver", listOf(0, 10, 60), draft.delaySeconds, name = {
                    if (it ==
                        0
                    ) {
                        "now"
                    } else {
                        "in $it s"
                    }
                }) {
                    draft = draft.copy(delaySeconds = it)
                }
                NumberFieldRow("Badge", draft.badge) { draft = draft.copy(badge = it) }
                SwitchRow("Reply / Open / Delete actions", draft.withActions) { draft = draft.copy(withActions = it) }
                SubHeading("Foreground presentation")
                Hint("How willPresent answers while the Lab is in front.")
                ChoiceRow(
                    "willPresent",
                    listOf(PresentationPreset.Banner, PresentationPreset.ListOnly, PresentationPreset.Nothing),
                    PresentationPreset.of(state.presentation),
                ) { vm.onIntent(MacNotificationsIntent.SetPresentation(it.options)) }
                Actions {
                    PrimaryAction("Send", enabled = enabled) { vm.onIntent(MacNotificationsIntent.Send(draft)) }
                    SecondaryAction(
                        "Refresh lists",
                        enabled = enabled,
                    ) { vm.onIntent(MacNotificationsIntent.RefreshLists) }
                    SecondaryAction("Remove all", enabled = enabled) { vm.onIntent(MacNotificationsIntent.RemoveAll) }
                }
            },
            observed = {
                Readout("Authorization", settings?.authorizationStatus?.name ?: state.authorization)
                Readout("Last request", state.authorization)
                settings?.let {
                    Readout("Alert style", it.alertStyle.name)
                    Readout("Alert · sound · badge", "${it.alertSetting} · ${it.soundSetting} · ${it.badgeSetting}")
                    Readout("Time sensitive", it.timeSensitiveSetting.name)
                }
                Readout(
                    "Category",
                    if (state.categoryInstalled) LAB_CATEGORY else "not installed",
                    tone = if (state.categoryInstalled) Tone.Neutral else Tone.Muted,
                )
                SubHeading("Sent")
                EventLog(state.sent.map { it.toLogEntry() }, empty = "Nothing sent yet.")
                SubHeading("Pending (${state.pending.size})")
                if (state.pending.isEmpty()) EmptyState("None.") else CodeBlock(state.pending.joinToString("\n"))
                SubHeading("Delivered (${state.delivered.size})")
                if (state.delivered.isEmpty()) EmptyState("None.") else CodeBlock(state.delivered.joinToString("\n"))
            },
        )
    }

    companion object {
        val ID = ProbeId("notifications.macos")
    }
}

/** The three answers worth comparing for a notification arriving while the app is frontmost. */
enum class PresentationPreset(
    val options: Set<PresentationOption>,
) {
    Banner(setOf(PresentationOption.BANNER, PresentationOption.SOUND, PresentationOption.LIST)),
    ListOnly(setOf(PresentationOption.LIST)),
    Nothing(emptySet()),
    ;

    companion object {
        fun of(options: Set<PresentationOption>): PresentationPreset =
            entries.firstOrNull { it.options == options } ?: Banner
    }
}
