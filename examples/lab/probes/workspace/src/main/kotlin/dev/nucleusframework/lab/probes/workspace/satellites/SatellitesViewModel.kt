package dev.nucleusframework.lab.probes.workspace.satellites

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import dev.nucleusframework.application.NucleusApplicationScope
import dev.nucleusframework.lab.core.session.SessionHost
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.nucleusframework.lab.probes.workspace.common.SatellitesObservation
import dev.nucleusframework.lab.probes.workspace.common.SessionModelViewModel
import dev.nucleusframework.lab.probes.workspace.common.SessionState
import dev.nucleusframework.lab.probes.workspace.common.diffSatellites
import dev.nucleusframework.lab.probes.workspace.common.observe
import dev.nucleusframework.lab.probes.workspace.common.toJson
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class SatellitesViewModel(
    host: SessionHost,
    timeline: Timeline,
) : SessionModelViewModel<SatellitesLive, SatellitesData, SatellitesIntent, SatellitesEvent, SatellitesSessionModel>(
        SessionState(live = SatellitesLive(), data = SatellitesData()),
        SatellitesReducer,
        host,
        timeline,
        SatellitesProbe.ID,
    ) {
    private var savedLayout: SatelliteLayoutSnapshot? = null

    override val sessionTitle = "Satellite workspace"

    override fun createModel(state: SatellitesState) =
        SatellitesSessionModel(state.data.options, state.live).apply { workspace.visible = state.live.showAll }

    override fun content(model: SatellitesSessionModel): @Composable NucleusApplicationScope.(() -> Unit) -> Unit =
        { close -> SatellitesSession(model, close) }

    override fun onOpened(model: SatellitesSessionModel) {
        var previous = SatellitesObservation()
        mirror({ model.workspace.observe { window -> model.document(window)?.title } }) { observation ->
            diffSatellites(previous, observation).forEach { dispatchProbe(SatellitesEvent.Changed(it)) }
            previous = observation
            reduceProbeSilently(SatellitesEvent.Observed(observation))
        }
    }

    override fun applyLive(
        model: SatellitesSessionModel,
        previous: SatellitesLive,
        live: SatellitesLive,
    ) {
        model.live = live
        model.workspace.visible = live.showAll
        if (live.anchor != previous.anchor || live.adjustment != previous.adjustment || live.gapDp != previous.gapDp) {
            model.applyPositioner()
        }
    }

    override suspend fun handleProbe(intent: SatellitesIntent) {
        when (intent) {
            is SatellitesIntent.SetOptions -> dispatchProbe(SatellitesEvent.OptionsChanged(intent.options))
            is SatellitesIntent.Toggle -> withModel { workspace.toggle(intent.id) }
            is SatellitesIntent.Dock ->
                withModel {
                    val entry = workspace.satellite(intent.id)
                    if (entry != null && intent.side !in entry.dockSides) {
                        refuse("${intent.id} is not declared for ${intent.side} — dock() must refuse it")
                    }
                    // Called anyway: the refusal itself is under test.
                    workspace.dock(intent.id, intent.side)
                }
            is SatellitesIntent.Undock ->
                withModel {
                    if (workspace.satellite(intent.id)?.isFloatable == false) {
                        refuse("${intent.id} is floatable = false — undock() must refuse it")
                    }
                    workspace.undock(intent.id)
                }
            is SatellitesIntent.Pin ->
                withModel { if (!pin(intent.document)) refuse("${intent.document?.title} is not open") }
            SatellitesIntent.Reanchor -> withModel { applyPositioner() }
            SatellitesIntent.SaveLayout ->
                withModel {
                    val snapshot = workspace.snapshot()
                    savedLayout = snapshot
                    dispatchProbe(SatellitesEvent.LayoutSaved(snapshot.toJson()))
                }
            SatellitesIntent.RestoreLayout ->
                withModel {
                    val snapshot = savedLayout
                    if (snapshot == null) {
                        refuse("nothing saved yet")
                    } else {
                        workspace.restore(snapshot)
                        dispatchProbe(SatellitesEvent.LayoutRestored)
                    }
                }
        }
    }
}
