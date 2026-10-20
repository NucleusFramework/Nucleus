package dev.nucleusframework.lab.probes.fixtures.runner

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.lab.core.mvi.Reducer

/** What the runner shows of a [Fixture]; the fixture itself stays in the ViewModel. */
@Immutable
data class FixtureInfo(
    val id: String,
    val title: String,
    val description: String,
    val variants: List<FixtureVariant>,
    val exitCodes: Map<Int, String>,
) {
    companion object {
        fun of(fixture: Fixture): FixtureInfo =
            FixtureInfo(fixture.id, fixture.title, fixture.description, fixture.variants, fixture.exitCodes)
    }
}

@Immutable
data class FixturesState(
    val relaunch: Availability = Availability.Unknown,
    val partialRedrawPatch: Availability = Availability.Unknown,
    val fixtures: List<FixtureInfo> = emptyList(),
    /** Chosen variant name per fixture id; absent = the fixture's first variant. */
    val selectedVariants: Map<String, String> = emptyMap(),
    val runs: List<FixtureRun> = emptyList(),
    /** Runs the tester stopped, so their exit code is not read as a failure of the fixture. */
    val stopped: Set<Int> = emptySet(),
    /** The run whose output is shown; follows the newest run until the tester picks one. */
    val focusedRunId: Int? = null,
    /** Clock for the live duration of running fixtures. */
    val now: Long = 0L,
) {
    fun variantOf(fixture: FixtureInfo): FixtureVariant =
        fixture.variants.firstOrNull { it.name == selectedVariants[fixture.id] } ?: fixture.variants.first()

    fun runningOf(fixtureId: String): List<FixtureRun> =
        runs.filter { it.fixtureId == fixtureId && it.exitCode == null }

    val focusedRun: FixtureRun? get() = runs.firstOrNull { it.runId == focusedRunId } ?: runs.lastOrNull()

    fun outcomeOf(run: FixtureRun): RunOutcome =
        RunOutcome.of(run, fixtures.firstOrNull { it.id == run.fixtureId }?.exitCodes.orEmpty(), run.runId in stopped)
}

sealed interface FixturesIntent {
    data class SelectVariant(
        val fixtureId: String,
        val variant: String,
    ) : FixturesIntent

    data class Run(
        val fixtureId: String,
    ) : FixturesIntent

    data class Stop(
        val runId: Int,
    ) : FixturesIntent

    data class ShowOutput(
        val runId: Int,
    ) : FixturesIntent

    data object ClearFinished : FixturesIntent
}

sealed interface FixturesEvent {
    data class Loaded(
        val fixtures: List<FixtureInfo>,
        val relaunch: Availability,
        val partialRedrawPatch: Availability,
    ) : FixturesEvent

    data class VariantSelected(
        val fixtureId: String,
        val variant: String,
    ) : FixturesEvent

    /** The launcher's run list changed (a start, an output line, an exit). */
    data class RunsChanged(
        val runs: List<FixtureRun>,
    ) : FixturesEvent

    data class StopRequested(
        val runId: Int,
    ) : FixturesEvent

    /** A run ended; recorded on the timeline with its outcome's severity. */
    data class RunFinished(
        val fixtureId: String,
        val variant: String,
        val outcome: RunOutcome,
        val durationMillis: Long,
    ) : FixturesEvent

    data class OutputFocused(
        val runId: Int,
    ) : FixturesEvent

    data class Ticked(
        val now: Long,
    ) : FixturesEvent
}

object FixturesReducer : Reducer<FixturesState, FixturesEvent> {
    override fun reduce(
        state: FixturesState,
        event: FixturesEvent,
    ): FixturesState =
        when (event) {
            is FixturesEvent.Loaded ->
                state.copy(
                    fixtures = event.fixtures.sortedBy { it.title },
                    relaunch = event.relaunch,
                    partialRedrawPatch = event.partialRedrawPatch,
                )
            is FixturesEvent.VariantSelected ->
                state.copy(selectedVariants = state.selectedVariants + (event.fixtureId to event.variant))
            is FixturesEvent.RunsChanged -> {
                val known = state.runs.mapTo(HashSet()) { it.runId }
                val started = event.runs.lastOrNull { it.runId !in known }
                val ids = event.runs.mapTo(HashSet()) { it.runId }
                state.copy(
                    runs = event.runs,
                    // A new run takes the output panel; a cleared one gives it back.
                    focusedRunId = started?.runId ?: state.focusedRunId?.takeIf { it in ids },
                    stopped = state.stopped intersect ids,
                )
            }
            is FixturesEvent.StopRequested -> state.copy(stopped = state.stopped + event.runId)
            is FixturesEvent.RunFinished -> state
            is FixturesEvent.OutputFocused -> state.copy(focusedRunId = event.runId)
            is FixturesEvent.Ticked -> state.copy(now = event.now)
        }
}
