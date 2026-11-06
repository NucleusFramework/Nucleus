package dev.nucleusframework.lab.probes.input.a11y

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.fixture.FixtureLauncher
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.mvi.map
import dev.nucleusframework.lab.core.mvi.stamped
import dev.nucleusframework.lab.core.mvi.toDelivery
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class A11yViewModel(
    private val fixture: A11ySurfaceFixture,
    private val launcher: FixtureLauncher,
    timeline: Timeline,
) : MviViewModel<A11yState, A11yIntent, A11yEvent, Nothing>(A11yState(), A11yReducer, timeline, A11yProbe.ID) {
    /** Child processes of the isolated surface, newest last. */
    val isolatedRuns: Flow<List<FixtureRun>> = launcher.runs.map { runs -> runs.filter { it.fixtureId == fixture.id } }

    /**
     * Called synchronously from the semantics/click lambda, i.e. on whichever thread the
     * platform bridge delivered the action: the stamp is taken here, not after a hop.
     */
    fun onSurfaceEvent(message: String) {
        val stamped = message.stamped()
        dispatch(stamped.map { A11yEvent.ActionObserved(stamped.toDelivery()) })
    }

    fun stop(runId: Int) = launcher.stop(runId)

    override suspend fun handle(intent: A11yIntent) {
        when (intent) {
            is A11yIntent.SelectTab -> dispatch(A11yEvent.TabSelected(intent.tab))
            is A11yIntent.LaunchIsolated -> {
                val variant = fixture.variants.first { it.name == intent.tab.label }
                launcher.launch(fixture, variant)
            }
            A11yIntent.ClearActions -> dispatch(A11yEvent.ActionsCleared)
        }
    }
}
