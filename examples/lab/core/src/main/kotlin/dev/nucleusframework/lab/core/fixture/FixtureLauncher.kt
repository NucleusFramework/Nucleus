package dev.nucleusframework.lab.core.fixture

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.process.JvmCommand
import dev.nucleusframework.lab.core.timeline.EntryKind
import dev.nucleusframework.lab.core.timeline.Severity
import dev.nucleusframework.lab.core.timeline.Timeline
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class FixtureRun(
    val runId: Int,
    val fixtureId: String,
    val variant: String,
    val command: List<String>,
    val pid: Long?,
    val output: List<String>,
    /** `null` while running. */
    val exitCode: Int?,
    val startedAt: Long,
    val endedAt: Long?,
)

/** Relaunches this JVM — same runtime, same classpath — as a single fixture. */
@SingleIn(AppScope::class)
@Inject
class FixtureLauncher(
    private val timeline: Timeline,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableMapOf<Int, Process>()
    private var nextRunId = 1

    private val state = MutableStateFlow<List<FixtureRun>>(emptyList())
    val runs: StateFlow<List<FixtureRun>> = state.asStateFlow()

    fun launch(
        fixture: Fixture,
        variant: FixtureVariant,
    ) {
        val command = command(fixture, variant)
        val runId = synchronized(this) { nextRunId++ }
        val process =
            runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
                .getOrElse {
                    timeline.record(
                        null,
                        EntryKind.Process,
                        "Could not start ${fixture.id}: ${it.message}",
                        Severity.Error,
                    )
                    return
                }
        synchronized(processes) { processes[runId] = process }
        val run =
            FixtureRun(
                runId = runId,
                fixtureId = fixture.id,
                variant = variant.name,
                command = command,
                pid = process.pid(),
                output = emptyList(),
                exitCode = null,
                startedAt = System.currentTimeMillis(),
                endedAt = null,
            )
        state.update { it + run }
        timeline.record(null, EntryKind.Process, "Started ${fixture.id} [${variant.name}] pid=${process.pid()}")

        scope.launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> update(runId) { it.copy(output = it.output.append(line, OUTPUT_LINES)) } }
            }
            val code = process.waitFor()
            synchronized(processes) { processes.remove(runId) }
            update(runId) { it.copy(exitCode = code, endedAt = System.currentTimeMillis()) }
            val meaning = fixture.exitCodes[code]?.let { " ($it)" }.orEmpty()
            timeline.record(
                null,
                EntryKind.Process,
                "${fixture.id} [${variant.name}] exited with $code$meaning",
                if (code == 0) Severity.Info else Severity.Error,
            )
        }
    }

    fun stop(runId: Int) {
        synchronized(processes) { processes[runId] }?.destroy()
    }

    fun clearFinished() {
        state.update { runs -> runs.filter { it.exitCode == null } }
    }

    private fun update(
        runId: Int,
        transform: (FixtureRun) -> FixtureRun,
    ) {
        state.update { runs -> runs.map { if (it.runId == runId) transform(it) else it } }
    }

    private fun command(
        fixture: Fixture,
        variant: FixtureVariant,
    ): List<String> = JvmCommand.fixture(fixture.id, variant.jvmArgs, variant.args)

    private companion object {
        const val OUTPUT_LINES = 400
    }
}
