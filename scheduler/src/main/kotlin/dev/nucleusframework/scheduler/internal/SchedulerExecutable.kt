package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.scheduler.DesktopBootReceiver
import dev.nucleusframework.scheduler.SchedulerConfig
import dev.nucleusframework.scheduler.TaskId

/**
 * Resolves the command the OS scheduler should invoke for a task.
 *
 * Honours the [SchedulerConfig] overrides and falls back to the running executable,
 * so all three platform backends agree on what gets registered with the OS.
 */
internal object SchedulerExecutable {
    /** The program to invoke, or `null` if it cannot be resolved. */
    val path: String?
        get() =
            SchedulerConfig.executablePath?.takeIf { it.isNotBlank() }
                ?: ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)

    /** Extra arguments placed before the scheduler flag. */
    val arguments: List<String>
        get() = SchedulerConfig.executableArguments

    /** The argument list following the executable: extra args, then the scheduler flag. */
    fun argumentsFor(taskId: TaskId): List<String> = arguments + listOf(DesktopBootReceiver.SCHEDULER_ARG, taskId.value)

    /** The full command line (executable + [argumentsFor]), or `null` if [path] is unresolved. */
    fun commandLine(taskId: TaskId): List<String>? = path?.let { listOf(it) + argumentsFor(taskId) }
}
