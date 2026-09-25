package dev.nucleusframework.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A Windows NSIS install laid out for hot updates: the launcher and its `app\<name>.cfg` stay at
 * [root], while the Java runtime and the application live in `versions\<version>\` ([versionDir]).
 *
 * A new version is installed into a sibling `versions\<new>\` directory while this one keeps
 * running — nothing this process holds open is overwritten — and the rewritten `.cfg` makes the
 * next launch of [launcher] start the new version.
 */
public class VersionedInstall(
    /** Installation directory: holds the launcher, `app\*.cfg` and `versions\`. */
    public val root: File,
    /** The `versions\<version>` directory this process runs from. */
    public val versionDir: File,
    /** The application launcher (`jpackage.app-path`), directly under [root]. */
    public val launcher: File,
) {
    /** Directory holding every installed version. */
    public val versionsDir: File get() = versionDir.parentFile
}

/**
 * The seamless restart that follows a hot update: the running (old) version launches the new one,
 * which calls [signalReady] once its first window is on screen; only then does the old version
 * exit, so the application never disappears from the screen while it updates.
 *
 * Nucleus windows signal readiness on their first presented frame, so applications built on
 * `nucleusApplication` need nothing. An application that shows no Nucleus window (tray-only, or
 * its own window toolkit) calls [signalReady] itself once it is usable; otherwise the old version
 * gives up waiting after a timeout and exits anyway.
 */
public object UpdateHandoff {
    /**
     * Set to `1` in the environment of an installer run as a hot update. The installer then leaves
     * the running application alone instead of closing it, and the old version's uninstaller keeps
     * its files in place.
     */
    public const val ENV_HOT_INSTALL: String = "NUCLEUS_HOT_UPDATE"

    /** File the new version creates once it is on screen. Set by the old version on the new one. */
    public const val ENV_READY_FILE: String = "NUCLEUS_UPDATE_READY_FILE"

    /**
     * Comma-separated process ids of the old version (its JVM and the launcher it runs under),
     * whose files the new version deletes once they have all exited.
     */
    public const val ENV_PREVIOUS_PID: String = "NUCLEUS_UPDATE_PREVIOUS_PID"

    /** Name of the directory holding the installed versions, under [VersionedInstall.root]. */
    public const val VERSIONS_DIR_NAME: String = "versions"

    /**
     * Suffix of a launcher moved aside during a hot update: a running executable can be renamed but
     * not overwritten, so the old launcher is renamed before the installer writes the new one.
     */
    public const val RETIRED_LAUNCHER_SUFFIX: String = ".nucleus-old"

    private const val RUNTIME_DIR_NAME = "runtime"
    private const val TRASH_PREFIX = ".trash-"
    private const val PREVIOUS_EXIT_TIMEOUT_SECONDS = 120L
    private const val CLEANUP_ATTEMPTS = 10
    private const val CLEANUP_RETRY_DELAY_MS = 300L

    private val logger: Logger = Logger.getLogger(UpdateHandoff::class.java.name)
    private val signaled = AtomicBoolean(false)

    /** The versioned install this process runs from, or `null` for any other layout or platform. */
    @JvmStatic
    public val versionedInstall: VersionedInstall? by lazy {
        detectVersionedInstall(
            javaHome = System.getProperty("java.home"),
            launcherPath = System.getProperty("jpackage.app-path"),
            isWindows = Platform.Current == Platform.Windows,
        )
    }

    /** Whether this process was launched by an older version handing over to it after a hot update. */
    @JvmStatic
    public val isHandoffLaunch: Boolean get() = System.getenv(ENV_READY_FILE) != null

    /**
     * Tells the version that launched this one that it is on screen, so it can exit, then deletes
     * the versions left behind by earlier updates once that version is gone. Idempotent and cheap:
     * the work runs on a background thread.
     */
    @JvmStatic
    public fun signalReady() {
        if (!signaled.compareAndSet(false, true)) return
        val readyFile = System.getenv(ENV_READY_FILE)
        if (readyFile == null && Platform.Current != Platform.Windows) return
        Thread({
            readyFile?.let(::writeReadyFile)
            awaitPreviousInstance()
            cleanupRetiredVersions()
        }, "nucleus-update-handoff").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Deletes the versions and launchers left behind by earlier hot updates. A version still in use
     * (another instance running it) cannot be renamed, which is how it is detected and kept.
     */
    @JvmStatic
    public fun cleanupRetiredVersions() {
        val install = versionedInstall ?: return
        cleanupRetiredVersions(install)
    }

    internal fun cleanupRetiredVersions(install: VersionedInstall) {
        // A process is reported gone slightly before Windows releases its image and mapped
        // DLLs, so what the previous version held may need a few more attempts.
        repeat(CLEANUP_ATTEMPTS) { attempt ->
            if (cleanupPass(install)) return
            if (attempt < CLEANUP_ATTEMPTS - 1) Thread.sleep(CLEANUP_RETRY_DELAY_MS)
        }
        logger.fine { "Retired versions still in use; the next start will retry" }
    }

    /** One cleanup pass; returns `true` when nothing retired is left. */
    private fun cleanupPass(install: VersionedInstall): Boolean {
        var clean = true
        val current = install.versionDir.canonicalFile
        install.versionsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.canonicalFile == current) return@forEach
            if (dir.name.startsWith(TRASH_PREFIX)) {
                if (!dir.deleteClearingReadOnly()) clean = false
                return@forEach
            }
            // Renaming first makes the deletion all-or-nothing: Windows refuses to rename a
            // directory with open files, so a version another instance still runs is left intact
            // instead of losing the files it has not opened yet.
            val trash = File(dir.parentFile, "$TRASH_PREFIX${dir.name}-${System.nanoTime()}")
            if (!dir.renameTo(trash) || !trash.deleteClearingReadOnly()) {
                logger.fine { "Could not delete retired version ${dir.name} yet" }
                clean = false
            }
        }
        install.root
            .listFiles { file -> file.isFile && file.name.endsWith(RETIRED_LAUNCHER_SUFFIX) }
            ?.forEach {
                // jpackage ships the launcher read-only, which Windows refuses to delete.
                it.setWritable(true)
                if (!it.delete()) {
                    logger.fine { "Could not delete retired launcher ${it.name} yet" }
                    clean = false
                }
            }
        return clean
    }

    /** [File.deleteRecursively] that first clears the read-only flag Windows refuses to delete. */
    private fun File.deleteClearingReadOnly(): Boolean {
        walkBottomUp().filter { !it.canWrite() }.forEach { it.setWritable(true) }
        return deleteRecursively()
    }

    private fun writeReadyFile(path: String) {
        val target = File(path)
        // The variable is inherited by whatever this instance starts later (a restart, say); by then
        // the version that waited for it is gone along with its directory, and nobody is listening.
        if (target.parentFile?.isDirectory != true) {
            logger.fine { "No update handoff waiting on $path" }
            return
        }
        try {
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(ProcessHandle.current().pid().toString())
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.WARNING, "Could not signal the update handoff through $path", e)
        }
    }

    private fun awaitPreviousInstance() {
        val pids = System.getenv(ENV_PREVIOUS_PID)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return
        logger.fine { "Waiting for the previous version to exit: $pids" }
        pids.forEach { pid -> awaitExit(pid) }
    }

    private fun awaitExit(pid: Long) {
        ProcessHandle.of(pid).ifPresent { previous ->
            try {
                previous.onExit().get(PREVIOUS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(Level.FINE, "Previous version $pid still running; cleanup may skip it", e)
            }
        }
    }

    /**
     * Recognizes the versioned layout from the running JVM: `java.home` is
     * `<root>\versions\<version>\runtime` and the launcher sits directly in `<root>`.
     */
    internal fun detectVersionedInstall(
        javaHome: String?,
        launcherPath: String?,
        isWindows: Boolean,
    ): VersionedInstall? {
        if (!isWindows || javaHome == null || launcherPath == null) return null
        val runtime = File(javaHome).absoluteFile
        val versionDir = runtime.parentFile ?: return null
        val versionsDir = versionDir.parentFile ?: return null
        val root = versionsDir.parentFile ?: return null
        val launcher = File(launcherPath).absoluteFile
        val matches =
            runtime.name.equals(RUNTIME_DIR_NAME, ignoreCase = true) &&
                versionsDir.name.equals(VERSIONS_DIR_NAME, ignoreCase = true) &&
                launcher.parentFile == root
        return if (matches) VersionedInstall(root, versionDir, launcher) else null
    }
}
