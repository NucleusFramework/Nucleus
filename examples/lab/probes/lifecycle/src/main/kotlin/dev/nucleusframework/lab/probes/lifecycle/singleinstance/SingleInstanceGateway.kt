package dev.nucleusframework.lab.probes.lifecycle.singleinstance

import dev.nucleusframework.core.runtime.ExecutableRuntime
import dev.nucleusframework.core.runtime.SingleInstanceManager
import dev.nucleusframework.lab.core.mvi.Stamped
import dev.nucleusframework.lab.core.mvi.stampedCallbackFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import kotlin.concurrent.thread

enum class LockHolder(
    val label: String,
) {
    ThisProcess("held by this process"),
    OtherProcess("held by another process"),
    Free("free — nobody holds it"),
    NoLockFile("no lock file"),
}

/** A change to the restore-request file a second instance writes to wake the first one. */
data class RestoreFileChange(
    val kind: String,
    val file: String,
)

interface SingleInstanceGateway {
    /** `nucleusApplication(enableSingleInstance = !ExecutableRuntime.isDev())`, the Lab's call. */
    val lockTakenByApp: Boolean

    val lockFile: Path
    val restoreRequestFile: Path
    val lockIdentifier: String

    fun holder(): Result<LockHolder>

    fun restoreFileChanges(): Flow<Stamped<RestoreFileChange>>
}

@ContributesBinding(AppScope::class)
@Inject
class NucleusSingleInstanceGateway : SingleInstanceGateway {
    private val configuration get() = SingleInstanceManager.configuration

    override val lockTakenByApp: Boolean = !ExecutableRuntime.isDev()
    override val lockFile: Path get() = configuration.lockFilePath
    override val restoreRequestFile: Path get() = configuration.restoreRequestFilePath
    override val lockIdentifier: String get() = configuration.lockIdentifier

    override fun holder(): Result<LockHolder> =
        runCatching {
            when {
                !Files.exists(lockFile) -> LockHolder.NoLockFile
                // Never probe a lock this JVM holds: on POSIX, closing any descriptor of the file
                // drops every lock the process has on it, so the probe would release the real one.
                lockTakenByApp -> LockHolder.ThisProcess
                else ->
                    FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
                        try {
                            val lock = channel.tryLock()
                            if (lock == null) {
                                LockHolder.OtherProcess
                            } else {
                                lock.release()
                                LockHolder.Free
                            }
                        } catch (_: OverlappingFileLockException) {
                            LockHolder.ThisProcess
                        }
                    }
            }
        }

    override fun restoreFileChanges(): Flow<Stamped<RestoreFileChange>> =
        stampedCallbackFlow {
            val dir = restoreRequestFile.parent
            val name = restoreRequestFile.fileName.toString()
            val watcher = FileSystems.getDefault().newWatchService()
            dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE,
            )
            val worker =
                thread(name = "lab-restore-request-watch", isDaemon = true) {
                    runCatching {
                        while (true) {
                            val key = watcher.take()
                            key.pollEvents().forEach { event ->
                                val changed = event.context()?.toString()
                                if (changed == name) emit(RestoreFileChange(event.kind().name(), changed))
                            }
                            if (!key.reset()) break
                        }
                    }
                }
            onClose {
                watcher.close()
                worker.interrupt()
            }
        }
}
