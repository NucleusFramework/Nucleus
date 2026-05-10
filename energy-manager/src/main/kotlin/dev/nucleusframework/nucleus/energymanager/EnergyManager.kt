package dev.nucleusframework.nucleus.energymanager

import dev.nucleusframework.nucleus.core.runtime.Platform
import dev.nucleusframework.nucleus.energymanager.linux.LinuxEnergyManager
import dev.nucleusframework.nucleus.energymanager.macos.MacOsEnergyManager
import dev.nucleusframework.nucleus.energymanager.windows.WindowsEnergyManager
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
 * Screen awake:
 *   Windows: SetThreadExecutionState (ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED).
 *   macOS/Linux: not yet implemented.
 */
@Suppress("TooManyFunctions")
object EnergyManager {
    data class Result(
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
    fun isAvailable(): Boolean = delegate?.isAvailable() ?: false

    /**
     * Enables efficiency mode for the current process.
     */
    fun enableEfficiencyMode(): Result = delegate?.enableEfficiencyMode() ?: unsupported

    /**
     * Disables efficiency mode, restoring default OS scheduling.
     */
    fun disableEfficiencyMode(): Result = delegate?.disableEfficiencyMode() ?: unsupported

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
    fun enableLightEfficiencyMode(): Result = delegate?.enableLightEfficiencyMode() ?: unsupported

    /**
     * Disables light efficiency mode, restoring default QoS tiers.
     */
    fun disableLightEfficiencyMode(): Result = delegate?.disableLightEfficiencyMode() ?: unsupported

    /**
     * Enables efficiency mode for the calling thread only.
     *
     * Windows: SetThreadInformation EcoQoS (Win 11+) + THREAD_PRIORITY_IDLE.
     * Linux: fully supported (nice, ioprio, timerslack are per-thread).
     * macOS: pthread QOS_CLASS_BACKGROUND.
     */
    fun enableThreadEfficiencyMode(): Result = delegate?.enableThreadEfficiencyMode() ?: unsupported

    /**
     * Disables efficiency mode for the calling thread, restoring defaults.
     *
     * Windows: resets thread EcoQoS + THREAD_PRIORITY_NORMAL.
     * Linux: fully supported.
     * macOS: resets to QOS_CLASS_DEFAULT.
     */
    fun disableThreadEfficiencyMode(): Result = delegate?.disableThreadEfficiencyMode() ?: unsupported

    /**
     * Prevents the display and system from entering sleep.
     *
     * Windows: uses SetThreadExecutionState with ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED.
     * macOS/Linux: not yet implemented.
     */
    fun keepScreenAwake(): Result = delegate?.keepScreenAwake() ?: unsupported

    /**
     * Releases the screen-awake state, allowing the OS to sleep normally.
     */
    fun releaseScreenAwake(): Result = delegate?.releaseScreenAwake() ?: unsupported

    /**
     * Returns true if screen-awake mode is currently active.
     */
    fun isScreenAwakeActive(): Boolean = delegate?.isScreenAwakeActive() ?: false

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
    suspend fun <T> withEfficiencyMode(block: suspend () -> T): T {
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
    suspend fun <T> withLightEfficiencyMode(block: suspend () -> T): T {
        enableLightEfficiencyMode()
        return try {
            block()
        } finally {
            disableLightEfficiencyMode()
        }
    }
}
