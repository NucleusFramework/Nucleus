package dev.nucleusframework.scheduler.internal

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.scheduler.DesktopBootReceiver
import dev.nucleusframework.scheduler.TaskId
import java.io.File

/**
 * Generates and manages wrapper scripts that act as the OS scheduler's execution target.
 *
 * Instead of registering the application executable directly with the OS scheduler,
 * we register a small wrapper script. The script checks whether the application
 * executable still exists before running it. If the executable is missing (e.g. after
 * uninstall), the script **self-destructs**: it unregisters the scheduled task from the
 * OS and deletes all associated files (script, plist/unit files, metadata).
 *
 * This ensures orphaned scheduled tasks are cleaned up automatically without requiring
 * explicit uninstall hooks.
 */
internal object TaskWrapperScript {
    private const val SCHEDULER_ARG = DesktopBootReceiver.SCHEDULER_ARG

    private fun scriptsDir(appId: String): File {
        val baseDir =
            when (Platform.Current) {
                Platform.Windows ->
                    System.getenv("LOCALAPPDATA")
                        ?: "${System.getProperty("user.home")}\\AppData\\Local"
                Platform.MacOS ->
                    "${System.getProperty("user.home")}/Library/Application Support"
                else ->
                    System.getenv("XDG_DATA_HOME")
                        ?: "${System.getProperty("user.home")}/.local/share"
            }
        return File(baseDir, "nucleus/scheduler/$appId/scripts")
    }

    fun scriptFile(
        appId: String,
        taskId: TaskId,
    ): File {
        val dir = scriptsDir(appId)
        val ext = if (Platform.Current == Platform.Windows) "vbs" else "sh"
        return File(dir, "${taskId.value}.$ext")
    }

    fun deleteScript(
        appId: String,
        taskId: TaskId,
    ) {
        scriptFile(appId, taskId).delete()
    }

    fun deleteAllScripts(appId: String) {
        val dir = scriptsDir(appId)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    // -- Windows VBScript wrapper ------------------------------------------------

    /**
     * Generates a `.vbs` script that checks whether the application executable exists.
     * If not, it removes the scheduled task via the Task Scheduler 2.0 COM API
     * (consistent with how the task was created), cleans up metadata, and self-deletes.
     *
     * The script is invoked by the Task Scheduler via:
     *   `wscript.exe "script.vbs"`
     *
     * Using `wscript.exe` (the GUI script host) instead of `cscript.exe`, `cmd.exe`,
     * or `powershell.exe` guarantees **zero visible console window** — wscript is a
     * Windows-subsystem process that never allocates a console.
     */
    fun generateWindowsScript(
        appId: String,
        taskId: TaskId,
        execPath: String,
        execArgs: List<String>,
        taskFolder: String,
        metadataDir: String,
    ): File {
        val file = scriptFile(appId, taskId)
        file.parentFile.mkdirs()
        file.writeText(buildWindowsScript(taskId, execPath, execArgs, taskFolder, metadataDir))
        return file
    }

    /** Builds the VBScript content written by [generateWindowsScript]. */
    internal fun buildWindowsScript(
        taskId: TaskId,
        execPath: String,
        execArgs: List<String>,
        taskFolder: String,
        metadataDir: String,
    ): String {
        val metadataFile = "$metadataDir\\${taskId.value}.properties"

        // shell.Run expects a single command string; the exe path is always quoted so
        // spaces are safe, and any argument containing a space gets quoted too.
        val commandLine =
            buildString {
                append('"').append(execPath).append('"')
                for (arg in execArgs + SCHEDULER_ARG + taskId.value) {
                    append(' ')
                    if (arg.contains(' ')) append('"').append(arg).append('"') else append(arg)
                }
            }

        return buildString {
            appendLine("Set fso = CreateObject(\"Scripting.FileSystemObject\")")
            appendLine("If Not fso.FileExists(${vbsQuote(execPath)}) Then")
            appendLine("    On Error Resume Next")
            appendLine("    Set svc = CreateObject(\"Schedule.Service\")")
            appendLine("    svc.Connect")
            appendLine("    Set folder = svc.GetFolder(${vbsQuote(taskFolder)})")
            appendLine("    folder.DeleteTask ${vbsQuote(taskId.value)}, 0")
            appendLine("    folder.DeleteTask ${vbsQuote("${taskId.value}-retry")}, 0")
            appendLine("    On Error GoTo 0")
            appendLine(
                "    If fso.FileExists(${vbsQuote(metadataFile)}) Then fso.DeleteFile ${vbsQuote(metadataFile)}",
            )
            appendLine("    fso.DeleteFile WScript.ScriptFullName")
            appendLine("    WScript.Quit 0")
            appendLine("End If")
            appendLine("Set shell = CreateObject(\"WScript.Shell\")")
            // VBS string: "..." with doubled quotes inside → literal quotes in the value.
            appendLine("shell.Run ${vbsQuote(commandLine)}, 0, True")
        }
    }

    /** Wraps a value in VBS double quotes, doubling any inner quotes. */
    private fun vbsQuote(s: String): String = "\"${vbsEscape(s)}\""

    /** Escapes double quotes for use inside a VBS string literal. */
    private fun vbsEscape(s: String): String = s.replace("\"", "\"\"")

    // -- Linux bash wrapper ---------------------------------------------------

    @Suppress("LongParameterList")
    fun generateLinuxScript(
        appId: String,
        taskId: TaskId,
        execPath: String,
        execArgs: List<String>,
        timerUnit: String,
        serviceUnit: String,
        serviceFilePath: String,
        timerFilePath: String,
        metadataDir: String,
    ): File {
        val file = scriptFile(appId, taskId)
        file.parentFile.mkdirs()
        file.writeText(
            buildLinuxScript(
                taskId = taskId,
                execPath = execPath,
                execArgs = execArgs,
                timerUnit = timerUnit,
                serviceUnit = serviceUnit,
                serviceFilePath = serviceFilePath,
                timerFilePath = timerFilePath,
                metadataDir = metadataDir,
                scriptPath = file.absolutePath,
            ),
        )
        file.setExecutable(true)
        return file
    }

    /** Builds the bash script content written by [generateLinuxScript]. */
    @Suppress("LongParameterList")
    internal fun buildLinuxScript(
        taskId: TaskId,
        execPath: String,
        execArgs: List<String>,
        timerUnit: String,
        serviceUnit: String,
        serviceFilePath: String,
        timerFilePath: String,
        metadataDir: String,
        scriptPath: String,
    ): String {
        val args =
            (execArgs + SCHEDULER_ARG + taskId.value).joinToString(" ") { shellQuote(it) }
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("EXEC=${shellQuote(execPath)}")
            appendLine("if [ ! -x \"${'$'}EXEC\" ]; then")
            appendLine("    systemctl --user disable --now ${shellQuote(timerUnit)} 2>/dev/null")
            appendLine("    systemctl --user disable ${shellQuote(serviceUnit)} 2>/dev/null")
            appendLine("    rm -f ${shellQuote(timerFilePath)}")
            appendLine("    rm -f ${shellQuote(serviceFilePath)}")
            appendLine("    systemctl --user daemon-reload 2>/dev/null")
            appendLine("    rm -f ${shellQuote("$metadataDir/${taskId.value}.properties")}")
            appendLine("    rm -f ${shellQuote(scriptPath)}")
            appendLine("    exit 0")
            appendLine("fi")
            appendLine("\"${'$'}EXEC\" $args")
        }
    }

    /** Wraps a value in single quotes, escaping any embedded single quote. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
