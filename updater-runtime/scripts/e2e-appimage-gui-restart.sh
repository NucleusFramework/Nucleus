#!/usr/bin/env bash
# Full GUI e2e for AppImage post-update restart (#178).
#
# 1. Install an old real nucleusdemo AppImage
# 2. Launch it on the desktop (X11 via NUCLEUS_TAO_LINUX_RENDERER=x11 so xdotool works)
# 3. Wait for the "Nucleus Demo" window
# 4. Apply the production update path (in-place replace + generated script)
# 5. Assert the old process dies, a new process appears, and the GUI window returns
#
# Usage:
#   OLD=/path/to/old.AppImage NEW=/path/to/new.AppImage \
#     ./updater-runtime/scripts/e2e-appimage-gui-restart.sh
#
# Requires: DISPLAY, xdotool, a working FUSE AppImage runtime.
set -u

OLD_ARTIFACT=${OLD:-/tmp/nucleus-e2e-appimage/v1-old.AppImage}
NEW_ARTIFACT=${NEW:-/tmp/nucleus-e2e-appimage/v1.AppImage}
WORKDIR=${WORKDIR:-/tmp/nucleus-e2e-gui}
DISPLAY_VAL=${DISPLAY:-:0}
export DISPLAY="$DISPLAY_VAL"
# Prefer XWayland so xdotool can see the window (tao defaults to native Wayland).
export NUCLEUS_TAO_LINUX_RENDERER=x11

log() { printf '[e2e-gui] %s\n' "$*"; }
die() { log "FAIL: $*"; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }
need xdotool
need setsid
[ -f "$OLD_ARTIFACT" ] || die "old AppImage not found: $OLD_ARTIFACT"
[ -f "$NEW_ARTIFACT" ] || die "new AppImage not found: $NEW_ARTIFACT"
[ -n "$DISPLAY" ] || die "DISPLAY is not set"

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"
INSTALLED="$WORKDIR/NucleusDemo.AppImage"
DOWNLOAD="$WORKDIR/NucleusDemo-new.AppImage"
UPDATE_LOG="$WORKDIR/nucleus-update.log"
SCRIPT="$WORKDIR/nucleus-update.sh"
BEFORE_SHOT="$WORKDIR/before.png"
AFTER_SHOT="$WORKDIR/after.png"
APP_LOG="$WORKDIR/app-v1.log"
APP_LOG_V2="$WORKDIR/app-v2.log"

cp -a "$OLD_ARTIFACT" "$INSTALLED"
cp -a "$NEW_ARTIFACT" "$DOWNLOAD"
chmod +x "$INSTALLED" "$DOWNLOAD"
OLD_SHA=$(sha256sum "$INSTALLED" | awk '{print $1}')
NEW_SHA=$(sha256sum "$DOWNLOAD" | awk '{print $1}')
[ "$OLD_SHA" != "$NEW_SHA" ] || die "old and new AppImages are identical (need two versions)"

log "installed=$INSTALLED"
log "old_sha=$OLD_SHA"
log "new_sha=$NEW_SHA"

# --- kill any leftover demo from a previous run (by absolute path only) ---
kill_demo_tree() {
  local pid cmd
  for pid in $(pgrep -x NucleusDemo 2>/dev/null || true); do
    cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)
    case "$cmd" in
      *"$WORKDIR"*|*NucleusDemo*)
        log "killing leftover pid=$pid ($cmd)"
        kill "$pid" 2>/dev/null || true
        ;;
    esac
  done
  # AppImage outer process
  for pid in $(pgrep -f "NucleusDemo\\.AppImage" 2>/dev/null || true); do
    cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)
    case "$cmd" in
      *"$WORKDIR"*)
        log "killing leftover appimage pid=$pid"
        kill "$pid" 2>/dev/null || true
        ;;
    esac
  done
}
kill_demo_tree
sleep 1

# --- launch v1 GUI ---
log "launching AppImage (X11 renderer)..."
(
  cd "$WORKDIR"
  # shellcheck disable=SC2093
  exec ./NucleusDemo.AppImage
) >"$APP_LOG" 2>&1 &
LAUNCHER_PID=$!
log "launcher_pid=$LAUNCHER_PID"

