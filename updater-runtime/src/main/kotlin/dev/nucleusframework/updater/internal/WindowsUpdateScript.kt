package dev.nucleusframework.updater.internal

import java.io.File

/**
 * Escapes a value for safe interpolation inside a **single-quoted** PowerShell string.
 *
 * The artifact path is derived from the `url` field of the remote update manifest, so a
 * hostile or compromised manifest could otherwise embed a `'` to break out of the quoting
 * and inject PowerShell that runs at install time. In a single-quoted PowerShell string a
 * literal quote is written as two quotes, so doubling every `'` closes the injection while
 * leaving ordinary Windows paths unchanged.
 */
internal fun psSingleQuote(value: String): String = value.replace("'", "''")

/**
 * Writes a PowerShell script as UTF-8 **with a BOM**. Windows PowerShell 5.1 reads a BOM-less
 * script in the ANSI code page, so any non-ASCII path — the installer under
 * `C:\Users\Hélène\AppData\Local\Temp`, the app under `...\Programs` — would be mangled and not
 * found: the update silently did nothing for every user with an accented account name.
 */
internal fun writePowerShellScript(
    script: File,
    content: String,
) {
    script.writeText("\uFEFF$content", Charsets.UTF_8)
}

/**
 * PowerShell that waits for the current process, runs the downloaded installer,
 * optionally relaunches, then deletes the artifact and itself.
 *
 * Extracted from [PlatformInstaller] so tests can assert the exact script.
 */
internal fun buildWindowsUpdateScript(
    pid: Long,
    installerCommand: String,
    relaunchCommand: String,
    artifactPath: String,
    scriptPath: String,
): String =
    """
    |# Wait for the app process to fully exit
    |while (Get-Process -Id $pid -ErrorAction SilentlyContinue) {
    |    Start-Sleep -Milliseconds 500
    |}
    |
    |# Run the installer silently
    |$installerCommand
    |$relaunchCommand
    |# Clean up
    |Remove-Item '${psSingleQuote(artifactPath)}' -Force -ErrorAction SilentlyContinue
    |Remove-Item '${psSingleQuote(scriptPath)}' -Force -ErrorAction SilentlyContinue
    """.trimMargin()

internal fun windowsInstallerCommand(
    file: File,
    extension: String,
): String {
    val path = psSingleQuote(file.absolutePath)
    return when (extension) {
        "msi" -> "Start-Process msiexec -ArgumentList '/i', '\"$path\"', '/passive' -Wait"
        else -> "Start-Process '$path' -ArgumentList '/S', '--updated' -Wait"
    }
}

internal fun windowsRelaunchCommand(
    restart: Boolean,
    launcher: String?,
    arguments: List<String> = emptyList(),
): String {
    if (!restart || launcher == null) return ""
    val argumentList =
        if (arguments.isEmpty()) "" else " -ArgumentList '${psSingleQuote(windowsCommandLine(arguments))}'"
    return "\n# Relaunch the application\nStart-Process '${psSingleQuote(launcher)}'$argumentList"
}

/**
 * Joins [arguments] into one Windows command line that `CommandLineToArgvW` (and so the JVM's
 * `main(args)`) splits back into the same list. `Start-Process -ArgumentList` passes an array
 * joined with bare spaces, which would split an argument holding a space.
 */
internal fun windowsCommandLine(arguments: List<String>): String =
    arguments.joinToString(" ") { argument ->
        if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '"' }) {
            argument
        } else {
            buildString {
                append('"')
                var backslashes = 0
                for (c in argument) {
                    when (c) {
                        '\\' -> backslashes++
                        '"' -> {
                            // Backslashes before a quote are doubled, and the quote itself escaped.
                            append("\\".repeat(backslashes * 2 + 1)).append('"')
                            backslashes = 0
                        }
                        else -> {
                            append("\\".repeat(backslashes)).append(c)
                            backslashes = 0
                        }
                    }
                }
                // Trailing backslashes are doubled so they do not escape the closing quote.
                append("\\".repeat(backslashes * 2)).append('"')
            }
        }
    }
