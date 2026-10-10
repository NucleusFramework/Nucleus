package dev.nucleusframework.lab.probes.system.fswatcher

import dev.nucleusframework.fswatcher.FsWatchBackendStrategy
import dev.nucleusframework.fswatcher.FsWatchDeliveryMode
import dev.nucleusframework.fswatcher.FsWatchEvent
import dev.nucleusframework.fswatcher.FsWatcher
import dev.nucleusframework.fswatcher.FsWatcherConfig
import dev.nucleusframework.fswatcher.FsWatchers
import dev.nucleusframework.lab.core.Availability
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.lab.core.format.summary
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stamped
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.nio.file.Path

/** One live watcher on the scratch root; closing it releases the native watcher. */
interface FsWatchSession : AutoCloseable {
    /** Stamped on the thread the watcher emitted on, not the collector's. */
    val events: Flow<Stamped<FsObservation>>

    val errors: Flow<Stamped<String>>
}

/** Port over `fs-watcher`, plus the real file operations the probe uses as stimuli. */
interface FsWatcherGateway {
    val root: Path

    fun availability(settings: WatchSettings): Availability

    /** @throws dev.nucleusframework.fswatcher.FsWatchException when the root cannot be watched */
    fun open(settings: WatchSettings): FsWatchSession

    /** Performs [action] on the root; throws with a readable message when it cannot. */
    fun perform(action: FsAction)
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusFsWatcherGateway : FsWatcherGateway {
    // A space in the path on macOS ("Application Support") is part of the test.
    override val root: Path by lazy { LabPaths.scratch("fs-watcher") }

    override fun availability(settings: WatchSettings): Availability =
        Availability.catching { FsWatchers.isSupported(settings.toConfig()) }

    override fun open(settings: WatchSettings): FsWatchSession {
        val watcher = FsWatchers.create(settings.toConfig())
        return try {
            watcher.watch(root, recursive = settings.recursive, name = "lab")
            Session(watcher, root)
        } catch (t: Throwable) {
            watcher.close()
            throw t
        }
    }

    override fun perform(action: FsAction) = ScratchActions(root).perform(action)

    private class Session(
        private val watcher: FsWatcher,
        private val root: Path,
    ) : FsWatchSession {
        // Unconfined: the stamping map runs on the thread that emitted, which is what the probe reports.
        override val events: Flow<Stamped<FsObservation>> =
            watcher.events.map { it.toObservation(root).stamped() }.flowOn(Dispatchers.Unconfined)

        override val errors: Flow<Stamped<String>> =
            watcher.errors
                .map { error ->
                    buildString {
                        append(error.message)
                        if (error.recoverable) append(" (recoverable)")
                        error.cause?.let { append(" — ${it.summary}") }
                    }.stamped()
                }.flowOn(Dispatchers.Unconfined)

        override fun close() = watcher.close()
    }
}

private fun WatchSettings.toConfig(): FsWatcherConfig =
    FsWatcherConfig(
        backend =
            when (backend) {
                Backend.Auto -> FsWatchBackendStrategy.Auto
                Backend.NativeOnly -> FsWatchBackendStrategy.NativeOnly
                Backend.Polling -> FsWatchBackendStrategy.Polling()
            },
        deliveryMode =
            when (delivery) {
                EventDelivery.Debounced -> FsWatchDeliveryMode.Debounced()
                EventDelivery.Raw -> FsWatchDeliveryMode.Raw
            },
        eventBufferCapacity = 512,
    )

private fun FsWatchEvent.toObservation(root: Path): FsObservation {
    fun rel(path: Path): String =
        if (path.startsWith(root)) root.relativize(path).toString().ifEmpty { "." } else path.toString()
    val (kind, paths, isDirectory) =
        when (this) {
            is FsWatchEvent.Created -> Triple("Created", rel(path), isDirectory)
            is FsWatchEvent.Modified -> Triple("Modified", rel(path), isDirectory)
            is FsWatchEvent.Removed -> Triple("Removed", rel(path), isDirectory)
            is FsWatchEvent.Moved -> Triple("Moved", "${rel(from)} → ${rel(to)}", isDirectory)
            is FsWatchEvent.Other -> Triple("Other", paths.joinToString { rel(it) }, isDirectory)
            is FsWatchEvent.Overflow -> Triple("Overflow", "events were dropped", null)
        }
    return FsObservation(kind, paths, isDirectory, needsRescan, System.nanoTime(), System.currentTimeMillis())
}
