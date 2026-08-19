package dev.nucleusframework.updater.internal

import java.io.File

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
    |Remove-Item '$artifactPath' -Force -ErrorAction SilentlyContinue
    |Remove-Item '$scriptPath' -Force -ErrorAction SilentlyContinue
    """.trimMargin()

internal fun windowsInstallerCommand(
    file: File,
    extension: String,
): String =
    when (extension) {
        "msi" -> "Start-Process msiexec -ArgumentList '/i', '\"${file.absolutePath}\"', '/passive' -Wait"
        else -> "Start-Process '${file.absolutePath}' -ArgumentList '/S', '--updated' -Wait"
    }

internal fun windowsRelaunchCommand(
    restart: Boolean,
    launcher: String?,
): String =
    if (restart && launcher != null) {
        "\n# Relaunch the application\nStart-Process '$launcher'"
    } else {
        ""
    }
