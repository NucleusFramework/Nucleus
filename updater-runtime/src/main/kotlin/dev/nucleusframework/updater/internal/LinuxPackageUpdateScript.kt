package dev.nucleusframework.updater.internal

/**
 * Builds the shell script that installs a downloaded `.deb` / `.rpm` once the app has exited, and
 * optionally relaunches it.
 *
 * With a [helper] (the passwordless, signature-verifying `nucleus-update-helper`) the package is
 * installed through it; otherwise `pkexec dpkg -i` / `pkexec rpm -U` shows an authentication
 * dialog. Every path is emitted as a single-quoted literal: the package file name comes from the
 * update manifest, which is remote input, so a `$(…)` or `"` in it must not be interpreted.
 *
 * Extracted from [PlatformInstaller] so the exact script can be exercised by tests.
 */
internal fun buildLinuxPackageUpdateScript(
    packageFile: String,
    extension: String,
    launcher: String,
    helper: String?,
    appPid: Long,
    restart: Boolean,
): String {
    val installCmd =
        when {
            helper != null -> "pkexec ${helper.quoteForShell()} \"\$PKG_FILE\""
            extension == "deb" -> "pkexec dpkg -i \"\$PKG_FILE\""
            extension == "rpm" -> "pkexec rpm -U \"\$PKG_FILE\""
            else -> error("Unsupported package format: $extension")
        }

    val relaunchCmd =
        if (restart) {
            "\n# Relaunch the application\nnohup \"\$APP_LAUNCHER\" > /dev/null 2>&1 &\n"
        } else {
            ""
        }

    return """
        |#!/usr/bin/env bash
        |
        |# Ignore SIGHUP to survive parent process exit
        |trap '' HUP
        |
        |PKG_FILE=${packageFile.quoteForShell()}
        |APP_PID=$appPid
        |APP_LAUNCHER=${launcher.quoteForShell()}
        |
        |# Wait for the app process to fully exit
        |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        |
        |sleep 1
        |
        |# Install the package. Silent path uses the signature-verifying helper;
        |# otherwise pkexec dpkg/rpm shows an authentication dialog.
        |# Do not use set -e: dpkg/rpm may return non-zero on warnings,
        |# which would prevent the application from relaunching.
        |$installCmd
        |
        |# Clean up the package file and its detached signature
        |rm -f "${'$'}PKG_FILE" "${'$'}PKG_FILE.asc"
        |$relaunchCmd
        |# Clean up this script
        |rm -f "${'$'}{0}"
        """.trimMargin()
}
