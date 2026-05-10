package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.scheduler.Constraints
import dev.nucleusframework.nucleus.scheduler.LastTaskResult
import dev.nucleusframework.nucleus.scheduler.NetworkType
import dev.nucleusframework.nucleus.scheduler.TaskContext
import dev.nucleusframework.nucleus.scheduler.TaskData
import dev.nucleusframework.nucleus.scheduler.TaskId
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties

/**
 * Persists per-task metadata (inputData, run count, last result, timestamps)
 * as `.properties` files in `~/.local/share/nucleus/scheduler/<appId>/`.
 */
@Suppress("TooManyFunctions")
internal object TaskMetadataStore {
    private val JSON: Json = Json { ignoreUnknownKeys = true }

    private const val KEY_INPUT_DATA = "_inputDataJson"
    private const val KEY_RUN_COUNT = "_runCount"
    private const val KEY_RUN_ATTEMPT = "_runAttemptCount"
    private const val KEY_LAST_RUN_MS = "_lastRunMs"
    private const val KEY_LAST_RESULT = "_lastResult"
    private const val KEY_SCHED_INTERVAL = "_schedInterval"
    private const val KEY_SCHED_CAL_DAY = "_schedCalDay"
    private const val KEY_SCHED_CAL_HOUR = "_schedCalHour"
    private const val KEY_SCHED_CAL_MINUTE = "_schedCalMinute"
    private const val KEY_SCHED_CAL_DAYS = "_schedCalDays"
    private const val KEY_TASK_TYPE = "_taskType"
    private const val KEY_CONSTRAINT_NETWORK = "_constraint_networkType"
    private const val KEY_CONSTRAINT_BATTERY = "_constraint_batteryNotLow"
    private const val KEY_CONSTRAINT_CHARGING = "_constraint_charging"
    private const val KEY_CONSTRAINT_IDLE = "_constraint_deviceIdle"
    private const val KEY_CONSTRAINT_MIN_STORAGE = "_constraint_minStorageBytes"

    fun storeDir(appId: String): File {
        val baseDir =
            when (Platform.Current) {
                Platform.Windows ->
                    System.getenv("LOCALAPPDATA")
                        ?: "${System.getProperty("user.home")}\\AppData\\Local"
                Platform.MacOS ->
                    "${System.getProperty("user.home")}/Library/Application Support"
                else ->
                    System.getenv("XDG_DATA_HOME")
                        ?: "${System.getProperty("user.home")}/.local/share"
            }
        return File(baseDir, "nucleus/scheduler/$appId")
    }

    private fun taskFile(
        appId: String,
        taskId: TaskId,
    ): File = File(storeDir(appId), "${taskId.value}.properties")

    fun save(
        appId: String,
        taskId: TaskId,
        inputData: TaskData,
    ) {
        val file = taskFile(appId, taskId)
        file.parentFile.mkdirs()
        val existing = load(file)
        if (inputData.json != null) {
            existing.setProperty(KEY_INPUT_DATA, inputData.json)
        } else {
            existing.remove(KEY_INPUT_DATA)
        }
        file.outputStream().use { existing.store(it, null) }
    }

    fun loadContext(
        appId: String,
        taskId: TaskId,
    ): TaskContext {
        val props = load(taskFile(appId, taskId))
        val runAttempt = props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1
        return TaskContext(
            taskId = taskId,
            rawInputData = TaskData(props.getProperty(KEY_INPUT_DATA)),
            runAttemptCount = runAttempt,
        )
    }

    private fun writeLastResult(
        props: Properties,
        result: LastTaskResult,
    ) {
        props.setProperty(KEY_LAST_RUN_MS, System.currentTimeMillis().toString())
        props.setProperty(KEY_LAST_RESULT, JSON.encodeToString(LastTaskResult.serializer(), result))
    }

