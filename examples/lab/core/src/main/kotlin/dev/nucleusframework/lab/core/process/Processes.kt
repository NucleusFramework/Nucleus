package dev.nucleusframework.lab.core.process

import dev.nucleusframework.lab.core.format.summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val READER_GRACE_MILLIS = 1_000L

/** What a child process did, as it happens. */
sealed interface ProcessUpdate {
    data class Started(
        val pid: Long,
        val command: List<String>,
        val process: Process,
    ) : ProcessUpdate {
        override fun toString(): String = "Started(pid=$pid)"
    }

    data class Output(
        val line: String,
    ) : ProcessUpdate

    data class Exited(
        val code: Int,
        val aliveMillis: Long,
    ) : ProcessUpdate

    data class Failed(
        val reason: String,
    ) : ProcessUpdate
}

/** Starts [command] (stderr merged into stdout) and streams its lines and exit code; cancelling destroys it. */
fun runStreaming(command: List<String>): Flow<ProcessUpdate> =
    callbackFlow {
        val startedAt = System.currentTimeMillis()
        val process =
            runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
                .getOrElse {
                    trySend(ProcessUpdate.Failed(it.summary))
                    close()
                    awaitClose()
                    return@callbackFlow
                }
        trySend(ProcessUpdate.Started(process.pid(), command, process))
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { trySend(ProcessUpdate.Output(it)) } }
        trySend(ProcessUpdate.Exited(process.waitFor(), System.currentTimeMillis() - startedAt))
        close()
        awaitClose { if (process.isAlive) process.destroy() }
    }.flowOn(Dispatchers.IO)

/** Result of a short-lived OS tool (`pmset`, `ps`, `powercfg`, `open`). */
data class ToolResult(
    val command: List<String>,
    val exitCode: Int?,
    val output: String,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && exitCode == 0
}

/** Runs a short-lived tool to completion (blocking: call from IO), killed after [timeoutMillis]. */
fun runTool(
    command: List<String>,
    timeoutMillis: Long = 5_000,
): ToolResult =
    runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Read on a thread of its own: a tool that hangs with stdout open must not block past the timeout.
        val output = StringBuilder()
        val reader =
            thread(
                isDaemon = true,
                name = "lab-tool-output",
            ) { output.append(process.inputStream.bufferedReader().readText()) }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(READER_GRACE_MILLIS)
            ToolResult(command, null, output.toString().trim(), "timed out after $timeoutMillis ms")
        } else {
            reader.join(READER_GRACE_MILLIS)
            ToolResult(command, process.exitValue(), output.toString().trim())
        }
    }.getOrElse { ToolResult(command, null, "", it.summary) }
