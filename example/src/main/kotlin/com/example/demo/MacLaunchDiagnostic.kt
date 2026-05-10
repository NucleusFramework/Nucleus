package com.example.demo

import dev.nucleusframework.nucleus.core.runtime.Platform
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Empirical probe: dumps everything that could discriminate between
 * an SMAppService.mainApp launch and a manual Finder/Dock launch.
 *
 * Captured at app startup, also written to ~/Library/Logs/Nucleus-AutoLaunch/
 * for cross-run comparison. Displayed in the Auto-Launch screen.
 */
object MacLaunchDiagnostic {
    @Volatile
    private var captured: String = "(diagnostic not yet captured)"

    val text: String get() = captured

    fun capture(args: Array<String>) {
        if (Platform.Current != Platform.MacOS) return

        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val pid = ProcessHandle.current().pid()
        val ppid =
            ProcessHandle
                .current()
                .parent()
                .map { it.pid() }
                .orElse(-1L)
        val gppid =
            ProcessHandle
                .current()
                .parent()
                .flatMap { it.parent() }
                .map { it.pid() }
                .orElse(-1L)

        val sb = StringBuilder()
        sb.appendLine("=== Nucleus macOS launch diagnostic ===")
        sb.appendLine("timestamp: $ts")
        sb.appendLine("pid: $pid  ppid: $ppid  gppid: $gppid")
        sb.appendLine("args: ${args.joinToString(" ")}")
        sb.appendLine()

        sb.appendLine("--- launchctl print pid/$pid ---")
        sb.appendLine(run("launchctl", "print", "pid/$pid"))
        sb.appendLine()

        sb.appendLine("--- ps self ---")
        sb.appendLine(run("ps", "-o", "pid,ppid,user,lstart,command", "-p", "$pid"))
        if (ppid > 0) {
            sb.appendLine("--- ps parent ---")
            sb.appendLine(run("ps", "-o", "pid,ppid,user,lstart,command", "-p", "$ppid"))
        }
        if (gppid > 0) {
            sb.appendLine("--- ps grandparent ---")
            sb.appendLine(run("ps", "-o", "pid,ppid,user,lstart,command", "-p", "$gppid"))
        }
        sb.appendLine()

        sb.appendLine("--- sysctl kern.boottime ---")
        sb.appendLine(run("sysctl", "-n", "kern.boottime"))
        sb.appendLine("now (epoch): ${System.currentTimeMillis() / 1000}")
        sb.appendLine()

        sb.appendLine("--- environment ---")
        System.getenv().toSortedMap().forEach { (k, v) -> sb.appendLine("$k=$v") }

        captured = sb.toString()

        val home = System.getProperty("user.home")
        if (home != null) {
            runCatching {
                val dir = File(home, "Library/Logs/Nucleus-AutoLaunch").apply { mkdirs() }
                File(dir, "diag-$ts.txt").writeText(captured)
            }
        }
        println("[MacLaunchDiagnostic] captured (pid=$pid, ppid=$ppid)")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(vararg cmd: String): String =
        try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        } catch (e: Exception) {
            "<error running ${cmd.joinToString(" ")}: ${e.message}>"
        }
}
