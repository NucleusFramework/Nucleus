package dev.nucleusframework.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for [DeepLinkHandler] CLI URI parsing.
 *
 * Each test launches a real subprocess so the [DeepLinkHandler] object
 * singleton state (cold-start flag, last URI) does not leak across cases.
 *
 * Covers issue #414: scheme-only URIs (`myapp:?query=…`) must be accepted,
 * not only authority-form URIs (`myapp://host/…`).
 */
class DeepLinkHandlerEndToEndTest {
    private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
    private val classpath = System.getProperty("java.class.path")
    private val processes = mutableListOf<Process>()

    @After
    fun tearDown() {
        processes.forEach { p ->
            if (p.isAlive) p.destroyForcibly()
        }
        processes.forEach { p -> p.waitFor(5, TimeUnit.SECONDS) }
    }

    // ── isDeepLinkArg unit checks ──────────────────────────────────────

    @Test
    fun `isDeepLinkArg accepts hierarchical and opaque schemes`() {
        assertTrue(isDeepLinkArg("myapp://open/item?id=42"))
        assertTrue(isDeepLinkArg("myapp:?queryParam=123"))
        assertTrue(isDeepLinkArg("https://example.com/path"))
        assertTrue(isDeepLinkArg("file:///tmp/x"))
        assertTrue(isDeepLinkArg("svn+ssh://host/repo"))
    }

    @Test
    fun `isDeepLinkArg rejects non-URI CLI tokens`() {
        assertFalse(isDeepLinkArg("--verbose"))
        assertFalse(isDeepLinkArg("path/to/file.txt"))
        assertFalse(isDeepLinkArg("/absolute/path"))
        assertFalse(isDeepLinkArg(""))
        // Windows drive letters must not be mistaken for schemes
        assertFalse(isDeepLinkArg("C:\\Users\\foo\\bar.txt"))
        assertFalse(isDeepLinkArg("D:/work/file.txt"))
    }

    // ── setHandler e2e ─────────────────────────────────────────────────

    @Test
    fun `authority-form deep link is delivered via setHandler`() {
        val process = startProcess("myapp://open/item?id=42")
        assertEquals("URI:myapp://open/item?id=42", readSignal(process))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `scheme-only deep link without authority is delivered via setHandler - issue 414`() {
        // xdg-open 'myapp:?queryParam=123' (and protocol handlers on Linux/Windows)
        // can pass scheme-only URIs that have no "://" authority separator.
        val process = startProcess("myapp:?queryParam=123")
        assertEquals(
            "Issue #414: setHandler must accept scheme-only URIs (scheme:), not only scheme://",
            "URI:myapp:?queryParam=123",
            readSignal(process),
        )
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `scheme-only deep link is found among mixed CLI args - issue 414`() {
        val process =
            startProcess(
                "--verbose",
                "myapp:?queryParam=123",
                "some-other-arg",
            )
        assertEquals(
            "Issue #414: scheme-only URI buried in args must still be detected",
            "URI:myapp:?queryParam=123",
            readSignal(process),
        )
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `plain args without URI yield NONE`() {
        val process = startProcess("--help", "path/to/file.txt")
        assertEquals("NONE", readSignal(process))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `windows drive path is not delivered as deep link`() {
        val process = startProcess("C:\\Users\\foo\\bar.txt")
        assertEquals("NONE", readSignal(process))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    // ── captureFromArgs e2e (secondary-instance path) ──────────────────

    @Test
    fun `captureFromArgs records authority-form URI`() {
        val process = startCaptureProcess("myapp://host/path")
        assertEquals("URI:myapp://host/path", readSignal(process))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun `captureFromArgs records scheme-only URI - issue 414`() {
        val process = startCaptureProcess("myapp:?queryParam=123")
        assertEquals(
            "Issue #414: captureFromArgs (secondary instance path) must accept scheme-only URIs",
            "URI:myapp:?queryParam=123",
            readSignal(process),
        )
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun startProcess(vararg cliArgs: String): Process {
        val args =
            mutableListOf(
                javaBin,
                "-cp",
                classpath,
                "dev.nucleusframework.core.runtime.DeepLinkHolderKt",
            )
        args.addAll(cliArgs)
        val process = ProcessBuilder(args).start()
        processes.add(process)
        return process
    }

    private fun startCaptureProcess(vararg cliArgs: String): Process {
        val args =
            mutableListOf(
                javaBin,
                "-cp",
                classpath,
                "dev.nucleusframework.core.runtime.DeepLinkCaptureHolderKt",
            )
        args.addAll(cliArgs)
        val process = ProcessBuilder(args).start()
        processes.add(process)
        return process
    }

    private fun readSignal(process: Process): String =
        process.inputStream.bufferedReader().readLine()
            ?: "NO_OUTPUT (exit=${if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue() else "alive"})"
}
