package dev.nucleusframework.systeminfo

import dev.nucleusframework.systeminfo.model.DiskInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// Mount changes come in bursts (an automount touches the table several times, a docker
// container start dozens): one rescan per burst.
private const val DEBOUNCE_MS = 100L

/**
 * Serializes disk scans. Each native side keeps one static snapshot per scan, read back
 * through several JNI calls, and [DiskMonitor] rescans from a background thread while the
 * app may call `SystemInfo.disks()`.
 */
internal val diskScanLock = Any()

/**
 * Reference-counted owner of a native volume monitor: the first collector starts it, the
 * last one stops it. The native side only signals that something changed; the volumes are
 * rescanned here, off the native thread, and emitted when the set of volumes changed.
 *
 * When [start] fails, collectors get a single snapshot.
 */
internal class DiskMonitor(
    private val start: () -> Boolean,
    private val stop: () -> Unit,
    private val scan: () -> List<DiskInfo>,
) {
    private val lock = Any()
    private var collectors = 0
    private var job: Job? = null
    private var signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // null while stopped, so a restart never replays a stale list.
    private val state = MutableStateFlow<List<DiskInfo>?>(null)

    fun changes(): Flow<List<DiskInfo>> =
        flow {
            acquire()
            try {
                emitAll(state.filterNotNull())
            } finally {
                release()
            }
        }

    // Never takes the lock: it runs on the native thread that stop() waits for.
    fun onChanged() {
        signals.trySend(Unit)
    }

    private fun acquire() =
        synchronized(lock) {
            if (collectors++ > 0) return@synchronized
            val pending = Channel<Unit>(Channel.CONFLATED)
            signals = pending
            // Started before the first scan, so no change between the two is lost.
            start()
            job =
                scope.launch {
                    val self = coroutineContext.job
                    publish(self, synchronized(diskScanLock) { scan() })
                    while (pending.receiveCatching().isSuccess) {
                        delay(DEBOUNCE_MS)
                        pending.tryReceive()
                        publish(self, synchronized(diskScanLock) { scan() })
                    }
                }
        }

    private fun release() =
        synchronized(lock) {
            if (--collectors == 0) {
                stop()
                signals.close()
                job?.cancel()
                job = null
                state.value = null
            }
        }

    // A scan cannot be interrupted: one that ends after release() must not leave its result
    // behind for the next collector.
    private fun publish(
        owner: Job,
        disks: List<DiskInfo>,
    ) = synchronized(lock) {
        if (job !== owner) return@synchronized
        val previous = state.value
        // Free space changes all the time: only a different set of volumes is news.
        if (previous == null || previous.map(::identity) != disks.map(::identity)) state.value = disks
    }

    private fun identity(disk: DiskInfo): DiskInfo = disk.copy(totalSpace = 0, availableSpace = 0)
}
