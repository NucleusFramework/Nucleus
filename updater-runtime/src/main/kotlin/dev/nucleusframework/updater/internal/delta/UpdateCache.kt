package dev.nucleusframework.updater.internal.delta

import dev.nucleusframework.core.runtime.NucleusApp
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.logging.Level
import java.util.logging.Logger

/** What the cache remembers about the artifact it holds. */
internal data class CachedArtifact(
    val fileName: String,
    val version: String,
) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
}

/**
 * Keeps the last downloaded installer and its block map so the *next* update can be differential.
 *
 * A differential download needs the previous artifact locally, and every platform installer deletes
 * the file it consumed. The cache therefore holds a second reference to it — a hard link when the
 * filesystem allows one, so the artifact costs no extra disk space, and a copy otherwise. It holds
 * exactly one artifact: each successful download replaces the previous one.
 *
 * AppImages never need the cache — the running executable *is* the previous artifact, block map
 * included — so a Linux AppImage user gets differential updates immediately, while other formats
 * get them from the second update on.
 */
internal class UpdateCache(
    private val dir: File,
) {
    val artifact: File get() = File(dir, ARTIFACT_NAME)
    val blockMap: File get() = File(dir, BLOCK_MAP_NAME)

    /** Metadata of the cached artifact, or `null` when the cache is empty or unreadable. */
    fun read(): CachedArtifact? {
        val meta = File(dir, META_NAME)
        if (!meta.isFile || !artifact.isFile || artifact.length() == 0L) return null
        return try {
            val values =
                meta
                    .readLines()
                    .filter { it.contains('=') }
                    .associate { line ->
                        val (key, value) = line.split("=", limit = 2)
                        key.trim() to value.trim()
                    }
            val fileName = values[KEY_FILE_NAME]?.takeIf { it.isNotEmpty() } ?: return null
            CachedArtifact(fileName, values[KEY_VERSION].orEmpty())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.log(Level.FINE, "Cannot read the update cache metadata", e)
            null
        }
    }

    /**
     * Replaces the cache contents with [source]. [blockMapGzip] is the artifact's standalone block
     * map; pass `null` for artifacts that embed their own (it is read back from the artifact) or
     * when none could be fetched — the next update then falls back to a full download.
     */
    fun store(
        source: File,
        fileName: String,
        version: String,
        blockMapGzip: ByteArray?,
    ) {
        try {
            clear()
            dir.mkdirs()
            link(source, artifact)
            if (blockMapGzip != null) blockMap.writeBytes(blockMapGzip)
            File(dir, META_NAME).writeText("$KEY_FILE_NAME=$fileName\n$KEY_VERSION=$version\n")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The cache is an optimisation: a failure here only costs the next update its delta.
            logger.log(Level.FINE, "Cannot populate the update cache", e)
            clear()
        }
    }

    fun clear() {
        artifact.delete()
        blockMap.delete()
        File(dir, META_NAME).delete()
    }

    /** Hard-links [source] to [target] so the cached copy costs no extra space, copying if it cannot. */
    private fun link(
        source: File,
        target: File,
    ) {
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (
            @Suppress("SwallowedException") e: IOException,
        ) {
            // Different volume, or a filesystem without hard links: a copy is equally correct.
            logger.log(Level.FINE, "Hard link unavailable, copying the artifact into the cache instead")
            source.copyTo(target, overwrite = true)
        } catch (
            @Suppress("SwallowedException") e: UnsupportedOperationException,
        ) {
            logger.log(Level.FINE, "Hard link unsupported, copying the artifact into the cache instead")
            source.copyTo(target, overwrite = true)
        }
    }

    internal companion object {
        private const val ARTIFACT_NAME = "current-artifact"
        private const val BLOCK_MAP_NAME = "current.blockmap"
        private const val META_NAME = "current.meta"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_VERSION = "version"

        private val logger: Logger = Logger.getLogger(UpdateCache::class.java.name)

        /**
         * `%LOCALAPPDATA%/nucleus/updates/<appId>` on Windows, `~/.cache/nucleus/updates/<appId>`
         * elsewhere — the same convention as the native library cache.
         */
        fun default(): UpdateCache = UpdateCache(File(resolveCacheRoot(), "updates/${NucleusApp.appId}"))

        private fun resolveCacheRoot(): File {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name", "").lowercase()
            return if (os.contains("win")) {
                File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "nucleus")
            } else {
                File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "nucleus")
            }
        }
    }
}
