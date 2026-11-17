package dev.nucleusframework.tray.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Rendered icon files, named after their content and capped in number.
 *
 * Native backends take icons as file paths, and a tray whose icon changes (a progress badge, an
 * animation) would otherwise leave a new temp file behind on every frame until the JVM exits.
 * Identical renders share one file; past [capacity] distinct files the least recently written
 * one is deleted. Backends read an icon when it is applied, so only recent files are needed.
 */
internal object IconFileCache {
    private const val PREFIX = "tray_icon_"
    private val capacity = Integer.getInteger("nucleus.tray.iconCacheSize", 64).coerceAtLeast(4)

    private val directory: File by lazy {
        Files.createTempDirectory("nucleus-tray-").toFile().also { dir ->
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    dir.listFiles()?.forEach { it.delete() }
                    dir.delete()
                }, "NucleusTray-icon-cleanup"),
            )
        }
    }

    // Insertion/access ordered, so the eldest entry is the least recently written file.
    private val files = LinkedHashMap<String, File>(16, 0.75f, true)

    /** Returns the path of a file holding [bytes], writing it only when no file has them yet. */
    @Synchronized
    fun store(
        bytes: ByteArray,
        extension: String,
    ): String {
        val key = sha256(bytes).take(32) + "." + extension
        files[key]?.takeIf { it.isFile }?.let { return it.absolutePath }

        val file = File(directory, PREFIX + key)
        val partial = File(directory, PREFIX + key + ".part")
        partial.writeBytes(bytes)
        // Atomic: a backend never reads a half-written icon.
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        files[key] = file

        while (files.size > capacity) {
            val eldest = files.entries.iterator()
            val (_, stale) = eldest.next()
            eldest.remove()
            stale.delete()
        }
        return file.absolutePath
    }

    /** Number of icon files currently on disk (tests). */
    @Synchronized
    fun size(): Int = files.size

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
