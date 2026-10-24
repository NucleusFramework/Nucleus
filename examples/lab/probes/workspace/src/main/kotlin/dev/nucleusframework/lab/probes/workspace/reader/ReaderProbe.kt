package dev.nucleusframework.lab.probes.workspace.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.Check
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.ProbeDescriptor
import dev.nucleusframework.lab.core.ProbeId
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.designsystem.ChoiceRow
import dev.nucleusframework.lab.designsystem.SubHeading
import dev.nucleusframework.lab.designsystem.SwitchRow
import dev.nucleusframework.lab.probes.workspace.common.ComposedProbeLayout
import dev.nucleusframework.lab.probes.workspace.common.ComposedViewModel
import dev.nucleusframework.lab.probes.workspace.common.SessionIntent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class ReaderViewModel(
    host: SessionHost,
    timeline: Timeline,
) : ComposedViewModel<ReaderLive, ReaderModel>(ReaderLive(), host, timeline, ReaderProbe.ID) {
    override val sessionTitle = "Reader dock"

    override fun newModel(live: ReaderLive) = ReaderModel(live)

    override fun content(model: ReaderModel): @Composable NucleusApplicationScope.(() -> Unit) -> Unit =
        { close -> ReaderSession(model, close) }
}

@ContributesIntoSet(AppScope::class)
@Inject
class ReaderProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Reader dock",
            domain = Domain.Workspace,
            summary =
                "Does a library reader composing tabs and a layered, furnished dock behave like a split-pane app, " +
                    "left to right and right to left?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "layered",
                        "Library | contents | notes are three columns on the right, each with its own width and splitter",
                    ),
                    Check(
                        "side-order",
                        "Bookmarks run under the text and the glossary, stopping at the navigation columns",
                    ),
                    Check(
                        "furniture",
                        "Library and contents cannot be floated, moved to another side, or have a pane dropped in front of them",
                    ),
                    Check(
                        "rtl-strip",
                        "With Right to left on, the first book is the rightmost tab, '+' follows the last leftwards, hover cards hang from the right edge, and the dock keeps its physical sides",
                    ),
                    Check(
                        "tab-change",
                        "Switching books changes the text and what the panes draw — no panel is rebuilt or resized",
                    ),
                    Check(
                        "tear-off",
                        "A book torn into its own window arrives with a dock of its own, pane widths independent",
                    ),
                    Check(
                        "styles",
                        "Islands turns every pane into a rounded card, Classic back to 1 dp dividers; splitters grip 5 dp either way",
                    ),
                ),
            keywords = listOf("rtl", "DockLayout", "layeredSides", "sideOrder", "floatable", "reorderable", "reader"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<ReaderViewModel>()
        val state by vm.state.collectAsState()
        val live = state.live
        ComposedProbeLayout(vm, state) {
            SubHeading("Live")
            ChoiceRow(
                "Style",
                ReaderStyle.entries,
                live.style,
            ) { vm.onIntent(SessionIntent.SetLive(live.copy(style = it))) }
            SwitchRow(
                "Right to left",
                live.rightToLeft,
            ) { vm.onIntent(SessionIntent.SetLive(live.copy(rightToLeft = it))) }
            SwitchRow(
                "Layered right side",
                live.layeredRight,
            ) { vm.onIntent(SessionIntent.SetLive(live.copy(layeredRight = it))) }
            SwitchRow("Navigation outermost", live.navigationOutermost) {
                vm.onIntent(SessionIntent.SetLive(live.copy(navigationOutermost = it)))
            }
        }
    }

    companion object {
        val ID = ProbeId("workspace.reader-dock")
    }
}
