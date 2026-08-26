package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskInfo
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * macOS implementation using launchd user agents.
 *
 * Generated plist files go to `~/Library/LaunchAgents/` and are managed via
 * native JNI bridge (NSDictionary + SMJobCopyDictionary + NSTask) when available,
 * falling back to shell commands otherwise.
 *
 * Each task creates a plist with label
 * `dev.nucleusframework.{appId}.{taskId}`.
 */
@OptIn(InternalSchedulerApi::class)
@Suppress("TooManyFunctions")
internal object MacOSLaunchdScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(MacOSLaunchdScheduler::class.java.name)
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val LABEL_PREFIX = "dev.nucleusframework"
    private const val CAL_NOT_SET = -1

    // The scheduler JNI library name is shared across OS; macOS symbols
    // exist only in the Darwin build.
    private val useNative: Boolean =
        Platform.Current == Platform.MacOS && MacOSLaunchdSchedulerJni.isLoaded

    private val launchAgentsDir: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents")

    private val appId: String
        get() = NucleusApp.appId

    // -- Naming ---------------------------------------------------------------

    internal fun label(taskId: TaskId): String = "$LABEL_PREFIX.$appId.${taskId.value}"

    private fun retryLabel(taskId: TaskId): String = "$LABEL_PREFIX.$appId.${taskId.value}-retry"

    private fun plistFileName(taskId: TaskId): String = "${label(taskId)}.plist"

    private fun retryPlistFileName(taskId: TaskId): String = "${retryLabel(taskId)}.plist"

    private fun plistFile(taskId: TaskId): File = File(launchAgentsDir, plistFileName(taskId))

    private fun retryPlistFile(taskId: TaskId): File = File(launchAgentsDir, retryPlistFileName(taskId))

    // -- Calendar config extraction -------------------------------------------

    /**
     * Structured representation of a launchd calendar interval,
     * extracted from a systemd OnCalendar expression.
     */
    internal data class CalendarConfig(
        val day: Int = CAL_NOT_SET,
        val hour: Int = CAL_NOT_SET,
        val minute: Int = CAL_NOT_SET,
        val days: IntArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CalendarConfig) return false
            return day == other.day &&
                hour == other.hour &&
                minute == other.minute &&
                days.contentEquals(other.days)
        }

        override fun hashCode(): Int {
            var result = day
            result = 31 * result + hour
            result = 31 * result + minute
            result = 31 * result + (days?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Parses a systemd OnCalendar expression into structured calendar fields
     * suitable for both JNI and shell plist generation.
     *
     * @throws IllegalArgumentException if the expression is not supported
     */
    internal fun parseCalendarConfig(expression: String): CalendarConfig {
        val trimmed = expression.trim()

        // Pattern: every hour — *-*-* *:00:00
        if (trimmed == "*-*-* *:00:00") {
            return CalendarConfig(minute = 0)
        }

        // Pattern: day range (Mon..Fri) with time
        val rangeMatch =
            Regex("""^(\w{3})\.\.(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
                .matchEntire(trimmed)
        if (rangeMatch != null) {
            val destructured = rangeMatch.destructured
            val days = expandDayRange(destructured.component1(), destructured.component2())
            if (days != null) {
                return CalendarConfig(
                    hour = destructured.component3().toInt(),
                    minute = destructured.component4().toInt(),
                    days = days.toIntArray(),
                )
            }
        }

        // Pattern: specific weekday with time — Mon *-*-* HH:MM:00
        val weekdayMatch =
            Regex("""^(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
                .matchEntire(trimmed)
        if (weekdayMatch != null) {
            val (day, hour, minute) = weekdayMatch.destructured
            val dayNum = dayToLaunchdWeekday(day)
            if (dayNum != null) {
                return CalendarConfig(day = dayNum, hour = hour.toInt(), minute = minute.toInt())
            }
        }

        // Pattern: daily at specific time — *-*-* HH:MM:00
        val dailyMatch = Regex("""^\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""").matchEntire(trimmed)
        if (dailyMatch != null) {
            val (hour, minute) = dailyMatch.destructured
            return CalendarConfig(hour = hour.toInt(), minute = minute.toInt())
        }

        throw IllegalArgumentException(
            "Unsupported cron expression '$expression' for macOS launchd. " +
                "Supported patterns: '*-*-* HH:MM:00', '*-*-* *:00:00', " +
                "'Mon *-*-* HH:MM:00', 'Mon..Fri *-*-* HH:MM:00'. " +
                "Use CronExpression factory methods for cross-platform compatibility.",
        )
    }

    // -- PlatformScheduler implementation -------------------------------------

    private fun persistMetadata(request: TaskRequest) {
        TaskMetadataStore.save(appId, request.taskId, request.inputData)
        TaskMetadataStore.saveTaskType(appId, request.taskId, request.type.name)
        if (request.constraints.hasConstraints()) {
            TaskMetadataStore.saveConstraints(appId, request.taskId, request.constraints)
        }
    }

    override fun enqueue(request: TaskRequest): Boolean {
        if (isScheduled(request.taskId)) {
            when (request.existingTaskPolicy) {
                ExistingTaskPolicy.KEEP -> return true
                ExistingTaskPolicy.UPDATE_DATA -> {
                    persistMetadata(request)
                    return true
                }
                ExistingTaskPolicy.REPLACE -> Unit // fall through to full re-create
            }
        }

        val command = SchedulerExecutable.commandLine(request.taskId)
        if (command == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        // If replacing, unload and remove the existing plist first
        if (request.existingTaskPolicy == ExistingTaskPolicy.REPLACE && isScheduled(request.taskId)) {
            unloadAndDelete(request.taskId)
        }

        persistMetadata(request)

        return if (useNative) {
            enqueueNative(request, command)
        } else {
            enqueueShell(request, command)
        }
    }

    private fun enqueueNative(
        request: TaskRequest,
        command: List<String>,
    ): Boolean {
        val plistPath = plistFile(request.taskId).absolutePath
        val programArgs = command.toTypedArray()

        var intervalSeconds = 0
        var calDay = CAL_NOT_SET
        var calHour = CAL_NOT_SET
        var calMinute = CAL_NOT_SET
        var calDays: IntArray? = null
        var runAtLoad = false

        when (request.type) {
            TaskRequest.Type.PERIODIC -> {
                intervalSeconds = request.interval!!.inWholeSeconds.toInt()
                runAtLoad = request.runImmediately
            }
            TaskRequest.Type.CALENDAR -> {
                val config =
                    try {
                        parseCalendarConfig(request.cronExpression!!.expression)
                    } catch (e: IllegalArgumentException) {
                        logger.warning("Task '${request.taskId}' not scheduled: ${e.message}")
                        return false
                    }
                calDay = config.day
                calHour = config.hour
                calMinute = config.minute
                calDays = config.days
            }
            TaskRequest.Type.ON_BOOT -> {
                runAtLoad = true
            }
        }

        // Persist schedule config so getTaskInfo() can compute next fire time
        TaskMetadataStore.saveScheduleHint(
            appId,
            request.taskId,
            TaskMetadataStore.ScheduleHint(intervalSeconds, calDay, calHour, calMinute, calDays),
        )

        val writeError =
            MacOSLaunchdSchedulerJni.nativeWritePlist(
                plistPath,
                label(request.taskId),
                programArgs,
                intervalSeconds,
                calDay,
                calHour,
                calMinute,
                runAtLoad,
                calDays,
            )
        if (writeError != null) {
            logger.warning("Failed to write plist for task '${request.taskId}': $writeError")
            return false
        }

        val loadError = MacOSLaunchdSchedulerJni.nativeLaunchctlLoad(plistPath)
        if (loadError != null) {
            logger.warning("Failed to load task '${request.taskId}': $loadError")
            return false
        }
        return true
    }

    private fun enqueueShell(
        request: TaskRequest,
        command: List<String>,
    ): Boolean {
        launchAgentsDir.mkdirs()

        val plistContent =
            try {
                buildPlist(request, command)
            } catch (e: IllegalArgumentException) {
                logger.warning("Task '${request.taskId}' not scheduled: ${e.message}")
                return false
            }
        val file = plistFile(request.taskId)
        file.writeText(plistContent)

        return launchctl("load", "-w", file.absolutePath)
    }

    override fun cancel(taskId: TaskId): Boolean {
        val file = plistFile(taskId)
        if (!file.exists()) return false

        unloadAndDelete(taskId)
        // Also clean up any pending retry
        val retryFile = retryPlistFile(taskId)
        if (retryFile.exists()) {
            if (useNative) {
                MacOSLaunchdSchedulerJni.nativeLaunchctlUnload(retryFile.absolutePath)
                MacOSLaunchdSchedulerJni.nativeDeleteFile(retryFile.absolutePath)
            } else {
                launchctl("unload", retryFile.absolutePath)
                retryFile.delete()
            }
        }
        TaskMetadataStore.delete(appId, taskId)
        return true
    }

    override fun cancelAll() {
        val allIds = listScheduledTaskIds()
        allIds.forEach { cancel(it) }
        TaskMetadataStore.deleteAll(appId)
    }

    override fun isScheduled(taskId: TaskId): Boolean = plistFile(taskId).exists()

    override fun getTaskInfo(taskId: TaskId): TaskInfo? {
        if (!plistFile(taskId).exists()) return null

        val state = if (isLoaded(label(taskId))) TaskState.SCHEDULED else TaskState.INACTIVE

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = computeNextRunMs(taskId),
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> = listScheduledTaskIds().mapNotNull { getTaskInfo(it) }

    // -- Next fire time -------------------------------------------------------

    /**
     * Computes the next fire time for a task by re-reading its schedule config.
     * Returns null when the native library is unavailable or the config cannot be parsed.
     */
    private fun computeNextRunMs(taskId: TaskId): Long? {
        if (!useNative) return null

        val metadata = TaskMetadataStore.loadScheduleHint(appId, taskId) ?: return null

        val result =
            MacOSLaunchdSchedulerJni.nativeComputeNextFireTime(
                metadata.intervalSeconds,
                metadata.calendarDay,
                metadata.calendarHour,
                metadata.calendarMinute,
                metadata.calendarDays,
            )
        return if (result > 0) result else null
    }

    // -- Retry support --------------------------------------------------------

    /**
     * Schedules a one-shot retry that fires after [delaySeconds].
     *
     * When the native library is available, writes a RunAtLoad plist atomically
     * and uses dispatch_after for the delayed load. Otherwise falls back to a
     * background thread with Thread.sleep.
     *
     * The retry plist is cleaned up after execution by [cleanupRetryPlist].
     */
    fun scheduleRetry(
        taskId: TaskId,
        delaySeconds: Long,
    ): Boolean {
        val command = SchedulerExecutable.commandLine(taskId) ?: return false

        // Remove any previous retry plist
        cleanupRetryPlist(taskId)

        return if (useNative) {
            scheduleRetryNative(taskId, command, delaySeconds)
        } else {
            scheduleRetryShell(taskId, command, delaySeconds)
        }
    }

    private fun scheduleRetryNative(
        taskId: TaskId,
        command: List<String>,
        delaySeconds: Long,
    ): Boolean {
        val retryPath = retryPlistFile(taskId).absolutePath
        val programArgs = command.toTypedArray()

        val error =
            MacOSLaunchdSchedulerJni.nativeScheduleRetry(
                retryPath,
                retryLabel(taskId),
                programArgs,
                delaySeconds,
            )
        if (error != null) {
            logger.warning("Native retry scheduling failed for task '$taskId': $error")
            return false
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleRetryShell(
        taskId: TaskId,
        command: List<String>,
        delaySeconds: Long,
    ): Boolean {
        val retryFile = retryPlistFile(taskId)

        val programArgs = programArgsXml(command, indent = "    ")

        val plist =
            buildString {
                appendLine(PLIST_HEADER)
                appendLine("<dict>")
                appendLine("  <key>Label</key>")
                appendLine("  <string>${retryLabel(taskId)}</string>")
                appendLine("  <key>ProgramArguments</key>")
                appendLine("  <array>")
                appendLine(programArgs)
                appendLine("  </array>")
                appendLine("  <key>RunAtLoad</key>")
                appendLine("  <true/>")
                appendLine("  <key>KeepAlive</key>")
                appendLine("  <false/>")
                appendLine("  <key>ProcessType</key>")
                appendLine("  <string>Background</string>")
                appendLine("</dict>")
                appendLine("</plist>")
            }

        launchAgentsDir.mkdirs()
        retryFile.writeText(plist)

        val thread =
            Thread({
                try {
                    Thread.sleep(delaySeconds * MILLIS_PER_SECOND)
                    launchctl("load", retryFile.absolutePath)
                } catch (_: InterruptedException) {
                    logger.warning("Retry delay interrupted for task '$taskId'")
                }
            }, "nucleus-retry-$taskId")
        thread.isDaemon = true
        thread.start()

        return true
    }

    /**
     * Removes the retry plist for a task after it has executed.
     * Called by [DesktopBootReceiver] after a retry run completes.
     */
    fun cleanupRetryPlist(taskId: TaskId) {
        val retryFile = retryPlistFile(taskId)
        if (retryFile.exists()) {
            if (useNative) {
                MacOSLaunchdSchedulerJni.nativeLaunchctlUnload(retryFile.absolutePath)
                MacOSLaunchdSchedulerJni.nativeDeleteFile(retryFile.absolutePath)
            } else {
                launchctl("unload", retryFile.absolutePath)
                retryFile.delete()
            }
        }
    }

    private const val MILLIS_PER_SECOND = 1000L

    // -- Shell fallback: plist generation -------------------------------------

    private const val PLIST_HEADER =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">"""

    /** Renders a command line as the `<string>` entries of a `ProgramArguments` array. */
    private fun programArgsXml(
        command: List<String>,
        indent: String,
    ): String = command.joinToString("\n") { "$indent<string>${xmlEscape(it)}</string>" }

    private fun xmlEscape(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun buildPlist(
        request: TaskRequest,
        command: List<String>,
    ): String =
        buildString {
            appendLine(PLIST_HEADER)
            appendLine("<dict>")
            appendLine("  <key>Label</key>")
            appendLine("  <string>${label(request.taskId)}</string>")
            appendLine("  <key>ProgramArguments</key>")
            appendLine("  <array>")
            appendLine(programArgsXml(command, indent = "    "))
            appendLine("  </array>")

            when (request.type) {
                TaskRequest.Type.PERIODIC -> {
                    val seconds = request.interval!!.inWholeSeconds
                    appendLine("  <key>StartInterval</key>")
                    appendLine("  <integer>$seconds</integer>")
                    if (request.runImmediately) {
                        appendLine("  <key>RunAtLoad</key>")
                        appendLine("  <true/>")
                    }
                }
                TaskRequest.Type.CALENDAR -> {
                    appendCalendarInterval(this, request.cronExpression!!.expression)
                }
                TaskRequest.Type.ON_BOOT -> {
                    appendLine("  <key>RunAtLoad</key>")
                    appendLine("  <true/>")
                }
            }

            appendLine("  <key>KeepAlive</key>")
            appendLine("  <false/>")
            appendLine("  <key>ProcessType</key>")
            appendLine("  <string>Background</string>")
            appendLine("</dict>")
            appendLine("</plist>")
        }

    internal fun appendCalendarInterval(
        sb: StringBuilder,
        expression: String,
    ) {
        val config = parseCalendarConfig(expression)
        val calDays = config.days

        if (calDays != null) {
            sb.appendLine("  <key>StartCalendarInterval</key>")
            sb.appendLine("  <array>")
            for (dayNum in calDays) {
                sb.appendLine("    <dict>")
                sb.appendLine("      <key>Weekday</key>")
                sb.appendLine("      <integer>$dayNum</integer>")
                if (config.hour != CAL_NOT_SET) {
                    sb.appendLine("      <key>Hour</key>")
                    sb.appendLine("      <integer>${config.hour}</integer>")
                }
                if (config.minute != CAL_NOT_SET) {
                    sb.appendLine("      <key>Minute</key>")
                    sb.appendLine("      <integer>${config.minute}</integer>")
                }
                sb.appendLine("    </dict>")
            }
            sb.appendLine("  </array>")
        } else {
            sb.appendLine("  <key>StartCalendarInterval</key>")
            sb.appendLine("  <dict>")
            if (config.day != CAL_NOT_SET) {
                sb.appendLine("    <key>Weekday</key>")
                sb.appendLine("    <integer>${config.day}</integer>")
            }
            if (config.hour != CAL_NOT_SET) {
                sb.appendLine("    <key>Hour</key>")
                sb.appendLine("    <integer>${config.hour}</integer>")
            }
            if (config.minute != CAL_NOT_SET) {
                sb.appendLine("    <key>Minute</key>")
                sb.appendLine("    <integer>${config.minute}</integer>")
            }
            sb.appendLine("  </dict>")
        }
    }

    // -- Day mapping (launchd uses 0=Sunday, 1=Monday, ..., 6=Saturday) -------

    private const val LAUNCHD_SUNDAY = 0
    private const val LAUNCHD_MONDAY = 1
    private const val LAUNCHD_TUESDAY = 2
    private const val LAUNCHD_WEDNESDAY = 3
    private const val LAUNCHD_THURSDAY = 4
    private const val LAUNCHD_FRIDAY = 5
    private const val LAUNCHD_SATURDAY = 6

    private val dayMap =
        mapOf(
            "MON" to LAUNCHD_MONDAY,
            "TUE" to LAUNCHD_TUESDAY,
            "WED" to LAUNCHD_WEDNESDAY,
            "THU" to LAUNCHD_THURSDAY,
            "FRI" to LAUNCHD_FRIDAY,
            "SAT" to LAUNCHD_SATURDAY,
            "SUN" to LAUNCHD_SUNDAY,
        )

    private val orderedDays = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    private fun dayToLaunchdWeekday(day: String): Int? = dayMap[day.uppercase()]

    private fun expandDayRange(
        start: String,
        end: String,
    ): List<Int>? {
        val startIdx = orderedDays.indexOf(start.uppercase())
        val endIdx = orderedDays.indexOf(end.uppercase())
        if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null
        return orderedDays.subList(startIdx, endIdx + 1).mapNotNull { dayMap[it] }
    }

    // -- launchctl shell helpers (fallback) ------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun launchctl(vararg args: String): Boolean =
        try {
            val process =
                ProcessBuilder("launchctl", *args)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                logger.warning("launchctl ${args.joinToString(" ")} timed out")
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "launchctl ${args.joinToString(" ")} failed", e)
            false
        }

    private fun isLoaded(jobLabel: String): Boolean =
        if (useNative) {
            MacOSLaunchdSchedulerJni.nativeIsJobLoaded(jobLabel)
        } else {
            isLoadedShell(jobLabel)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun isLoadedShell(jobLabel: String): Boolean =
        try {
            val process =
                ProcessBuilder("launchctl", "list", jobLabel)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            val exited = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }

    private fun unloadAndDelete(taskId: TaskId) {
        val file = plistFile(taskId)
        if (file.exists()) {
            if (useNative) {
                MacOSLaunchdSchedulerJni.nativeLaunchctlUnload(file.absolutePath)
                MacOSLaunchdSchedulerJni.nativeDeleteFile(file.absolutePath)
            } else {
                launchctl("unload", file.absolutePath)
                file.delete()
            }
        }
    }

    // -- Introspection --------------------------------------------------------

    private fun listScheduledTaskIds(): List<TaskId> {
        val prefix = "$LABEL_PREFIX.$appId."
        val dir = launchAgentsDir
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".plist") }
            ?.map { it.name.removePrefix(prefix).removeSuffix(".plist") }
            ?.filter { !it.endsWith("-retry") }
            ?.mapNotNull { runCatching { TaskId(it) }.getOrNull() }
            ?: emptyList()
    }
}
