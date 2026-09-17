package dev.nucleusframework.updater.internal

/**
 * Builds the shell script that swaps a running macOS `.app` bundle for the one shipped in an update
 * archive.
 *
 * The archive's bundle directory does not necessarily carry the same name as the installed one — a
 * DMG stages the app under the product name while a ZIP preserves whatever the build produced — so
 * the script never assumes the two match:
 *
 *  1. it extracts into a staging directory next to the installed app and leaves that app untouched
 *     until a complete replacement exists on disk, so a truncated download or a failed `ditto`
 *     cannot leave the machine without an application;
 *  2. it locates the `.app` inside the archive instead of guessing its name;
 *  3. it keeps the installed path when both bundles share a `CFBundleIdentifier`, so Dock tiles,
 *     login items and aliases keep resolving, and only adopts the archive's name when the identifier
 *     changed — a deliberate rebranding — removing the old bundle so no duplicate is left behind;
 *  4. it restores the previous bundle if the swap fails.
 *
 * Extracted from [PlatformInstaller] so the exact script can be exercised by tests.
 */
internal fun buildMacZipUpdateScript(
    zipFile: String,
    appPath: String,
    installDir: String,
    appPid: Long,
    logFile: String,
    restart: Boolean,
    selfDelete: Boolean = true,
): String = scriptPreamble(zipFile, appPath, installDir, appPid, logFile) + scriptSwap(restart, selfDelete)

/** Setup, staging extraction and bundle identification — everything before the swap. */
@Suppress("LongParameterList")
private fun scriptPreamble(
    zipFile: String,
    appPath: String,
    installDir: String,
    appPid: Long,
    logFile: String,
): String =
    """
        |#!/usr/bin/env bash
        |set -euo pipefail
        |
        |ZIP_FILE=${zipFile.quoteForShell()}
        |APP_PATH=${appPath.quoteForShell()}
        |INSTALL_DIR=${installDir.quoteForShell()}
        |LOG_FILE=${logFile.quoteForShell()}
        |APP_PID=$appPid
        |
        |exec >>"${'$'}LOG_FILE" 2>&1
        |echo "--- nucleus update ${'$'}(date) ---"
        |
        |STAGE_DIR="${'$'}INSTALL_DIR/.nucleus-update-${'$'}${'$'}"
        |BACKUP=""
        |TARGET="${'$'}APP_PATH"
        |
        |# Runs on every exit path, including an interrupt between the two renames below: if the
        |# installed bundle was moved aside and nothing took its place, put it back. Leaving the
        |# machine without an application is the one outcome this script must never produce.
        |cleanup() {
        |    if [ -n "${'$'}BACKUP" ] && [ -d "${'$'}BACKUP" ] && [ ! -d "${'$'}TARGET" ]; then
        |        echo "Restoring the previous bundle after an interrupted update"
        |        mv "${'$'}BACKUP" "${'$'}TARGET" || true
        |    fi
        |    rm -rf "${'$'}STAGE_DIR"
        |}
        |trap cleanup EXIT INT TERM
        |
        |# Wait for the app process to fully exit before touching its bundle.
        |while kill -0 "${'$'}APP_PID" 2>/dev/null; do
        |    sleep 0.5
        |done
        |
        |# Unpack next to the installed app: same volume, so the swap below is a plain rename, and
        |# the installed bundle stays intact until a complete replacement exists.
        |rm -rf "${'$'}STAGE_DIR"
        |mkdir -p "${'$'}STAGE_DIR"
        |ditto -x -k "${'$'}ZIP_FILE" "${'$'}STAGE_DIR"
        |
        |# -print -quit rather than a pipe into head: under pipefail a killed `find` surfaces as
        |# exit 141 and errexit would abort here, before any diagnostic is logged.
        |NEW_APP="${'$'}(/usr/bin/find "${'$'}STAGE_DIR" -maxdepth 2 -name '*.app' -type d -print -quit)"
        |if [ -z "${'$'}NEW_APP" ] || [ ! -f "${'$'}NEW_APP/Contents/Info.plist" ]; then
        |    echo "No .app bundle found in ${'$'}ZIP_FILE — keeping the installed application"
        |    exit 1
        |fi
        |echo "Archive contains: ${'$'}(basename "${'$'}NEW_APP")"
        |
        |read_bundle_id() {
        |    /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${'$'}1/Contents/Info.plist" 2>/dev/null || true
        |}
        |CURRENT_BUNDLE_ID="${'$'}(read_bundle_id "${'$'}APP_PATH")"
        |NEW_BUNDLE_ID="${'$'}(read_bundle_id "${'$'}NEW_APP")"
        |
        |# Same identifier: keep the installed path so the Dock, login items and aliases stay valid.
        |# Different identifier: the app was renamed on purpose, adopt the new name and drop the old one.
        |REMOVE_OLD=0
        |if [ -n "${'$'}CURRENT_BUNDLE_ID" ] && [ -n "${'$'}NEW_BUNDLE_ID" ] &&
        |    [ "${'$'}CURRENT_BUNDLE_ID" != "${'$'}NEW_BUNDLE_ID" ]; then
        |    TARGET="${'$'}INSTALL_DIR/${'$'}(basename "${'$'}NEW_APP")"
        |    if [ "${'$'}TARGET" != "${'$'}APP_PATH" ]; then
        |        REMOVE_OLD=1
        |    fi
        |fi
        |echo "Installing to: ${'$'}TARGET"
        |
    """.trimMargin()

/** The swap itself, plus quarantine removal, relaunch and cleanup. */
private fun scriptSwap(
    restart: Boolean,
    selfDelete: Boolean,
): String {
    // A failed relaunch must not abort the script under errexit: the new version is already
    // installed at that point, and aborting would skip the cleanup below and report a failure.
    val relaunch =
        if (restart) {
            "open \"\$TARGET\" || echo \"Relaunch failed; the update itself succeeded\""
        } else {
            "echo \"Relaunch skipped\""
        }
    val selfDeleteCmd = if (selfDelete) "rm -f \"\$0\"" else "true"
    return """
        |# Swap through a backup so a failed move can be rolled back.
        |BACKUP="${'$'}TARGET.nucleus-old-${'$'}${'$'}"
        |if [ -d "${'$'}TARGET" ]; then
        |    mv "${'$'}TARGET" "${'$'}BACKUP"
        |fi
        |if ! mv "${'$'}NEW_APP" "${'$'}TARGET"; then
        |    echo "Failed to install the new bundle — restoring the previous one"
        |    if [ -d "${'$'}BACKUP" ]; then
        |        mv "${'$'}BACKUP" "${'$'}TARGET"
        |    fi
        |    exit 1
        |fi
        |rm -rf "${'$'}BACKUP"
        |BACKUP=""
        |
        |if [ "${'$'}REMOVE_OLD" = "1" ] && [ -d "${'$'}APP_PATH" ]; then
        |    echo "Removing the previous bundle: ${'$'}APP_PATH"
        |    rm -rf "${'$'}APP_PATH"
        |fi
        |
        |xattr -r -d com.apple.quarantine "${'$'}TARGET" 2>/dev/null || true
        |$relaunch
        |
        |rm -f "${'$'}ZIP_FILE"
        |$selfDeleteCmd
        """.trimMargin()
}

/** Wraps a value in single quotes for safe interpolation into the generated shell script. */
private fun String.quoteForShell(): String = "'" + replace("'", "'\\''") + "'"
