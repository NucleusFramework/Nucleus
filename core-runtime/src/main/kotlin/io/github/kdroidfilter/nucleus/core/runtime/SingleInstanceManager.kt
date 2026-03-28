package io.github.kdroidfilter.nucleus.core.runtime

import io.github.kdroidfilter.nucleus.core.runtime.tools.AppIdProvider
import io.github.kdroidfilter.nucleus.core.runtime.tools.debugln
import io.github.kdroidfilter.nucleus.core.runtime.tools.errorln
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds

/**
 * Singleton object to manage the single instance of an application.
 *
 * This object ensures that only one instance of the application can run at a time,
 * and provides a mechanism to notify the running instance when another instance attempts to start.
 */
object SingleInstanceManager {
    private const val TAG = "SingleInstanceChecker"

    /**
     * Don't inline to [Configuration] initializer to prevent multiple calls with the different stack depth.
     */
    private val APP_IDENTIFIER = getAppIdentifier()

    /**
     * Configuration for a locking mechanism.
     *
     * @property lockFilesDir The directory where lock files will be stored.
     *   Defaults to the system's temporary directory.
     * @property lockIdentifier The lock identifier that will be used for generating lock files names.
     */
    data class Configuration(
        val lockFilesDir: Path = Paths.get(System.getProperty("java.io.tmpdir")),
        val lockIdentifier: String = APP_IDENTIFIER,
    ) {
        val lockFileName: String = "$lockIdentifier.lock"
        val restoreRequestFileName: String = "$lockIdentifier.restore_request"

        val lockFilePath: Path = lockFilesDir.resolve(lockFileName)
        val restoreRequestFilePath: Path = lockFilesDir.resolve(restoreRequestFileName)
    }

    var configuration: Configuration = Configuration()
        set(value) {
            check(fileChannel == null) { "Configuration can be changed only before first call to isSingleInstance()!" }
            field = value
        }

    private var fileChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var isWatching = false

    /**
     * Checks if the current process is the single running instance.
     *
     * @param onRestoreRequest A function to be executed if a restore request is received from another instance.
     */
    fun isSingleInstance(
        onRestoreFileCreated: (Path.() -> Unit)? = null,
        onRestoreRequest: Path.() -> Unit,
    ): Boolean {
        // If the lock is already acquired by this process, we are the first instance
        if (fileLock != null) {
            debugLog { "The lock is already held by this process" }
            return true
        }
        val lockFile = createLockFile()
        fileChannel = RandomAccessFile(lockFile, "rw").channel
        return try {
            fileLock = tryLockWithRetry(fileChannel)
            if (fileLock != null) {
                // We are the only instance
                debugLog { "Lock acquired, starting to watch for restore requests" }
                // Clean any stale restore request file left from a previous crash
                deleteRestoreRequestFile()
                // Ensure that watching is started only once
                if (!isWatching) {
                    isWatching = true
                    watchForRestoreRequests(onRestoreRequest)
                }
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        releaseLock()
                        lockFile.delete()
                        deleteRestoreRequestFile()
                        debugLog { "Shutdown hook executed" }
                    },
                )
                true
            } else {
                // Another instance is already running
                sendRestoreRequest(onRestoreFileCreated)
                debugLog { "Restore request sent to the existing instance" }
                false
            }
        } catch (e: OverlappingFileLockException) {
            // The lock is already held by this process
            debugLog { "The lock is already held by this process (${e.message})" }
            return true
        } catch (e: IOException) {
            // Fail-open: if we cannot determine lock state, let the app run
            // rather than silently terminating
            errorLog { "Error in isSingleInstance (proceeding as primary): $e" }
            true
        }
    }

    /**
     * Attempts to acquire the file lock with retries.
     *
     * On macOS (and other platforms), a rapid relaunch can race with the previous
     * process's shutdown hook that is still releasing the lock. A short retry window
     * handles this gracefully.
     */
    @Suppress("MagicNumber")
    internal fun tryLockWithRetry(
        channel: FileChannel?,
        maxAttempts: Int = 3,
        retryDelayMs: Long = 150,
    ): FileLock? {
        repeat(maxAttempts) { attempt ->
            val lock = channel?.tryLock()
            if (lock != null) return lock
            if (attempt < maxAttempts - 1) {
                debugLog { "Lock attempt ${attempt + 1}/$maxAttempts failed, retrying in ${retryDelayMs}ms" }
                Thread.sleep(retryDelayMs)
            }
        }
        return null
    }

    private fun createLockFile(): File {
        val lockFile = configuration.lockFilePath.toFile()
        lockFile.parentFile.mkdirs()
        return lockFile
    }

    @Suppress("TooGenericExceptionCaught")
    private fun watchForRestoreRequests(onRestoreRequest: Path.() -> Unit) {
        Thread {
            try {
                val watchService = FileSystems.getDefault().newWatchService()
                configuration.lockFilesDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE)
                debugLog { "Watching directory: ${configuration.lockFilesDir} for restore requests" }
                while (true) {
                    val key = watchService.take()
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue
                        }
                        val filename = event.context() as Path
                        if (filename.toString() == configuration.restoreRequestFileName) {
                            debugLog { "Restore request file detected" }
                            configuration.restoreRequestFilePath.onRestoreRequest()
                            // Remove the request file after processing
                            deleteRestoreRequestFile()
                        }
                    }
                    val valid = key.reset()
                    if (!valid) {
                        break
                    }
                }
            } catch (e: Exception) {
                errorLog { "Error in watchForRestoreRequests: $e" }
            }
        }.start()
    }

    private fun sendRestoreRequest(onRestoreFileCreated: (Path.() -> Unit)?) {
        try {
            val restoreRequestFilePath = configuration.restoreRequestFilePath
            if (onRestoreFileCreated != null) {
                val tempRestoreFilePath = Files.createTempFile(configuration.lockIdentifier, ".restore_request")
                tempRestoreFilePath.onRestoreFileCreated()
                Files.move(tempRestoreFilePath, restoreRequestFilePath, StandardCopyOption.REPLACE_EXISTING)
            } else {
                // Delete any stale file first, then create a fresh one so the
                // WatchService sees an ENTRY_CREATE event
                Files.deleteIfExists(restoreRequestFilePath)
                Files.createFile(restoreRequestFilePath)
            }
            debugLog { "Restore request file created: $restoreRequestFilePath" }
        } catch (e: IOException) {
            errorLog { "Error while sending restore request: $e" }
        }
    }

    private fun deleteRestoreRequestFile() {
        try {
            val restoreRequestFilePath = configuration.restoreRequestFilePath
            Files.deleteIfExists(restoreRequestFilePath)
            debugLog { "Restore request file deleted: $restoreRequestFilePath" }
        } catch (e: IOException) {
            errorLog { "Error while deleting restore request file: $e" }
        }
    }

    private fun releaseLock() {
        try {
            fileLock?.release()
            fileChannel?.close()
            debugLog { "Lock released" }
        } catch (e: IOException) {
            errorLog { "Error while releasing the lock: $e" }
        }
    }

    private fun getAppIdentifier(): String {
        // Use unified app ID provider to avoid cross-app conflicts and allow explicit override
        return AppIdProvider.appId()
    }

    private fun debugLog(msg: () -> String) {
        debugln { "[$TAG] ${msg()}" }
    }

    private fun errorLog(msg: () -> String) {
        errorln { "[$TAG] ${msg()}" }
    }
}
