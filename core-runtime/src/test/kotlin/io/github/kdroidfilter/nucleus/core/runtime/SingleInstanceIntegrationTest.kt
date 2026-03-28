package io.github.kdroidfilter.nucleus.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real multi-process integration tests for [SingleInstanceManager].
 *
 * Each test launches one or more child JVM processes running
 * [SingleInstanceTestHarness] and asserts on their exit codes and stdout output.
 */
class SingleInstanceIntegrationTest {
    private lateinit var lockDir: Path
    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        lockDir = Files.createTempDirectory("si-integration-")
    }

    @After
    fun tearDown() {
        processes.forEach { p ->
            if (p.isAlive) p.destroyForcibly()
        }
        lockDir.toFile().deleteRecursively()
    }

    // ── Test 1: single launch acquires lock ──────────────────────────

    @Test
    fun `single instance acquires lock and exits cleanly`() {
        val p = launchHarness(lockId = "test1", holdSeconds = 1)
        val output = waitForOutput(p, "READY", timeoutMs = 5_000)

        assertTrue("Should print LOCK_ACQUIRED", output.any { it == "LOCK_ACQUIRED" })
        assertTrue("Should print READY", output.any { it == "READY" })

        val exited = p.waitFor(10, TimeUnit.SECONDS)
        assertTrue("Process should exit within timeout", exited)
        assertEquals("Primary instance should exit with code 0", 0, p.exitValue())
    }

    // ── Test 2: second instance is denied ────────────────────────────

    @Test
    fun `second instance is denied while first holds the lock`() {
        val lockId = "test2"

        // Launch primary — hold lock for 5s
        val primary = launchHarness(lockId = lockId, holdSeconds = 5)
        waitForOutput(primary, "READY", timeoutMs = 5_000)

        // Launch secondary — should be denied immediately
        val secondary = launchHarness(lockId = lockId, holdSeconds = 0)
        val secondaryExited = secondary.waitFor(10, TimeUnit.SECONDS)
        assertTrue("Secondary should exit promptly", secondaryExited)
        assertEquals("Secondary should exit with code 1 (denied)", 1, secondary.exitValue())

        val secondaryOutput = secondary.inputStream.bufferedReader().readText()
        assertTrue(
            "Secondary should print LOCK_DENIED",
            secondaryOutput.contains("LOCK_DENIED"),
        )

        // Clean up primary
        primary.destroyForcibly()
    }

    // ── Test 3: relaunch after clean exit ────────────────────────────

    @Test
    fun `relaunch succeeds after first instance exits cleanly`() {
        val lockId = "test3"

        // First launch — hold 1s then exit
        val first = launchHarness(lockId = lockId, holdSeconds = 1)
        val firstExited = first.waitFor(10, TimeUnit.SECONDS)
        assertTrue("First process should exit", firstExited)
        assertEquals(0, first.exitValue())

        // Second launch — should succeed
        val second = launchHarness(lockId = lockId, holdSeconds = 1)
        val output = waitForOutput(second, "READY", timeoutMs = 5_000)
        assertTrue("Relaunch should acquire lock", output.any { it == "LOCK_ACQUIRED" })

        val secondExited = second.waitFor(10, TimeUnit.SECONDS)
        assertTrue("Second process should exit", secondExited)
        assertEquals(0, second.exitValue())
    }

    // ── Test 4: relaunch after kill -9 (stale lock) ──────────────────

    @Test
    fun `relaunch succeeds after first instance is killed`() {
        val lockId = "test4"

        // Launch and hold lock
        val first = launchHarness(lockId = lockId, holdSeconds = 30)
        waitForOutput(first, "READY", timeoutMs = 5_000)

        // Kill without shutdown hook
        first.destroyForcibly()
        first.waitFor(5, TimeUnit.SECONDS)

        // Lock file may still exist, but OS should have released the lock
        val lockFile = lockDir.resolve("$lockId.lock").toFile()
        assertTrue("Stale lock file should still exist after kill", lockFile.exists())

        // Relaunch — should succeed (OS releases file locks on process death)
        val second = launchHarness(lockId = lockId, holdSeconds = 1)
        val output = waitForOutput(second, "READY", timeoutMs = 5_000)
        assertTrue("Relaunch after kill should acquire lock", output.any { it == "LOCK_ACQUIRED" })

        val exited = second.waitFor(10, TimeUnit.SECONDS)
        assertTrue(exited)
        assertEquals(0, second.exitValue())
    }

    // ── Test 5: rapid relaunch (race condition) ──────────────────────

    @Test
    fun `rapid relaunch succeeds thanks to retry mechanism`() {
        val lockId = "test5"

        // Launch and wait until ready
        val first = launchHarness(lockId = lockId, holdSeconds = 1)
        waitForOutput(first, "READY", timeoutMs = 5_000)

        // Now tell the first process to exit by waiting for it (it holds for 1s)
        // and immediately launch the second one while the shutdown hook may still be running
        // We don't wait for first to fully exit — launch second right away
        // The retry mechanism (3 attempts × 150ms) should handle the overlap
        Thread.sleep(900) // close to the 1s hold time
        val second = launchHarness(lockId = lockId, holdSeconds = 1)

        // Wait for first to exit
        first.waitFor(5, TimeUnit.SECONDS)

        // Second should eventually acquire the lock
        val output = waitForOutput(second, "READY", timeoutMs = 5_000)
        assertTrue("Rapid relaunch should acquire lock", output.any { it == "LOCK_ACQUIRED" })

        val exited = second.waitFor(10, TimeUnit.SECONDS)
        assertTrue(exited)
        assertEquals(0, second.exitValue())
    }

    // ── Test 6: stale restore_request file does not block ────────────

    @Test
    fun `stale restore_request file does not prevent launch`() {
        val lockId = "test6"

        // Create a stale restore_request file
        val staleFile = lockDir.resolve("$lockId.restore_request").toFile()
        staleFile.createNewFile()
        assertTrue("Stale file should exist before test", staleFile.exists())

        // Launch — should succeed despite stale file
        val p = launchHarness(lockId = lockId, holdSeconds = 1)
        val output = waitForOutput(p, "READY", timeoutMs = 5_000)
        assertTrue("Should acquire lock despite stale restore_request", output.any { it == "LOCK_ACQUIRED" })

        val exited = p.waitFor(10, TimeUnit.SECONDS)
        assertTrue(exited)
        assertEquals(0, p.exitValue())
    }

    // ── Test 7: restore request is received by primary ───────────────

    @Test
    fun `primary instance receives restore request from secondary`() {
        val lockId = "test7"

        // Launch primary — hold lock for 5s
        val primary = launchHarness(lockId = lockId, holdSeconds = 5)
        waitForOutput(primary, "READY", timeoutMs = 5_000)

        // Launch secondary — will be denied and will create restore_request file
        val secondary = launchHarness(lockId = lockId, holdSeconds = 0)
        secondary.waitFor(10, TimeUnit.SECONDS)
        assertEquals("Secondary should be denied", 1, secondary.exitValue())

        // Give the WatchService time to pick up the file event
        // macOS WatchService can be slow (uses polling)
        val restoreReceived = waitForOutput(primary, "RESTORE_REQUEST", timeoutMs = 15_000)
        assertTrue(
            "Primary should receive RESTORE_REQUEST",
            restoreReceived.any { it == "RESTORE_REQUEST" },
        )

        primary.destroyForcibly()
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun launchHarness(
        lockId: String,
        holdSeconds: Long,
    ): Process {
        val javaHome = System.getProperty("java.home")
        val java = File(javaHome, "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")

        val pb = ProcessBuilder(
            java,
            "-cp", classpath,
            "io.github.kdroidfilter.nucleus.core.runtime.SingleInstanceTestHarnessKt",
            lockDir.toAbsolutePath().toString(),
            lockId,
            holdSeconds.toString(),
        )
        pb.redirectErrorStream(false)
        val process = pb.start()
        processes.add(process)
        return process
    }

    /**
     * Reads stdout of [process] line-by-line until [marker] is found or timeout is reached.
     * Returns all lines read so far.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun waitForOutput(
        process: Process,
        marker: String,
        timeoutMs: Long,
    ): List<String> {
        val lines = mutableListOf<String>()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive && !reader.ready()) break
            if (reader.ready()) {
                val line = reader.readLine() ?: break
                lines.add(line)
                if (line.contains(marker)) return lines
            } else {
                Thread.sleep(50)
            }
        }
        return lines
    }
}
