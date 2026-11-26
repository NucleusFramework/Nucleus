package dev.nucleusframework.updater.internal

import dev.nucleusframework.core.runtime.NucleusApp
import java.io.File

/**
 * The platform-specific application data directory resolved from [NucleusApp.appId], where the
 * updater keeps the files that outlive a restart ([UpdateMarker], [InstallFailureRecord]):
 * - Linux:   `$XDG_DATA_HOME/<appId>/` or `~/.local/share/<appId>/`
 * - macOS:   `~/Library/Application Support/<appId>/`
 * - Windows: `%APPDATA%/<appId>/`
 */
internal fun resolveAppDataDir(): File {
    val appId = NucleusApp.appId
    val os = System.getProperty("os.name", "").lowercase()
    val home = System.getProperty("user.home")

    return when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
            File(appData, appId)
        }
        os.contains("mac") -> {
            File(home, "Library/Application Support/$appId")
        }
        else -> {
            val xdgData = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
            File(xdgData, appId)
        }
    }
}
