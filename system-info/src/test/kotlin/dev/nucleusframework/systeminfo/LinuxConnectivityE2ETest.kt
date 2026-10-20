package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

private const val PYTHON = "/usr/bin/python3"

/**
 * Drives SystemInfo.connectivityFlow() in a child JVM whose session *and* system
 * buses point at a private dbus-daemon, served by fake_network_services.py.
 * Skipped when dbus-daemon or python3-gi is missing.
 */
class LinuxConnectivityE2ETest {
    private val processes = mutableListOf<Process>()

    @AfterTest
    fun tearDown() {
        processes.asReversed().forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `portal v3 emits initial state then deduplicated changes`() {
        val bus = startBus() ?: return
        val service = startService(bus, "portal3")
        val probe = startProbe(bus, "watch")

        assertEquals("STATE true UNMETERED FULL", probe.next())
        // Bursts of identical notifications must not reach the collector.
        service.command("signal")
        service.command("signal")
        service.command("set 1 1 4")
        assertEquals("STATE true METERED FULL", probe.next())
        service.command("set 1 1 3")
        assertEquals("STATE true METERED CAPTIVE_PORTAL", probe.next())
        service.command("set 0 0 1")
        assertEquals("STATE false NOT_AVAILABLE LOCAL", probe.next())
        assertNull(probe.next(timeoutMs = 500))
    }

    @Test
    fun `portal v2 without GetStatus falls back to per-property calls`() {
        val bus = startBus() ?: return
        val service = startService(bus, "portal2")
        val probe = startProbe(bus, "watch")

        assertEquals("STATE true UNMETERED FULL", probe.next())
        service.command("set 1 0 2")
        assertEquals("STATE true UNMETERED LIMITED", probe.next())
    }

    @Test
    fun `network manager is used when the portal is absent`() {
        val bus = startBus() ?: return
        val service = startService(bus, "nm")
        val probe = startProbe(bus, "watch")

        assertEquals("STATE true UNMETERED FULL", probe.next())
        service.command("set 1 1 4")
        assertEquals("STATE true METERED FULL", probe.next())
        service.command("set 0 0 1")
        assertEquals("STATE false NOT_AVAILABLE NONE", probe.next())
    }

    @Test
    fun `no backend emits a single unknown state`() {
        val bus = startBus() ?: return
        val probe = startProbe(bus, "watch")

        assertEquals("STATE false NOT_AVAILABLE UNKNOWN", probe.next())
        assertNull(probe.next(timeoutMs = 500))
    }

    @Test
    fun `monitor stops with the last collector and restarts with the next`() {
        val bus = startBus() ?: return
        startService(bus, "portal3")
        val probe = startProbe(bus, "restart")

        assertEquals("STATE true UNMETERED FULL", probe.next())
        assertEquals("THREADS 0", probe.next())
        assertEquals("STATE true UNMETERED FULL", probe.next())
        assertEquals("THREADS 0", probe.next())
    }

    // --- Harness -------------------------------------------------------------

    private class Child(
        val process: Process,
    ) {
        private val lines = LinkedBlockingQueue<String>()
        private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

        init {
            val reader: BufferedReader = process.inputStream.bufferedReader()
            thread(isDaemon = true) { reader.lineSequence().forEach(lines::put) }
        }

        fun next(timeoutMs: Long = 5_000): String? = lines.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun command(line: String) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
            assertEquals("ok", next(), "fake service did not acknowledge '$line'")
        }
    }

    private fun startBus(): String? {
        if (Platform.Current != Platform.Linux) return null
        if (!File("/usr/bin/dbus-daemon").canExecute() || !File(PYTHON).canExecute()) return null
        val hasGi = ProcessBuilder(PYTHON, "-c", "from gi.repository import Gio").start().waitFor() == 0
        if (!hasGi) return null

        // No <servicedir>: the stock session config would D-Bus-activate the real
        // xdg-desktop-portal on this private bus.
        val config =
            File.createTempFile("nucleus-connectivity-bus", ".conf").apply {
                deleteOnExit()
                writeText(
                    """
                    <busconfig>
                      <type>session</type>
                      <listen>unix:tmpdir=/tmp</listen>
                      <policy context="default">
                        <allow send_destination="*" eavesdrop="true"/>
                        <allow eavesdrop="true"/>
                        <allow own="*"/>
                      </policy>
                    </busconfig>
                    """.trimIndent(),
                )
            }
        val daemon =
            ProcessBuilder("dbus-daemon", "--config-file=${config.absolutePath}", "--nofork", "--print-address=1")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .also(processes::add)
        return daemon.inputStream.bufferedReader().readLine() ?: fail("dbus-daemon printed no address")
    }

    private fun startService(
        bus: String,
        mode: String,
    ): Child {
        val script = File(javaClass.getResource("/fake_network_services.py")!!.toURI())
        val child = Child(launch(bus, listOf(PYTHON, "-I", script.absolutePath, mode)))
        assertEquals("ready", child.next(), "fake $mode service did not start")
        return child
    }

    private fun startProbe(
        bus: String,
        mode: String,
    ): Child {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        return Child(
            launch(
                bus,
                listOf(java, "-cp", System.getProperty("java.class.path"), ConnectivityProbe::class.java.name, mode),
            ),
        )
    }

    private fun launch(
        bus: String,
        command: List<String>,
    ): Process {
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.environment()["DBUS_SESSION_BUS_ADDRESS"] = bus
        builder.environment()["DBUS_SYSTEM_BUS_ADDRESS"] = bus
        return builder.start().also(processes::add)
    }
}
