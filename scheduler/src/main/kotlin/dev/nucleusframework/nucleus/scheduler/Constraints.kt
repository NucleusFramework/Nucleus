package dev.nucleusframework.nucleus.scheduler

/**
 * Describes conditions that must be met before a scheduled task executes.
 *
 * Constraints are checked at execution time by [DesktopBootReceiver] — the OS
 * still triggers the process on schedule, but `doWork()` is only called when
 * all constraints are satisfied.
 *
 * When constraints are not met:
 * - **Periodic tasks**: the execution is silently skipped; the next trigger re-checks.
 * - **Calendar / on-boot tasks**: a retry is scheduled with backoff.
 *
 * ```kotlin
 * TaskRequest.periodic("sync", 2.hours) {
 *     constraints {
 *         requiredNetworkType = NetworkType.UNMETERED
 *         requiresBatteryNotLow = true
 *     }
 * }
 * ```
 */
public data class Constraints(
    /** The type of network connectivity required. Defaults to [NetworkType.NOT_REQUIRED]. */
    val requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED,
    /** Requires battery level above 15 %. Devices without a battery satisfy this constraint. */
    val requiresBatteryNotLow: Boolean = false,
    /** Requires the device to be plugged in (charging or full). */
    val requiresCharging: Boolean = false,
    /** Requires the user to be idle (no input for at least 5 minutes). */
    val requiresDeviceIdle: Boolean = false,
    /** Minimum available disk space (in bytes) on the application partition, or `null` for no requirement. */
    val minimumStorageBytes: Long? = null,
) {
    public companion object {
        /** No constraints — the task always executes when triggered. */
        public val NONE: Constraints = Constraints()
    }

    /** Returns `true` if at least one constraint is set. */
    public fun hasConstraints(): Boolean = this != NONE
}

/**
 * Required network connectivity type for a scheduled task.
 */
public enum class NetworkType {
    /** No network requirement — the task runs regardless of connectivity. */
    NOT_REQUIRED,

    /** Any active network connection is sufficient. */
    CONNECTED,

    /** Requires an unmetered (non-cellular / non-tethered) connection. */
    UNMETERED,
}

/**
 * DSL builder for [Constraints], used inside [TaskRequest.Builder.constraints].
 */
public class ConstraintsBuilder internal constructor() {
    public var requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED
    public var requiresBatteryNotLow: Boolean = false
    public var requiresCharging: Boolean = false
    public var requiresDeviceIdle: Boolean = false
    public var minimumStorageBytes: Long? = null

    internal fun build(): Constraints =
        Constraints(
            requiredNetworkType = requiredNetworkType,
            requiresBatteryNotLow = requiresBatteryNotLow,
            requiresCharging = requiresCharging,
            requiresDeviceIdle = requiresDeviceIdle,
            minimumStorageBytes = minimumStorageBytes,
        )
}
