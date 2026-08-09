package dev.nucleusframework.energymanager

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.energymanager.linux.LinuxEnergyManager
import dev.nucleusframework.energymanager.macos.MacOsEnergyManager
import dev.nucleusframework.energymanager.windows.WindowsEnergyManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Manages process-level and thread-level energy efficiency mode,
 * and screen-awake (caffeine) state.
 *
 * Energy efficiency:
 *   Windows: EcoQoS + IDLE_PRIORITY_CLASS (green leaf in Task Manager);
 *            thread-level via SetThreadInformation EcoQoS (Win 11+) + THREAD_PRIORITY_IDLE.
 *   macOS:   setpriority(PRIO_DARWIN_BG) + task_policy_set(TIER_5).
 *   Linux:   nice +19, ioprio IDLE, timerslack 100ms — reversible without root.
 *
 * Awake (caffeine):
 *   Windows: SetThreadExecutionState — ES_SYSTEM_REQUIRED, plus ES_DISPLAY_REQUIRED
 *            unless [AwakeMode.SYSTEM_ONLY] is requested.
 *   macOS:   IOPMAssertionCreateWithName — kIOPMAssertPreventUserIdleDisplaySleep,
 *            or kIOPMAssertPreventUserIdleSystemSleep for [AwakeMode.SYSTEM_ONLY].
 *   Linux:   GNOME SessionManager / freedesktop PowerManagement / systemd-logind /
 *            X11 inhibitors — [AwakeMode.SYSTEM_ONLY] drops the idle bits and skips
 *            the X11 screen-saver backend.
 */
@Suppress("TooManyFunctions")
public object EnergyManager {
    public data class Result(
        val success: Boolean,
        val errorCode: Int = 0,
        val message: String = "",
    )

    private val unsupported = Result(false, -1, "Not supported on this platform")

    private val delegate: PlatformEnergyManager? =
        when (Platform.Current) {
            Platform.Windows -> WindowsEnergyManager
            Platform.MacOS -> MacOsEnergyManager
            Platform.Linux -> LinuxEnergyManager
            else -> null
        }

    /**
     * Returns true if the energy efficiency API is available on this platform.
     */
    public fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    /**
     * Enables efficiency mode for the current process.
     */
    public fun enableEfficiencyMode(): Result = delegate?.enableEfficiencyMode() ?: unsupported

    /**
     * Disables efficiency mode, restoring default OS scheduling.
     */
    public fun disableEfficiencyMode(): Result = delegate?.disableEfficiencyMode() ?: unsupported

    /**
     * Enables light efficiency mode for the current process.
     *
     * This is a softer alternative to [enableEfficiencyMode] that deprioritizes
     * CPU scheduling without throttling I/O or network.
     *
     * macOS: task_policy_set(TIER_5) only — no PRIO_DARWIN_BG.
     * Windows: EcoQoS only — no IDLE_PRIORITY_CLASS.
     * Linux: nice +10 only — no ioprio, no timer slack.
     */
    public fun enableLightEfficiencyMode(): Result = delegate?.enableLightEfficiencyMode() ?: unsupported

    /**
     * Disables light efficiency mode, restoring default QoS tiers.
     */
    public fun disableLightEfficiencyMode(): Result = delegate?.disableLightEfficiencyMode() ?: unsupported

    /**
     * Enables efficiency mode for the calling thread only.
     *
     * Windows: SetThreadInformation EcoQoS (Win 11+) + THREAD_PRIORITY_IDLE.
     * Linux: fully supported (nice, ioprio, timerslack are per-thread).
     * macOS: pthread QOS_CLASS_BACKGROUND.
     */
    public fun enableThreadEfficiencyMode(): Result = delegate?.enableThreadEfficiencyMode() ?: unsupported

    /**
     * Disables efficiency mode for the calling thread, restoring defaults.
     *
     * Windows: resets thread EcoQoS + THREAD_PRIORITY_NORMAL.
     * Linux: fully supported.
     * macOS: resets to QOS_CLASS_DEFAULT.
     */
    public fun disableThreadEfficiencyMode(): Result = delegate?.disableThreadEfficiencyMode() ?: unsupported

    /**
     * Prevents the system — and, unless [mode] is [AwakeMode.SYSTEM_ONLY], the display —
     * from entering sleep until [releaseAwake] is called.
     *
     * [AwakeMode.SYSTEM_ONLY] lets the screen saver and display sleep behave normally
     * while long background work keeps running.
     *
     * Calling this while a request is already active replaces it with [mode].
     *
     * The request is held by a dedicated internal thread, so it stays active
     * regardless of which thread calls this function — including short-lived
     * coroutine dispatcher workers.
     */
    public fun keepAwake(mode: AwakeMode = AwakeMode.SYSTEM_AND_DISPLAY): Result =
        delegate?.keepAwake(mode) ?: unsupported

    /**
     * Releases the awake state, allowing the OS to sleep normally.
     */
    public fun releaseAwake(): Result = delegate?.releaseAwake() ?: unsupported

    /**
     * Returns true if an awake request is currently held.
     */
    public fun isAwakeActive(): Boolean = delegate?.isAwakeActive() ?: false

    @Deprecated(
        "Renamed to keepAwake(), which also accepts an AwakeMode",
        ReplaceWith("keepAwake()"),
    )
    public fun keepScreenAwake(): Result = keepAwake()

    @Deprecated("Renamed to releaseAwake()", ReplaceWith("releaseAwake()"))
    public fun releaseScreenAwake(): Result = releaseAwake()

    @Deprecated("Renamed to isAwakeActive()", ReplaceWith("isAwakeActive()"))
    public fun isScreenAwakeActive(): Boolean = isAwakeActive()

    /**
     * Executes [block] on a dedicated thread with efficiency mode enabled.
     *
     * The thread is created with efficiency mode applied before [block] runs,
     * and the dispatcher is shut down after [block] completes.
     * This is safe for coroutines because the block runs on a single, pinned thread.
     *
     * ```
     * EnergyManager.withEfficiencyMode {
     *     // This code runs on a low-priority, energy-efficient thread
     *     performBackgroundWork()
     * }
     * ```
     */
    public suspend fun <T> withEfficiencyMode(block: suspend () -> T): T {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "nucleus-efficient").apply { isDaemon = true }
            }
        val dispatcher = executor.asCoroutineDispatcher()
        return try {
            withContext(dispatcher) {
                enableThreadEfficiencyMode()
                try {
                    block()
                } finally {
                    disableThreadEfficiencyMode()
                }
            }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }
    }

    /**
     * Executes [block] with light efficiency mode enabled for the current process.
     *
     * Unlike [withEfficiencyMode], this applies process-level light QoS
     * (no I/O or network throttling) and restores defaults when done.
     *
     * ```
     * EnergyManager.withLightEfficiencyMode {
     *     // Process runs with reduced CPU priority but normal I/O
     *     performBackgroundWork()
     * }
     * ```
     */
    public suspend fun <T> withLightEfficiencyMode(block: suspend () -> T): T {
        enableLightEfficiencyMode()
        return try {
            block()
        } finally {
            disableLightEfficiencyMode()
        }
    }
}
