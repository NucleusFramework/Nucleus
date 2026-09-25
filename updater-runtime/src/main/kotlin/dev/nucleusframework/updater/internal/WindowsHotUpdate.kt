package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.ExecutableType
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.core.runtime.SingleInstanceManager
import dev.nucleusframework.core.runtime.UpdateHandoff
import dev.nucleusframework.core.runtime.VersionedInstall
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private val logger: Logger = Logger.getLogger(WindowsHotUpdate::class.java.name)

/**
 * Hot update of a Windows NSIS install: the new version is installed **while this one keeps
 * running**, then launched, and this process only exits once the new version's first window is on
 * screen — the application never disappears while it updates.
 *
 * It relies on the versioned layout the Gradle plugin builds for NSIS (`versions\<version>\`
 * holding the runtime and the app, see [VersionedInstall]): the installer writes the new version
 * next to the running one and rewrites the launcher's `.cfg`, so nothing this process holds open is
 * touched. The installer is told it runs as a hot update through
 * [UpdateHandoff.ENV_HOT_INSTALL]; without that it would close the running application first.
 *
 * Installs are serialized across processes by a lock file in `versions\` — the role Chromium gives
 * its single machine-wide updater: an instance that finds another one installing waits, then sees
 * the new version already installed and only hands over to it.
 *
 * If the hot path cannot start, the classic close-install-relaunch update runs instead. If the
 * installer itself fails, the application simply keeps running: the classic update would run the
 * same installer, fail the same way, and close and reopen the app at every update check.
 */
@Suppress("TooManyFunctions")
internal object WindowsHotUpdate {
    private const val INSTALL_TIMEOUT_MINUTES = 10L
    private const val READY_TIMEOUT_MS = 30_000L
    private const val READY_POLL_MS = 20L
    private const val LOCK_ATTEMPTS = 50
    private const val LOCK_RETRY_MS = 100L
    private const val UNINSTALLER_PREFIX = "Uninstall "
    private const val INSTALL_LOCK_NAME = ".nucleus-install.lock"

    private val HOT_UPDATABLE_TYPES = setOf(ExecutableType.NSIS, ExecutableType.EXE, ExecutableType.NSIS_WEB)

    private val started = AtomicBoolean(false)

    /** Outcome of the locked part of a hot update. */
    private enum class InstallOutcome { INSTALLED, FAILED, CANNOT_START }

    /** The install to hot-update with [installer], or `null` when only a classic update applies. */
    fun eligibleInstall(
        installer: File,
        platform: Platform,
        type: ExecutableType,
        install: VersionedInstall? = UpdateHandoff.versionedInstall,
    ): VersionedInstall? {
        if (!installer.name.endsWith(".exe", ignoreCase = true)) return null
        return currentInstall(platform, type, install)
    }

    /** The versioned install this process can hot-update and hand over from, if any. */
    fun currentInstall(
        platform: Platform,
        type: ExecutableType,
        install: VersionedInstall? = UpdateHandoff.versionedInstall,
    ): VersionedInstall? {
        if (System.getProperty(DISABLE_PROPERTY).toBoolean()) return null
        if (platform != Platform.Windows || type !in HOT_UPDATABLE_TYPES) return null
        return install?.takeIf { it.launcher.isFile && canWriteInstall(it) }
    }

    /**
     * A per-machine install (`Program Files`) is not writable by the running app: it could neither
     * move its launcher aside nor delete the retired version, and the elevated installer does not
     * reliably inherit [UpdateHandoff.ENV_HOT_INSTALL] through UAC — it would close the app anyway.
     * Those installs take the classic update. Probed with a real file, since ACLs are what decide.
     */
    internal fun canWriteInstall(install: VersionedInstall): Boolean =
        try {
            val probe = File.createTempFile(".nucleus-write-probe", null, install.versionsDir)
            probe.delete()
            true
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            logger.info("Install directory is not writable (${e.message}); using a classic update")
            false
        }

    /**
     * Starts the hot update on a background thread and returns immediately: the application stays
     * usable while the installer runs, and exits once the new version has taken over, launched
     * with [relaunchArguments].
     */
    fun start(
        installer: File,
        install: VersionedInstall,
        relaunchArguments: List<String>,
    ) {
        if (!started.compareAndSet(false, true)) return
        Thread({ run(installer, install, relaunchArguments) }, "nucleus-hot-update").start()
    }

    /**
     * Hands over to the version another instance already installed, without installing anything.
     * Returns immediately; the process exits once the new version is on screen.
     */
    fun startHandOff(
        install: VersionedInstall,
        relaunchArguments: List<String>,
    ) {
        if (!started.compareAndSet(false, true)) return
        Thread({ handOff(install, relaunchArguments) }, "nucleus-hot-update").start()
    }

