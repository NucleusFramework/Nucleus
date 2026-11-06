package dev.nucleusframework.lab.probes.shell.menubar

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
class MenuBarProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Menu bar & popup menu",
            domain = Domain.Shell,
            summary = "Is the native macOS menu bar the app declares installed, live, and wired back to the app?",
            modules = listOf("menu-macos", "sf-symbols"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "installed",
                        "The menu bar shows a “Lab” menu with SF Symbol icons, a ⌘⇧P shortcut, a badge " +
                            "and a section header",
                    ),
                    Check("shortcut", "⌘⇧P fires Ping without opening the menu"),
                    Check("live", "Checkable ticks, Mode radio moves and the Inbox badge increments after each pick"),
                    Check("roles", "Window lists the Lab window; Help shows the system search field"),
                    Check(
                        "persists",
                        "Navigating to another probe keeps the menu bar; Remove restores the default one",
                    ),
                    Check(
                        "popup",
                        "The popup opens at the cursor; picks are listed, a dismissal reads “dismissed without a pick”",
                    ),
                ),
            keywords = listOf("NSMenu", "main menu", "context menu", "SF Symbols"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<MenuBarViewModel>()
        val state by vm.state.collectAsState()
        val enabled = state.availability.isAvailable

        ProbeLayout(
            capabilities = listOf(Capability("NSMenu bridge", state.availability)),
            controls = {
                Actions {
                    if (state.installed) {
                        SecondaryAction("Remove menu bar") { vm.onIntent(MenuBarIntent.Remove) }
                    } else {
                        PrimaryAction("Install menu bar", enabled = enabled) { vm.onIntent(MenuBarIntent.Install) }
                    }
                    SecondaryAction(
                        "Pop up a menu at the cursor",
                        enabled = enabled,
                    ) { vm.onIntent(MenuBarIntent.PopUp) }
                }
                Hint("The menu bar stays installed while you browse other probes.")
            },
            observed = {
                Readout("Menu bar", if (state.installed) "installed" else "default")
                Readout("Checkable", state.checkable.toString())
                Readout("Mode", state.mode.name)
                Readout("Inbox badge", state.inboxBadge.toString())
                Readout("Last popup", state.lastPopup)
                SubHeading("Picks")
                EventLog(state.picks.map { it.toLogEntry() }, empty = "No menu item picked yet.")
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.menu-bar")
    }
}
