package dev.nucleusframework.tray.linux

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The tray against a real panel: GNOME Shell (headless, its own bus, Wayland socket and XWayland)
 * with the AppIndicator extension Ubuntu ships. Each scenario starts a shell, runs
 * [GnomeTrayProbeMain] in it and fails with the checks it reported. Skipped without
 * `gnome-shell` and the extension.
 */
class LinuxGnomeTrayE2ETest {
    private fun scenario(
        name: String,
        scale: Int = 1,
        timeoutS: Long = 120,
    ) {
        if (!GnomeShell.isAvailable) return
        println("[torture] GNOME tray $name scale=$scale")
        GnomeShell.start(scale = scale).use { shell ->
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process =
                ProcessBuilder(
                    java,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.nucleusframework.tray.linux.GnomeTrayProbeMainKt",
                    name,
                ).redirectErrorStream(true)
                    .apply {
                        val env = environment()
                        env["DBUS_SESSION_BUS_ADDRESS"] = shell.busAddress
                        env["WAYLAND_DISPLAY"] = shell.waylandDisplay
                        env["DISPLAY"] = shell.x11Display
                        env["XAUTHORITY"] = shell.xauthority
                        env["XDG_CURRENT_DESKTOP"] = "GNOME"
                        env["NO_AT_BRIDGE"] = "1"
                        env["GSETTINGS_BACKEND"] = "memory"
                        // What gsd-xsettings sets on a real session (the shell has no settings daemon).
                        env["NUCLEUS_TEST_XFT_DPI"] = (96 * scale).toString()
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
                val tail = synchronized(lines) { lines.filterNot { it.startsWith("CHECK ok") }.takeLast(40) }
                fail("GNOME tray $name (scale=$scale, exited=$exited):\n" + (failed + tail).joinToString("\n"))
            }
            assertTrue(checks.any { it.startsWith("CHECK ok") }, "no check ran")
        }
    }

    @Test fun `the panel draws the item and its composable menu works`() = scenario("panel")

    @Test fun `the item comes back after the panel host restarts`() = scenario("restart")

    @Test fun `TrayApp opens on a double click, anchored under its icon`() = scenario("trayapp")

    @Test fun `TrayApp at 200 percent is sized and anchored in logical pixels`() = scenario("trayapp", scale = 2)

    @Test fun `two TrayApps each anchor under their own icon`() = scenario("multi")
}
