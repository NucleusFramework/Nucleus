#!/bin/bash
# E2E + torture suite for the Linux screen-capture backend (screen-capture/src/main/native/linux).
#
# X11: Xvfb at depths 24 / 16 / 8, RandR monitors, a 4K screen, cursor, window capture (with and
# without an XComposite redirect), random-region torture from 8 threads with RSS / fd checks, and
# the X server dying mid-capture. Every display capture is checked pixel-exact against a known
# pattern and, at depth 24, against `xwd -root`.
# Portal: a fake org.freedesktop.portal.Screenshot on a private session bus (ok, cancel, error,
# timeout, bogus / missing / garbage files, percent-encoded paths, symlinks, ignored
# handle_token, a response before the method reply, concurrency).
#
# Requires: gcc, a JDK, Xvfb, xwd + ImageMagick `convert`, dbus-run-session, python3-dbus,
# python3-gi, and the X11 / Xrandr / Xcomposite / dbus development headers.
# Usage: scripts/screen-capture-linux-e2e.sh   (prints RESULT lines; exits non-zero on failure)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/scripts/screen-capture-linux-e2e"
bash "$ROOT/screen-capture/src/main/native/linux/build.sh" >/dev/null || exit 1
case "$(uname -m)" in x86_64) ARCH=x64 ;; *) ARCH=$(uname -m) ;; esac
LIB="$ROOT/screen-capture/src/main/resources/nucleus/native/linux-$ARCH/libnucleus_screencapture.so"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
main() {
gcc -O2 "$T/xtool.c" -o $W/xtool -lX11 -lXrandr -lXcomposite || return 1
javac -d $W/classes "$T"/harness/dev/nucleusframework/screencapture/internal/*.java "$T"/harness/dev/nucleusframework/core/runtime/*.java || return 1
JV="java --enable-native-access=ALL-UNNAMED -Xms1g -Xmx1g -XX:+AlwaysPreTouch -Dlib=$LIB -cp $W/classes"
H=dev.nucleusframework.screencapture.internal.Harness
J="$JV $H"
X=$W/xtool
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill $p 2>/dev/null; done; PIDS=(); sleep 0.3; }

start_x() { # display depth [size]
    LASTD=$1
    Xvfb :$1 -screen 0 ${3:-1920x1080}x$2 -nolisten tcp >/dev/null 2>&1 & PIDS+=($!)
    for i in $(seq 50); do DISPLAY=:$1 $X warp 0 0 2>/dev/null && return 0; sleep 0.1; done; echo "Xvfb :$1 failed"; return 1
}
solid() { # x y w h rgb -> sets WID
    coproc SOLID { exec $X window "$@"; }; PIDS+=($SOLID_PID); read -r WID <&${SOLID[0]}
}

for depth in 24 16; do
    echo "=== X11 depth $depth"
    start_x $((80 + depth)) $depth; export DISPLAY=:$LASTD
    $X pattern > /dev/null & PIDS+=($!); sleep 0.5
    solid 1500 800 200 150 3366CC; sleep 0.3
    DEAD=$($X destroyed)
    $J x11 $depth $X "1500,800,200,150,3366CC,$WID" $DEAD
    echo "=== X11 depth $depth, two RandR monitors"
    $X monitors
    $JV -Diterations=2000 $H x11 $depth $X "1500,800,200,150,3366CC,$WID" $DEAD monitors 2>&1 || true
    cleanup
done

echo "=== X11 4K (3840x2160x24) timing"
start_x 82 24 3840x2160; export DISPLAY=:$LASTD
$X pattern > /dev/null & PIDS+=($!); sleep 1.5
solid 1500 800 200 150 3366CC; sleep 0.3
$JV -Diterations=1200 -Dphases=8 $H x11 24 $X "1500,800,200,150,3366CC,$WID" $($X destroyed) 2>&1
cleanup

echo "=== X11 8-bit PseudoColor"
start_x 83 8 800x600; export DISPLAY=:$LASTD
solid 100 100 200 150 3366CC; sleep 0.3
$J smoke8 "100,100,200,150,3366CC,$WID" $($X destroyed)
cleanup

echo "=== occluded window, no compositor (fallback: visible pixels only)"
start_x 84 24; export DISPLAY=:$LASTD
solid 100 100 200 150 3366CC; W1=$WID; sleep 0.2
solid 150 150 50 50 FF0000; sleep 0.3
$JV -DallowOccluded=true $H window-only "100,100,200,150,3366CC,$W1" $($X destroyed) 2>&1 | grep -E "window|RESULT|compositor"
cleanup
echo "=== occluded window, composite redirect (XCompositeNameWindowPixmap)"
start_x 85 24; export DISPLAY=:$LASTD
$X redirect > /dev/null & PIDS+=($!); sleep 0.3
solid 100 100 200 150 3366CC; W1=$WID; sleep 0.2
solid 150 150 50 50 FF0000; sleep 0.3
$J window-only "100,100,200,150,3366CC,$W1" $($X destroyed)
echo "=== partly off-screen window"
solid -50 -40 200 150 00FF00; sleep 0.3
$J window-only "0,0,200,150,00FF00,$WID" $($X destroyed) 2>&1 | grep -E "window|RESULT|FAIL"
cleanup

echo "=== X server dies mid-capture"
start_x 86 24 1280x720; export DISPLAY=:$LASTD
XPID=${PIDS[-1]}
( sleep 1.5; kill -9 $XPID ) &
$J xkill 4000
cleanup

echo "=== no DISPLAY"
unset DISPLAY
env -u DISPLAY -u WAYLAND_DISPLAY $J nodisplay

echo "=== portal"
rm -rf $W/shots; mkdir -p $W/shots; echo ok > $W/mode
dbus-run-session -- bash -c "PORTAL_MODE_FILE=$W/mode PORTAL_DIR=$W/shots python3 $T/fake_portal.py & sleep 1.5; $JV -DportalMode=$W/mode -DportalDir=$W/shots $H portal"
echo "=== portal missing (bus without portal)"
dbus-run-session -- $J portal-missing
echo "=== portal missing (no session bus)"
env -u DISPLAY -u WAYLAND_DISPLAY -u DBUS_SESSION_BUS_ADDRESS XDG_RUNTIME_DIR=/nonexistent $J portal-missing
}

main 2>&1 | grep -vE "^WARNING|^$" | tee "$W/log"
PASSED=$(grep -c "RESULT: PASS" "$W/log")
FAILURES=$(grep -c "RESULT: FAIL" "$W/log")
echo "=== $PASSED passed, $FAILURES failed"
[ "$FAILURES" = 0 ] && [ "$PASSED" -gt 0 ]
