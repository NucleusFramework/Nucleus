package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives SystemInfo.connectivityFlow() on the real Network List Manager in a child JVM,
 * so a native crash (an NLM callback outliving stop()) fails the test instead of the
 * runner. Live transitions need the network itself to change and are checked by hand
 * with the probe's `watch` mode (see the PR); this covers everything around them.
 */
class WindowsConnectivityE2ETest {
    private val processes = mutableListOf<Process>()

    @AfterTest
    fun tearDown() {
        processes.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `flow starts with the same state as the one-shot query`() {
        if (Platform.Current != Platform.Windows) return
        val oneShot = run("oneshot").single()
        val watched = run("restart").first()

        assertEquals(oneShot, watched)
        // NLM always reports a level: a routed network is available, a local link is not.
        assertTrue(
            oneShot.matches(Regex("STATE false NOT_AVAILABLE (NONE|LOCAL)")) ||
                oneShot.matches(Regex("STATE true ((UN)?METERED|UNKNOWN) (FULL|LIMITED|CAPTIVE_PORTAL)")),
            oneShot,
        )
    }

    @Test
    fun `monitor stops with the last collector and restarts with the next`() {
        if (Platform.Current != Platform.Windows) return
        val lines = run("restart")

        assertEquals(4, lines.size, lines.toString())
        assertEquals(lines[0], lines[2])
        // stop() joins the STA thread, which detaches itself on the way out.
        assertEquals(listOf("THREADS 0", "THREADS 0"), listOf(lines[1], lines[3]))
    }

    @Test
    fun `collectors share one monitor`() {
        if (Platform.Current != Platform.Windows) return
        val lines = run("share")

        val state = lines[0].removePrefix("FIRST ")
        assertEquals(listOf("FIRST $state", "SECOND $state", "AFTER $state", "THREADS 0"), lines)
    }

    @Test
    fun `rapid start and stop never crashes`() {
        if (Platform.Current != Platform.Windows) return
        assertEquals(listOf("STRESSED 0"), run("stress"))
    }

    private fun run(mode: String): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ConnectivityProbe::class.java.name, mode)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .also(processes::add)
        val lines = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe '$mode' hung")
        assertEquals(0, process.exitValue(), "probe '$mode' exited abnormally: $lines")
        return lines
    }
}
