package dev.nucleusframework.updater.internal

import java.io.File

/**
 * Persists a marker file before an update install so the next launch
 * can detect that the application was just updated.
 *
 * The marker is stored in the application data directory ([resolveAppDataDir]).
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

    private fun markerFile(): File = File(resolveAppDataDir(), MARKER_FILE_NAME)
}
