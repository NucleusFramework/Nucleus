package dev.nucleusframework.tray.windows

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `TrayApp` end to end on Windows: each scenario runs [TrayAppProbeMain] in its own JVM (a real
 * `nucleusApplication`, Tao loop and standalone popup) and fails with the checks it reported.
 */
class WindowsTrayAppE2ETest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    private fun scenario(
        name: String,
        mainClass: String = "dev.nucleusframework.tray.windows.TrayAppProbeMainKt",
        extra: List<String> = emptyList(),
        timeoutS: Long = 90,
    ) {
        if (!isWindows) return
        val seed = seed()
        println("[torture] TrayApp $name seed=$seed")
        val java = File(System.getProperty("java.home"), "bin/java.exe").path
        val process =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                mainClass,
                name,
                seed.toString(),
                *extra.toTypedArray(),
            ).redirectErrorStream(true).start()
        val lines = mutableListOf<String>()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { synchronized(lines) { lines += it } } }
        reader.start()
        val exited = process.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        reader.join(2000)
        val checks = synchronized(lines) { lines.filter { it.startsWith("CHECK ") || it.startsWith("DONE") } }
        checks.forEach { println("[torture] $it") }
        val failed = checks.filter { it.startsWith("CHECK fail") }
        if (!exited || failed.isNotEmpty() || checks.none { it.startsWith("DONE") }) {
            val tail = synchronized(lines) { lines.filterNot { it.startsWith("CHECK ok") }.takeLast(25) }
            fail("TrayApp $name (seed=$seed, exited=$exited):\n" + (failed.ifEmpty { tail }).joinToString("\n"))
        }
        assertTrue(checks.any { it.startsWith("CHECK ok") }, "no check ran")
    }

    @Test fun `click toggles an anchored popup`() = scenario("click")

    @Test fun `visibleOnStart opens anchored`() = scenario("visible-on-start")

    @Test fun `seeded click storm keeps state and screen in sync`() = scenario("toggle-storm")

    @Test fun `concurrent show hide toggle keep state and screen in sync`() = scenario("programmatic")

    @Test fun `resizing a visible popup re-anchors it`() = scenario("resize")

    @Test fun `the menu still works next to the popup`() = scenario("menu")

    @Test fun `removing TrayApp takes popup and icon away`() = scenario("remove")

    private val rounds = System.getProperty("nucleus.tray.torture.iterations")?.toIntOrNull() ?: 60

    @Test fun `seeded TrayApp fuzz holds every invariant`() =
        scenario("fuzz", FUZZ_MAIN, listOf(rounds.toString()), timeoutS = 120L + rounds * 4)

    @Test fun `two TrayApps fuzzed together stay on their own icons`() =
        scenario("multi", FUZZ_MAIN, listOf(rounds.toString()), timeoutS = 120L + rounds * 5)

    @Test fun `outside clicks dismiss per dismissMode`() = scenario("dismiss", FUZZ_MAIN, listOf("0"))

    private companion object {
        const val FUZZ_MAIN = "dev.nucleusframework.tray.windows.TrayAppFuzzMainKt"
    }
}
