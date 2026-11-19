package dev.nucleusframework.tray.app

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end coverage for the AeroDL "window opens at the top-left instead of
 * the tray" race. Spawns a dedicated JVM, whose launcher keeps the process main
 * thread in a run loop where AppKit creates a real NSStatusItem, then asserts [awaitAnchoredWindowPosition]
 * never latches the unlaid-out origin.
 */
class MacTrayWindowPositionE2ETest {
    @Test
    fun `initially visible tray popup is not placed at the work-area origin`() {
        if (!isMac) return

        val java =
            ProcessHandle.current().info().command().orElseThrow {
                IllegalStateException("Cannot resolve the current java executable")
            }
        val classpath = System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                java,
                "-Djava.awt.headless=false",
                "-cp",
                classpath,
                MacTrayWindowPositionE2EMain::class.java.name,
            ).redirectErrorStream(true).start()

        val finished = process.waitFor(45, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) {
            process.destroyForcibly()
            fail("e2e JVM timed out.\n$output")
        }
        assertTrue(
            output.contains("RESULT=OK"),
            "tray popup latched the top-left instead of the status item:\n$output",
        )
        assertTrue(
            !output.contains("RESULT=TOP_LEFT"),
            "at least one trial opened at the work-area origin:\n$output",
        )
    }

    private val isMac: Boolean
        get() =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
}
