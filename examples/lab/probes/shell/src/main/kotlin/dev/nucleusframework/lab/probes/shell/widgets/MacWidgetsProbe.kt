package dev.nucleusframework.lab.probes.shell.widgets

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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.metroViewModel

/** The macOS side of the Lab widgets (WidgetKit); the view model is [WindowsWidgetsProbe]'s. */
@ContributesIntoSet(AppScope::class)
@Inject
class MacWidgetsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "macOS widgets",
            domain = Domain.Shell,
            summary =
                "Does a Glance-like NucleusWidget show up in the widget gallery, render on the desktop, and " +
                    "route its buttons and app-side updates back through the widget state?",
            modules = listOf("widget-runtime", "widget-macos"),
            platforms = setOf(Platform.MacOS),
            checks =
                listOf(
                    Check(
                        "gallery",
                        "Edit Widgets lists Nucleus Lab with “Lab counter” and “Lab tasks” and their previews",
                    ),
                    Check("pin", "Adding “Lab counter” shows it at 0; “pinned” then “rendered” are listed here"),
                    Check("actions", "+1 and Reset on the widget change the number; each click is listed as an action"),
                    Check("push", "Push +1 from this probe changes the widget within a second"),
                    Check("sizes", "Small keeps only the count and +step; Medium adds the note and the links"),
                    Check(
                        "cold-start",
                        "With the Lab quit, a click on the widget starts it without a window or activation; " +
                            "it quits again 30 s later",
                    ),
                    Check("persist", "The count survives quitting and relaunching the Lab (widget state)"),
                    Check(
                        "open-url",
                        "“Open in the Lab” on the widget brings this probe forward through the deep link",
                    ),
                    Check("image", "“Lab tasks” draws its check marks from app resources at icon size"),
                    Check(
                        "tasks",
                        "“Lab tasks”: a click on a task checks it, the filter tabs switch, “Add a task” here adds one",
                    ),
                    Check(
                        "remove",
                        "Removing a widget then refreshing here lists “unpinned”; adding it again starts at 0",
                    ),
                ),
            keywords = listOf("widget", "WidgetKit", "Glance", "NucleusWidget", "AppIntent", "desktop widgets"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WindowsWidgetsViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability("WidgetCenter", state.manager, detail = "WidgetKit, macOS 12+"),
                    Capability(
                        "Widget host",
                        state.host.host,
                        detail = "the NucleusWidgets.appex the plugin embeds; interactive widgets need macOS 14",
                    ),
                ),
            controls = {
                Actions {
                    PrimaryAction("Refresh", enabled = state.host.host.isAvailable) {
                        vm.onIntent(WindowsWidgetsIntent.Refresh)
                    }
                }
                SubHeading("Lab widgets on screen")
                if (state.widgets.isEmpty()) {
                    Hint(
                        "Right-click the desktop, Edit Widgets, search “Nucleus Lab” and add “Lab counter” or “Lab tasks”.",
                    )
                }
                state.widgets.forEach { widget ->
                    Readout(widget.id, "${widget.definitionId}, ${widget.size}, ${widget.summary}")
                    Actions {
                        SecondaryAction(if (widget.definitionId == LAB_TASKS_WIDGET) "Add a task" else "Push +1") {
                            vm.onIntent(WindowsWidgetsIntent.Push(widget.id, widget.definitionId))
                        }
                    }
                }
            },
            observed = {
                Readout("Launched by", if (state.host.launchedByHost) "the widget extension" else "the user")
                SubHeading("Widget events")
                EventLog(state.events, empty = "The widget extension has not called the app yet.")
                SubHeading("Calls")
                EventLog(state.calls)
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.macos-widgets")
    }
}
