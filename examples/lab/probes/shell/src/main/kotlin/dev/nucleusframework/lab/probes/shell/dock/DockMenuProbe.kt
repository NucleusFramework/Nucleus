package dev.nucleusframework.lab.probes.shell.dock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.nucleusframework.core.runtime.Platform
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
import dev.nucleusframework.lab.designsystem.toLogEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ContributesIntoSet(AppScope::class)
@Inject
class DockMenuProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Dock menu",
            domain = Domain.Shell,
            summary = "Does right-clicking the Dock icon show the app's items, and do they reach the app?",
            modules = listOf("launcher-macos"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "shown",
                        "Right-click on the Dock icon lists Ping, Counter, a disabled item, a separator, " +
                            "Badge ▸ and Overview above the system items",
                    ),
                    Check("click", "Ping the Lab is listed below once per click, on the UI thread"),
                    Check("rebuild", "After clicking Counter, reopening the menu shows the incremented count"),
                    Check("submenu", "Badge ▸ Set badge to 5 opens the Badge probe with 5 applied"),
                    Check("disabled", "The disabled item is greyed and never appears below"),
                    Check("clear", "After Clear, the Dock menu only has the system items"),
                ),
            keywords = listOf("NSApplicationDelegate", "applicationDockMenu", "dock"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<DockMenuViewModel>()
        val state by vm.state.collectAsState()
        val enabled = state.availability.isAvailable

        ProbeLayout(
            capabilities = listOf(Capability("Dock menu", state.availability)),
            controls = {
                Actions {
                    PrimaryAction(if (state.installed) "Rebuild menu" else "Install menu", enabled = enabled) {
                        vm.onIntent(DockMenuIntent.Install)
                    }
                    SecondaryAction("Clear", enabled = enabled && state.installed) { vm.onIntent(DockMenuIntent.Clear) }
                }
                Hint("Then right-click (or long-press) the Lab's Dock icon.")
            },
            observed = {
                Readout("Menu", if (state.installed) "installed" else "system items only")
                Readout("Counter item reads", "Counter: ${state.counter}")
                SubHeading("Clicks")
                EventLog(state.clicks.map { it.toLogEntry() }, empty = "No Dock menu item picked yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.dock-menu")
    }
}