    fun recordSuccess(
        appId: String,
        taskId: TaskId,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        val runCount = (props.getProperty(KEY_RUN_COUNT)?.toIntOrNull() ?: 0) + 1
        props.setProperty(KEY_RUN_COUNT, runCount.toString())
        props.setProperty(KEY_RUN_ATTEMPT, "1")
        writeLastResult(props, LastTaskResult.Success)
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun recordFailure(
        appId: String,
        taskId: TaskId,
        message: String,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        props.setProperty(KEY_RUN_ATTEMPT, "1")
        writeLastResult(props, LastTaskResult.Failure(message))
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun recordRetry(
        appId: String,
        taskId: TaskId,
        message: String,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        val attempt = (props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1) + 1
        props.setProperty(KEY_RUN_ATTEMPT, attempt.toString())
        writeLastResult(props, LastTaskResult.Retry(message))
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    fun getRunCount(
        appId: String,
        taskId: TaskId,
    ): Int {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_RUN_COUNT)?.toIntOrNull() ?: 0
    }

    fun getLastRunMs(
        appId: String,
        taskId: TaskId,
    ): Long? {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_LAST_RUN_MS)?.toLongOrNull()
    }

    fun getLastResult(
        appId: String,
        taskId: TaskId,
    ): LastTaskResult? {
        val raw = load(taskFile(appId, taskId)).getProperty(KEY_LAST_RESULT) ?: return null
        return runCatching { JSON.decodeFromString(LastTaskResult.serializer(), raw) }.getOrNull()
    }

    fun getRunAttemptCount(
        appId: String,
        taskId: TaskId,
    ): Int {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1
    }

    fun delete(
        appId: String,
        taskId: TaskId,
    ) {
        taskFile(appId, taskId).delete()
    }

    fun deleteAll(appId: String) {
        val dir = storeDir(appId)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Persists schedule parameters so [MacOSLaunchdScheduler] can compute
     * the next fire time without re-parsing the plist.
     */
    fun saveScheduleHint(
        appId: String,
        taskId: TaskId,
        hint: ScheduleHint,
    ) {
        val file = taskFile(appId, taskId)
        file.parentFile.mkdirs()
        val props = load(file)
        props.setProperty(KEY_SCHED_INTERVAL, hint.intervalSeconds.toString())
        props.setProperty(KEY_SCHED_CAL_DAY, hint.calendarDay.toString())
        props.setProperty(KEY_SCHED_CAL_HOUR, hint.calendarHour.toString())
        props.setProperty(KEY_SCHED_CAL_MINUTE, hint.calendarMinute.toString())
        if (hint.calendarDays != null) {
            props.setProperty(KEY_SCHED_CAL_DAYS, hint.calendarDays.joinToString(","))
        } else {
            props.remove(KEY_SCHED_CAL_DAYS)
        }
        file.outputStream().use { props.store(it, null) }
    }

    fun loadScheduleHint(
        appId: String,
        taskId: TaskId,
    ): ScheduleHint? {
        val props = load(taskFile(appId, taskId))
        val interval = props.getProperty(KEY_SCHED_INTERVAL)?.toIntOrNull() ?: return null
        return ScheduleHint(
            intervalSeconds = interval,
            calendarDay = props.getProperty(KEY_SCHED_CAL_DAY)?.toIntOrNull() ?: -1,
            calendarHour = props.getProperty(KEY_SCHED_CAL_HOUR)?.toIntOrNull() ?: -1,
            calendarMinute = props.getProperty(KEY_SCHED_CAL_MINUTE)?.toIntOrNull() ?: -1,
            calendarDays =
                props
                    .getProperty(KEY_SCHED_CAL_DAYS)
                    ?.split(",")
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toIntArray(),
        )
    }

    data class ScheduleHint(
        val intervalSeconds: Int,
        val calendarDay: Int,
        val calendarHour: Int,
        val calendarMinute: Int,
        val calendarDays: IntArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScheduleHint) return false
            return intervalSeconds == other.intervalSeconds &&
                calendarDay == other.calendarDay &&
                calendarHour == other.calendarHour &&
                calendarMinute == other.calendarMinute &&
                calendarDays.contentEquals(other.calendarDays)
        }

        override fun hashCode(): Int {
            var result = intervalSeconds
            result = 31 * result + calendarDay
            result = 31 * result + calendarHour
            result = 31 * result + calendarMinute
            result = 31 * result + (calendarDays?.contentHashCode() ?: 0)
            return result
        }
    }

