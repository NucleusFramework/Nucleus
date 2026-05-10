package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_scheduler"

/**
 * JNI bridge to the Windows Task Scheduler 2.0 COM API.
 *
 * All methods are thin wrappers around COM calls (ITaskService, ITaskFolder,
 * ITaskDefinition, etc.) — no schtasks.exe subprocess involved.
 *
 * Creation methods return `null` on success or an error message on failure.
 */
internal object WindowsTaskSchedulerJni {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, WindowsTaskSchedulerJni::class.java)

    // ── Task creation ───────────────────────────────────────────────────

    @JvmStatic
    external fun nativeCreatePeriodicTask(
        folderPath: String,
        taskName: String,
        exePath: String,
        arguments: String,
        intervalMinutes: Int,
        startDelayMinutes: Int,
    ): String?

    @JvmStatic
    external fun nativeCreateDailyTask(
        folderPath: String,
        taskName: String,
        exePath: String,
        arguments: String,
        hour: Int,
        minute: Int,
    ): String?

    @JvmStatic
    external fun nativeCreateWeeklyTask(
        folderPath: String,
        taskName: String,
        exePath: String,
        arguments: String,
        daysOfWeek: Int,
        hour: Int,
        minute: Int,
    ): String?

    @JvmStatic
    external fun nativeCreateLogonTask(
        folderPath: String,
        taskName: String,
        exePath: String,
        arguments: String,
    ): String?

    @JvmStatic
    external fun nativeCreateOnceTask(
        folderPath: String,
        taskName: String,
        exePath: String,
        arguments: String,
        startBoundary: String,
    ): String?

    // ── Deletion ────────────────────────────────────────────────────────

    @JvmStatic
    external fun nativeDeleteTask(
        folderPath: String,
        taskName: String,
    ): String?

    @JvmStatic
    external fun nativeDeleteFolder(folderPath: String): String?

    // ── Query ───────────────────────────────────────────────────────────

    @JvmStatic
    external fun nativeTaskExists(
        folderPath: String,
        taskName: String,
    ): Boolean

    /**
     * Returns the raw Task Scheduler state:
     * - `1` = DISABLED
     * - `2` = QUEUED
     * - `3` = READY
     * - `4` = RUNNING
     * - `-1` = task not found or error
     */
    @JvmStatic
    external fun nativeGetTaskState(
        folderPath: String,
        taskName: String,
    ): Int

    /**
     * Returns the next scheduled run time as epoch milliseconds, or `0` if unknown.
     */
    @JvmStatic
    external fun nativeGetTaskNextRunTime(
        folderPath: String,
        taskName: String,
    ): Long

    /**
     * Returns all task names in the given folder, or `null` if the folder doesn't exist.
     */
    @JvmStatic
    external fun nativeGetTaskNames(folderPath: String): Array<String>?

    // ── Task Scheduler day-of-week bitmask constants ────────────────────

    const val SUNDAY = 0x01
    const val MONDAY = 0x02
    const val TUESDAY = 0x04
    const val WEDNESDAY = 0x08
    const val THURSDAY = 0x10
    const val FRIDAY = 0x20
    const val SATURDAY = 0x40

    // ── TASK_STATE constants from taskschd.h ────────────────────────────

    const val TASK_STATE_DISABLED = 1
    const val TASK_STATE_QUEUED = 2
    const val TASK_STATE_READY = 3
    const val TASK_STATE_RUNNING = 4
}
