package dev.nucleusframework.nucleus.updater.internal

import dev.nucleusframework.nucleus.core.runtime.NucleusApp
import java.io.File

/**
 * Persists a marker file before an update install so the next launch
 * can detect that the application was just updated.
 *
 * The marker is stored in the platform-specific application data directory
 * resolved from [NucleusApp.appId]:
 * - Linux:   `$XDG_DATA_HOME/<appId>/` or `~/.local/share/<appId>/`
 * - macOS:   `~/Library/Application Support/<appId>/`
 * - Windows: `%APPDATA%/<appId>/`
 */
internal object UpdateMarker {
    private const val MARKER_FILE_NAME = "nucleus-update-event"
    private const val KEY_PREVIOUS_VERSION = "previousVersion"
    private const val KEY_NEW_VERSION = "newVersion"

    fun write(
        previousVersion: String,
        newVersion: String,
    ) {
        val file = markerFile()
        file.parentFile?.mkdirs()
        file.writeText("$KEY_PREVIOUS_VERSION=$previousVersion\n$KEY_NEW_VERSION=$newVersion\n")
    }

    fun read(): Pair<String, String>? {
        val file = markerFile()
        if (!file.isFile) return null
        return try {
            val props =
                file.readLines().associate { line ->
                    val (key, value) = line.split("=", limit = 2)
                    key.trim() to value.trim()
                }
            val previous = props[KEY_PREVIOUS_VERSION] ?: return null
            val newVer = props[KEY_NEW_VERSION] ?: return null
            previous to newVer
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    }

    fun exists(): Boolean = markerFile().isFile

    fun delete() {
        markerFile().delete()
    }

    private fun markerFile(): File = File(resolveDataDir(), MARKER_FILE_NAME)

    private fun resolveDataDir(): File {
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
}
