package dev.nucleusframework.nucleus.autolaunch.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_autolaunch"

/**
 * JNI bridge. All methods return status codes instead of throwing — the
 * Kotlin side maps them to [dev.nucleusframework.nucleus.autolaunch.AutoLaunchState] /
 * [dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult].
 */
@Suppress("TooManyFunctions")
internal object NativeAutoLaunchBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeAutoLaunchBridge::class.java)

    val isLoaded: Boolean get() = loaded

    // ---- Runtime packaging detection ----

    /** Returns `true` if the current process runs inside an MSIX package. */
    fun isPackaged(): Boolean = if (loaded) nativeIsPackaged() else false

    @JvmStatic
    private external fun nativeIsPackaged(): Boolean

    /**
     * Returns `1` if the current process's parent is `taskhostw.exe`, meaning
     * it was launched by the Windows Task Scheduler — which is how MSIX
     * `windows.startupTask` extensions are implemented. Returns `0` for
     * manual launches (parent `explorer.exe` / `runtimebroker.exe`), `-1` on error.
     */
    fun isLaunchedByTaskScheduler(): Int = if (loaded) nativeIsLaunchedByTaskScheduler() else -1

    @JvmStatic
    private external fun nativeIsLaunchedByTaskScheduler(): Int

    /** Diagnostic log of native auto-launch operations, accumulated since process start. */
    fun getDiagnostic(): String = if (loaded) nativeGetDiagnostic() else "(native not loaded)"

    @JvmStatic
    private external fun nativeGetDiagnostic(): String

    // ---- Win32 registry (HKCU Run + StartupApproved\Run) ----

    /**
     * Reads the combined state of Run + StartupApproved\Run for [valueName] under HKCU.
     *
     * Return codes:
     *  - 0 = not present in Run (disabled / not configured)
     *  - 1 = enabled (in Run, StartupApproved absent or flag even)
     *  - 2 = disabled by user (in Run, StartupApproved flag odd, typ. 0x03)
     *  - -1 = error
     */
    fun regReadState(valueName: String): Int = if (loaded) nativeRegReadState(valueName) else -1

    @JvmStatic
    private external fun nativeRegReadState(valueName: String): Int

    /** Writes `HKCU\...\Run\<valueName> = <command>`. Returns 0 on success, -1 on error. */
    fun regWriteRun(
        valueName: String,
        command: String,
    ): Int = if (loaded) nativeRegWriteRun(valueName, command) else -1

    @JvmStatic
    private external fun nativeRegWriteRun(
        valueName: String,
        command: String,
    ): Int

    /**
     * Deletes `HKCU\...\Run\<valueName>`. If [alsoStartupApproved] is true, also deletes
     * `...\StartupApproved\Run\<valueName>`. Returns 0 on success (or absent), -1 on error.
     */
    fun regDeleteRun(
        valueName: String,
        alsoStartupApproved: Boolean,
    ): Int = if (loaded) nativeRegDeleteRun(valueName, alsoStartupApproved) else -1

    @JvmStatic
    private external fun nativeRegDeleteRun(
        valueName: String,
        alsoStartupApproved: Boolean,
    ): Int

    // ---- MSIX StartupTask (Windows.ApplicationModel) ----

    /**
     * Queries `StartupTask.State` for [taskId].
     *
     * Return codes mirror the WinRT enum:
     *  - 0 = Disabled
     *  - 1 = DisabledByUser
     *  - 2 = Enabled
     *  - 3 = DisabledByPolicy
     *  - 4 = EnabledByPolicy
     *  - -1 = error / task not declared in manifest
     */
    fun msixGetState(taskId: String): Int = if (loaded) nativeMsixGetState(taskId) else -1

    @JvmStatic
    private external fun nativeMsixGetState(taskId: String): Int

    /** Calls `RequestEnableAsync` and blocks until resolved. Returns new state (same codes as getState). */
    fun msixRequestEnable(taskId: String): Int = if (loaded) nativeMsixRequestEnable(taskId) else -1

    @JvmStatic
    private external fun nativeMsixRequestEnable(taskId: String): Int

    /** Calls `Disable()`. Returns 0 on success, -1 on error. */
    fun msixDisable(taskId: String): Int = if (loaded) nativeMsixDisable(taskId) else -1

    @JvmStatic
    private external fun nativeMsixDisable(taskId: String): Int
}
