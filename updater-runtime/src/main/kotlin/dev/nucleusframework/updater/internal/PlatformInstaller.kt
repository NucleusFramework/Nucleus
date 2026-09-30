package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import kotlin.system.exitProcess

/**
 * Creates a fresh, owner-only (POSIX `0700`) working directory for the detached update
 * scripts and their log.
 *
 * A predictable path in the shared temp dir — e.g. `/tmp/nucleus-update.sh` on Linux — lets
 * any other local user pre-create that path as a file they own or as a symlink, so this
 * process ends up writing the script body into (and then executing) a file the attacker
 * controls. Each update run therefore gets its own unguessable private directory, mirroring
 * the owner-only download staging directory used one layer up in `NucleusUpdater`.
 *
 * Exposed for unit tests.
 */
internal fun createUpdateWorkDir(): File = Files.createTempDirectory("nucleus-update-").toFile()

/**
 * Walks up from [launcher]'s directory looking for [PlatformInstaller.UPDATE_HELPER_NAME].
 * Exposed for unit tests (jpackage may run as `/opt/App/bin/App` or `/usr/bin/App`).
 */
internal fun resolveUpdateHelperFromLauncher(
    launcher: String,
    helperName: String = PlatformInstaller.UPDATE_HELPER_NAME,
    maxDepth: Int = PlatformInstaller.HELPER_SEARCH_MAX_DEPTH,
): File? {
    var dir = File(launcher).canonicalFile.parentFile ?: return null
    repeat(maxDepth) {
        val helper = dir.resolve(helperName)
        if (helper.isFile) return helper
        dir = dir.parentFile ?: return null
    }
    return null
}

private val logger: Logger = Logger.getLogger(PlatformInstaller::class.java.name)

@Suppress("TooManyFunctions")
internal object PlatformInstaller {
    /**
     * Package-owned silent-update helper file name (must match plugin
     * `LinuxUpdateHelper.HELPER_FILE_NAME`).
     */
    internal const val UPDATE_HELPER_NAME = "nucleus-update-helper"

    /** Max parents walked from the launcher path when looking for [UPDATE_HELPER_NAME]. */
    internal const val HELPER_SEARCH_MAX_DEPTH = 3

    fun install(
        file: File,
        platform: Platform,
        restart: Boolean = true,
    ) {
        val extension = file.name.substringAfterLast('.').lowercase()

        when {
            platform == Platform.MacOS && extension == "zip" -> installMacZip(file, restart)
            platform == Platform.Windows -> installWindows(file, extension, restart)
            platform == Platform.Linux && extension == "appimage" -> installLinuxAppImage(file, restart)
            platform == Platform.Linux && (extension == "deb" || extension == "rpm") ->
                installLinuxPackage(file, extension, restart)
            else -> buildProcessForInstaller(file, platform, extension).start()
        }
        exitProcess(0)
    }

    private fun buildProcessForInstaller(
        file: File,
        platform: Platform,
        extension: String,
    ): ProcessBuilder =
        when (platform) {
            Platform.Linux -> buildLinuxInstaller(file, extension)
            Platform.MacOS -> buildMacInstaller(file)
            Platform.Windows -> error("Windows uses installWindows()")
            Platform.Unknown -> error("Unsupported platform: ${System.getProperty("os.name")}")
        }

    private fun buildLinuxInstaller(
        file: File,
        extension: String,
    ): ProcessBuilder =
        when (extension) {
            "deb" -> ProcessBuilder("sudo", "dpkg", "-i", file.absolutePath)
            "rpm" -> ProcessBuilder("sudo", "rpm", "-U", file.absolutePath)
            else -> ProcessBuilder("xdg-open", file.absolutePath)
        }

