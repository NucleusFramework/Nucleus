#!/bin/bash
# macOS E2E of #953: the content must be laid out for the size the window has on screen in every frame
# of a resize. Runs examples/resize-lag-demo, then per round records the window with ScreenCaptureKit
# while it is resized, once by a fast corner drag and once by the zoom animation (title-bar double-click,
# grow then shrink back). Every window size that reaches the screen must show the demo's right and bottom
# stripes on the window edges and its rail at the left edge.
# Needs Screen Recording and Accessibility (synthetic mouse events) for the terminal running it.
# Exits with the number of failed recordings.
#
#   scripts/macos-resize-lag-e2e.sh [rounds=5]
set -uo pipefail
ROUNDS=${1:-5}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT=${OUT_DIR:-${TMPDIR:-/tmp}/resize-lag-e2e}
TITLE=ResizeLagDemo
mkdir -p "$OUT"
rm -f "$OUT"/*.csv

HELPER="$OUT/helper"
swiftc -O -o "$HELPER" "$ROOT/scripts/macos-resize-lag-e2e/helper.swift" || exit 1

"$ROOT/gradlew" -p "$ROOT" :examples:resize-lag-demo:run --console=plain -q >"$OUT/gradle.out" 2>&1 &
GRADLE=$!
cleanup() { pkill -f resizelagdemo.MainKt; wait "$GRADLE" 2>/dev/null; }
trap cleanup EXIT

for _ in $(seq 300); do "$HELPER" find "$TITLE" >/dev/null 2>&1 && break; sleep 1; done
read -r PID _ < <("$HELPER" find "$TITLE") || { echo "no $TITLE window, see $OUT/gradle.out"; exit 1; }
sleep 2 # first frames, JIT

# Same start for every recording: 800x500 at (100, 100), not zoomed.
reset() {
    osascript -e "tell application \"System Events\" to tell (first process whose unix id is $PID)
        set position of window \"$TITLE\" to {100, 100}
        set size of window \"$TITLE\" to {800, 500}
    end tell" >/dev/null
    sleep 0.5
}

# record <name> <command...>: records 3.5 s, running the command 0.8 s in.
record() {
    local name=$1; shift
    reset
    "$HELPER" record "$TITLE" 3.5 "$OUT/$name.csv" &
    local rec=$!
    sleep 0.8
    "$@"
    wait "$rec"
}

zoom() { "$HELPER" dblclick "$TITLE"; sleep 1.2; "$HELPER" dblclick "$TITLE"; }

for i in $(seq "$ROUNDS"); do
    record "drag-$i" "$HELPER" drag "$TITLE" 400 250 12 8 2
    record "zoom-$i" zoom
done

"$HELPER" summary "$OUT"/drag-*.csv "$OUT"/zoom-*.csv
