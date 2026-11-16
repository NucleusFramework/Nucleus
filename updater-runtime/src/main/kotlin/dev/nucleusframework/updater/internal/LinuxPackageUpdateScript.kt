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
 * The installer's output goes to [logFile]. When it exits non-zero, [failureFile] records
 * [version], [runningVersion] and its exit status (see [InstallFailureRecord]) for the relaunched
 * app to report: the app is relaunched either way, so without the record a failed install would
 * look like an update that did nothing.
 *
 * Extracted from [PlatformInstaller] so the exact script can be exercised by tests.
 */
@Suppress("LongParameterList")
internal fun buildLinuxPackageUpdateScript(
    packageFile: String,
    extension: String,
    launcher: String,
    helper: String?,
    appPid: Long,
    restart: Boolean,
    version: String,
    runningVersion: String,
    logFile: String,
    failureFile: String,
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
        |NEW_VERSION=${version.quoteForShell()}
        |RUNNING_VERSION=${runningVersion.quoteForShell()}
        |LOG_FILE=${logFile.quoteForShell()}
        |FAILURE_FILE=${failureFile.quoteForShell()}
        |
        |# Wait for the app process to fully exit
        |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        |
        |sleep 1
        |
        |# Keep the installer's output: this script's own output is discarded, so the log is the
        |# only record of why an install failed. A log that cannot be written must not stop the
        |# install, so fall back to discarding it.
        |{ mkdir -p "$(dirname "${'$'}LOG_FILE")" && : > "${'$'}LOG_FILE"; } 2>/dev/null || LOG_FILE=/dev/null
        |rm -f "${'$'}FAILURE_FILE"
        |
        |# Install the package. Silent path uses the signature-verifying helper;
        |# otherwise pkexec dpkg/rpm shows an authentication dialog.
        |$installCmd > "${'$'}LOG_FILE" 2>&1
        |STATUS=${'$'}?
        |
        |# Any non-zero status is a failed install: dpkg exits 1 when it leaves the package
        |# unconfigured and 2 when it cannot start (say, another package manager holds its lock),
        |# pkexec exits 126 when the authentication dialog is dismissed, and the helper exits
        |# non-zero when it refuses the package. errexit stays off so the app is relaunched either
        |# way; the record tells it why it is still the old version.
        |if [ "${'$'}STATUS" -ne 0 ]; then
        |    printf '${InstallFailureRecord.KEY_VERSION}=%s\n${InstallFailureRecord.KEY_RUNNING_VERSION}=%s\n${InstallFailureRecord.KEY_EXIT_CODE}=%s\n' \
        |        "${'$'}NEW_VERSION" "${'$'}RUNNING_VERSION" "${'$'}STATUS" > "${'$'}FAILURE_FILE.tmp" &&
        |        mv -f "${'$'}FAILURE_FILE.tmp" "${'$'}FAILURE_FILE"
        |fi
        |
        |# Clean up the package file and its detached signature
        |rm -f "${'$'}PKG_FILE" "${'$'}PKG_FILE.asc"
        |$relaunchCmd
        |# Clean up this script
        |rm -f "${'$'}{0}"
        """.trimMargin()
}
