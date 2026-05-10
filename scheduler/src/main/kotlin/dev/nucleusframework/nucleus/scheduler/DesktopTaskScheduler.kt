package dev.nucleusframework.nucleus.scheduler

import dev.nucleusframework.nucleus.core.runtime.ExecutableRuntime
import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.scheduler.internal.LinuxSystemdScheduler
import dev.nucleusframework.nucleus.scheduler.internal.MacOSLaunchdScheduler
import dev.nucleusframework.nucleus.scheduler.internal.NoopScheduler
import dev.nucleusframework.nucleus.scheduler.internal.PlatformScheduler
import dev.nucleusframework.nucleus.scheduler.internal.WindowsTaskScheduler
import java.util.logging.Logger

/**
 * Schedules background tasks with the OS so they run even when the app is closed.
 *
 * On Linux, this uses systemd user timers. On other platforms, all operations
 * are no-ops that return `false` / empty results.
 *
 * ```kotlin
 * val scheduler = DesktopTaskScheduler.getInstance()
 * scheduler.enqueue(TaskRequest.periodic("sync", 1.hours))
 * ```
 */
@OptIn(InternalSchedulerApi::class)
public object DesktopTaskScheduler {
    private val logger = Logger.getLogger(DesktopTaskScheduler::class.java.name)

    private val platformDelegate: PlatformScheduler =
        when (Platform.Current) {
            Platform.Linux -> LinuxSystemdScheduler
            Platform.Windows -> if (WindowsTaskScheduler.isAvailable) WindowsTaskScheduler else NoopScheduler
            Platform.MacOS -> MacOSLaunchdScheduler
            else -> NoopScheduler
        }

    private var delegate: PlatformScheduler = platformDelegate

    /**
     * Replaces the active scheduler backend with the given [scheduler].
     *
     * This is intended for testing only — use the `scheduler-testing` module
     * which provides [TestDesktopTaskScheduler] to call this safely.
     */
    @InternalSchedulerApi
    public fun setTestDelegate(scheduler: PlatformScheduler) {
        delegate = scheduler
    }

    /**
     * Restores the platform-default scheduler backend.
     */
    @InternalSchedulerApi
    public fun resetDelegate() {
        delegate = platformDelegate
    }

    /**
     * Returns the singleton scheduler instance.
     *
     * Kept for API parity with the Android WorkManager style — calling
     * `DesktopTaskScheduler.getInstance()` is equivalent to using the object directly.
     */
    @JvmStatic
    public fun getInstance(): DesktopTaskScheduler = this

    /**
     * Returns `true` if scheduling is available on this platform.
     */
    @JvmStatic
    public fun isAvailable(): Boolean = delegate !is NoopScheduler

    /**
     * Registers a task with the OS scheduler.
     *
     * By default, if a task with the same ID is already scheduled, this is a no-op
     * (returns `true`). Use [ExistingTaskPolicy.REPLACE] in the [TaskRequest] builder
     * to overwrite the existing schedule.
     *
     * @return `true` if the task was successfully scheduled (or already existed)
     */
    @JvmStatic
    public fun enqueue(request: TaskRequest): Boolean {
        if (ExecutableRuntime.isPkg()) {
            logger.severe(
                "DesktopTaskScheduler is not supported in sandboxed Mac App Store builds (.pkg). " +
                    "Use the service-management-macos module with SMAppService instead.",
            )
            return false
        }
        return delegate.enqueue(request)
    }

    /**
     * Cancels a scheduled task.
     *
     * @return `true` if the task was found and removed
     */
    @JvmStatic
    public fun cancel(taskId: TaskId): Boolean = delegate.cancel(taskId)

    /**
     * Cancels all tasks belonging to this application.
     */
    @JvmStatic
    public fun cancelAll(): Unit = delegate.cancelAll()

    /**
     * Returns `true` if the given task is currently scheduled.
     */
    @JvmStatic
    public fun isScheduled(taskId: TaskId): Boolean = delegate.isScheduled(taskId)

    /**
     * Returns detailed runtime info about a task, or `null` if not found.
     */
    @JvmStatic
    public fun getTaskInfo(taskId: TaskId): TaskInfo? = delegate.getTaskInfo(taskId)

    /**
     * Returns info for all tasks registered by this application.
     */
    @JvmStatic
    public fun getAllTasks(): List<TaskInfo> = delegate.getAllTasks()
}
