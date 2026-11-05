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

@ContributesIntoSet(AppScope::class)
@Inject
class WindowsWidgetsProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Windows 11 widgets",
            domain = Domain.Shell,
            summary =
                "Does a Glance-like NucleusWidget show up in the Widgets board, render, and route its " +
                    "buttons and app-side updates back through the widget state?",
            modules = listOf("widget-runtime", "widget-windows"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check("picker", "In the Widgets board, “+” lists Nucleus Lab with “Lab counter” and its preview"),
                    Check("pin", "Pinning it shows a card at 0; “pinned” then “rendered” are listed here"),
                    Check("actions", "+1 and Reset on the card change the number; each click is listed as an action"),
                    Check("push", "Push +1 from this probe changes the pinned card within a second"),
                    Check("resize", "Resizing the widget re-renders it; Small keeps only the count and +step"),
                    Check("cold-start", "With the Lab closed, opening the Widgets board starts it and the card shows"),
                    Check("persist", "The count survives quitting and relaunching the Lab (widget state)"),
                    Check("open-url", "“Open in the Lab” on the card brings this probe forward through the deep link"),
                    Check("unpin", "Unpinning the widget lists “unpinned”"),
                    Check(
                        "customize",
                        "“Customize widget” in the counter's menu shows the settings; Done applies title, step, " +
                            "note and background",
                    ),
                    Check("image", "“Lab tasks” draws its check marks from app resources at icon size (not embedded)"),
                    Check("web", "“Lab web” shows its page in the EEA, its fallback card elsewhere"),
                    Check(
                        "tasks",
                        "“Lab tasks”: Add puts the typed task in the list, a click on a task checks it, " +
                            "“Add a task” here adds one from the app",
                    ),
                    Check(
                        "tasks-sizes",
                        "“Lab tasks” Small shows the next task and Check; Large adds filters and Clear done",
                    ),
                    Check(
                        "tasks-customize",
                        "Customizing “Lab tasks” applies its title, hiding done tasks and the to-do-first order",
                    ),
                ),
            keywords = listOf("widget", "Widgets board", "Glance", "NucleusWidget", "Adaptive Cards", "COM"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WindowsWidgetsViewModel>()
        val state by vm.state.collectAsState()

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "WidgetManager",
                        state.manager,
                        detail = "needs package identity and the Windows App SDK 1.8 framework",
                    ),
                    Capability("Widget host", state.host.host, detail = state.host.clsid?.let { "CLSID $it" }),
                ),
            controls = {
                Actions {
                    PrimaryAction("Refresh", enabled = state.host.host.isAvailable) {
                        vm.onIntent(WindowsWidgetsIntent.Refresh)
                    }
                }
                SubHeading("Pinned Lab widgets")
                if (state.widgets.isEmpty()) {
                    Hint("Open the Widgets board (Win+W), press “+” and pin “Lab counter” or “Lab tasks”.")
                }
                state.widgets.forEach { widget ->
                    Readout(
                        widget.id,
                        "${widget.definitionId}, ${widget.size}, ${if (widget.isActive) "active" else "inactive"}, ${widget.summary}",
                    )
                    Actions {
                        SecondaryAction(
                            if (widget.definitionId ==
                                LAB_TASKS_WIDGET
                            ) {
                                "Add a task"
                            } else {
                                "Push +1"
                            },
                        ) { vm.onIntent(WindowsWidgetsIntent.Push(widget.id)) }
                        SecondaryAction("Unpin") { vm.onIntent(WindowsWidgetsIntent.Delete(widget.id)) }
                    }
                }
            },
            observed = {
                Readout("Launched by", if (state.host.launchedByHost) "the Widgets board (COM)" else "the user")
                SubHeading("Widget events")
                EventLog(state.events, empty = "The widget host has not called the app yet.")
                SubHeading("Calls")
                EventLog(state.calls)
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.windows-widgets")
    }
}