    private fun run(
        installer: File,
        install: VersionedInstall,
        relaunchArguments: List<String>,
    ) {
        val outcome =
            try {
                withInstallLock(install) { installLocked(installer, install) }
            } catch (e: IOException) {
                logger.log(Level.WARNING, "Could not take the install lock", e)
                InstallOutcome.CANNOT_START
            }
        when (outcome) {
            InstallOutcome.CANNOT_START -> {
                logger.warning("Hot update could not start; falling back to a classic update")
                started.set(false)
                PlatformInstaller.install(
                    installer,
                    Platform.Windows,
                    restart = true,
                    relaunchArguments = relaunchArguments,
                )
            }
            InstallOutcome.FAILED -> {
                logger.severe("Hot update failed; the application keeps running on its current version")
                started.set(false)
            }
            InstallOutcome.INSTALLED -> {
                installer.delete()
                handOff(install, relaunchArguments)
            }
        }
    }

    private fun installLocked(
        installer: File,
        install: VersionedInstall,
    ): InstallOutcome {
        // Another instance (an app without single instance) installed a newer version while this
        // one waited for the lock, or earlier: installing again would overwrite files it may be
        // running from. Just hand over.
        installedVersionDir(install)?.let { installed ->
            logger.info("${installed.name} is already installed; handing over to it")
            return InstallOutcome.INSTALLED
        }
        val workDir =
            try {
                retireLaunchers(install.root)
                createUpdateWorkDir()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(Level.WARNING, "Could not prepare the hot update", e)
                return InstallOutcome.CANNOT_START
            }
        val installed =
            try {
                runInstaller(installer, install, workDir)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(Level.WARNING, "Hot update installer failed", e)
                null
            }
        if (installed == null) return InstallOutcome.FAILED
        logger.info("Hot update installed ${installed.name}; handing over to it")
        return InstallOutcome.INSTALLED
    }

    /**
     * Runs [block] under the cross-process install lock: exclusive for an install, [shared] for a
     * reader that must not see an install half done. Blocks while another process holds it.
     *
     * A lock the same JVM already holds through another channel is reported by Java as an
     * [OverlappingFileLockException] rather than waited for, so that case is retried briefly (the
     * installed-version watcher only reads under the lock for a moment).
     */
    internal fun <T> withInstallLock(
        install: VersionedInstall,
        shared: Boolean = false,
        block: () -> T,
    ): T {
        RandomAccessFile(File(install.versionsDir, INSTALL_LOCK_NAME), "rw").use { file ->
            var attempt = 0
            while (true) {
                try {
                    file.channel.lock(0, Long.MAX_VALUE, shared).use { return block() }
                } catch (e: OverlappingFileLockException) {
                    if (++attempt >= LOCK_ATTEMPTS) throw IOException("Install lock held by this process", e)
                    Thread.sleep(LOCK_RETRY_MS)
                }
            }
        }
    }

    /**
     * Frees every launcher at the install root for the installer. A running executable cannot be
     * overwritten but can be renamed, so each one is moved aside and copied back: the copy is not
     * mapped by any process, so the installer can replace it, and the launcher path — shortcuts,
     * the Run key, protocol handlers — keeps working throughout the install.
     *
     * Returns the retired originals, which the new version deletes once this process has exited.
     */
    internal fun retireLaunchers(root: File): List<File> =
        root
            .listFiles { file ->
                file.isFile &&
                    file.name.endsWith(".exe", ignoreCase = true) &&
                    !file.name.startsWith(UNINSTALLER_PREFIX)
            }.orEmpty()
            .mapNotNull { launcher ->
                val suffix = "${System.nanoTime()}${UpdateHandoff.RETIRED_LAUNCHER_SUFFIX}"
                val retired = File(root, "${launcher.name}.$suffix")
                if (!launcher.renameTo(retired)) return@mapNotNull null
                Files.copy(retired.toPath(), launcher.toPath())
                // jpackage ships the launcher read-only, and the installer cannot overwrite that.
                launcher.setWritable(true)
                retired
            }

