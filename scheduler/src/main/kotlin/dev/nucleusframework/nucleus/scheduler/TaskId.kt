package dev.nucleusframework.nucleus.scheduler

/**
 * Strongly-typed identifier for a scheduled task.
 *
 * Constructed from a string matching `[a-zA-Z0-9_-]+`. The value is used as-is
 * for filenames, systemd unit names, launchd labels and Windows Task Scheduler
 * task names — keeping the character set narrow avoids per-platform escaping
 * concerns.
 *
 * ```kotlin
 * val Sync = TaskId("sync")
 * scheduler.enqueue(TaskRequest.periodic(Sync, 1.hours))
 * ```
 */
@JvmInline
public value class TaskId(
    public val value: String,
) {
    init {
        require(value.isNotEmpty()) { "taskId must not be empty" }
        require(PATTERN.matches(value)) { "taskId must match [a-zA-Z0-9_-]+, got '$value'" }
    }

    override fun toString(): String = value

    public companion object {
        private val PATTERN = Regex("^[a-zA-Z0-9_-]+$")
    }
}
