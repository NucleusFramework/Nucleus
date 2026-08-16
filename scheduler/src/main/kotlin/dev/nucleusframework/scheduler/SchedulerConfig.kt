package dev.nucleusframework.scheduler

/**
 * Optional overrides for the command the OS scheduler invokes.
 *
 * By default every backend wakes up the current executable, resolved from
 * `ProcessHandle.current().info().command()`. Apps that bootstrap through a custom
 * launcher can point the scheduler at that launcher instead:
 *
 * ```kotlin
 * SchedulerConfig.executablePath = "/opt/myapp/myapp-launcher"
 * SchedulerConfig.executableArguments = listOf("--background")
 *
 * DesktopTaskScheduler.enqueue(TaskRequest.periodic(TaskId("sync"), 1.hours))
 * ```
 *
 * The resulting invocation is
 * `<executablePath> <executableArguments…> --nucleus-scheduler-run <taskId>`.
 *
 * Configure this before the first [DesktopTaskScheduler.enqueue] call — the values are
 * baked into the generated unit/plist/task at enqueue time, so changing them later only
 * affects tasks scheduled afterwards.
 */
public object SchedulerConfig {
    /**
     * Absolute path to the program the OS scheduler should invoke.
     *
     * Must be an absolute path: the generated wrapper scripts check that this file still
     * exists and unregister the scheduled task when it is gone (e.g. after an uninstall).
     * If `null` or blank, resolved from `ProcessHandle.current().info().command()`.
     */
    @JvmStatic
    public var executablePath: String? = null

    /**
     * Arguments inserted before the `--nucleus-scheduler-run <taskId>` flag, for launchers
     * that need flags of their own. Empty by default.
     */
    @JvmStatic
    public var executableArguments: List<String> = emptyList()
}
