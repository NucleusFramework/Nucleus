package dev.nucleusframework.lab.probes.fixtures.runner

import androidx.lifecycle.ViewModel
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureLauncher
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.mvi.MviViewModel
import dev.nucleusframework.lab.core.process.JvmCommand
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.delay

@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@Inject
class FixturesViewModel(
    fixtures: Set<Fixture>,
    private val launcher: FixtureLauncher,
    timeline: Timeline,
) : MviViewModel<FixturesState, FixturesIntent, FixturesEvent, Nothing>(
        FixturesState(now = System.currentTimeMillis()),
        FixturesReducer,
        timeline,
        FixturesProbe.ID,
    ) {
    private val byId = fixtures.associateBy { it.id }

    init {
        dispatch(FixturesEvent.Loaded(fixtures.map(FixtureInfo::of), JvmCommand.availability(), partialRedrawPatch()))
        launch {
            // Output lines arrive one by one: mirrored silently, only the endings reach the timeline.
            launcher.runs.collect { runs ->
                val before = state.value
                reduceSilently(FixturesEvent.RunsChanged(runs))
                // A run short enough to start and end between two emissions is reported too.
                runs
                    .filter {
                        it.exitCode != null &&
                            before.runs.none { old -> old.runId == it.runId && old.exitCode != null }
                    }.forEach(::reportFinished)
            }
        }
        launch {
            while (true) {
                delay(TICK_MILLIS)
                if (state.value.runs.any { it.exitCode == null }) {
                    reduceSilently(
                        FixturesEvent.Ticked(System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    override suspend fun handle(intent: FixturesIntent) {
        when (intent) {
            is FixturesIntent.SelectVariant -> dispatch(FixturesEvent.VariantSelected(intent.fixtureId, intent.variant))
            is FixturesIntent.Run -> {
                val fixture = byId[intent.fixtureId] ?: return
                val variant = state.value.variantOf(FixtureInfo.of(fixture))
                reduceSilently(FixturesEvent.Ticked(System.currentTimeMillis()))
                io { launcher.launch(fixture, variant) }
            }
            is FixturesIntent.Stop -> {
                dispatch(FixturesEvent.StopRequested(intent.runId))
                launcher.stop(intent.runId)
            }
            is FixturesIntent.ShowOutput -> reduceSilently(FixturesEvent.OutputFocused(intent.runId))
            FixturesIntent.ClearFinished -> launcher.clearFinished()
        }
    }

    private fun reportFinished(run: FixtureRun) {
        val now = System.currentTimeMillis()
        reduceSilently(FixturesEvent.Ticked(now))
        val outcome = state.value.outcomeOf(run)
        dispatch(
            FixturesEvent.RunFinished(run.fixtureId, run.variant, outcome, run.durationMillis(now)),
            outcome.severity,
        )
    }

    override fun describe(value: Any): String =
        when (value) {
            is FixturesEvent.RunFinished ->
                "${value.fixtureId} [${value.variant}] ${value.outcome.label()} after " +
                    formatDurationMillis(value.durationMillis)
            is FixturesEvent.Loaded ->
                "Loaded ${value.fixtures.size} fixtures: ${value.fixtures.joinToString { it.id }}"
            else -> super.describe(value)
        }

    private companion object {
        const val TICK_MILLIS = 250L

        /** The plugin's Compose patch (`nucleusOptimization { partialRedraw = true }`) ships this class. */
        fun partialRedrawPatch(): Availability =
            Availability.of(
                ClassLoader.getSystemResource("androidx/compose/ui/node/NucleusLayerDamage.class") != null,
            ) {
                "Compose not patched: partial-redraw runs repaint every frame in full"
            }
    }
}
