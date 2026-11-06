package dev.nucleusframework.lab.probes.shell.linux

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
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.NumberFieldRow
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SliderRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class LinuxLauncherProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Launcher entry & quicklist",
            domain = Domain.Shell,
            summary =
                "Do count, progress, urgency and the quicklist reach the dock through " +
                    "com.canonical.Unity.LauncherEntry?",
            modules = listOf("launcher-linux", "freedesktop-icons"),
            platforms = setOf(Platform.Linux),
            checks =
                listOf(
                    Check(
                        "count",
                        "The count bubble on the dock icon shows the value, and hides when “count visible” is off",
                    ),
                    Check("progress", "The progress bar on the icon follows the slider"),
                    Check("urgent", "Urgent makes the icon wiggle / highlight until cleared"),
                    Check("reset", "Reset leaves the icon exactly as without the Lab's changes"),
                    Check(
                        "quicklist",
                        "Right-click on the dock icon lists the quicklist items, icon and submenu included",
                    ),
                    Check(
                        "quicklist-click",
                        "Each quicklist pick is listed once on the UI thread; the checkbox tick follows it",
                    ),
                    Check(
                        "restart",
                        "Restarting the dock (e.g. `killall plank`) restores the current state via the Query handler",
                    ),
                ),
            keywords = listOf("unity", "dash to dock", "plank", "kde", "dbusmenu", "quicklist", "urgent"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<LinuxLauncherViewModel>()
        val state by vm.state.collectAsState()
        var draft by remember(state.applied) { mutableStateOf(state.applied) }
        var desktopFile by remember(state.desktopFile) { mutableStateOf(state.desktopFile) }
        val ready = state.availability.isAvailable && state.desktopFile.isNotEmpty()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("LauncherEntry", state.availability),
                    Capability(
                        ".desktop file",
                        if (state.detectedDesktopFile != null) {
                            Availability.Available
                        } else {
                            Availability.Unavailable("not detected for this process — enter one")
                        },
                        detail = state.detectedDesktopFile,
                    ),
                ),
            controls = {
                TextFieldRow("Desktop file id", desktopFile) { desktopFile = it }
                Actions {
                    SecondaryAction("Use this id", enabled = state.availability.isAvailable) {
                        vm.onIntent(LinuxLauncherIntent.SetDesktopFile(desktopFile))
                    }
                }
                SubHeading("Properties (sent as one Update)")
                NumberFieldRow("Count", draft.count.toInt()) { draft = draft.copy(count = (it ?: 0).toLong()) }
                SwitchRow("Count visible", draft.countVisible) { draft = draft.copy(countVisible = it) }
                SliderRow("Progress", draft.progress.toFloat(), format = { percent(it.toDouble(), decimals = 0) }) {
                    draft =
                        draft.copy(progress = it.toDouble())
                }
                SwitchRow("Progress visible", draft.progressVisible) { draft = draft.copy(progressVisible = it) }
                SwitchRow("Urgent", draft.urgent) { draft = draft.copy(urgent = it) }
                SwitchRow("Updating", draft.updating) { draft = draft.copy(updating = it) }
                Actions {
                    PrimaryAction("Apply", enabled = ready) { vm.onIntent(LinuxLauncherIntent.Apply(draft)) }
                    SecondaryAction("Reset", enabled = ready) { vm.onIntent(LinuxLauncherIntent.Reset) }
                }
                SubHeading("Quicklist")
                Actions {
                    PrimaryAction(if (state.quicklistActive) "Republish" else "Publish", enabled = ready) {
                        vm.onIntent(LinuxLauncherIntent.PublishQuicklist)
                    }
                    SecondaryAction(
                        "Remove",
                        enabled = ready && state.quicklistActive,
                    ) { vm.onIntent(LinuxLauncherIntent.RemoveQuicklist) }
                }
            },
            observed = {
                Readout("App URI", if (state.desktopFile.isEmpty()) null else "application://${state.desktopFile}")
                Readout("Query handler", state.queryHandler?.let { if (it.ok) "registered" else "failed: ${it.error}" })
                Readout("Dock should show", state.applied.summary())
                val checkbox = if (state.checkableOn) "on" else "off"
                Readout("Quicklist", if (state.quicklistActive) "published · checkbox $checkbox" else "none")
                SubHeading("Quicklist clicks")
                EventLog(state.clicks.map { it.toLogEntry() }, empty = "No quicklist item picked yet.")
                SubHeading("Calls")
                EventLog(state.calls.map { it.toLogEntry() }, empty = "No call made yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.linux-launcher")
    }
}
