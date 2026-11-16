package dev.nucleusframework.widget.macos.host

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The directory the app and its widget extension share (an App Group container, or a folder under
 * the user's home the extension's sandbox is allowed into):
 *
 * - `renders/<kind>/<family>.json`: the composed widget the extension draws;
 * - `previews/<kind>/<family>.json`: the same, from an empty state, for the widget gallery;
 * - `state/<kind>/<family>.json`: the widget state (the app's alone);
 * - `requests/<id>.json`: what the extension asks (a render, an action), `done/<id>` the answer;
 * - `images/`: the images the renders point at, read by the extension;
 * - `launched-by-widget`: written by the extension right before it starts the app.
 */
internal class WidgetStore(
    val root: File,
) {
    val requests = root.resolve("requests")
    val done = root.resolve("done")
    val images = root.resolve("images")
    private val launchMarker = root.resolve("launched-by-widget")

    fun render(
        kind: String,
        family: String,
    ): File = root.resolve("renders/$kind/$family.json")

    fun preview(
        kind: String,
        family: String,
    ): File = root.resolve("previews/$kind/$family.json")

    fun state(
        kind: String,
        family: String,
    ): File = root.resolve("state/$kind/$family.json")

    fun create() {
        listOf(requests, done, images).forEach { it.mkdirs() }
    }

    /**
     * Whether the extension started this process within [maxAgeMillis]; consumes the marker, so
     * only the launch it was written for sees it.
     */
    fun consumeLaunchMarker(
        nowMillis: Long,
        maxAgeMillis: Long,
    ): Boolean {
        if (!launchMarker.isFile) return false
        val age = nowMillis - launchMarker.lastModified()
        launchMarker.delete()
        return age in 0..maxAgeMillis
    }

    /** The pending requests, oldest first, without the ones still being written (dot files). */
    fun pendingRequests(): List<File> =
        requests
            .listFiles { file -> file.isFile && file.name.endsWith(".json") && !file.name.startsWith(".") }
            .orEmpty()
            .sortedBy { it.lastModified() }

    fun acknowledge(id: String) {
        done.mkdirs()
        writeAtomically(done.resolve(id), "")
    }

    /** Drops answers no extension waited for (a request it did not wait on), and old requests. */
    fun sweep(
        nowMillis: Long,
        maxAgeMillis: Long,
    ) {
        done
            .listFiles()
            .orEmpty()
            .filter { nowMillis - it.lastModified() > maxAgeMillis }
            .forEach { it.delete() }
        requests
            .listFiles { file -> file.name.startsWith(".") }
            .orEmpty()
            .filter { nowMillis - it.lastModified() > maxAgeMillis }
            .forEach { it.delete() }
    }

    companion object {
        /** Writes [text] beside [file] and renames it over, so the other process never reads half of it. */
        fun writeAtomically(
            file: File,
            text: String,
        ) {
            writeAtomically(file, text.toByteArray())
        }

        fun writeAtomically(
            file: File,
            bytes: ByteArray,
        ) {
            file.parentFile.mkdirs()
            val partial = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
            try {
                partial.writeBytes(bytes)
                Files.move(
                    partial.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: IOException) {
                partial.delete()
                throw e
            }
        }
    }
}
