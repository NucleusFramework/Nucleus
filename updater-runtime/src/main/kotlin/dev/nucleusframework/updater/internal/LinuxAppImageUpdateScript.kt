package dev.nucleusframework.updater.internal

/**
 * Builds the shell script that finalises a Linux AppImage self-update and optionally relaunches.
 *
 * The downloaded AppImage is preferably swapped into place by [PlatformInstaller] *before* this
 * script runs (while the old FUSE/loop mount still holds the previous inode open — the
 * electron-updater pattern). The script then:
 *
 *  1. waits for the previous process to exit so [dev.nucleusframework.core.runtime.SingleInstanceManager]
 *     releases its lock;
 *  2. retries the file replace if the in-process swap could not complete;
 *  3. relaunches with a clean environment — stale `APPDIR` / `LD_LIBRARY_PATH` values still point
 *     into the unmounted squashfs of the previous instance and prevent the new process from
 *     starting, which is the failure mode behind issue #178.
 *
 * Extracted from [PlatformInstaller] so the exact script can be exercised by tests.
 */
internal fun buildLinuxAppImageUpdateScript(
    newFile: String,
    oldFile: String,
    appPid: Long,
    logFile: String,
    restart: Boolean,
    /** When true the JVM already swapped [newFile] onto [oldFile]; the script only retries on miss. */
    alreadyReplaced: Boolean,
    selfDelete: Boolean = true,
): String {
    val restartFlag = if (restart) "1" else "0"
    val alreadyFlag = if (alreadyReplaced) "1" else "0"
    val selfDeleteCmd = if (selfDelete) "rm -f \"\$0\"" else "true"
    return """
        |#!/usr/bin/env bash
        |# Intentionally no `set -e`: a failed intermediate step must not skip the relaunch —
        |# the user would otherwise be left with an installed update and a dead process (#178).
        |
        |# Survive parent process exit (desktop session / terminal hangup).
        |trap '' HUP
        |
        |NEW_FILE=${newFile.quoteForShell()}
        |OLD_FILE=${oldFile.quoteForShell()}
        |LOG_FILE=${logFile.quoteForShell()}
        |APP_PID=$appPid
        |RESTART=$restartFlag
        |ALREADY_REPLACED=$alreadyFlag
        |
        |log() {
        |    echo "${'$'}(date -Iseconds 2>/dev/null || date) ${'$'}*" >> "${'$'}LOG_FILE" 2>/dev/null || true
        |}
        |
        |log "waiting for pid ${'$'}APP_PID (already_replaced=${'$'}ALREADY_REPLACED restart=${'$'}RESTART)"
        |
        |# Wait for the app process to fully exit.
        |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        |log "pid ${'$'}APP_PID exited"
        |
        |# FUSE/loop unmount can lag the process exit by a moment.
        |sleep 1
        |
        |replace_appimage() {
        |    if [ -f "${'$'}NEW_FILE" ]; then
        |        # Prefer atomic rename; fall back to rm+mv when crossing filesystems.
        |        if mv -f "${'$'}NEW_FILE" "${'$'}OLD_FILE" 2>/dev/null; then
        |            return 0
        |        fi
        |        rm -f "${'$'}OLD_FILE" 2>/dev/null || true
        |        mv -f "${'$'}NEW_FILE" "${'$'}OLD_FILE"
        |        return ${'$'}?
        |    fi
        |    # In-process swap already put the new bytes at OLD_FILE.
        |    [ -f "${'$'}OLD_FILE" ] && [ -x "${'$'}OLD_FILE" ]
        |}
        |
        |if [ "${'$'}ALREADY_REPLACED" != "1" ] || [ -f "${'$'}NEW_FILE" ]; then
        |    attempts=0
        |    until replace_appimage; do
        |        attempts=${'$'}((attempts + 1))
        |        if [ "${'$'}attempts" -ge 10 ]; then
        |            log "ERROR: failed to install AppImage after ${'$'}attempts attempts"
        |            $selfDeleteCmd
        |            exit 1
        |        fi
        |        log "replace attempt ${'$'}attempts failed; retrying"
        |        sleep 0.5
        |    done
        |    log "AppImage installed at ${'$'}OLD_FILE"
        |else
        |    log "using in-process install at ${'$'}OLD_FILE"
        |fi
        |
        |chmod +x "${'$'}OLD_FILE" 2>/dev/null || true
        |
        |if [ "${'$'}RESTART" = "1" ]; then
        |    # Drop FUSE/AppImage leftovers from the previous instance. Stale APPDIR and
        |    # LD_LIBRARY_PATH point into the unmounted squashfs and break the new launch.
        |    unset APPDIR APPIMAGE OWD ARGV0
        |    unset LD_LIBRARY_PATH LD_PRELOAD
        |    unset QT_PLUGIN_PATH GTK_PATH GIO_MODULE_DIR
        |    # AppImage desktop-integration prompts must not block an unattended relaunch.
        |    export APPIMAGE_SILENT_INSTALL=true
        |    # CWD may still be the old mount point (ENOENT after unmount).
        |    cd "${'$'}{HOME:-/}" 2>/dev/null || cd / || true
        |    log "relaunching ${'$'}OLD_FILE"
        |    nohup "${'$'}OLD_FILE" >> "${'$'}LOG_FILE" 2>&1 &
        |    log "relaunch spawned (pid ${'$'}!)"
        |else
        |    log "relaunch skipped"
        |fi
        |
        |$selfDeleteCmd
        """.trimMargin()
}

/** Wraps a value in single quotes for safe interpolation into the generated shell script. */
private fun String.quoteForShell(): String = "'" + replace("'", "'\\''") + "'"
