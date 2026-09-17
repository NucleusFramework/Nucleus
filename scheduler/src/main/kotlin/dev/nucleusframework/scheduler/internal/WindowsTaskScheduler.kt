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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger

/**
 * Windows implementation using the Task Scheduler 2.0 COM API via JNI.
 *
 * Tasks are created under a `\Nucleus\{appId}\` folder in the Windows Task Scheduler.
 * Each task invokes the application executable with `--nucleus-scheduler-run {taskId}`.
 *
 * Requires `nucleus_scheduler.dll` — if the native library is not loaded, all operations
 * return failure (the scheduler is effectively unavailable).
 */
@OptIn(InternalSchedulerApi::class)
@Suppress("TooManyFunctions")
internal object WindowsTaskScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(WindowsTaskScheduler::class.java.name)
    private const val TASK_FOLDER = "Nucleus"
    private const val HOURLY_INTERVAL_MINUTES = 60
    private const val HOURLY_DURATION_MINUTES = 60

    // macOS and Windows share the `nucleus_scheduler` library name, so the
    // JNI class can report loaded off-Windows even though COM entry points
    // exist only in the Win32 build.
    val isAvailable: Boolean
        get() = Platform.Current == Platform.Windows && WindowsTaskSchedulerJni.isLoaded

    private val appId: String
        get() = NucleusApp.appId

    // -- Task naming ----------------------------------------------------------

    private fun folderPath(): String = "\\$TASK_FOLDER\\$appId"

    private fun retryTaskName(taskId: TaskId): String = "${taskId.value}-retry"

    /** Argument string for a direct executable invocation (retry fallback path). */
    private fun arguments(taskId: TaskId): String =
        SchedulerExecutable.argumentsFor(taskId).joinToString(" ") {
            if (it.contains(' ')) "\"$it\"" else it
        }

    // -- WScript invocation ---------------------------------------------------

    private const val WSCRIPT_EXE = "wscript.exe"

    private fun wscriptArgs(script: File): String = "\"${script.absolutePath}\""

    // -- PlatformScheduler implementation -------------------------------------

    override fun enqueue(request: TaskRequest): Boolean {
        if (!isAvailable) {
            logger.warning("Native library not loaded — task '${request.taskId}' not scheduled")
            return false
        }

        if (isScheduled(request.taskId)) {
            when (request.existingTaskPolicy) {
                ExistingTaskPolicy.KEEP -> return true
                ExistingTaskPolicy.UPDATE_DATA -> {
                    persistMetadata(request)
                    return true
                }
                ExistingTaskPolicy.REPLACE -> Unit // fall through to full re-create below
            }
        }

        val execPath = SchedulerExecutable.path
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        if (request.existingTaskPolicy == ExistingTaskPolicy.REPLACE && isScheduled(request.taskId)) {
            deleteTask(request.taskId.value)
        }

        val metadataDir = TaskMetadataStore.storeDir(appId).absolutePath
        val wrapperScript =
            TaskWrapperScript.generateWindowsScript(
                appId = appId,
                taskId = request.taskId,
                execPath = execPath,
                execArgs = SchedulerExecutable.arguments,
                taskFolder = folderPath(),
                metadataDir = metadataDir,
            )

        val error = createTask(request, WSCRIPT_EXE, wscriptArgs(wrapperScript))
        if (error != null) {
            logger.warning("Failed to create task '${request.taskId}': $error")
            wrapperScript.delete()
            return false
        }

        persistMetadata(request)
        return true
    }

    private fun persistMetadata(request: TaskRequest) {
        TaskMetadataStore.save(appId, request.taskId, request.inputData)
        TaskMetadataStore.saveTaskType(appId, request.taskId, request.type.name)
        if (request.constraints.hasConstraints()) {
            TaskMetadataStore.saveConstraints(appId, request.taskId, request.constraints)
        }
    }

    override fun cancel(taskId: TaskId): Boolean {
        if (!isAvailable || !isScheduled(taskId)) return false
        val result = deleteTask(taskId.value)
        deleteTask(retryTaskName(taskId)) // Clean up retry task
        TaskWrapperScript.deleteScript(appId, taskId)
        if (result) TaskMetadataStore.delete(appId, taskId)
        return result
    }

    override fun cancelAll() {
        if (!isAvailable) return
        getAllTaskIds().forEach { taskId ->
            val deleted = deleteTask(taskId.value)
            deleteTask(retryTaskName(taskId))
            if (deleted) TaskMetadataStore.delete(appId, taskId)
        }
        TaskWrapperScript.deleteAllScripts(appId)
        // Delete the app folder (fails silently if not empty or missing)
        val error = WindowsTaskSchedulerJni.nativeDeleteFolder(folderPath())
        if (error != null) {
            logger.fine("Could not delete folder '${folderPath()}': $error")
        }
    }

    override fun isScheduled(taskId: TaskId): Boolean {
        if (!isAvailable) return false
        return WindowsTaskSchedulerJni.nativeTaskExists(folderPath(), taskId.value)
    }

    override fun getTaskInfo(taskId: TaskId): TaskInfo? {
        if (!isAvailable || !isScheduled(taskId)) return null

        val rawState = WindowsTaskSchedulerJni.nativeGetTaskState(folderPath(), taskId.value)
        val state = mapTaskState(rawState)
        val nextRunMs = WindowsTaskSchedulerJni.nativeGetTaskNextRunTime(folderPath(), taskId.value)

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = if (nextRunMs > 0) nextRunMs else null,
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> {
        if (!isAvailable) return emptyList()
        return getAllTaskIds().mapNotNull { getTaskInfo(it) }
    }

    // -- Retry support --------------------------------------------------------

    fun scheduleRetry(
        taskId: TaskId,
        delaySeconds: Long,
    ): Boolean {
        if (!isAvailable) return false
        val execPath = SchedulerExecutable.path ?: return false
        val startTime = LocalDateTime.now().plusSeconds(delaySeconds)
        val startBoundary = startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        // Delete any previous retry task
        deleteTask(retryTaskName(taskId))

        val wrapperScript = TaskWrapperScript.scriptFile(appId, taskId)
        val retryExe: String
        val retryArgs: String
        if (wrapperScript.exists()) {
            retryExe = WSCRIPT_EXE
            retryArgs = wscriptArgs(wrapperScript)
        } else {
            retryExe = execPath
            retryArgs = arguments(taskId)
        }

        val error =
            WindowsTaskSchedulerJni.nativeCreateOnceTask(
                folderPath = folderPath(),
                taskName = retryTaskName(taskId),
                exePath = retryExe,
                arguments = retryArgs,
                startBoundary = startBoundary,
            )
        if (error != null) {
            logger.warning("Failed to schedule retry for '$taskId': $error")
            return false
        }
        return true
    }

    // -- Task creation --------------------------------------------------------

    private fun createTask(
        request: TaskRequest,
        exePath: String,
        args: String,
    ): String? {
        val folder = folderPath()
        val name = request.taskId.value

        return when (request.type) {
            TaskRequest.Type.PERIODIC -> {
                val minutes = request.interval!!.inWholeMinutes.toInt()
                val startDelay = if (request.runImmediately) 0 else minutes
                WindowsTaskSchedulerJni.nativeCreatePeriodicTask(
                    folder,
                    name,
                    exePath,
                    args,
                    minutes,
                    startDelay,
                )
            }

            TaskRequest.Type.CALENDAR -> {
                val schedule = parseCronExpression(request.cronExpression!!.expression)
                if (schedule == null) {
                    "Unsupported cron expression '${request.cronExpression}'"
                } else {
                    when (schedule) {
                        is CronSchedule.Hourly ->
                            WindowsTaskSchedulerJni.nativeCreatePeriodicTask(
                                folder,
                                name,
                                exePath,
                                args,
                                HOURLY_INTERVAL_MINUTES,
                                HOURLY_DURATION_MINUTES,
                            )
                        is CronSchedule.Daily ->
                            WindowsTaskSchedulerJni.nativeCreateDailyTask(
                                folder,
                                name,
                                exePath,
                                args,
                                schedule.hour,
                                schedule.minute,
                            )
                        is CronSchedule.Weekly ->
                            WindowsTaskSchedulerJni.nativeCreateWeeklyTask(
                                folder,
                                name,
                                exePath,
                                args,
                                schedule.daysOfWeek,
                                schedule.hour,
                                schedule.minute,
                            )
                    }
                }
            }

            TaskRequest.Type.ON_BOOT -> {
                WindowsTaskSchedulerJni.nativeCreateLogonTask(
                    folder,
                    name,
                    exePath,
                    args,
                )
            }
        }
    }

    // -- Cron expression parsing ----------------------------------------------

    /**
     * Parses a systemd OnCalendar expression into a [CronSchedule]
     * that maps directly to a Task Scheduler trigger type.
     *
     * Supported patterns:
     * - `*-*-* HH:MM:00`            → [CronSchedule.Daily]
     * - `*-*-* *:00:00`             → [CronSchedule.Hourly]
     * - `Mon *-*-* HH:MM:00`        → [CronSchedule.Weekly] (single day)
     * - `Mon..Fri *-*-* HH:MM:00`   → [CronSchedule.Weekly] (day range)
     *
     * Returns `null` for unsupported expressions.
     */
    @Suppress("CyclomaticComplexity", "ReturnCount")
    internal fun parseCronExpression(expression: String): CronSchedule? {
        val trimmed = expression.trim()

        // Hourly: *-*-* *:00:00
        if (trimmed == "*-*-* *:00:00") {
            return CronSchedule.Hourly
        }

        // Day range: Mon..Fri *-*-* HH:MM:00
        val rangeMatch =
            Regex("""^(\w{3})\.\.(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
                .matchEntire(trimmed)
        if (rangeMatch != null) {
            val destructured = rangeMatch.destructured
            val startDay = destructured.component1()
            val endDay = destructured.component2()
            val hour = destructured.component3()
            val minute = destructured.component4()
            val bitmask = expandDayRangeBitmask(startDay, endDay) ?: return null
            return CronSchedule.Weekly(bitmask, hour.toInt(), minute.toInt())
        }

        // Single weekday: Mon *-*-* HH:MM:00
        val weekdayMatch =
            Regex("""^(\w{3})\s+\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
                .matchEntire(trimmed)
        if (weekdayMatch != null) {
            val (day, hour, minute) = weekdayMatch.destructured
            val bit = DAY_BITS[day.uppercase()] ?: return null
            return CronSchedule.Weekly(bit, hour.toInt(), minute.toInt())
        }

        // Daily: *-*-* HH:MM:00
        val dailyMatch =
            Regex("""^\*-\*-\*\s+(\d{2}):(\d{2}):\d{2}$""")
                .matchEntire(trimmed)
        if (dailyMatch != null) {
            val (hour, minute) = dailyMatch.destructured
            return CronSchedule.Daily(hour.toInt(), minute.toInt())
        }

        return null
    }

    // -- Day-of-week helpers --------------------------------------------------

    private val DAY_BITS =
        mapOf(
            "SUN" to WindowsTaskSchedulerJni.SUNDAY,
            "MON" to WindowsTaskSchedulerJni.MONDAY,
            "TUE" to WindowsTaskSchedulerJni.TUESDAY,
            "WED" to WindowsTaskSchedulerJni.WEDNESDAY,
            "THU" to WindowsTaskSchedulerJni.THURSDAY,
            "FRI" to WindowsTaskSchedulerJni.FRIDAY,
            "SAT" to WindowsTaskSchedulerJni.SATURDAY,
        )

    private val ORDERED_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    private fun expandDayRangeBitmask(
        start: String,
        end: String,
    ): Int? {
        val startIdx = ORDERED_DAYS.indexOf(start.uppercase())
        val endIdx = ORDERED_DAYS.indexOf(end.uppercase())
        if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null
        return ORDERED_DAYS.subList(startIdx, endIdx + 1).sumOf { DAY_BITS[it]!! }
    }

    // -- Task state mapping ---------------------------------------------------

    private fun mapTaskState(rawState: Int): TaskState =
        when (rawState) {
            WindowsTaskSchedulerJni.TASK_STATE_RUNNING -> TaskState.RUNNING
            WindowsTaskSchedulerJni.TASK_STATE_DISABLED -> TaskState.INACTIVE
            WindowsTaskSchedulerJni.TASK_STATE_READY,
            WindowsTaskSchedulerJni.TASK_STATE_QUEUED,
            -> TaskState.SCHEDULED
            else -> TaskState.SCHEDULED
        }

    // -- Helpers --------------------------------------------------------------

    private fun deleteTask(taskName: String): Boolean {
        val error = WindowsTaskSchedulerJni.nativeDeleteTask(folderPath(), taskName)
        if (error != null) {
            logger.fine("Could not delete task '$taskName': $error")
            return false
        }
        return true
    }

    /**
     * Lists all task IDs using COM folder enumeration cross-referenced with
     * the metadata store. The metadata store remains the authority for tasks
     * that belong to *this* app (the folder may contain retry tasks too).
     */
    private fun getAllTaskIds(): List<TaskId> {
        val comNames =
            WindowsTaskSchedulerJni
                .nativeGetTaskNames(folderPath())
                ?.toSet() ?: emptySet()
        // Use metadata store IDs, filtered by actual COM presence
        return TaskMetadataStore.listTaskIds(appId).filter { it.value in comNames }
    }
}

/**
 * Parsed cron schedule that maps directly to a Task Scheduler trigger.
 */
internal sealed class CronSchedule {
    data object Hourly : CronSchedule()

    data class Daily(
        val hour: Int,
        val minute: Int,
    ) : CronSchedule()

    data class Weekly(
        val daysOfWeek: Int,
        val hour: Int,
        val minute: Int,
    ) : CronSchedule()
}