    private fun installLinuxAppImage(
        newAppImage: File,
        restart: Boolean,
    ) {
        val pid = ProcessHandle.current().pid()
        val currentAppImage =
            System.getenv("APPIMAGE")
                ?: error("APPIMAGE environment variable not set — update is only supported from a packaged AppImage")
        val destination = File(currentAppImage)

        // electron-updater pattern: unlink + replace while the running mount still holds the
        // previous inode open. Avoids racing the FUSE unmount that follows process exit.
        val replacedInPlace = replaceAppImageInPlace(newAppImage, destination)

        val workDir = createUpdateWorkDir()
        val script = File(workDir, "nucleus-update.sh")
        val logFile = File(workDir, "nucleus-update.log")
        script.writeText(
            buildLinuxAppImageUpdateScript(
                newFile = newAppImage.absolutePath,
                oldFile = destination.absolutePath,
                appPid = pid,
                logFile = logFile.absolutePath,
                restart = restart,
                alreadyReplaced = replacedInPlace,
            ),
        )
        script.setExecutable(true)

        // New session, started from $HOME so a FUSE-mount CWD cannot poison the relaunch.
        val home = File(System.getProperty("user.home") ?: workDir.absolutePath)
        ProcessBuilder("setsid", "bash", script.absolutePath)
            .directory(home)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /**
     * Swaps [newAppImage] onto [destination] while this process is still running.
     *
     * Returns `true` when [destination] holds the new bytes (caller may leave [newAppImage]
     * missing). Returns `false` when the swap could not be completed; the detached script
     * will retry after this process exits.
     */
    internal fun replaceAppImageInPlace(
        newAppImage: File,
        destination: File,
    ): Boolean {
        if (!newAppImage.isFile) return false
        return try {
            // Unlink first so a busy destination (open as the loop/FUSE backend) does not block
            // the subsequent rename the way a direct overwrite can on some kernels.
            if (destination.exists() && !destination.delete() && destination.exists()) {
                return false
            }
            val moved =
                newAppImage.renameTo(destination) ||
                    run {
                        newAppImage.copyTo(destination, overwrite = true)
                        newAppImage.delete()
                        destination.isFile
                    }
            if (moved) {
                // ownerOnly = false: match `chmod +x` so any user can relaunch the AppImage
                destination.setExecutable(true, false)
            }
            moved && destination.isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun installLinuxPackage(
        packageFile: File,
        extension: String,
        restart: Boolean,
    ) {
        val pid = ProcessHandle.current().pid()
        val launcher =
            currentExecutablePath()
                ?: error("Cannot resolve application launcher from the running process")

        // Prefer the passwordless, signature-verifying update helper (plugin silentUpdate).
        // Both helper and detached <pkg>.asc are required for that path; otherwise we fall back
        // to a password-prompting install and log why (no silent fallback without a reason).
        val helper = resolveUpdateHelper(launcher)
        val signatureFile = File("${packageFile.absolutePath}.asc")
        val silentHelper = helper?.takeIf { signatureFile.isFile }
        if (silentHelper == null) logLinuxInstallFallback(helper, signatureFile)

        val script = File(createUpdateWorkDir(), "nucleus-update.sh")
        script.writeText(
            buildLinuxPackageUpdateScript(
                packageFile = packageFile.absolutePath,
                extension = extension,
                launcher = launcher,
                helper = silentHelper?.absolutePath,
                appPid = pid,
                restart = restart,
            ),
        )
        script.setExecutable(true)

        ProcessBuilder("setsid", "bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /**
     * Resolves the passwordless update helper installed in the app dir, or `null` if absent.
     *
     * The helper is packaged as `/opt/<App>/nucleus-update-helper`. The running process may be
     * `/usr/bin/<app>` (symlink), `/opt/<App>/<app>`, or `/opt/<App>/bin/<app>` (jpackage layout),
     * so walk up a few parents from the canonical launcher path until the helper is found.
     */
    internal fun resolveUpdateHelper(launcher: String): File? = resolveUpdateHelperFromLauncher(launcher)

    private fun logLinuxInstallFallback(
        helper: File?,
        signatureFile: File,
    ) {
        val reason =
            when {
                helper == null ->
                    "no $UPDATE_HELPER_NAME next to the launcher (app not packaged with silentUpdate?)"
                !signatureFile.isFile ->
                    "helper found at ${helper.absolutePath} but detached signature missing: " +
                        "${signatureFile.absolutePath} (publish <pkg>.asc next to the package)"
                else -> "unknown"
            }
        logger.warning {
            "Passwordless Linux update unavailable ($reason); falling back to interactive pkexec install"
        }
    }

    /**
     * Resolves the absolute path of the executable that launched the current process.
     *
     * Unlike reconstructing the launcher from `java.home`, this works identically for a
     * jpackage launcher on the JVM and for a single-file GraalVM native image, where the
     * `java.home` runtime layout does not exist.
     */
    private fun currentExecutablePath(): String? =
        ProcessHandle
            .current()
            .info()
            .command()
            .map { File(it).absolutePath }
            .orElse(null)

    private fun buildMacInstaller(file: File): ProcessBuilder = ProcessBuilder("open", file.absolutePath)

    private fun installMacZip(
        zipFile: File,
        restart: Boolean,
    ) {
        val appBundle =
            resolveCurrentAppBundle()
                ?: error("Cannot resolve current .app bundle from java.home")
        val installDir = appBundle.parentFile
        val workDir = createUpdateWorkDir()

        val script = File(workDir, "nucleus-update.sh")
        script.writeText(
            buildMacZipUpdateScript(
                zipFile = zipFile.absolutePath,
                appPath = appBundle.absolutePath,
                installDir = installDir.absolutePath,
                appPid = ProcessHandle.current().pid(),
                logFile = File(workDir, "nucleus-update.log").absolutePath,
                restart = restart,
            ),
        )
        script.setExecutable(true)

        // Launch the script as a detached process that survives our exit
        ProcessBuilder("bash", script.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        // exitProcess(0) is called by install() right after this returns
    }

    private fun resolveCurrentAppBundle(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        var dir = File(javaHome)
        while (dir.parentFile != null) {
            if (dir.name.endsWith(".app")) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun installWindows(
        file: File,
        extension: String,
        restart: Boolean,
    ) {
        val pid = ProcessHandle.current().pid()
        val launcher = currentExecutablePath()
        val script = File(createUpdateWorkDir(), "nucleus-update.ps1")
        script.writeText(
            buildWindowsUpdateScript(
                pid = pid,
                installerCommand = windowsInstallerCommand(file, extension),
                relaunchCommand = windowsRelaunchCommand(restart, launcher),
                artifactPath = file.absolutePath,
                scriptPath = script.absolutePath,
            ),
        )

        ProcessBuilder(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
