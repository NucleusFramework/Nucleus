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
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.format.formatTime
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
                "Does the Widgets board activate the Lab's COM widget provider, render its Adaptive Card " +
                    "and route the card's actions back into the app?",
            modules = listOf("widget-windows"),
            platforms = setOf(Platform.Windows),
            checks =
                listOf(
                    Check("picker", "In the Widgets board, “+” lists Nucleus Lab with the Lab counter widget"),
                    Check("pin", "Pinning it shows a card at 0; createWidget then activate are listed here"),
                    Check("actions", "+1 and Reset on the card change the number; onActionInvoked shows the verb"),
                    Check("push", "Push +1 from this probe changes the pinned card within a second"),
                    Check("resize", "Resizing the widget lists onWidgetContextChanged with the new size"),
                    Check(
                        "cold-start",
                        "With the Lab closed, opening the Widgets board starts it through COM; the first callback " +
                            "comes within the host's patience",
                    ),
                    Check("persist", "The count survives quitting and relaunching the Lab (widget custom state)"),
                    Check("open-url", "“Open probe” on the card brings this probe forward through the deep link"),
                    Check("unpin", "Unpinning the widget lists deleteWidget with its last count"),
                ),
            keywords =
                listOf("widget", "Widgets board", "IWidgetProvider", "WidgetManager", "Adaptive Cards", "COM"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<WindowsWidgetsViewModel>()
        val state by vm.state.collectAsState()
        val provider = state.provider

        ProbeLayout(
            capabilities =
                listOf(
                    Capability(
                        "WidgetManager",
                        state.manager,
                        detail = "needs package identity and the Windows App SDK 1.8 framework",
                    ),
                    Capability(
                        "COM provider",
                        provider.registration,
                        detail = "CLSID ${NucleusWindowsWidgetsGateway.CLSID}",
                    ),
                ),
            controls = {
                Actions {
                    PrimaryAction("Refresh", enabled = state.manager.isAvailable) {
                        vm.onIntent(WindowsWidgetsIntent.Refresh)
                    }
                }
                SubHeading("Pinned widgets")
                if (state.widgets.isEmpty()) {
                    Hint("Open the Widgets board (Win+W), press “+” and pin “Lab counter”.")
                }
                state.widgets.forEach { widget ->
                    Readout(
                        widget.id,
                        "${widget.size}, ${if (widget.isActive) "active" else "inactive"}, count ${widget.count}",
                    )
                    Actions {
                        SecondaryAction("Push +1") { vm.onIntent(WindowsWidgetsIntent.Push(widget.id)) }
                        SecondaryAction("Unpin") { vm.onIntent(WindowsWidgetsIntent.Delete(widget.id)) }
                    }
                }
            },
            observed = {
                Readout("Launched by", if (provider.launchedByCom) "COM (widget host)" else "user")
                Readout(
                    "First host callback",
                    provider.firstCallbackMillis?.let {
                        "${formatTime(
                            it,
                        )}, ${formatDurationMillis(it - provider.processStartMillis)} after process start"
                    } ?: "none yet",
                )
                state.widgets.forEach { widget ->
                    Readout("${widget.definitionId} ${widget.id}", "updated ${formatTime(widget.lastUpdateMillis)}")
                }
                SubHeading("Host callbacks")
                EventLog(state.callbacks, empty = "The widget host has not called the provider yet.")
                SubHeading("Calls")
                EventLog(state.calls)
            },
        )
    }

    companion object {
        val ID = ProbeId("shell.windows-widgets")
    }
}
