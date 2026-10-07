package dev.nucleusframework.lab.probes.fixtures.runner

import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.fixture.FixtureVariant
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.timeline.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FixturesReducerTest {
    private val stress =
        FixtureInfo(
            id = "rect-stress",
            title = "RectManager stress",
            description = "",
            variants = listOf(FixtureVariant("default"), FixtureVariant("timed 60 s", listOf("-Dx=60"))),
            exitCodes = mapOf(55 to "escaped to the EDT"),
        )
    private val swing = FixtureInfo("swing-tao", "Swing", "", listOf(FixtureVariant("default")), emptyMap())

    private fun loaded() =
        FixturesReducer.reduce(
            FixturesState(),
            FixturesEvent.Loaded(listOf(swing, stress), Availability.Available, Availability.Unknown),
        )

    private fun run(
        id: Int,
        exit: Int? = null,
        fixtureId: String = "rect-stress",
    ) = FixtureRun(
        id,
        fixtureId,
        "default",
        emptyList(),
        1L,
        emptyList(),
        exit,
        startedAt = 1_000,
        endedAt =
            exit?.let {
                61_000
            },
    )

    private fun FixturesState.with(vararg runs: FixtureRun) =
        FixturesReducer.reduce(this, FixturesEvent.RunsChanged(runs.toList()))

    @Test
    fun `fixtures are sorted by title and default to their first variant`() {
        val state = loaded()
        assertEquals(listOf("rect-stress", "swing-tao"), state.fixtures.map { it.id })
        assertEquals("default", state.variantOf(stress).name)
    }

    @Test
    fun `a selected variant is kept per fixture and an unknown one falls back`() {
        val state = FixturesReducer.reduce(loaded(), FixturesEvent.VariantSelected("rect-stress", "timed 60 s"))
        assertEquals(listOf("-Dx=60"), state.variantOf(stress).jvmArgs)
        assertEquals("default", state.variantOf(swing).name)
        val unknown = FixturesReducer.reduce(state, FixturesEvent.VariantSelected("rect-stress", "gone"))
        assertEquals("default", unknown.variantOf(stress).name)
    }

    @Test
    fun `a new run takes the output panel, a picked one keeps it while output grows`() {
        val first = loaded().with(run(1))
        assertEquals(1, first.focusedRunId)
        val second = first.with(run(1), run(2))
        assertEquals(2, second.focusedRunId)
        val picked = FixturesReducer.reduce(second, FixturesEvent.OutputFocused(1))
        assertEquals(1, picked.with(run(1), run(2, exit = 0)).focusedRunId)
    }

    @Test
    fun `clearing the focused run falls back to the newest one`() {
        val state = loaded().with(run(1, exit = 0), run(2))
        val focused = FixturesReducer.reduce(state, FixturesEvent.OutputFocused(1))
        val cleared = focused.with(run(2))
        assertNull(cleared.focusedRunId)
        assertEquals(2, cleared.focusedRun?.runId)
    }

    @Test
    fun `exit codes read against the fixture's own meanings`() {
        val state = loaded().with(run(1), run(2, exit = 0), run(3, exit = 55), run(4, exit = 9))
        assertEquals(RunOutcome.Running, state.outcomeOf(state.runs[0]))
        assertEquals(RunOutcome.Passed, state.outcomeOf(state.runs[1]))
        assertEquals(RunOutcome.Failed(55, "escaped to the EDT"), state.outcomeOf(state.runs[2]))
        assertEquals(RunOutcome.Failed(9, "unexpected exit code"), state.outcomeOf(state.runs[3]))
        assertEquals(Severity.Error, state.outcomeOf(state.runs[2]).severity)
        assertEquals("exit 55 — escaped to the EDT", state.outcomeOf(state.runs[2]).label())
    }

    @Test
    fun `a stopped run is a warning, not a failure, and is forgotten once cleared`() {
        val running = loaded().with(run(1))
        val stopped = FixturesReducer.reduce(running, FixturesEvent.StopRequested(1)).with(run(1, exit = 143))
        val outcome = stopped.outcomeOf(stopped.runs.single())
        assertEquals(RunOutcome.Stopped(143), outcome)
        assertEquals(Severity.Warning, outcome.severity)
        assertEquals(emptySet(), stopped.with().stopped)
    }

    @Test
    fun `running runs are listed per fixture`() {
        val state = loaded().with(run(1), run(2, exit = 0), run(3, fixtureId = "swing-tao"))
        assertEquals(listOf(1), state.runningOf("rect-stress").map { it.runId })
        assertEquals(listOf(3), state.runningOf("swing-tao").map { it.runId })
    }

    @Test
    fun `system exit codes have a meaning of their own`() {
        assertEquals("terminated (SIGTERM)", RunOutcome.meaningOf(143, emptyMap()))
        assertEquals("override", RunOutcome.meaningOf(143, mapOf(143 to "override")))
    }

    @Test
    fun `durations run on the clock until the run ends`() {
        assertEquals(60_000, run(1, exit = 0).durationMillis(now = 999_999))
        assertEquals(4_000, run(1).durationMillis(now = 5_000))
        assertEquals("4.2 s", formatDurationMillis(4_200))
        assertEquals("1 min 02 s", formatDurationMillis(62_000))
        assertEquals("1 h 03 min", formatDurationMillis(3_780_000))
    }
}
