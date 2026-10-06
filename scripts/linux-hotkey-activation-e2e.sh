#!/usr/bin/env bash
# E2E for #739: a global hotkey received through org.freedesktop.portal.GlobalShortcuts brings a
# hidden window to the front with keyboard focus on a GNOME Wayland session.
#
# Everything is real: GNOME's portal, Mutter's focus-stealing prevention, and the key press, which
# goes through a uinput virtual keyboard (the user must be able to write /dev/uinput). The app is
# examples/hotkey-activation-demo, launched inside an `app-<id>-<n>.scope` so the portal resolves
# its app id (the portal also wants a matching .desktop file, installed for the run), with the
# shortcut grant pre-seeded in dconf so no approval dialog is needed (the previous dconf state is
# restored on exit).
#
#   scripts/linux-hotkey-activation-e2e.sh            # token mode (the fix) and plain mode
#   scripts/linux-hotkey-activation-e2e.sh token      # the window focuses with the activation token
#   scripts/linux-hotkey-activation-e2e.sh plain      # requestFocus() without the token: the bug
#
# HOTKEY_E2E_START=visible (default) keeps the app's window mapped while a zenity window takes
# focus — the case Mutter's focus-stealing prevention refuses without a token. =hidden hides it
# instead: a re-mapped Wayland toplevel is a new window, which Mutter focuses either way.
#
# Requires GNOME 50+ / xdg-desktop-portal 1.21+ for the activation token, and zenity. Presses
# Ctrl+Alt+Shift+Pause on the live session.

set -euo pipefail

MODES=("${@:-token plain}")
read -r -a MODES <<<"${MODES[*]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="dev.nucleusframework.HotKeyActivationDemo"
SHORTCUT_ID="nucleus_m7_k13" # Ctrl+Alt+Shift (7) + VK_PAUSE (0x13)
TRIGGER="<Shift><Control><Alt>Pause"
DCONF_ROOT="/org/gnome/settings-daemon/global-shortcuts"
JAVA="${JAVA:-java}"
START="${HOTKEY_E2E_START:-visible}"

if [[ "${XDG_SESSION_TYPE:-}" != "wayland" ]]; then
    echo "SKIP: needs a Wayland session (XDG_SESSION_TYPE=${XDG_SESSION_TYPE:-unset})"
    exit 0
fi
[[ -w /dev/uinput ]] || { echo "FAIL: /dev/uinput is not writable"; exit 1; }

echo "== Building examples:hotkey-activation-demo"
"$ROOT/gradlew" -p "$ROOT" -q --no-configuration-cache :examples:hotkey-activation-demo:writeE2eClasspath
CLASSPATH_FILE="$ROOT/examples/hotkey-activation-demo/build/e2e-classpath.txt"

# The portal only accepts a host app id that has a desktop file.
DESKTOP_FILE="${XDG_DATA_HOME:-$HOME/.local/share}/applications/$APP_ID.desktop"
DESKTOP_EXISTED=0
[[ -e "$DESKTOP_FILE" ]] && DESKTOP_EXISTED=1
if [[ $DESKTOP_EXISTED -eq 0 ]]; then
    mkdir -p "$(dirname "$DESKTOP_FILE")"
    printf '[Desktop Entry]\nType=Application\nName=Nucleus Hotkey Activation E2E\nExec=true\nNoDisplay=true\n' >"$DESKTOP_FILE"
fi

# Seed the grant; keep what was there to restore it.
OLD_APPS="$(dconf read "$DCONF_ROOT/applications" || true)"
OLD_SHORTCUTS="$(dconf read "$DCONF_ROOT/$APP_ID/shortcuts" || true)"
restore() {
    if [[ -n "$OLD_APPS" ]]; then dconf write "$DCONF_ROOT/applications" "$OLD_APPS"; else dconf reset "$DCONF_ROOT/applications"; fi
    if [[ -n "$OLD_SHORTCUTS" ]]; then dconf write "$DCONF_ROOT/$APP_ID/shortcuts" "$OLD_SHORTCUTS"; else dconf reset -f "$DCONF_ROOT/$APP_ID/"; fi
    if [[ $DESKTOP_EXISTED -eq 0 ]]; then rm -f "$DESKTOP_FILE"; fi
}
trap restore EXIT
python3 - "$OLD_APPS" "$APP_ID" <<'EOF' | xargs -0 dconf write "$DCONF_ROOT/applications"
import ast, sys
old, app = sys.argv[1], sys.argv[2]
apps = ast.literal_eval(old.removeprefix("@as ")) if old else []
if app not in apps:
    apps.append(app)
sys.stdout.write("[" + ", ".join(repr(a) for a in apps) + "]")
EOF
dconf write "$DCONF_ROOT/$APP_ID/shortcuts" \
    "[('$SHORTCUT_ID', {'shortcuts': <['$TRIGGER']>, 'description': <'Open quick entry'>})]"

