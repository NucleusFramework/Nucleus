package dev.nucleusframework.lab.core.checks

import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `checks.json` on disk, shared by every Lab process of one user: two instances that run side by
 * side (a dev run next to a packaged one, both sandboxed in the same container) each apply their
 * change to the file as it is now, never to a copy loaded at start-up, so neither erases the
 * other's results. Writes go through a temporary file and a move, so a reader never sees half a
 * file.
 */
internal class CheckFile(
    private val file: Path,
) {
    /** The book on disk, or `null` when there is none or it cannot be read. */
    fun read(): CheckBook? =
        runCatching { if (file.exists()) json.decodeFromString(CheckBook.serializer(), file.readText()) else null }
            .onFailure { logger.log(Level.WARNING, "Unreadable $file", it) }
            .getOrNull()

    /**
     * Applies [change] to the book on disk ([fallback] when there is none) and writes the result.
     * Returns the new book even when the write fails, which is logged.
     */
    fun update(
        fallback: CheckBook,
        change: (CheckBook) -> CheckBook,
    ): CheckBook =
        synchronized(this) {
            val next = change(read() ?: fallback)
            runCatching { write(next) }.onFailure { logger.log(Level.WARNING, "Could not write $file", it) }
            next
        }

    private fun write(book: CheckBook) {
        val temp = file.resolveSibling("${file.fileName}.${ProcessHandle.current().pid()}.tmp")
        temp.writeText(json.encodeToString(CheckBook.serializer(), book))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        val json = Json { prettyPrint = true }
        val logger: Logger = Logger.getLogger(CheckFile::class.java.name)
    }
}
