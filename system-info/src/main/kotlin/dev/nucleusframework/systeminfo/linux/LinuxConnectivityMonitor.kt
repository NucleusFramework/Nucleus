package dev.nucleusframework.systeminfo.linux

import dev.nucleusframework.systeminfo.model.ConnectivityInfo
import dev.nucleusframework.systeminfo.model.ConnectivityLevel
import dev.nucleusframework.systeminfo.model.MeteredStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow

/**
 * Reference-counted owner of the native connectivity monitor: the first collector
 * starts it, the last one stops it. The native side already drops identical states,
 * and the [MutableStateFlow] dedupes whatever slips through.
 */
internal object LinuxConnectivityMonitor {
    private val bridge = NativeLinuxSystemInfoBridge
    private val lock = Any()
    private var collectors = 0

    // null while stopped, so a restart never replays a stale state.
    private val state = MutableStateFlow<ConnectivityInfo?>(null)

    fun changes(): Flow<ConnectivityInfo> =
        flow {
            acquire()
            try {
                emitAll(state.filterNotNull())
            } finally {
                release()
            }
        }

    // Never takes the lock: it runs on the native thread that stop() joins.
    fun onChanged(info: ConnectivityInfo) {
        state.value = info
    }

    private fun acquire() =
        synchronized(lock) {
            if (collectors++ == 0 && !bridge.nativeStartConnectivityMonitor()) {
                state.value = LinuxSystemInfo.connectivityInfo()
            }
        }

    private fun release() =
        synchronized(lock) {
            if (--collectors == 0) {
                bridge.nativeStopConnectivityMonitor()
                state.value = null
            }
        }
}

// Codes mirrored in nucleus_system_info_connectivity.c.
internal fun connectivityInfoOf(
    available: Boolean,
    metered: Int,
    level: Int,
): ConnectivityInfo =
    ConnectivityInfo(
        isConnected = available,
        meteredStatus =
            when {
                !available -> MeteredStatus.NOT_AVAILABLE
                metered == 1 -> MeteredStatus.UNMETERED
                metered == 2 -> MeteredStatus.METERED
                else -> MeteredStatus.UNKNOWN
            },
        level =
            when (level) {
                1 -> ConnectivityLevel.NONE
                2 -> ConnectivityLevel.LOCAL
                3 -> ConnectivityLevel.LIMITED
                4 -> ConnectivityLevel.CAPTIVE_PORTAL
                5 -> ConnectivityLevel.FULL
                else -> ConnectivityLevel.UNKNOWN
            },
    )
