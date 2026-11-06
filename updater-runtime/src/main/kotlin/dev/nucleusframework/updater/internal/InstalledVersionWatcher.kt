package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.VersionedInstall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Publishes the version the launcher now starts when another process installed one next to the
 * running version — typically another instance of an app without single instance.
 *
 * This is Chromium's `InstalledVersionMonitor` + `InstalledVersionPoller` pair: a change
 * notification (here on `app\`, where the installer rewrites the launcher's `.cfg`) backed by a
 * slow poll in case a notification is missed. The `.cfg` is written before the version it points
 * to has finished extracting, so it is read under the shared install lock, which waits for an
 * install in progress to complete.
 */
internal class InstalledVersionWatcher(
    private val install: VersionedInstall,
) {
    private val state = MutableStateFlow(read())

    val version: StateFlow<String?> = state.asStateFlow()

    fun start() {
        Thread(::watch, "nucleus-installed-version-watcher").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun watch() {
        try {
            FileSystems.getDefault().newWatchService().use { watcher ->
                File(install.root, APP_DIR_NAME).toPath().register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
                while (true) {
                    val key = watcher.poll(POLL_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    key?.pollEvents()
                    key?.reset()
                    state.value = readSettled()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Cannot watch ${install.root} for installed updates", e)
        }
    }

    /** Reads once no install is in progress; this very process installing keeps the last value. */
    private fun readSettled(): String? =
        try {
            WindowsHotUpdate.withInstallLock(install, shared = true) { read() }
        } catch (e: IOException) {
            if (e.cause is OverlappingFileLockException) {
                state.value
            } else {
                logger.log(Level.FINE, "Install lock unavailable; reading without it", e)
                read()
            }
        }

    private fun read(): String? = WindowsHotUpdate.installedVersionDir(install)?.name

    private companion object {
        const val APP_DIR_NAME = "app"
        const val POLL_INTERVAL_MINUTES = 30L
        val logger: Logger = Logger.getLogger(InstalledVersionWatcher::class.java.name)
    }
}
