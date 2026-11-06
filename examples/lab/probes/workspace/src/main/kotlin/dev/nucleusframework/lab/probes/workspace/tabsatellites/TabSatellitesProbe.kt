package dev.nucleusframework.lab.probes.workspace.tabsatellites

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
class TabSatellitesViewModel(
    host: SessionHost,
    timeline: Timeline,
) : ComposedViewModel<TabSatellitesLive, TabSatellitesModel>(
        TabSatellitesLive(),
        host,
        timeline,
        TabSatellitesProbe.ID,
    ) {
    override val sessionTitle = "Tab satellites"

    override fun newModel(live: TabSatellitesLive) = TabSatellitesModel(live)

    override fun content(model: TabSatellitesModel): @Composable NucleusApplicationScope.(() -> Unit) -> Unit =
        { close -> TabSatellitesSession(model, close) }
}

@ContributesIntoSet(AppScope::class)
@Inject
class TabSatellitesProbe : Probe {
    override val descriptor =
        ProbeDescriptor(
            id = ID,
            title = "Tab satellites",
            domain = Domain.Workspace,
            summary = "Can each tab window carry palettes drawing its selected tab, without churning windows?",
            modules = listOf("decorated-window-tao", "nucleus-application"),
            checks =
                listOf(
                    Check(
                        "no-flash",
                        "Switching between two tabs that want the same palette changes its content only — no window flashes",
                    ),
                    Check(
                        "per-document",
                        "Scene.kt shows both palettes, Shader.glsl the Inspector only, notes.md none",
                    ),
                    Check(
                        "values",
                        "Inspector strength / Palette swatch belong to the document: switch tabs and back, they are as left",
                    ),
                    Check(
                        "tear-off",
                        "A tab torn into its own window arrives with palettes of its own; both windows show two independent sets",
                    ),
                    Check(
                        "dock",
                        "The Palette docks inside its own window's dock only, and a tab change leaves the dock untouched",
                    ),
                    Check(
                        "dock-snapshot",
                        "Save dock → rearrange that window's palettes → Restore dock puts them back; the other window is untouched",
                    ),
                ),
            keywords = listOf("TabWindows", "SatelliteWorkspace", "per window", "windowBodyWrapper", "palette"),
        )

    @Composable
    override fun Content() {
        val vm = metroViewModel<TabSatellitesViewModel>()
        val state by vm.state.collectAsState()
        ComposedProbeLayout(vm, state) {
            SubHeading("Live")
            SwitchRow("Documents decide palettes", state.live.documentsDecide) {
                vm.onIntent(SessionIntent.SetLive(state.live.copy(documentsDecide = it)))
            }
        }
    }

    companion object {
        val ID = ProbeId("workspace.tab-satellites")
    }
}
