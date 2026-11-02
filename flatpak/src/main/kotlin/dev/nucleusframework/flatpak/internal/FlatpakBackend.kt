package dev.nucleusframework.flatpak.internal

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.flatpak.FlatpakAvailability
import dev.nucleusframework.flatpak.FlatpakErrorCode
import dev.nucleusframework.flatpak.FlatpakException
import dev.nucleusframework.flatpak.FlatpakUpdateProgress
import dev.nucleusframework.flatpak.FlatpakUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What [dev.nucleusframework.flatpak.Flatpak] needs from a platform. */
internal interface FlatpakBackend {
    val isSupported: Boolean

    suspend fun availability(): FlatpakAvailability

    fun status(): Flow<FlatpakUpdateStatus>

    fun install(parentWindow: String): Flow<FlatpakUpdateProgress>

    suspend fun spawnLatest(
        cwd: String,
        argv: List<String>,
    )

    suspend fun openUri(
        parentWindow: String,
        uri: String,
    )
}

/** macOS, Windows, or a missing bridge: every call fails with [FlatpakErrorCode.Unsupported]. */
internal class UnsupportedFlatpakBackend(
    private val reason: String,
) : FlatpakBackend {
    override val isSupported: Boolean = false

    override suspend fun availability(): FlatpakAvailability = FlatpakAvailability.Unsupported

    override fun status(): Flow<FlatpakUpdateStatus> = flow { throw unsupported() }

    override fun install(parentWindow: String): Flow<FlatpakUpdateProgress> = flow { throw unsupported() }

    override suspend fun spawnLatest(
        cwd: String,
        argv: List<String>,
    ): Unit = throw unsupported()

    override suspend fun openUri(
        parentWindow: String,
        uri: String,
    ): Unit = throw unsupported()

    private fun unsupported() = FlatpakException(FlatpakErrorCode.Unsupported, reason)
}

/**
 * The portals through [bridge]. Every flow runs an update monitor of its own: the portal keeps no
 * state between them that a shared one would save.
 */
internal class NativeFlatpakBackend(
    private val bridge: FlatpakBridge,
    private val insideFlatpak: () -> Boolean = { ExecutableRuntime.isFlatpak() },
) : FlatpakBackend {
    private val ids = AtomicLong()
    private val installing = AtomicBoolean(false)
    private val availabilityLock = Mutex()

    @Volatile
    private var cachedAvailability: FlatpakAvailability? = null

    // Closing a monitor is a blocking D-Bus call, never made on the collector's thread (often
    // the UI thread) from `awaitClose`.
    private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isSupported: Boolean
        get() = bridge.isAvailable

    override suspend fun availability(): FlatpakAvailability =
        cachedAvailability ?: availabilityLock.withLock {
            cachedAvailability ?: withContext(Dispatchers.IO) {
                when {
                    !insideFlatpak() -> FlatpakAvailability.NotFlatpak
                    bridge.portalVersion() < MIN_PORTAL_VERSION -> FlatpakAvailability.PortalMissing
                    else -> FlatpakAvailability.Available
                }
            }.also { cachedAvailability = it }
        }

    private suspend fun requireAvailable() {
        val error =
            when (availability()) {
                FlatpakAvailability.Available -> return
                FlatpakAvailability.NotFlatpak ->
                    FlatpakException(FlatpakErrorCode.NotFlatpak, "This app does not run in a Flatpak sandbox")
                FlatpakAvailability.PortalMissing ->
                    FlatpakException(
                        FlatpakErrorCode.PortalMissing,
                        "No Flatpak portal with update monitors (flatpak 1.6 or later) on this session bus",
                    )
                FlatpakAvailability.Unsupported ->
                    FlatpakException(FlatpakErrorCode.Unsupported, "The Flatpak native bridge failed to load")
            }
        throw error
    }

    override fun status(): Flow<FlatpakUpdateStatus> =
        callbackFlow {
            requireAvailable()
            val id = ids.incrementAndGet()
            val listener =
                object : MonitorListener {
                    override fun onUpdateAvailable(
                        running: String,
                        local: String,
                        remote: String,
                    ) {
                        trySend(statusOf(running, local, remote))
                    }

                    override fun onProgress(progress: PortalProgress) = Unit

                    override fun onClosed(reason: String) {
                        close(FlatpakException(FlatpakErrorCode.PortalMissing, "The update monitor closed: $reason"))
                    }
                }
            var started = false
            try {
                withContext(Dispatchers.IO) { bridge.startMonitor(id, listener) }?.let { throw parsePortalError(it) }
                started = true
            } finally {
                // Also a collection cancelled while the monitor was starting: awaitClose is never reached.
                if (!started) closer.launch { bridge.closeMonitor(id) }
            }
            awaitClose { closer.launch { bridge.closeMonitor(id) } }
        }.conflate()

    override fun install(parentWindow: String): Flow<FlatpakUpdateProgress> =
        callbackFlow {
            requireAvailable()
            if (!installing.compareAndSet(false, true)) {
                throw FlatpakException(FlatpakErrorCode.InProgress, "An update is already being installed")
            }
            val id = ids.incrementAndGet()
            var started = false
            try {
                val listener =
                    object : MonitorListener {
                        override fun onUpdateAvailable(
                            running: String,
                            local: String,
                            remote: String,
                        ) = Unit

                        override fun onProgress(progress: PortalProgress) {
                            when (val step = progressStepOf(progress)) {
                                is ProgressStep.Report -> {
                                    trySend(step.progress)
                                    if (step.isFinal) close()
                                }
                                is ProgressStep.Failed -> close(step.error)
                            }
                        }

                        override fun onClosed(reason: String) {
                            close(
                                FlatpakException(FlatpakErrorCode.PortalMissing, "The update monitor closed: $reason"),
                            )
                        }
                    }
                withContext(Dispatchers.IO) {
                    bridge.startMonitor(id, listener)?.let { throw parsePortalError(it) }
                    bridge.update(id, parentWindow)?.let { throw parsePortalError(it) }
                }
                started = true
            } finally {
                if (!started) {
                    closer.launch { bridge.closeMonitor(id) }
                    installing.set(false)
                }
            }
            awaitClose {
                closer.launch { bridge.closeMonitor(id) }
                installing.set(false)
            }
        }.conflate()

    override suspend fun spawnLatest(
        cwd: String,
        argv: List<String>,
    ) {
        requireAvailable()
        withContext(Dispatchers.IO) { bridge.spawnLatest(cwd, argv) }?.let { throw parsePortalError(it) }
    }

    override suspend fun openUri(
        parentWindow: String,
        uri: String,
    ) {
        requireAvailable()
        withContext(Dispatchers.IO) { bridge.openUri(parentWindow, uri) }?.let { throw parsePortalError(it) }
    }

    private companion object {
        /** `CreateUpdateMonitor` arrived in version 2 of the Flatpak portal (flatpak 1.6). */
        const val MIN_PORTAL_VERSION = 2
    }
}