find_demo_window() {
  # Return first window id whose name is exactly/contains "Nucleus Demo"
  # and whose pid is related to our AppImage (not the Warp terminal title).
  local id name pid cmd
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    name=$(xdotool getwindowname "$id" 2>/dev/null || true)
    pid=$(xdotool getwindowpid "$id" 2>/dev/null || true)
    [ -n "$pid" ] || continue
    case "$name" in
      "Nucleus Demo"|"NucleusDemo"|*"Nucleus Demo"*)
        cmd=$(tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true)
        case "$cmd" in
          *NucleusDemo*|*mount_Nucleu*)
            printf '%s\n' "$id"
            return 0
            ;;
        esac
        ;;
    esac
  done < <(xdotool search --onlyvisible --name 'Nucleus' 2>/dev/null || true)
  return 1
}

wait_for_window() {
  local timeout=${1:-120}
  local i=0 id
  while [ "$i" -lt "$timeout" ]; do
    if id=$(find_demo_window); then
      printf '%s\n' "$id"
      return 0
    fi
    # also accept any window owned by NucleusDemo binary
    for pid in $(pgrep -x NucleusDemo 2>/dev/null || true); do
      id=$(xdotool search --pid "$pid" 2>/dev/null | head -1 || true)
      if [ -n "$id" ]; then
        printf '%s\n' "$id"
        return 0
      fi
    done
    sleep 1
    i=$((i + 1))
  done
  return 1
}

log "waiting for GUI window..."
WIN1=$(wait_for_window 120) || {
  log "app log:"; tail -80 "$APP_LOG" || true
  die "GUI window did not appear within 120s"
}
WIN1_PID=$(xdotool getwindowpid "$WIN1")
WIN1_NAME=$(xdotool getwindowname "$WIN1")
log "window_before id=$WIN1 pid=$WIN1_PID name=[$WIN1_NAME]"

# Resolve the process PlatformInstaller would wait on: the JVM/native main
# (ProcessHandle.current()), which is the NucleusDemo binary under the mount.
APP_PID=$WIN1_PID
APPIMAGE_ENV=$(tr '\0' '\n' <"/proc/$APP_PID/environ" 2>/dev/null | sed -n 's/^APPIMAGE=//p' | head -1)
[ -n "$APPIMAGE_ENV" ] || APPIMAGE_ENV="$INSTALLED"
log "app_pid=$APP_PID APPIMAGE=$APPIMAGE_ENV"

# Screenshot before (best-effort)
if command -v import >/dev/null 2>&1; then
  import -window "$WIN1" "$BEFORE_SHOT" 2>/dev/null || import -window root "$BEFORE_SHOT" 2>/dev/null || true
elif command -v scrot >/dev/null 2>&1; then
  scrot -u "$BEFORE_SHOT" 2>/dev/null || scrot "$BEFORE_SHOT" 2>/dev/null || true
fi
[ -f "$BEFORE_SHOT" ] && log "screenshot_before=$BEFORE_SHOT"

# --- production update path ---
# 1) in-place replace while the app still holds the old inode open
log "replacing AppImage in place (electron-updater pattern)..."
rm -f "$APPIMAGE_ENV"
mv -f "$DOWNLOAD" "$APPIMAGE_ENV"
chmod +x "$APPIMAGE_ENV"
POST_SHA=$(sha256sum "$APPIMAGE_ENV" | awk '{print $1}')
[ "$POST_SHA" = "$NEW_SHA" ] || die "installed sha mismatch after replace (got $POST_SHA want $NEW_SHA)"
log "replaced ok (sha=$POST_SHA); old process still alive? $(kill -0 "$APP_PID" 2>/dev/null && echo yes || echo no)"

# 2) emit the *exact* production script from buildLinuxAppImageUpdateScript
REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
log "generating production update script via Kotlin..."
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
RAW_SCRIPT="$WORKDIR/nucleus-update.raw.sh"
if ! (
  cd "$REPO_ROOT"
  # --args paths are simple absolute /tmp paths (no spaces) so quoting is safe.
  ./gradlew -q :updater-runtime:dumpLinuxAppImageUpdateScript \
    --args="$DOWNLOAD $APPIMAGE_ENV $APP_PID $UPDATE_LOG true" \
    >"$RAW_SCRIPT" 2>"$WORKDIR/dump.err"
); then
  log "dump.err:"; cat "$WORKDIR/dump.err" 2>/dev/null || true
  die "failed to generate production update script"
