package dev.nucleusframework.nucleus.scheduler.testing

import dev.nucleusframework.nucleus.scheduler.Constraints
import dev.nucleusframework.nucleus.scheduler.DesktopBootReceiver
import dev.nucleusframework.nucleus.scheduler.InternalSchedulerApi
import dev.nucleusframework.nucleus.scheduler.NetworkType
import dev.nucleusframework.nucleus.scheduler.internal.ConstraintChecker
import dev.nucleusframework.nucleus.scheduler.internal.ConstraintResult
import java.io.Closeable

/**
 * In-memory [ConstraintChecker] for tests.
 *
 * Exposes mutable properties representing system state so tests can simulate
 * different constraint scenarios without touching real hardware APIs.
 *
 * ```kotlin
 * TestConstraintChecker().use { checker ->
 *     checker.install()
 *     checker.networkConnected = false
 *
 *     // Tasks requiring network will now be skipped
 * }
 * ```
 */
@OptIn(InternalSchedulerApi::class)
public class TestConstraintChecker :
    ConstraintChecker,
    Closeable {
    /** Whether any network connection is active. */
    public var networkConnected: Boolean = true

    /** Whether the active network is unmetered. Only relevant when [networkConnected] is `true`. */
    public var networkUnmetered: Boolean = true

    /** Battery charge level (0.0–1.0). Set to `null` to simulate no battery. */
    public var batteryLevel: Float? = 1.0f

    /** Whether the device is plugged in. */
    public var isCharging: Boolean = false

    /** User idle time in seconds. Set to -1 to simulate unavailable. */
    public var idleTimeSeconds: Long = 0L

    /** Available disk space in bytes. */
    public var availableStorageBytes: Long = Long.MAX_VALUE

    private companion object {
        const val BATTERY_LOW_THRESHOLD = 0.15f
        const val IDLE_THRESHOLD_SECONDS = 300L
    }

    override fun check(constraints: Constraints): ConstraintResult {
        val unsatisfied = mutableSetOf<String>()

        if (constraints.requiredNetworkType != NetworkType.NOT_REQUIRED) {
            val met =
                when (constraints.requiredNetworkType) {
                    NetworkType.CONNECTED -> networkConnected
                    NetworkType.UNMETERED -> networkConnected && networkUnmetered
                    NetworkType.NOT_REQUIRED -> true
                }
            if (!met) unsatisfied += "network"
        }

        if (constraints.requiresBatteryNotLow) {
            val level = batteryLevel
            if (level != null && level < BATTERY_LOW_THRESHOLD) {
                unsatisfied += "batteryNotLow"
            }
        }

        if (constraints.requiresCharging) {
            if (!isCharging) unsatisfied += "charging"
        }

        if (constraints.requiresDeviceIdle) {
            if (idleTimeSeconds >= 0 && idleTimeSeconds < IDLE_THRESHOLD_SECONDS) {
                unsatisfied += "deviceIdle"
            }
        }

        val minStorage = constraints.minimumStorageBytes
        if (minStorage != null) {
            if (availableStorageBytes < minStorage) {
                unsatisfied += "storage"
            }
        }

        return ConstraintResult(
            satisfied = unsatisfied.isEmpty(),
            unsatisfied = unsatisfied,
        )
    }

    /**
     * Installs this checker as the active constraint checker in [DesktopBootReceiver].
     */
    public fun install() {
        DesktopBootReceiver.setTestConstraintChecker(this)
    }

    /**
     * Restores the production constraint checker.
     */
    public fun uninstall() {
        DesktopBootReceiver.resetConstraintChecker()
    }

    override fun close() {
        uninstall()
    }
}
