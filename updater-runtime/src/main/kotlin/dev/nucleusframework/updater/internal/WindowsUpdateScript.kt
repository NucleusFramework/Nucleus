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
): String =
    if (restart && launcher != null) {
        "\n# Relaunch the application\nStart-Process '${psSingleQuote(launcher)}'"
    } else {
        ""
    }
