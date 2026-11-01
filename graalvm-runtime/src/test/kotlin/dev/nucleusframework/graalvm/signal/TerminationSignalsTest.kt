package dev.nucleusframework.graalvm.signal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A child JVM started with `-Xrs` installs no handler for SIGINT / SIGTERM / SIGHUP — the state of
 * the sandboxed native image, compiled without Substrate's signal handling. With
 * [TerminationSignals] installed, each signal must still run the shutdown hooks and exit with
 * 128 + signal, as HotSpot does.
 */
class TerminationSignalsTest {
    @Test
    fun `SIGTERM runs the shutdown hooks and exits 143`() = assertSignalExit("TERM", 143)

    @Test
    fun `SIGINT runs the shutdown hooks and exits 130`() = assertSignalExit("INT", 130)

    @Test
    fun `SIGHUP runs the shutdown hooks and exits 129`() = assertSignalExit("HUP", 129)

    @Test
    fun `a child process started afterwards still dies on SIGTERM`() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("nucleus-signals").toFile()
        try {
            val result = File(dir, "result")
            val child = startChild(dir, "spawn", result.path)
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child hung")
            assertEquals("destroyed", result.readText(), File(dir, "child.log").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun startChild(
        dir: File,
        vararg args: String,
    ): Process =
        ProcessBuilder(
            listOf(
                File(System.getProperty("java.home"), "bin/java").path,
                "-Xrs",
                "-cp",
                System.getProperty("java.class.path"),
                TerminationSignalsChild::class.java.name,
            ) + args,
        ).redirectErrorStream(true)
            .redirectOutput(File(dir, "child.log"))
            .start()

    private fun assertSignalExit(
        signal: String,
        expectedStatus: Int,
    ) {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val dir = Files.createTempDirectory("nucleus-signals").toFile()
        try {
            val ready = File(dir, "ready")
            val hookRan = File(dir, "hook-ran")
            val child = startChild(dir, "wait", ready.path, hookRan.path)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(ready.exists(), "child never got ready: ${File(dir, "child.log").readText()}")
            assertEquals("installed", ready.readText())

            ProcessBuilder("kill", "-$signal", child.pid().toString()).start().waitFor()
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child ignored SIG$signal")
            assertEquals(expectedStatus, child.exitValue())
            assertTrue(hookRan.exists(), "shutdown hook did not run")
        } finally {
            dir.deleteRecursively()
        }
    }
}

/**
 * Child process of [TerminationSignalsTest]: installs the handlers, then either waits for a signal
 * (`wait <ready> <hook-ran>`) or checks that a process it starts still dies on SIGTERM
 * (`spawn <result>`) — an ignored disposition would be inherited across exec.
 */
object TerminationSignalsChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val installed = TerminationSignals.install()
        when (args.first()) {
            "wait" -> {
                val (ready, hookRan) = args.drop(1).map(::File)
                Runtime.getRuntime().addShutdownHook(Thread { hookRan.writeText("yes") })
                ready.writeText(if (installed) "installed" else "unavailable")
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            }
            "spawn" -> {
                val sleeper = ProcessBuilder("sleep", "60").start()
                Thread.sleep(500)
                sleeper.destroy()
                val died = sleeper.waitFor(10, TimeUnit.SECONDS)
                val result =
                    when {
                        !installed -> "unavailable"
                        died -> "destroyed"
                        else -> "survived"
                    }
                File(args[1]).writeText(result)
                if (!died) sleeper.destroyForcibly()
            }
        }
    }
}
