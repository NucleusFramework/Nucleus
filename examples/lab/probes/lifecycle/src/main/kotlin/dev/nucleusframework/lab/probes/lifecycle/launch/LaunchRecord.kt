package dev.nucleusframework.lab.probes.lifecycle.launch

import androidx.compose.runtime.Immutable
import dev.nucleusframework.lab.core.format.formatDurationMillis
import dev.nucleusframework.lab.core.mvi.append
import dev.nucleusframework.lab.core.process.ProcessUpdate

/** A second instance started from a probe, folded from its [ProcessUpdate]s. */
@Immutable
data class LaunchRecord(
    val id: Int,
    val purpose: String,
    val epochMillis: Long,
    val pid: Long? = null,
    val command: List<String> = emptyList(),
    val output: List<String> = emptyList(),
    val exitCode: Int? = null,
    val aliveMillis: Long? = null,
    val failure: String? = null,
) {
    val running: Boolean get() = failure == null && exitCode == null

    fun apply(update: ProcessUpdate): LaunchRecord =
        when (update) {
            is ProcessUpdate.Started -> copy(pid = update.pid, command = update.command)
            is ProcessUpdate.Output -> copy(output = output.append(update.line, OUTPUT_LINES))
            is ProcessUpdate.Exited -> copy(exitCode = update.code, aliveMillis = update.aliveMillis)
            is ProcessUpdate.Failed -> copy(failure = update.reason)
        }

    /** One line for the observed list. */
    val summary: String
        get() =
            when {
                failure != null -> "failed to start: $failure"
                exitCode == null -> "running, pid $pid"
                else -> "pid $pid exited with $exitCode after ${formatDurationMillis(aliveMillis ?: 0)}"
            }

    private companion object {
        const val OUTPUT_LINES = 40
    }
}