# A Right Shift tap into whatever window the user is in (Mutter weighs that interaction against the
# app's own: without it, the app is still the last one the user touched and may take focus freely),
# Ctrl+Alt+Shift+Pause on a virtual keyboard, Pause held long enough to auto-repeat, then another
# lone Right Shift tap: it reaches the app only if the hotkey gave it keyboard focus. Shift is
# harmless wherever it lands.
press_chord() {
    python3 - <<'EOF'
import fcntl, os, struct, time
UI_SET_EVBIT, UI_SET_KEYBIT, UI_DEV_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY = 0x40045564, 0x40045565, 0x405C5503, 0x5501, 0x5502
EV_SYN, EV_KEY = 0, 1
CTRL, ALT, SHIFT, PAUSE, RIGHTSHIFT = 29, 56, 42, 119, 54
fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
for key in (CTRL, ALT, SHIFT, PAUSE, RIGHTSHIFT):
    fcntl.ioctl(fd, UI_SET_KEYBIT, key)
fcntl.ioctl(fd, UI_DEV_SETUP, struct.pack("HHHH80sI", 0x03, 0x1234, 0x0739, 1, b"nucleus-739 keyboard", 0))
fcntl.ioctl(fd, UI_DEV_CREATE)
time.sleep(1.5)  # let libinput / Mutter pick the device up

def key(code, value):
    now = time.time()
    sec, usec = int(now), int((now % 1) * 1e6)
    os.write(fd, struct.pack("qqHHi", sec, usec, EV_KEY, code, value) + struct.pack("qqHHi", sec, usec, EV_SYN, 0, 0))
    time.sleep(0.03)

key(RIGHTSHIFT, 1)
key(RIGHTSHIFT, 0)
time.sleep(1.0)

for code in (CTRL, ALT, SHIFT, PAUSE):
    key(code, 1)
time.sleep(1.5)
for code in (PAUSE, SHIFT, ALT, CTRL):
    key(code, 0)
time.sleep(1.0)
print("probe", flush=True)
key(RIGHTSHIFT, 1)
key(RIGHTSHIFT, 0)
time.sleep(0.5)
fcntl.ioctl(fd, UI_DEV_DESTROY)
os.close(fd)
EOF
}

FAILED=0
for MODE in "${MODES[@]}"; do
    echo "== Mode: $MODE"
    LOG="$(mktemp -t hotkey-activation-demo.XXXXXX.log)"
    HOTKEY_DEMO_MODE="$MODE" HOTKEY_DEMO_LOG="$LOG" HOTKEY_DEMO_START="$START" \
        systemd-run --user --scope --quiet --collect --unit="app-$APP_ID-$$$RANDOM" -- \
        "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$(cat "$CLASSPATH_FILE")" hotkeydemo.MainKt >/dev/null 2>&1 &
    APP_PID=$!

    for _ in $(seq 1 100); do grep -q '^ready' "$LOG" 2>/dev/null && break; sleep 0.2; done
    if ! grep -q '^ready' "$LOG"; then
        echo "FAIL: the app did not start"; cat "$LOG"; kill "$APP_PID" 2>/dev/null || true; FAILED=1; continue
    fi
    grep '^registered' "$LOG"
    OTHER_PID=""
    if [[ "$START" == "visible" ]]; then
        # Another app takes focus over the still-mapped window.
        zenity --info --title="Nucleus #739 E2E" --text="Another app holding focus" >/dev/null 2>&1 &
        OTHER_PID=$!
    fi
    sleep 1.5
    press_chord | while read -r line; do echo "$line" >>"$LOG"; done
    sleep 1
    [[ -n "$OTHER_PID" ]] && kill "$OTHER_PID" 2>/dev/null || true
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true

    echo "-- app log"
    sed 's/^/   /' "$LOG"
    PRESSES=$(grep -c '^event state=PRESSED repeat=false' "$LOG" || true)
    REPEATS=$(grep -c '^event state=PRESSED repeat=true' "$LOG" || true)
    RELEASES=$(grep -c '^event state=RELEASED' "$LOG" || true)
    TOKENS=$(grep -c '^event state=PRESSED .*token=yes' "$LOG" || true)
    # The chord itself must not reach the window: it is hidden and unfocused when the hotkey fires.
    if sed -n '/^ready/,/^event state=PRESSED/p' "$LOG" | grep -q '^key '; then
        echo "FAIL: the window had keyboard focus before the hotkey — the scenario did not start from a hidden window"
        FAILED=1; rm -f "$LOG"; continue
    fi
    # The probe key typed after the hotkey reached the window: it had keyboard focus.
    FOCUSED=$(sed -n '/^probe/,$p' "$LOG" | grep -c '^key .*KeyDown' || true)
    echo "-- presses=$PRESSES repeats=$REPEATS releases=$RELEASES tokens=$TOKENS probeKeyReachedWindow=$FOCUSED"

    case "$MODE" in
        token)
            if [[ $PRESSES -ge 1 && $RELEASES -ge 1 && $TOKENS -ge 1 && $FOCUSED -ge 1 ]]; then
                echo "PASS: token mode — the hotkey delivered a token and a release, and the window took keyboard focus"
            else
                echo "FAIL: token mode"; FAILED=1
            fi
            ;;
        plain)
            if [[ $PRESSES -ge 1 && $FOCUSED -eq 0 ]]; then
                echo "REPRODUCED: plain mode — requestFocus() without the token leaves the window without keyboard focus (#739)"
            elif [[ $PRESSES -ge 1 ]]; then
                echo "NOTE: plain mode — the compositor let the window focus without a token"
            else
                echo "FAIL: plain mode — no hotkey event"; FAILED=1
            fi
            ;;
    esac
    rm -f "$LOG"
done
exit $FAILED
