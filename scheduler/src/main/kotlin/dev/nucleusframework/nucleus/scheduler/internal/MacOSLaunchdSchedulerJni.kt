package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_scheduler"

/**
 * JNI bridge to macOS Foundation / ServiceManagement APIs for launchd
 * agent management.
 *
 * Replaces the shell-based approach (ProcessBuilder + launchctl) with:
 * - **NSDictionary + NSPropertyListSerialization** for type-safe plist generation
 * - **SMJobCopyDictionary** for subprocess-free job state queries
 * - **NSCalendar** for next-fire-time computation
 * - **NSTask** for launchctl load/unload (still subprocess, no public alternative)
 *
 * Creation/mutation methods return `null` on success or an error message on failure.
 */
internal object MacOSLaunchdSchedulerJni {
    val isLoaded: Boolean = NativeLibraryLoader.load(LIBRARY_NAME, MacOSLaunchdSchedulerJni::class.java)

    // -- Plist generation + atomic file write ---------------------------------

    /**
     * Builds a launchd plist via NSDictionary and writes it atomically to [plistPath].
     *
     * @param calendarDay    single weekday (launchd convention: 0=Sunday..6=Saturday), or -1 if N/A
     * @param calendarHour   hour (0-23), or -1 if N/A
     * @param calendarMinute minute (0-59), or -1 if N/A
     * @param calendarDays   array of weekdays for day-range patterns, or null
     */
    @JvmStatic
    external fun nativeWritePlist(
        plistPath: String,
        label: String,
        programArgs: Array<String>,
        intervalSeconds: Int,
        calendarDay: Int,
        calendarHour: Int,
        calendarMinute: Int,
        runAtLoad: Boolean,
        calendarDays: IntArray?,
    ): String?

    // -- launchctl load/unload (NSTask, still subprocess) ---------------------

    @JvmStatic
    external fun nativeLaunchctlLoad(plistPath: String): String?

    @JvmStatic
    external fun nativeLaunchctlUnload(plistPath: String): String?

    // -- Job state query (SMJobCopyDictionary, no subprocess) -----------------

    @JvmStatic
    external fun nativeIsJobLoaded(jobLabel: String): Boolean

    // -- File management ------------------------------------------------------

    @JvmStatic
    external fun nativeDeleteFile(path: String): Boolean

    // -- Next fire time computation (NSCalendar) ------------------------------

    /**
     * Computes the next fire time from the schedule configuration.
     *
     * @return epoch milliseconds, or 0 if unknown
     */
    @JvmStatic
    external fun nativeComputeNextFireTime(
        intervalSeconds: Int,
        calendarDay: Int,
        calendarHour: Int,
        calendarMinute: Int,
        calendarDays: IntArray?,
    ): Long

    // -- Retry: write plist + dispatch_after for delayed load -----------------

    /**
     * Writes a RunAtLoad-only retry plist and schedules a delayed launchctl load
     * via dispatch_after. The plist persists on disk even if the app crashes.
     */
    @JvmStatic
    external fun nativeScheduleRetry(
        plistPath: String,
        label: String,
        programArgs: Array<String>,
        delaySeconds: Long,
    ): String?
}