    /**
     * Runs the installer in hot mode and returns the version directory it installed, or `null` when
     * it failed or did not install a new version next to the running one.
     */
    private fun runInstaller(
        installer: File,
        install: VersionedInstall,
        workDir: File,
    ): File? {
        val script = File(workDir, "nucleus-hot-update.ps1")
        // Tells the script the app quit on its own: it must not be relaunched then. A process the
        // installer kills runs no shutdown hook, which is exactly the case the relaunch is for.
        val exitedMarker = File(workDir, "app-exited")
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { exitedMarker.createNewFile() } })
        writePowerShellScript(
            script,
            buildWindowsHotUpdateScript(
                pid = ProcessHandle.current().pid(),
                installerPath = installer.absolutePath,
                launcher = install.launcher.absolutePath,
                exitedMarker = exitedMarker.absolutePath,
            ),
        )
        val process =
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-WindowStyle",
                "Hidden",
                "-File",
                script.absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()[UpdateHandoff.ENV_HOT_INSTALL] = "1" }
                .start()
        if (!process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            logger.warning("Hot update installer did not finish within $INSTALL_TIMEOUT_MINUTES minutes")
            return null
        }
        workDir.deleteRecursively()
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            logger.warning("Hot update installer exited with code $exitCode")
            return null
        }
        return installedVersionDir(install)
    }

    /**
     * The version the launcher's `.cfg` now starts, when it is not the one this process runs — a
     * newer version installed next to it, by this process or another. Read from the `.cfg` rather
     * than derived from the update's version string, so the plugin's directory naming is the only
     * source of truth.
     */
    internal fun installedVersionDir(install: VersionedInstall): File? {
        val cfg = File(install.root, "app/${install.launcher.nameWithoutExtension}.cfg")
        if (!cfg.isFile || !install.launcher.isFile) return null
        val runtimeLine =
            cfg.readLines().firstOrNull { it.trim().startsWith(RUNTIME_KEY) } ?: return null
        val runtimePath = runtimeLine.substringAfter('=').trim()
        val prefix = "${ROOTDIR_MACRO}\\${UpdateHandoff.VERSIONS_DIR_NAME}\\"
        if (!runtimePath.startsWith(prefix, ignoreCase = true)) return null
        val versionName = runtimePath.removePrefix(prefix).substringBefore('\\')
        val versionDir = File(install.versionsDir, versionName)
        val isNew = !versionDir.name.equals(install.versionDir.name, ignoreCase = true)
        return versionDir.takeIf { isNew && File(it, "runtime").isDirectory }
    }

    /**
     * Launches the new version with [relaunchArguments] and exits once it signals it is on screen.
     * Should it quit before that, this process stays: an application that stays visible beats a gap.
     */
    private fun handOff(
        install: VersionedInstall,
        relaunchArguments: List<String>,
    ) {
        val workDir = createUpdateWorkDir()
        val readyFile = File(workDir, "ready")
        SingleInstanceManager.releaseForHandoff()
        val successor =
            try {
                ProcessBuilder(listOf(install.launcher.absolutePath) + relaunchArguments)
                    .directory(install.root)
                    .apply {
                        environment().remove(UpdateHandoff.ENV_HOT_INSTALL)
                        environment()[UpdateHandoff.ENV_READY_FILE] = readyFile.absolutePath
                        environment()[UpdateHandoff.ENV_PREVIOUS_PID] = previousPids(install).joinToString(",")
                    }.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(Level.SEVERE, "Could not launch the updated application", e)
                started.set(false)
                return
            }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS)
        while (!readyFile.isFile) {
            if (!successor.isAlive) {
                logger.severe(
                    "The updated application exited (code ${successor.exitValue()}) before showing a " +
                        "window; keeping this instance running",
                )
                workDir.deleteRecursively()
                started.set(false)
                return
            }
            if (System.nanoTime() > deadline) {
                logger.warning(
                    "The updated application did not signal readiness within ${READY_TIMEOUT_MS}ms " +
                        "(no Nucleus window? call UpdateHandoff.signalReady()); exiting anyway",
                )
                break
            }
            Thread.sleep(READY_POLL_MS)
        }
        workDir.deleteRecursively()
        exitProcess(0)
    }

    /**
     * This process, plus the launcher it runs under: the jpackage launcher restarts itself as a
     * child, and the parent — running the retired launcher executable — outlives the JVM briefly.
     * The new version waits for both before deleting what they hold.
     */
    private fun previousPids(install: VersionedInstall): List<Long> {
        val current = ProcessHandle.current()
        val launcherParent =
            current.parent().filter { parent ->
                parent
                    .info()
                    .command()
                    .map { File(it).absoluteFile.parentFile == install.root }
                    .orElse(false)
            }
        return listOf(current.pid()) + launcherParent.map { listOf(it.pid()) }.orElse(emptyList())
    }

    /** Set to `true` to always take the classic close-install-relaunch path. */
    internal const val DISABLE_PROPERTY = "nucleus.updater.hotUpdate.disabled"

    private const val RUNTIME_KEY = "app.runtime"
    private const val ROOTDIR_MACRO = "\$ROOTDIR"
}

/**
 * PowerShell that runs the NSIS installer as a hot update and waits for it. The environment
 * carries [UpdateHandoff.ENV_HOT_INSTALL], so a hot-update-aware installer leaves the application
 * running; should the installer close it anyway (one built without hot update support), the
 * script relaunches it once the installer is done, exactly like a classic update — but not when
 * the user quit the app during the install ([exitedMarker] exists then).
 *
 * Exits with the installer's exit code.
 */
internal fun buildWindowsHotUpdateScript(
    pid: Long,
    installerPath: String,
    launcher: String,
    exitedMarker: String,
): String =
    """
    |${'$'}installer = Start-Process '${psSingleQuote(installerPath)}' -ArgumentList '/S', '--updated' -Wait -PassThru
    |${'$'}code = ${'$'}installer.ExitCode
    |${'$'}closedByInstaller = -not (Get-Process -Id $pid -ErrorAction SilentlyContinue) -and
    |    -not (Test-Path -LiteralPath '${psSingleQuote(exitedMarker)}')
    |if (${'$'}closedByInstaller) {
    |    # The installer closed the application: relaunch it as a classic update would
    |    Remove-Item Env:${UpdateHandoff.ENV_HOT_INSTALL} -ErrorAction SilentlyContinue
    |    Start-Process '${psSingleQuote(launcher)}'
    |}
    |exit ${'$'}code
    """.trimMargin()
