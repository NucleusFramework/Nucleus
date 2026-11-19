package dev.nucleusframework.tray.macos

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `TrayApp` end to end on macOS: each scenario runs [MacTrayAppProbeMain] in its own JVM (a real
 * `nucleusApplication`, Tao loop and standalone popup panel) and fails with the checks it reported.
 */
class MacTrayAppE2ETest {
    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    private val rounds = System.getProperty("nucleus.tray.torture.iterations")?.toIntOrNull() ?: 30

    private fun scenario(
        name: String,
        extra: List<String> = emptyList(),
        timeoutS: Long = 90,
    ) {
        if (!MacProbe.isMac) return
        val seed = seed()
        println("[torture] TrayApp $name seed=$seed")
        val process =
            ProcessBuilder(
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElseThrow(),
                "-cp",
                System.getProperty("java.class.path"),
                "dev.nucleusframework.tray.macos.MacTrayAppProbeMainKt",
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
            val log =
                synchronized(lines) {
                    val other = lines.filterNot { it.startsWith("CHECK") }
                    other.filter { "Exception" in it || "Error" in it }.take(10) + other.takeLast(15)
                }
            fail("TrayApp $name (seed=$seed, exited=$exited):\n" + (failed + log).joinToString("\n"))
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

    @Test fun `seeded TrayApp fuzz holds every invariant`() =
        scenario("fuzz", listOf(rounds.toString()), timeoutS = 120L + rounds * 6)

    @Test fun `two TrayApps fuzzed together stay on their own icons`() =
        scenario("multi", listOf(rounds.toString()), timeoutS = 120L + rounds * 8)
}
