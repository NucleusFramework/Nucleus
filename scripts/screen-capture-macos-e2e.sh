#!/bin/bash
# macOS E2E of screen-capture: runs examples/screen-capture-demo in self-test mode, the same
# protocol as screen-capture-windows-e2e.ps1. On top of the demo's own checks this script:
#   - parks the cursor at the centre of the main display (cursor compositing check);
#   - opens a window whose app never pumps its run loop again (a hung foreign window);
#   - samples the demo's file descriptors, threads, Mach ports and RSS around the torture phase.
# Needs the Screen Recording permission for the terminal running it. Exits with the failure count.
#
#   scripts/screen-capture-macos-e2e.sh [torture=800] [timeoutSeconds=900]
set -uo pipefail
TORTURE=${1:-800}
TIMEOUT=${2:-900}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT=${OUT_DIR:-${TMPDIR:-/tmp}/screen-capture-e2e}
mkdir -p "$OUT"
LOG="$OUT/selftest.log"; HUNGFILE="$OUT/hung.id"
rm -f "$LOG" "$HUNGFILE"

HELPER="$OUT/helper"
swiftc -O -o "$HELPER" "$ROOT/scripts/screen-capture-macos-e2e/helper.swift" || exit 1

"$HELPER" hung "$HUNGFILE" & HUNG_PID=$!
for _ in $(seq 50); do [ -s "$HUNGFILE" ] && break; sleep 0.2; done
HUNG_ID=$(cat "$HUNGFILE" 2>/dev/null || echo 0)
echo "hung window: $HUNG_ID (pid $HUNG_PID)"
"$HELPER" park

sample() { # fds threads ports rssKb
    echo "$(lsof -p "$1" 2>/dev/null | wc -l | tr -d ' ') $(($(ps -M -p "$1" | wc -l) - 1)) \
$(top -l 1 -pid "$1" -stats ports | tail -1 | tr -dc '0-9') $(ps -o rss= -p "$1" | tr -d ' ')"
}

SCREEN_CAPTURE_DEMO_SELFTEST=1 SCREEN_CAPTURE_DEMO_LOG="$LOG" SCREEN_CAPTURE_DEMO_OUT="$OUT" \
SCREEN_CAPTURE_DEMO_TORTURE="$TORTURE" SCREEN_CAPTURE_DEMO_HUNG_HWND="$HUNG_ID" \
SCREEN_CAPTURE_DEMO_CURSOR_EXPECTED=1 \
    "$ROOT/gradlew" -p "$ROOT" :examples:screen-capture-demo:run --console=plain -q >"$OUT/gradle.out" 2>&1 &
GRADLE=$!

deadline=$((SECONDS + TIMEOUT)); APP=0; BEFORE=""; AFTER=""
while [ $SECONDS -lt $deadline ]; do
    sleep 0.5
    [ -f "$LOG" ] || { kill -0 $GRADLE 2>/dev/null && continue || break; }
    [ $APP = 0 ] && APP=$(sed -n 's/.*START pid=\([0-9]*\).*/\1/p' "$LOG" | head -1) && APP=${APP:-0}
    if [ $APP != 0 ] && [ -z "$BEFORE" ] && grep -q "PHASE torture-begin" "$LOG"; then sleep 1; BEFORE=$(sample $APP); fi
    if [ $APP != 0 ] && [ -z "$AFTER" ] && grep -q "PHASE torture-end" "$LOG"; then sleep 1.5; AFTER=$(sample $APP); fi
    grep -q "DONE failures=" "$LOG" && break
done
wait $GRADLE
kill -9 $HUNG_PID 2>/dev/null

FAILURES=0
if [ -f "$LOG" ]; then
    grep -E " (FAIL|INFO|SKIP|DISPLAY) " "$LOG"
    FAILURES=$(grep -c " FAIL " "$LOG")
    echo "passed checks: $(grep -c " PASS " "$LOG")"
    grep -q "DONE failures=" "$LOG" || { echo "FAIL self-test did not finish"; FAILURES=$((FAILURES + 1)); }
else
    echo "FAIL no self-test log"; FAILURES=$((FAILURES + 1))
fi
if [ -n "$BEFORE" ] && [ -n "$AFTER" ]; then
    read -r f0 t0 p0 r0 <<<"$BEFORE"; read -r f1 t1 p1 r1 <<<"$AFTER"
    echo "resources before torture: fds=$f0 threads=$t0 ports=$p0 rssMb=$((r0 / 1024))"
    echo "resources after torture:  fds=$f1 threads=$t1 ports=$p1 rssMb=$((r1 / 1024))"
    # Threads and JIT come and go; a per-capture leak would be thousands (or GBs of pixels).
    [ $((f1 - f0)) -gt 50 ] && { echo "FAIL file descriptor leak"; FAILURES=$((FAILURES + 1)); }
    [ $((p1 - p0)) -gt 200 ] && { echo "FAIL Mach port leak"; FAILURES=$((FAILURES + 1)); }
    [ $((t1 - t0)) -gt 50 ] && { echo "FAIL thread leak"; FAILURES=$((FAILURES + 1)); }
    [ $((r1 - r0)) -gt $((1024 * 1024)) ] && { echo "FAIL RSS grew by over 1 GB"; FAILURES=$((FAILURES + 1)); }
else
    echo "FAIL resources were not sampled"; FAILURES=$((FAILURES + 1))
fi
echo "failures=$FAILURES"
exit $FAILURES
