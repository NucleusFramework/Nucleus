package dev.nucleusframework.updater.internal.delta

import java.io.File
import java.util.Locale

/**
 * The installer that electron-builder's NSIS template copies to
 * `%LOCALAPPDATA%\<updaterCacheDirName>\installer.exe` on every install, including the silent
 * installs this updater runs. It is the artifact of the version currently installed, so even the
 * very first update on a machine — before this updater has cached anything — can diff against it.
 *
 * electron-builder names the directory `sanitizeFileName(name).toLowerCase() + "-updater"`, where
 * `name` is the `package.json` name the plugin generates from the executable's name. The running
 * `.exe` carries that same name, so the directory is derived from it; when it cannot be found the
 * update is simply a full download.
 */
internal object SeededInstaller {
    private const val INSTALLER_NAME = "installer.exe"

    /** The seeded installer for the running Windows executable, or `null` when there is none. */
    fun locate(
        osName: String = System.getProperty("os.name").orEmpty(),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        executablePath: String? = currentExecutablePath(),
    ): File? {
        if (!osName.lowercase(Locale.ROOT).contains("win")) return null
        val root = localAppData?.takeIf { it.isNotEmpty() } ?: return null
        val executable =
            executablePath?.let(::File)?.takeIf { it.name.endsWith(".exe", ignoreCase = true) } ?: return null
        val installer = File(File(root, updaterCacheDirName(executable.name.dropLast(".exe".length))), INSTALLER_NAME)
        return installer.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * electron-builder's `updaterCacheDirName` for an app whose executable is [executableName]: the
     * plugin's npm-name normalisation (which `sanitizeFileName` leaves unchanged) plus `-updater`.
     */
    fun updaterCacheDirName(executableName: String): String =
        executableName
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-')
            .ifBlank { "app" } + "-updater"

    /**
     * The file name the release for [currentVersion] published [newFileName] under, assuming the
     * version appears in the artifact name as it does in electron-builder's default `artifactName`.
     * `null` when [newFileName] does not contain [newVersion].
     */
    fun previousArtifactName(
        newFileName: String,
        newVersion: String,
        currentVersion: String,
    ): String? =
        newFileName
            .takeIf { newVersion.isNotEmpty() && newVersion != currentVersion && it.contains(newVersion) }
            ?.replace(newVersion, currentVersion)

    private fun currentExecutablePath(): String? =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
}
