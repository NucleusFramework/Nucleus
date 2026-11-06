package dev.nucleusframework.lab.core.checks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckFileTest {
    private val dir: Path = Files.createTempDirectory("lab-checks")
    private val path: Path = dir.resolve("checks.json")

    @AfterTest
    fun cleanUp() {
        dir.toFile().setWritable(true)
        dir.toFile().deleteRecursively()
    }

    private fun pass(
        book: CheckBook,
        probe: String,
        check: String,
    ): CheckBook {
        val env = book.results["fp"].orEmpty()
        val probeResults = env[probe].orEmpty() + (check to CheckResult(CheckStatus.Pass, epochMillis = 1))
        return book.copy(results = book.results + ("fp" to env + (probe to probeResults)))
    }

    private fun CheckBook.checks(): Set<String> =
        results["fp"].orEmpty().flatMap { (probe, checks) -> checks.keys.map { "$probe/$it" } }.toSet()

    @Test
    fun `two processes writing in turn keep each other's results`() {
        // Each Lab process holds its own CheckFile and its own start-up copy of the book.
        val first = CheckFile(path)
        val second = CheckFile(path)
        val firstStartup = first.read() ?: CheckBook()
        val secondStartup = second.read() ?: CheckBook()

        first.update(firstStartup) { pass(it, "window.state", "a") }
        second.update(secondStartup) { pass(it, "window.popups", "b") }
        first.update(firstStartup) { pass(it, "window.state", "c") }

        assertEquals(
            setOf("window.state/a", "window.popups/b", "window.state/c"),
            CheckFile(path).read()!!.checks(),
        )
    }

    @Test
    fun `an unreadable file falls back to the book in memory and is repaired`() {
        path.writeText("""{"garbage": [1,2,""")
        val file = CheckFile(path)
        assertNull(file.read())

        val inMemory = pass(CheckBook(), "system.zstd", "roundtrip")
        val next = file.update(inMemory) { pass(it, "window.state", "a") }

        assertEquals(setOf("system.zstd/roundtrip", "window.state/a"), next.checks())
        assertEquals(next, CheckFile(path).read())
    }

    @Test
    fun `a failed write is reported and still returns the new book`() {
        val file = CheckFile(path)
        file.update(CheckBook()) { pass(it, "window.state", "a") }
        val before = path.readText()
        dir.toFile().setWritable(false)

        val next = file.update(CheckBook()) { pass(it, "window.state", "b") }

        assertEquals(setOf("window.state/a", "window.state/b"), next.checks())
        assertEquals(before, path.readText())
    }
}