fi
# Gradle plugins may chatter on stdout — keep only the shell script payload.
if ! grep -q '^#!/usr/bin/env bash' "$RAW_SCRIPT"; then
  log "dump.err:"; cat "$WORKDIR/dump.err" 2>/dev/null || true
  log "raw dump head:"; head -20 "$RAW_SCRIPT" || true
  die "generated output is not a shell script"
fi
# Drop any banner lines before the shebang.
sed -n '/^#!\/usr\/bin\/env bash/,$p' "$RAW_SCRIPT" >"$SCRIPT"
# DISPLAY / NUCLEUS_TAO_LINUX_RENDERER are inherited by setsid → relaunch (script only unsets AppImage vars).
chmod +x "$SCRIPT"
grep -q 'unset APPDIR APPIMAGE' "$SCRIPT" || die "generated script missing env cleanup"
grep -q 'APPIMAGE_SILENT_INSTALL' "$SCRIPT" || die "generated script missing APPIMAGE_SILENT_INSTALL"
log "production script written to $SCRIPT ($(wc -l <"$SCRIPT") lines)"

log "starting update script via setsid..."
setsid bash "$SCRIPT" >/dev/null 2>&1 &
sleep 0.3

# 3) quit the running app (PlatformInstaller does exitProcess after spawning the script)
log "terminating app pid=$APP_PID (simulating exitProcess after installAndRestart)..."
kill "$APP_PID" 2>/dev/null || true
# also terminate sibling tree under the AppImage if needed
if [ -n "${LAUNCHER_PID:-}" ]; then
  kill "$LAUNCHER_PID" 2>/dev/null || true
fi
# Wait for death
for i in $(seq 1 60); do
  kill -0 "$APP_PID" 2>/dev/null || break
  sleep 0.5
done
kill -0 "$APP_PID" 2>/dev/null && kill -9 "$APP_PID" 2>/dev/null || true
log "old process gone"

# --- wait for GUI relaunch ---
log "waiting for relaunched GUI..."
# give the script its sleep 1 + app cold start
sleep 2
WIN2=$(wait_for_window 120) || {
  log "update log:"; cat "$UPDATE_LOG" 2>/dev/null || true
  log "app log v1:"; tail -40 "$APP_LOG" 2>/dev/null || true
  die "GUI did not reappear after update"
}
WIN2_PID=$(xdotool getwindowpid "$WIN2")
WIN2_NAME=$(xdotool getwindowname "$WIN2")
log "window_after id=$WIN2 pid=$WIN2_PID name=[$WIN2_NAME]"

[ "$WIN2_PID" != "$APP_PID" ] || die "relaunch pid equals old pid (did not actually restart)"

# Confirm new process still points at the installed AppImage path
NEW_APPIMAGE=$(tr '\0' '\n' <"/proc/$WIN2_PID/environ" 2>/dev/null | sed -n 's/^APPIMAGE=//p' | head -1 || true)
log "relaunch APPIMAGE=${NEW_APPIMAGE:-unknown}"
INSTALLED_SHA=$(sha256sum "$APPIMAGE_ENV" | awk '{print $1}')
[ "$INSTALLED_SHA" = "$NEW_SHA" ] || die "on-disk AppImage is not the new version after relaunch"

if command -v import >/dev/null 2>&1; then
  import -window "$WIN2" "$AFTER_SHOT" 2>/dev/null || import -window root "$AFTER_SHOT" 2>/dev/null || true
elif command -v scrot >/dev/null 2>&1; then
  scrot -u "$AFTER_SHOT" 2>/dev/null || scrot "$AFTER_SHOT" 2>/dev/null || true
fi
[ -f "$AFTER_SHOT" ] && log "screenshot_after=$AFTER_SHOT"

log "update log:"
cat "$UPDATE_LOG" || true

log "PASS: full GUI AppImage update+restart verified"
log "  before_window=$WIN1 (pid $APP_PID)"
log "  after_window=$WIN2 (pid $WIN2_PID)"
log "  artifact_sha=$INSTALLED_SHA"

# Leave the relaunched app running so the user can see it; comment next lines to auto-quit.
# kill "$WIN2_PID" 2>/dev/null || true
exit 0
