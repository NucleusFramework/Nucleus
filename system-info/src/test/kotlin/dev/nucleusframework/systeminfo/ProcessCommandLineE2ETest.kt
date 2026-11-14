package dev.nucleusframework.systeminfo

import dev.nucleusframework.core.runtime.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spawns real processes and checks the argv the native backends report (#785). */
class ProcessCommandLineE2ETest {
    private val supported = Platform.Current == Platform.Linux || Platform.Current == Platform.MacOS

    @Test
    fun `cmd holds the full argv of a process and nothing of its environment`() {
        if (!supported) return
        // `; :` keeps the shell from exec-ing sleep, so the child keeps this argv.
        val argv =
            listOf(
                "/bin/sh",
                "-c",
                "sleep 30; :",
                "nucleus-arg",
                "",
                "été 🚀 עברית",
                "x".repeat(4096),
                "last",
            )
        val child =
            ProcessBuilder(argv)
                .apply { environment()["NUCLEUS_785_SECRET"] = "must-not-leak" }
                .start()
        try {
            val pid = child.pid()
            assertEquals(argv, SystemInfo.process(pid)?.cmd)
            assertEquals(argv, SystemInfo.processes().single { it.pid == pid }.cmd)
        } finally {
            child.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `cmd replaces invalid utf-8 instead of failing`() {
        if (!supported) return
        // ProcessBuilder can only pass valid UTF-8, so let python exec raw bytes.
        val script = """import os; os.execv("/bin/sh", [b"/bin/sh", b"-c", b"sleep 30; :", b"\xff\xc3(ok"])"""
        val child = ProcessBuilder("python3", "-c", script).start()
        try {
            val expected = listOf("/bin/sh", "-c", "sleep 30; :", "��(ok")
            val deadline = System.nanoTime() + 10_000_000_000L
            var cmd = SystemInfo.process(child.pid())?.cmd
            while (cmd != expected && System.nanoTime() < deadline) {
                Thread.sleep(50)
                cmd = SystemInfo.process(child.pid())?.cmd
            }
            assertEquals(expected, cmd)
        } finally {
            child.destroyForcibly().waitFor()
        }
    }
}
