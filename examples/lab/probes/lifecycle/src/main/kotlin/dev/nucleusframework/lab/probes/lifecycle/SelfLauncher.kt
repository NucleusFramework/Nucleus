package dev.nucleusframework.lab.probes.lifecycle

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.process.JvmCommand
import dev.nucleusframework.lab.core.process.ProcessUpdate
import dev.nucleusframework.lab.core.process.runStreaming
import dev.nucleusframework.lab.core.process.runTool
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts another instance of this very app — the packaged launcher when there is one, the
 * same JVM and classpath in a dev run — or hands a URI to the OS, which is what a click on a
 * link in a browser does.
 */
interface SelfLauncher {
    fun launch(args: List<String>): Flow<ProcessUpdate>

    /** `open` / `xdg-open` / `start`: routed through the OS's URL scheme registry. Blocking. */
    fun openWithOs(uri: String): Result<String>

    fun killAll(): Int
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ProcessSelfLauncher : SelfLauncher {
    private val alive = ConcurrentHashMap<Long, Process>()

    override fun launch(args: List<String>): Flow<ProcessUpdate> =
        flow {
            val command =
                runCatching { JvmCommand.self(args) }.getOrElse {
                    emit(ProcessUpdate.Failed(it.summary))
                    return@flow
                }
            var pid: Long? = null
            try {
                runStreaming(command).collect { update ->
                    if (update is ProcessUpdate.Started) {
                        pid = update.pid
                        alive[update.pid] = update.process
                    }
                    emit(update)
                }
            } finally {
                pid?.let(alive::remove)
            }
        }

    override fun openWithOs(uri: String): Result<String> {
        val command =
            when (Platform.Current) {
                Platform.MacOS -> listOf("open", uri)
                Platform.Windows -> listOf("cmd", "/c", "start", "", uri)
                else -> listOf("xdg-open", uri)
            }
        val result = runTool(command)
        return if (result.ok) {
            Result.success(command.joinToString(" "))
        } else {
            val reason =
                result.error ?: "${command.first()} exited with ${result.exitCode}" +
                    result.output
                        .takeIf { it.isNotEmpty() }
                        ?.let { ": $it" }
                        .orEmpty()
            Result.failure(IllegalStateException(reason))
        }
    }

    override fun killAll(): Int {
        val processes = alive.values.toList()
        processes.forEach { it.destroy() }
        return processes.size
    }
}
