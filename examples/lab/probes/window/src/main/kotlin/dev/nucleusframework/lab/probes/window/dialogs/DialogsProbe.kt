package dev.nucleusframework.lab.probes.window.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.Capability
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EmptyState
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.LogEntry
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.Tone
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class DialogsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Secondary windows & dialogs",
            domain = Domain.Window,
            summary =
                "Do dialogs block and centre on their owner, do hosted windows go through the installed host, and " +
                    "do native file dialogs attach to the right window?",
            modules = listOf("nucleus-application", "decorated-window-material3", "decorated-window-tao"),
            checks =
                listOf(
                    Check(
                        "modal",
                        "While any dialog is open, clicking the owner does nothing (presses-while-modal stays 0)",
                    ),
                    Check(
                        "centre",
                        "Each dialog opens centred on the owner (offset readout near 0,0), wherever the owner is",
                    ),
                    Check("focus", "Closing a dialog gives focus back to the owner (focus returned = true)"),
                    Check(
                        "host",
                        "Lab-host variants show a '[Lab host]' title and report the Lab host; default variants do not",
                    ),
                    Check(
                        "hosted-window",
                        "A HostedWindow is a real window: movable, own taskbar entry, the owner stays clickable",
                    ),
                    Check(
                        "filekit",
                        "Each file dialog opens attached to the owner (sheet on macOS, owned dialog on Windows / portal parent on Linux) and the path comes back",
                    ),
                ),
            keywords = listOf("HostedWindow", "HostedDialog", "DecoratedDialog", "modal", "FileKit", "file picker"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<DialogsViewModel>()
        val state by vm.state.collectAsState()
        val open = state.sessionOpen
        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Parented file dialogs",
                        Availability.Available,
                        when (Platform.Current) {
                            Platform.MacOS -> "macOS: FileKit's panel is app-modal, left unparented on purpose"
                            Platform.Linux -> "Linux: X11 xid or Wayland xdg_foreign export as portal parent"
                            else -> "Windows: the owner's HWND"
                        },
                    ),
                ),
            controls = {
                Actions {
                    if (open) {
                        SecondaryAction("Close owner window") { vm.onIntent(DialogsIntent.Close) }
                    } else {
                        PrimaryAction("Open owner window") { vm.onIntent(DialogsIntent.Open) }
                    }
                }
                SubHeading("Open from the owner")
                Actions {
                    Secondary.entries.forEach { s ->
                        SecondaryAction(
                            s.label,
                            enabled = open && s !in state.open,
                        ) { vm.onIntent(DialogsIntent.Show(s)) }
                    }
                }
                SubHeading("FileKit, parented to the owner")
                Actions {
                    FilePick.entries.forEach { p ->
                        SecondaryAction(p.name, enabled = open) { vm.onIntent(DialogsIntent.Pick(p)) }
                    }
                }
            },
            observed = {
                Readout("Open now", state.open.joinToString { it.label }.ifEmpty { "nothing" })
                Readout(
                    "Owner presses",
                    "${state.ownerPresses} total · ${state.ownerPressesWhileModal} while modal",
                    tone = if (state.ownerPressesWhileModal > 0) Tone.Error else Tone.Neutral,
                )
                Readout(
                    "Focus returned",
                    state.focusReturned?.toString() ?: "close a dialog to measure",
                    tone =
                        when (state.focusReturned) {
                            true -> Tone.Ok
                            false -> Tone.Error
                            null -> Tone.Muted
                        },
                )
                Readout("Lab host invocations", state.hostInvocations.toString())
                SubHeading("Reported from inside each surface")
                if (state.reports.isEmpty()) EmptyState("Open something from the owner.")
                state.reports.forEach { (s, r) ->
                    val hostOk = r.composedByLabHost == s.viaLabHost
                    val host = if (r.composedByLabHost) "Lab host" else "default host"
                    val offset = r.centreOffsetPx?.let { "${it.first},${it.second}" } ?: "?"
                    Readout(s.label, "$host · centre Δ $offset", tone = if (hostOk) Tone.Neutral else Tone.Error)
                }
                SubHeading("File dialogs")
                EventLog(state.picks.map { it.toLogEntry() }, newestFirst = false, empty = "No dialog yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("window.dialogs")
    }
}

private fun PickResult.toLogEntry(): LogEntry =
    LogEntry(
        text = "$pick → $result",
        epochMillis = epochMillis,
        tone = if (ok) Tone.Neutral else Tone.Error,
        detail = "${if (parented) "parented" else "unparented"} · $millis ms",
    )
