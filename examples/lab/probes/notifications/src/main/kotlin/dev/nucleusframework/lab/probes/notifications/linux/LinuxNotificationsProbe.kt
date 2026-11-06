package dev.nucleusframework.lab.probes.notifications.linux

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
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.ChoiceRow
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
import dev.nucleusframework.notification.linux.Urgency
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class LinuxNotificationsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Freedesktop notifications",
            domain = Domain.Notifications,
            summary =
                "Does the session's notification server render what it claims to support, and " +
                    "report actions and closes?",
            modules = listOf("notification-linux", "freedesktop-icons"),
            platforms = setOf(Platform.Linux),
            checks =
                listOf(
                    Check("server", "Server name and capabilities are listed (GNOME Shell, Plasma, dunst, mako…)"),
                    Check(
                        "markup",
                        "With body-markup, the body shows bold text and a link; without it, the tags are " +
                            "stripped, not shown raw",
                    ),
                    Check(
                        "actions",
                        "With actions, Reply / Mute buttons appear; clicks are listed as action “reply”, “mute”",
                    ),
                    Check("default", "Clicking the body lists action “default” (and an activation token on Wayland)"),
                    Check("replace", "Replace last updates the open notification in place: same id, no new popup"),
                    Check(
                        "closed",
                        "Expiry lists closed: EXPIRED, a click on × closed: DISMISSED, Close here closed: CLOSED",
                    ),
                    Check("urgency", "Critical stays until dismissed (on servers that honour urgency)"),
                ),
            keywords = listOf("libnotify", "D-Bus", "org.freedesktop.Notifications", "GNOME", "KDE", "dunst", "mako"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<LinuxNotificationsViewModel>()
        val state by vm.state.collectAsState()
        var draft by remember { mutableStateOf(LinuxDraft()) }
        val enabled = state.availability.isAvailable

        ProbeLayout(
            capabilities =
                listOf(Capability("org.freedesktop.Notifications", state.availability, detail = state.server)) +
                    listOf("actions", "body-markup", "body-hyperlinks", "persistence", "sound").map { capability ->
                        Capability(
                            capability,
                            Availability.of(state.supports(capability)) { "server does not advertise it" },
                        )
                    },
            controls = {
                TextFieldRow("Summary", draft.summary) { draft = draft.copy(summary = it) }
                TextFieldRow("Body (markup)", draft.body) { draft = draft.copy(body = it) }
                TextFieldRow("Category", draft.category) { draft = draft.copy(category = it) }
                ChoiceRow("Urgency", Urgency.entries, draft.urgency) { draft = draft.copy(urgency = it) }
                ChoiceRow("Expires", listOf(-1, 0, 5_000), draft.expireMs, name = ::expiryName) {
                    draft = draft.copy(expireMs = it)
                }
                SwitchRow("Actions (default, Reply, Mute)", draft.actions) { draft = draft.copy(actions = it) }
                SwitchRow("Icon (dialog-information)", draft.icon) { draft = draft.copy(icon = it) }
                SwitchRow("Sound (message-new-instant)", draft.sound) { draft = draft.copy(sound = it) }
                SwitchRow("Resident", draft.resident) { draft = draft.copy(resident = it) }
                SwitchRow("Transient", draft.transient) { draft = draft.copy(transient = it) }
                SwitchRow("Replace last open", draft.replaceLast, enabled = state.lastOpenId != null) {
                    draft = draft.copy(replaceLast = it)
                }
                Actions {
                    PrimaryAction("Notify", enabled = enabled) { vm.onIntent(LinuxNotificationsIntent.Send(draft)) }
                    state.lastOpenId?.let { id ->
                        SecondaryAction("Close #$id") { vm.onIntent(LinuxNotificationsIntent.Close(id)) }
                    }
                    SecondaryAction(
                        "Re-read server",
                        enabled = enabled,
                    ) { vm.onIntent(LinuxNotificationsIntent.ReadServer) }
                }
            },
            observed = {
                Readout("Server", state.server)
                Readout(
                    "Capabilities",
                    state.capabilities.joinToString().ifEmpty { "none reported" },
                    tone = if (state.capabilities.isEmpty()) Tone.Muted else Tone.Neutral,
                )
                SubHeading("Sent")
                EventLog(state.sent.map { it.toLogEntry() }, empty = "Nothing sent yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("notifications.linux")
    }
}

/** `Notify`'s expire timeout: -1 = server default, 0 = never. */
private fun expiryName(millis: Int): String =
    when {
        millis < 0 -> "server default"
        millis == 0 -> "never"
        else -> formatDurationMillis(millis.toLong())
    }