    fun saveTaskType(
        appId: String,
        taskId: TaskId,
        type: String,
    ) {
        val file = taskFile(appId, taskId)
        file.parentFile.mkdirs()
        val props = load(file)
        props.setProperty(KEY_TASK_TYPE, type)
        file.outputStream().use { props.store(it, null) }
    }

    fun loadTaskType(
        appId: String,
        taskId: TaskId,
    ): String? {
        val props = load(taskFile(appId, taskId))
        return props.getProperty(KEY_TASK_TYPE)
    }

    fun saveConstraints(
        appId: String,
        taskId: TaskId,
        constraints: Constraints,
    ) {
        val file = taskFile(appId, taskId)
        file.parentFile.mkdirs()
        val props = load(file)
        if (constraints.requiredNetworkType != NetworkType.NOT_REQUIRED) {
            props.setProperty(KEY_CONSTRAINT_NETWORK, constraints.requiredNetworkType.name)
        } else {
            props.remove(KEY_CONSTRAINT_NETWORK)
        }
        props.setProperty(KEY_CONSTRAINT_BATTERY, constraints.requiresBatteryNotLow.toString())
        props.setProperty(KEY_CONSTRAINT_CHARGING, constraints.requiresCharging.toString())
        props.setProperty(KEY_CONSTRAINT_IDLE, constraints.requiresDeviceIdle.toString())
        val minStorage = constraints.minimumStorageBytes
        if (minStorage != null) {
            props.setProperty(KEY_CONSTRAINT_MIN_STORAGE, minStorage.toString())
        } else {
            props.remove(KEY_CONSTRAINT_MIN_STORAGE)
        }
        file.outputStream().use { props.store(it, null) }
    }

    fun loadConstraints(
        appId: String,
        taskId: TaskId,
    ): Constraints {
        val props = load(taskFile(appId, taskId))
        return Constraints(
            requiredNetworkType =
                props
                    .getProperty(KEY_CONSTRAINT_NETWORK)
                    ?.let { runCatching { NetworkType.valueOf(it) }.getOrNull() }
                    ?: NetworkType.NOT_REQUIRED,
            requiresBatteryNotLow = props.getProperty(KEY_CONSTRAINT_BATTERY)?.toBooleanStrictOrNull() ?: false,
            requiresCharging = props.getProperty(KEY_CONSTRAINT_CHARGING)?.toBooleanStrictOrNull() ?: false,
            requiresDeviceIdle = props.getProperty(KEY_CONSTRAINT_IDLE)?.toBooleanStrictOrNull() ?: false,
            minimumStorageBytes = props.getProperty(KEY_CONSTRAINT_MIN_STORAGE)?.toLongOrNull(),
        )
    }

    fun recordConstraintSkip(
        appId: String,
        taskId: TaskId,
        unsatisfied: Set<String>,
        incrementAttempt: Boolean = false,
    ) {
        val file = taskFile(appId, taskId)
        val props = load(file)
        if (incrementAttempt) {
            val attempt = (props.getProperty(KEY_RUN_ATTEMPT)?.toIntOrNull() ?: 1) + 1
            props.setProperty(KEY_RUN_ATTEMPT, attempt.toString())
        }
        writeLastResult(props, LastTaskResult.ConstraintsNotMet(unsatisfied))
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, null) }
    }

    /** Lists all task IDs that have metadata stored. */
    fun listTaskIds(appId: String): List<TaskId> {
        val dir = storeDir(appId)
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.extension == "properties" }
            ?.mapNotNull { runCatching { TaskId(it.nameWithoutExtension) }.getOrNull() }
            ?: emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(file: File): Properties {
        val props = Properties()
        if (file.isFile) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (_: Exception) {
                // Corrupted file — start fresh
            }
        }
        return props
    }
}
