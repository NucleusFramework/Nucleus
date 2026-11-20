package dev.nucleusframework.lab.probes.fixtures.runner

import dev.nucleusframework.lab.core.fixture.FixtureRun
import dev.nucleusframework.lab.core.timeline.Severity

/** How a run ended, read against the fixture's own [exit codes][dev.nucleusframework.lab.core.fixture.Fixture.exitCodes]. */
sealed interface RunOutcome {
    val severity: Severity

    data object Running : RunOutcome {
        override val severity = Severity.Info
    }

    data object Passed : RunOutcome {
        override val severity = Severity.Info
    }

    /** The tester pressed Stop: whatever the code, it says nothing about the fixture. */
    data class Stopped(
        val code: Int,
    ) : RunOutcome {
        override val severity = Severity.Warning
    }

    data class Failed(
        val code: Int,
        val meaning: String,
    ) : RunOutcome {
        override val severity = Severity.Error
    }

    companion object {
        fun of(
            run: FixtureRun,
            exitCodes: Map<Int, String>,
            stopped: Boolean,
        ): RunOutcome {
            val code = run.exitCode ?: return Running
            return when {
                stopped -> Stopped(code)
                code == 0 -> Passed
                else -> Failed(code, meaningOf(code, exitCodes))
            }
        }

        /** The fixture's meaning first, then the codes the JVM and the OS produce on their own. */
        fun meaningOf(
            code: Int,
            exitCodes: Map<Int, String>,
        ): String =
            exitCodes[code]
                ?: when (code) {
                    0 -> "pass"
                    1 -> "uncaught failure"
                    2 -> "unknown fixture id"
                    130 -> "interrupted (SIGINT)"
                    134 -> "aborted (SIGABRT, JVM crash)"
                    137 -> "killed (SIGKILL)"
                    139 -> "segmentation fault (SIGSEGV)"
                    143 -> "terminated (SIGTERM)"
                    else -> "unexpected exit code"
                }
    }
}

/** One line of the history: `exit 55 — RectManager dispatch escaped to the EDT`. */
fun RunOutcome.label(): String =
    when (this) {
        RunOutcome.Running -> "running"
        RunOutcome.Passed -> "exit 0 — pass"
        is RunOutcome.Stopped -> "stopped (exit $code)"
        is RunOutcome.Failed -> "exit $code — $meaning"
    }

fun FixtureRun.durationMillis(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0L)
