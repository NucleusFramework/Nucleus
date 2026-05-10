package dev.nucleusframework.nucleus.scheduler.internal

import dev.nucleusframework.nucleus.scheduler.Constraints
import dev.nucleusframework.nucleus.scheduler.InternalSchedulerApi
import dev.nucleusframework.nucleus.scheduler.NetworkType
import dev.nucleusframework.nucleus.systeminfo.SystemInfo
import dev.nucleusframework.nucleus.systeminfo.model.MeteredStatus
import java.util.logging.Logger

/**
 * Production [ConstraintChecker] that delegates to `system-info` APIs.
 *
 * When a native query returns `null` or an error value (e.g. idle time = -1),
 * the constraint is treated as satisfied — we don't block tasks on missing data.
 */
@OptIn(InternalSchedulerApi::class)
internal object SystemInfoConstraintChecker : ConstraintChecker {
    private val logger = Logger.getLogger(SystemInfoConstraintChecker::class.java.name)

    private const val BATTERY_LOW_THRESHOLD = 0.15f
    private const val IDLE_THRESHOLD_SECONDS = 300L

    override fun check(constraints: Constraints): ConstraintResult {
        val unsatisfied = mutableSetOf<String>()

        if (constraints.requiredNetworkType != NetworkType.NOT_REQUIRED) {
            if (!checkNetwork(constraints.requiredNetworkType)) {
                unsatisfied += "network"
            }
        }
        if (constraints.requiresBatteryNotLow) {
            if (!checkBatteryNotLow()) {
                unsatisfied += "batteryNotLow"
            }
        }
        if (constraints.requiresCharging) {
            if (!checkCharging()) {
                unsatisfied += "charging"
            }
        }
        if (constraints.requiresDeviceIdle) {
            if (!checkDeviceIdle()) {
                unsatisfied += "deviceIdle"
            }
        }
        val minStorage = constraints.minimumStorageBytes
        if (minStorage != null) {
            if (!checkStorage(minStorage)) {
                unsatisfied += "storage"
            }
        }

        return ConstraintResult(
            satisfied = unsatisfied.isEmpty(),
            unsatisfied = unsatisfied,
        )
    }

    private fun checkNetwork(required: NetworkType): Boolean {
        val info = SystemInfo.connectivityInfo() ?: return true
        return when (required) {
            NetworkType.CONNECTED -> info.isConnected
            NetworkType.UNMETERED -> info.isConnected && info.meteredStatus == MeteredStatus.UNMETERED
            NetworkType.NOT_REQUIRED -> true
        }
    }

    private fun checkBatteryNotLow(): Boolean {
        val info = SystemInfo.batteryInfo() ?: return true
        return info.stateOfCharge >= BATTERY_LOW_THRESHOLD
    }

    private fun checkCharging(): Boolean {
        val info = SystemInfo.batteryInfo() ?: return true
        return info.isPluggedIn
    }

    private fun checkDeviceIdle(): Boolean {
        val idleTime = SystemInfo.idleTime()
        if (idleTime < 0) return true
        return idleTime >= IDLE_THRESHOLD_SECONDS
    }

    private fun checkStorage(minimumBytes: Long): Boolean {
        val disks = SystemInfo.disks()
        if (disks.isEmpty()) return true

        val appPath = System.getProperty("user.dir") ?: System.getProperty("user.home") ?: "/"
        val disk =
            disks
                .filter { appPath.startsWith(it.mountPoint) }
                .maxByOrNull { it.mountPoint.length }
                ?: disks.first()

        return disk.availableSpace >= minimumBytes
    }
}
