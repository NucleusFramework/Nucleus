package dev.nucleusframework.lab.probes.shell.tray

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
import dev.nucleusframework.lab.designsystem.Actions
import dev.nucleusframework.lab.designsystem.EventLog
import dev.nucleusframework.lab.designsystem.Hint
import dev.nucleusframework.lab.designsystem.PrimaryAction
import dev.nucleusframework.lab.designsystem.ProbeLayout
import dev.nucleusframework.lab.designsystem.Readout
import dev.nucleusframework.lab.designsystem.SecondaryAction
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.TextFieldRow
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class TrayProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "System tray",
            domain = Domain.Shell,
            summary =
                "Does an AWT-free tray icon appear, keep its menu in sync with app state, and " +
                    "call back on the UI thread?",
            modules = listOf("composenativetray"),
            checks =
                listOf(
                    Check(
                        "icon",
                        "The icon appears in the tray / menu bar extras, crisp, and with the tooltip on hover",
                    ),
                    Check(
                        "primary",
                        "A left click on the icon is listed as a primary click (where the platform has one)",
                    ),
                    Check("menu", "The menu shows Ping, Checkable, a disabled item, More ▸ and Remove"),
                    Check("live", "Ping's counter and Checkable's tick update the next time the menu opens"),
                    Check("tooltip", "Applying a new tooltip changes the hover text without re-creating the icon"),
                    Check("thread", "No tray delivery is flagged off the UI thread"),
                    Check("remove", "Remove tray icon (menu or button) makes the icon disappear, no ghost left"),
                ),
            keywords = listOf("ComposeNativeTray", "status item", "notification area", "AppIndicator", "SNI"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<TrayViewModel>()
        val state by vm.state.collectAsState()
        var tooltip by remember(state.tooltip) { mutableStateOf(state.tooltip) }

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "Tray host",
                        Availability.Available,
                        detail =
                            when (Platform.Current) {
                                Platform.Linux -> "StatusNotifierItem: GNOME needs the AppIndicator extension"
                                Platform.MacOS -> "NSStatusItem in the menu bar"
                                else -> "Shell_NotifyIcon in the notification area"
                            },
                    ),
                ),
            controls = {
                Actions {
                    if (state.installed) {
                        SecondaryAction("Remove tray icon") { vm.onIntent(TrayIntent.Remove) }
                    } else {
                        PrimaryAction("Show tray icon") { vm.onIntent(TrayIntent.Show) }
                    }
                }
                TextFieldRow("Tooltip", tooltip) { tooltip = it }
                Actions {
                    SecondaryAction(
                        "Apply tooltip",
                        enabled = state.installed,
                    ) { vm.onIntent(TrayIntent.SetTooltip(tooltip)) }
                }
                Hint("The icon stays while you browse other probes.")
            },
            observed = {
                Readout("Icon", if (state.installed) "installed" else "not shown")
                Readout("Tooltip", state.tooltip)
                Readout("Primary clicks", state.primaryClicks.toString())
                Readout("Menu opens", state.menuOpens.toString())
                Readout("Checkable", state.checked.toString())
                SubHeading("Deliveries")
                EventLog(state.deliveries.map { it.toLogEntry() }, empty = "Nothing received from the tray yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.tray")
    }
}
