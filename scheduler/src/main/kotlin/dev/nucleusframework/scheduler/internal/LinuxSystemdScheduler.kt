package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.NucleusApp
import dev.nucleusframework.scheduler.ExistingTaskPolicy
import dev.nucleusframework.scheduler.InternalSchedulerApi
import dev.nucleusframework.scheduler.TaskId
import dev.nucleusframework.scheduler.TaskInfo
import dev.nucleusframework.scheduler.TaskRequest
import dev.nucleusframework.scheduler.TaskState
import java.io.File
import java.util.logging.Logger

/**
 * Linux implementation using systemd user timers.
 *
 * Generated unit files go to `~/.config/systemd/user/` and are managed via
 * D-Bus calls to org.freedesktop.systemd1.Manager (through JNI).
 *
 * Requires `libnucleus_scheduler_linux.so` — if the native library is not loaded,
 * all operations return failure (the scheduler is effectively unavailable).
 */
@OptIn(InternalSchedulerApi::class)
@Suppress("TooManyFunctions")
internal object LinuxSystemdScheduler : PlatformScheduler {
    private val logger = Logger.getLogger(LinuxSystemdScheduler::class.java.name)

    internal const val UNIT_PREFIX = "nucleus"
    private const val USEC_PER_MS = 1000L

    val isAvailable: Boolean get() = LinuxSystemdSchedulerJni.isLoaded

    private val systemdUserDir: File
        get() {
            val configHome =
                System.getenv("XDG_CONFIG_HOME")
                    ?: "${System.getProperty("user.home")}/.config"
            return File(configHome, "systemd/user")
        }

    private val appId: String
        get() = NucleusApp.appId

    // -- Unit naming ----------------------------------------------------------

    internal fun unitBaseName(taskId: TaskId): String = "$UNIT_PREFIX-$appId-${taskId.value}"

    private fun serviceFileName(taskId: TaskId): String = "${unitBaseName(taskId)}.service"

    private fun timerFileName(taskId: TaskId): String = "${unitBaseName(taskId)}.timer"

    // -- PlatformScheduler implementation -------------------------------------

