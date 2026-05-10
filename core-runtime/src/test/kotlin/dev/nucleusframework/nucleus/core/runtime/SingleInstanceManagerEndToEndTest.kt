package dev.nucleusframework.nucleus.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for [SingleInstanceManager].
 *
 * Each test launches real subprocesses exercising [SingleInstanceManager]
 * to avoid singleton state pollution between tests.
 *
 * Tests cover:
 * - Normal behavior (single instance, rejection, clean relaunch, restore requests)
 * - Issue #161: stale/read-only residual lock files must not block startup
 */
class SingleInstanceManagerEndToEndTest {
    private lateinit var tempDir: Path
    private val lockIdentifier = "e2e-test-instance"

    private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    private val classpath = System.getProperty("java.class.path")
    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("single-instance-e2e")
    }

    @After
    fun tearDown() {
        processes.forEach { p ->
            if (p.isAlive) p.destroyForcibly()
        }
        processes.forEach { p -> p.waitFor(5, TimeUnit.SECONDS) }
        tempDir.toFile().deleteRecursively()
    }

    // ── Normal behavior ───────────────────────────────────────────────

    @Test
    fun `first instance acquires lock and reports SINGLE`() {
        val process = startProcess()
        assertEquals("SINGLE", readSignal(process))
    }

    @Test
    fun `second instance is rejected while first holds the lock`() {
        val primary = startProcess()
        assertEquals("SINGLE", readSignal(primary))

        val secondary = startProcess()
        assertEquals("NOT_SINGLE", readSignal(secondary))
    }

    @Test
    fun `relaunch succeeds after first instance exits cleanly`() {
        val first = startProcess(holdSeconds = 1)
        assertEquals("SINGLE", readSignal(first))

        // Wait for first to exit via its shutdown hook
        assertTrue("First process should exit cleanly", first.waitFor(10, TimeUnit.SECONDS))
        assertEquals("First process should exit with code 0", 0, first.exitValue())

        // Lock file should be cleaned up by the shutdown hook
        val lockFile = tempDir.resolve("$lockIdentifier.lock").toFile()
        assertFalse("Lock file should be deleted by shutdown hook", lockFile.exists())

        // Second launch must succeed
        val second = startProcess(holdSeconds = 1)
        assertEquals("SINGLE", readSignal(second))
        assertTrue(second.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, second.exitValue())
    }

    @Test
    fun `relaunch succeeds after first instance is killed (SIGKILL)`() {
        val first = startProcess()
        assertEquals("SINGLE", readSignal(first))

        first.destroyForcibly()
        assertTrue(first.waitFor(5, TimeUnit.SECONDS))

        // Lock file remains (shutdown hook didn't run)
        val lockFile = tempDir.resolve("$lockIdentifier.lock").toFile()
        assertTrue("Lock file should remain after SIGKILL", lockFile.exists())

        // Relaunch must still succeed
        val second = startProcess(holdSeconds = 1)
        assertEquals("SINGLE", readSignal(second))
        assertTrue(second.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, second.exitValue())
    }

    @Test
    fun `secondary instance exits with code 1`() {
        val primary = startProcess()
        assertEquals("SINGLE", readSignal(primary))

        val secondary = startProcess()
        assertEquals("NOT_SINGLE", readSignal(secondary))
        assertTrue(secondary.waitFor(5, TimeUnit.SECONDS))
        assertEquals("Secondary should exit with code 1", 1, secondary.exitValue())
    }

    @Test
    fun `primary receives restore request from secondary`() {
        val primary = startProcess()
        val primaryReader = primary.inputStream.bufferedReader()
        assertEquals("SINGLE", primaryReader.readLine())

        // Launch secondary — it creates a .restore_request file
        val secondary = startProcess()
        assertEquals("NOT_SINGLE", readSignal(secondary))
        assertTrue(secondary.waitFor(5, TimeUnit.SECONDS))

        // Primary's WatchService should detect it (macOS polling can be slow)
        val restoreLine = waitForLine(primary, primaryReader, "RESTORE_REQUEST", timeoutMs = 15_000)
        assertEquals("Primary should receive RESTORE_REQUEST", "RESTORE_REQUEST", restoreLine)
    }

    // ── Issue #161: stale residual files ───────────────────────────────

    @Test
    fun `stale lock file does not prevent new instance from starting`() {
        createStaleLockFile()
        val process = startProcess(holdSeconds = 1)
        assertEquals("SINGLE", readSignal(process))
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `stale lock AND restore_request files do not prevent new instance`() {
        createStaleLockFile()
        tempDir.resolve("$lockIdentifier.restore_request").toFile().createNewFile()

        val process = startProcess(holdSeconds = 1)
        assertEquals("SINGLE", readSignal(process))
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `read-only stale lock file does not prevent new instance from starting`() {
        val staleLock = createStaleLockFile()
        staleLock.setReadOnly()
        assertTrue("Lock file must be read-only", !staleLock.canWrite())

        val process = startProcess(holdSeconds = 1)
        try {
            assertEquals("SINGLE", readSignal(process))
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            staleLock.setWritable(true)
        }
    }

    @Test
    fun `read-only stale lock AND restore_request do not prevent new instance`() {
        val staleLock = createStaleLockFile()
        staleLock.setReadOnly()
        tempDir.resolve("$lockIdentifier.restore_request").toFile().createNewFile()

        val process = startProcess(holdSeconds = 1)
        try {
            assertEquals("SINGLE", readSignal(process))
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            staleLock.setWritable(true)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createStaleLockFile(): File {
        val lockFile = tempDir.resolve("$lockIdentifier.lock").toFile()
        lockFile.createNewFile()
        assertTrue("Stale lock file must exist", lockFile.exists())
        return lockFile
    }

    private fun startProcess(holdSeconds: Long? = null): Process {
        val args =
            mutableListOf(
                javaBin,
                "-cp",
                classpath,
                "dev.nucleusframework.nucleus.core.runtime.SingleInstanceHolderKt",
                tempDir.toAbsolutePath().toString(),
                lockIdentifier,
            )
        if (holdSeconds != null) args.add(holdSeconds.toString())

        val process = ProcessBuilder(args).start()
        processes.add(process)
        return process
    }

    private fun readSignal(process: Process): String = process.inputStream.bufferedReader().readLine()

    @Suppress("LoopWithTooManyJumpStatements")
    private fun waitForLine(
        process: Process,
        reader: BufferedReader,
        marker: String,
        timeoutMs: Long,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive && !reader.ready()) return null
            if (reader.ready()) {
                val line = reader.readLine() ?: return null
                if (line.contains(marker)) return line
            } else {
                Thread.sleep(50)
            }
        }
        return null
    }
}
