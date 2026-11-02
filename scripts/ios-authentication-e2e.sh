#!/bin/bash
# iOS E2E for `authentication` on a booted Face ID simulator, without a person: builds
# examples/authentication-ios-demo, then drives Face ID enrolment and matches through the
# simulator's BiometricKit notifications.
#
#   scripts/ios-authentication-e2e.sh [simulator-udid]   (default: the booted one)
#
# Not covered here (needs a tap): the prompt's cancel button → UserCanceled.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEMO="$ROOT/examples/authentication-ios-demo"
APP="$DEMO/build/AuthenticationIosDemo.app"
BUNDLE_ID="dev.nucleusframework.authenticationiosdemo"
UDID="${1:-$(xcrun simctl list devices booted | grep -oE '[0-9A-F-]{36}' | head -1)}"
[ -n "$UDID" ] || { echo "No booted simulator" >&2; exit 1; }
LOG_DIR="$(mktemp -d)"
FAILED=0

"$ROOT/gradlew" -p "$ROOT" :examples:authentication-ios-demo:linkDebugExecutableIosSimulatorArm64 -q
mkdir -p "$APP"
cp "$DEMO/bundle/Info.plist" "$APP/"
cp "$DEMO/build/bin/iosSimulatorArm64/debugExecutable/authentication-ios-demo.kexe" "$APP/AuthenticationIosDemo"
codesign -s - --force "$APP" >/dev/null 2>&1
xcrun simctl install "$UDID" "$APP"

enrol() {
    xcrun simctl spawn "$UDID" notifyutil -s com.apple.BiometricKit.enrollmentChanged "$1"
    xcrun simctl spawn "$UDID" notifyutil -p com.apple.BiometricKit.enrollmentChanged
    sleep 1
}

# run <scenario> <face: match|nomatch|none> <expected line regex>
run() {
    local scenario="$1" face="$2" expected="$3" log="$LOG_DIR/$1-$2.log"
    SIMCTL_CHILD_E2E_SCENARIO="$scenario" xcrun simctl launch --console-pty --terminate-running-process \
        "$UDID" "$BUNDLE_ID" > "$log" 2>&1 &
    local pid=$!
    for _ in $(seq 1 40); do grep -q "PROMPT\|DONE" "$log" && break; sleep 0.25; done
    if [ "$face" != none ]; then
        sleep 1.5 # the panel animates in before it accepts a face
        xcrun simctl spawn "$UDID" notifyutil -p "com.apple.BiometricKit_Sim.pearl.$face"
    fi
    for _ in $(seq 1 40); do grep -q DONE "$log" && break; sleep 0.25; done
    kill "$pid" 2>/dev/null || true
    if grep -qE "$expected" "$log"; then
        echo "PASS $scenario/$face: $(grep -E "$expected" "$log" | head -1 | sed 's/^\[E2E [^]]*\] //')"
    else
        echo "FAIL $scenario/$face: expected /$expected/"
        sed 's/^/    /' "$log"
        FAILED=1
    fi
}

enrol 0
run availability none 'availability\(Biometrics\): .*error=NotEnrolled'
enrol 1
run availability none 'availability\(Biometrics\): .*biometry=Face, error=null'
run biometrics match 'RESULT Succeeded'
run deviceowner match 'RESULT Succeeded'
run appcancel none 'RESULT Cancelled'
run blank none 'RESULT Failed\(InvalidPrompt'

exit $FAILED