    private fun persistMetadata(request: TaskRequest) {
        if (request.inputData.isNotEmpty()) {
            TaskMetadataStore.save(appId, request.taskId, request.inputData)
        }
        TaskMetadataStore.saveTaskType(appId, request.taskId, request.type.name)
        if (request.constraints.hasConstraints()) {
            TaskMetadataStore.saveConstraints(appId, request.taskId, request.constraints)
        }
    }

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
                ExistingTaskPolicy.REPLACE -> Unit // fall through to full re-create
            }
        }

        val execPath = SchedulerExecutable.path
        if (execPath == null) {
            logger.warning("Cannot resolve executable path — task '${request.taskId}' not scheduled")
            return false
        }

        systemdUserDir.mkdirs()

        persistMetadata(request)

        // Generate wrapper script
        val serviceFile = File(systemdUserDir, serviceFileName(request.taskId))
        val timerFile = File(systemdUserDir, timerFileName(request.taskId))
        val metadataDir = TaskMetadataStore.storeDir(appId).absolutePath
        val wrapperScript =
            TaskWrapperScript.generateLinuxScript(
                appId = appId,
                taskId = request.taskId,
                execPath = execPath,
                execArgs = SchedulerExecutable.arguments,
                timerUnit = timerFileName(request.taskId),
                serviceUnit = serviceFileName(request.taskId),
                serviceFilePath = serviceFile.absolutePath,
                timerFilePath = timerFile.absolutePath,
                metadataDir = metadataDir,
            )

        // Generate .service file pointing to wrapper script
        val serviceContent = buildServiceUnit(request.taskId, wrapperScript.absolutePath)
        serviceFile.writeText(serviceContent)

        // Generate .timer file (not needed for onBoot without timer)
        val needsTimer = request.type != TaskRequest.Type.ON_BOOT
        if (needsTimer) {
            val timerContent = buildTimerUnit(request)
            File(systemdUserDir, timerFileName(request.taskId)).writeText(timerContent)
        }

        // Reload and enable via D-Bus
        LinuxSystemdSchedulerJni.nativeReload()

        return if (needsTimer) {
            val error =
                LinuxSystemdSchedulerJni.nativeEnableUnitFiles(
                    arrayOf(timerFileName(request.taskId)),
                    startNow = true,
                )
            if (error != null) {
                logger.warning("Failed to enable timer '${request.taskId}': $error")
                false
            } else {
                true
            }
        } else {
            // onBoot: enable the service to start at user login
            val error =
                LinuxSystemdSchedulerJni.nativeEnableUnitFiles(
                    arrayOf(serviceFileName(request.taskId)),
                    startNow = false,
                )
            if (error != null) {
                logger.warning("Failed to enable service '${request.taskId}': $error")
                false
            } else {
                true
            }
        }
    }

    override fun cancel(taskId: TaskId): Boolean {
        if (!isAvailable) return false

        val timerFile = File(systemdUserDir, timerFileName(taskId))
        val serviceFile = File(systemdUserDir, serviceFileName(taskId))

        if (!timerFile.exists() && !serviceFile.exists()) return false

        if (timerFile.exists()) {
            LinuxSystemdSchedulerJni.nativeDisableUnitFiles(
                arrayOf(timerFileName(taskId)),
                stopNow = true,
            )
        }
        if (serviceFile.exists()) {
            LinuxSystemdSchedulerJni.nativeDisableUnitFiles(
                arrayOf(serviceFileName(taskId)),
                stopNow = false,
            )
        }

        timerFile.delete()
        serviceFile.delete()
        LinuxSystemdSchedulerJni.nativeReload()
        TaskWrapperScript.deleteScript(appId, taskId)
        TaskMetadataStore.delete(appId, taskId)
        return true
    }

    override fun cancelAll() {
        val allIds = listScheduledTaskIds()
        allIds.forEach { cancel(it) }
        TaskWrapperScript.deleteAllScripts(appId)
        TaskMetadataStore.deleteAll(appId)
    }

    override fun isScheduled(taskId: TaskId): Boolean {
        if (!isAvailable) return false
        val timerFile = File(systemdUserDir, timerFileName(taskId))
        val serviceFile = File(systemdUserDir, serviceFileName(taskId))
        if (!timerFile.exists() && !serviceFile.exists()) return false
        return LinuxSystemdSchedulerJni.nativeGetUnitFileState(timerFileName(taskId)) == "enabled" ||
            LinuxSystemdSchedulerJni.nativeGetUnitFileState(serviceFileName(taskId)) == "enabled"
    }

    override fun getTaskInfo(taskId: TaskId): TaskInfo? {
        if (!isAvailable || !isScheduled(taskId)) return null

        val state = resolveState(taskId)
        val nextRunMs = parseNextElapse(taskId)

        return TaskInfo(
            taskId = taskId,
            state = state,
            lastRunMs = TaskMetadataStore.getLastRunMs(appId, taskId),
            nextRunMs = nextRunMs,
            runCount = TaskMetadataStore.getRunCount(appId, taskId),
            lastResult = TaskMetadataStore.getLastResult(appId, taskId),
        )
    }

    override fun getAllTasks(): List<TaskInfo> = listScheduledTaskIds().mapNotNull { getTaskInfo(it) }

    // -- Retry support --------------------------------------------------------

    /**
     * Schedules a one-shot retry timer that fires after [delaySeconds].
     */
    fun scheduleRetry(
        taskId: TaskId,
        delaySeconds: Long,
    ): Boolean {
        if (!isAvailable) return false

        val retryTimerName = "${unitBaseName(taskId)}-retry.timer"
        val content =
            buildString {
                appendLine("[Unit]")
                appendLine("Description=Nucleus retry timer: $taskId")
                appendLine()
                appendLine("[Timer]")
                appendLine("OnActiveSec=${delaySeconds}s")
                appendLine("RemainAfterElapse=false")
                appendLine()
                appendLine("[Install]")
                appendLine("WantedBy=timers.target")
            }

        val retryTimerFile = File(systemdUserDir, retryTimerName)
        retryTimerFile.writeText(content)
        LinuxSystemdSchedulerJni.nativeReload()
        return LinuxSystemdSchedulerJni.nativeStartUnit(retryTimerName)
    }

    // -- Unit file generation -------------------------------------------------

    private fun buildServiceUnit(
        taskId: TaskId,
        scriptPath: String,
    ): String =
        buildString {
            appendLine("[Unit]")
            appendLine("Description=Nucleus scheduled task: ${taskId.value}")
            appendLine()
            appendLine("[Service]")
            appendLine("Type=oneshot")
            appendLine("ExecStart=$scriptPath")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=default.target")
        }

    internal fun buildTimerUnit(request: TaskRequest): String =
        buildString {
            appendLine("[Unit]")
            appendLine("Description=Nucleus timer: ${request.taskId.value}")
            appendLine()
            appendLine("[Timer]")

            when (request.type) {
                TaskRequest.Type.PERIODIC -> {
                    val seconds = request.interval!!.inWholeSeconds
                    appendLine("OnUnitInactiveSec=${seconds}s")
                    if (request.runImmediately) {
                        appendLine("OnActiveSec=0")
                    } else {
                        appendLine("OnActiveSec=${seconds}s")
                    }
                }
                TaskRequest.Type.CALENDAR -> {
                    appendLine("OnCalendar=${request.cronExpression!!.expression}")
                }
                TaskRequest.Type.ON_BOOT -> {
                    // Should not reach here — onBoot tasks don't use timers
                }
            }

            appendLine("Persistent=true")
            appendLine()
            appendLine("[Install]")
            appendLine("WantedBy=timers.target")
        }

    // -- Introspection --------------------------------------------------------

    private fun listScheduledTaskIds(): List<TaskId> {
        val prefix = "$UNIT_PREFIX-$appId-"
        val dir = systemdUserDir
        if (!dir.isDirectory) return emptyList()
        return dir
            .listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".service") }
            ?.map { it.name.removePrefix(prefix).removeSuffix(".service") }
            ?.filter { !it.endsWith("-retry") }
            ?.mapNotNull { runCatching { TaskId(it) }.getOrNull() }
            ?: emptyList()
    }

    private fun resolveState(taskId: TaskId): TaskState {
        val activeState = LinuxSystemdSchedulerJni.nativeGetUnitActiveState(serviceFileName(taskId))
        return when (activeState) {
            "active", "activating" -> TaskState.RUNNING
            else -> TaskState.SCHEDULED
        }
    }

    private fun parseNextElapse(taskId: TaskId): Long? {
        val usec = LinuxSystemdSchedulerJni.nativeGetTimerNextElapseUSec(timerFileName(taskId))
        return if (usec == 0L) null else usec / USEC_PER_MS
    }
}
