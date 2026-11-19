package dev.nucleusframework.tray.linux

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `TrayApp` end to end on Linux (X11): each scenario runs [LinuxTrayAppProbeMain] in its own JVM
 * on its own private session bus (a real `nucleusApplication`, Tao loop and popup), and fails with
 * the checks it reported. Skipped without an X display.
 */
class LinuxTrayAppE2ETest {
    private val runs = LinuxTestBus.isAvailable && System.getenv("DISPLAY") != null

    private fun seed(): Long = System.getProperty("nucleus.tray.torture.seed")?.toLongOrNull() ?: System.nanoTime()

    private fun scenario(
        name: String,
        timeoutS: Long = 90,
    ) {
        if (!runs) {
            val required = System.getProperty("nucleus.tray.e2e.required") == "true"
            val display = System.getenv("DISPLAY")
            check(
                !required,
            ) { "TrayApp E2E required but cannot run (dbus-daemon: ${LinuxTestBus.isAvailable}, DISPLAY: $display)" }
            return
        }
        val seed = seed()
        println("[torture] Linux TrayApp $name seed=$seed")
        PrivateBus.start().use { bus ->
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process =
                ProcessBuilder(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.nucleusframework.tray.linux.LinuxTrayAppProbeMainKt",
                    name,
                    seed.toString(),
                ).redirectErrorStream(true)
                    .apply {
                        environment()["DBUS_SESSION_BUS_ADDRESS"] = bus.address
                        environment()["XDG_CURRENT_DESKTOP"] = "NucleusTest"
                    }.start()
            val lines = mutableListOf<String>()
            val reader =
                Thread { process.inputStream.bufferedReader().forEachLine { synchronized(lines) { lines += it } } }
            reader.start()
            val exited = process.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            reader.join(2000)
            val checks = synchronized(lines) { lines.filter { it.startsWith("CHECK ") || it.startsWith("DONE") } }
            checks.forEach { println("[torture] $it") }
            val failed = checks.filter { it.startsWith("CHECK fail") }
            if (!exited || failed.isNotEmpty() || checks.none { it.startsWith("DONE") }) {
                val tail = synchronized(lines) { lines.filterNot { it.startsWith("CHECK ok") }.takeLast(30) }
                fail("TrayApp $name (seed=$seed, exited=$exited):\n" + (failed + tail).joinToString("\n"))
            }
            assertTrue(checks.any { it.startsWith("CHECK ok") }, "no check ran")
        }
    }

    @Test fun `click toggles a popup anchored to the click, top and bottom panels`() = scenario("click")

    @Test fun `visibleOnStart opens inside the work area`() = scenario("visible-on-start")

    @Test fun `seeded click storm keeps state and screen in sync`() = scenario("toggle-storm")

    @Test fun `concurrent show hide toggle keep state and screen in sync`() = scenario("programmatic")

    @Test fun `resizing a visible popup re-anchors it`() = scenario("resize")

    @Test fun `the menu works and does not open the popup`() = scenario("menu")

    @Test fun `removing TrayApp takes popup and icon away`() = scenario("remove")

    @Test fun `two TrayApps each anchor to their own icon`() = scenario("multi")
}
