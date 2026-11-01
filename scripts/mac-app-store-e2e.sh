#!/bin/bash
# macOS E2E of mac-app-store against real StoreKit, with no Apple account: StoreKit Testing
# serves mac-app-store/src/test/resources/e2e/Products.storekit inside a development .app.
#
# The .app is a jpackage shell built once under build/e2e: ad-hoc signed with
# com.apple.security.get-task-allow (StoreKit Testing refuses an app "not installed for
# development" without it) and registered with LaunchServices (purchases fail with
# StoreKitError.unknown otherwise). Its launcher reads an absolute classpath pointing at the
# build outputs, so later runs only recompile, never repackage. Needs Xcode (StoreKitTest ships with it).
# Exits with the failure count.
#
# --sheet also runs the real purchase sheet: it cancels the waiting coroutine, then presses the
# sheet's Cancel button through accessibility (scripts/axtool.swift), so the terminal needs the
# Accessibility permission. Leave the Mac alone while it runs.
#
#   scripts/mac-app-store-e2e.sh [--sheet]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/mac-app-store"
OUT="$MODULE/build/e2e"
APP="$OUT/MacAppStoreE2E.app"
BUNDLE_ID="dev.nucleusframework.macappstore.e2e"
CONFIG="$MODULE/src/test/resources/e2e/Products.storekit"
MAIN="dev.nucleusframework.store.mac.e2e.MacAppStoreE2E"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister

"$ROOT/gradlew" -p "$ROOT" :mac-app-store:buildNativeMacOs :mac-app-store:e2eClasspath --console=plain -q || exit 1
CLASSPATH="$(cat "$OUT/classpath.txt")"

if [ ! -d "$APP" ]; then
    echo "Creating the development app shell (once)"
    STUB="$OUT/stub"
    mkdir -p "$STUB/classes"
    printf 'public class Stub { public static void main(String[] a) {} }\n' > "$STUB/Stub.java"
    "$JAVA_HOME/bin/javac" -d "$STUB/classes" "$STUB/Stub.java" || exit 1
    "$JAVA_HOME/bin/jar" --create --file "$STUB/stub.jar" -C "$STUB/classes" . || exit 1
    "$JAVA_HOME/bin/jpackage" --type app-image --name MacAppStoreE2E --dest "$OUT" \
        --input "$STUB" --main-jar stub.jar --main-class Stub \
        --mac-package-identifier "$BUNDLE_ID" || exit 1
    rm -f "$APP/Contents/app/stub.jar"
    cat > "$OUT/dev.entitlements" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>com.apple.security.get-task-allow</key><true/></dict></plist>
EOF
fi

# The launcher reads its classpath here; re-signing after the rewrite takes a second and keeps
# the bundle seal valid.
cat > "$APP/Contents/app/MacAppStoreE2E.cfg" <<EOF
[Application]
app.classpath=$CLASSPATH
app.mainclass=$MAIN

[JavaOptions]
java-options=-Dnucleus.macAppStore.storeKitConfiguration=$CONFIG
EOF
codesign --force --deep -s - --entitlements "$OUT/dev.entitlements" "$APP" 2>/dev/null || exit 1
"$LSREGISTER" -f "$APP"

FAILURES=0
echo "== inside the development app"
"$APP/Contents/MacOS/MacAppStoreE2E" | tee "$OUT/app.log"
FAILURES=$((FAILURES + ${PIPESTATUS[0]}))
grep -q "DONE failures=" "$OUT/app.log" || { echo "FAIL the app did not finish"; FAILURES=$((FAILURES + 1)); }

echo "== bare java"
"$JAVA_HOME/bin/java" -Dnucleus.macAppStore.storeKitConfiguration="$CONFIG" -cp "$CLASSPATH" "$MAIN" expect=notBundled | tee "$OUT/java.log"
FAILURES=$((FAILURES + ${PIPESTATUS[0]}))

if [ "${1:-}" = "--sheet" ]; then
    echo "== real purchase sheet"
    swiftc -O "$ROOT/scripts/axtool.swift" -o "$OUT/axtool" || exit 1
    "$APP/Contents/MacOS/MacAppStoreE2E" sheet > "$OUT/sheet.log" 2>&1 &
    SHEET_PID=$!
    for _ in $(seq 120); do grep -q "STAGE press-cancel" "$OUT/sheet.log" && break; sleep 0.5; done
    "$OUT/axtool" "$SHEET_PID" press AXButton Cancel
    wait $SHEET_PID
    FAILURES=$((FAILURES + $?))
    grep -E "^(PASS|FAIL|DONE)" "$OUT/sheet.log"
    grep -q "DONE failures=" "$OUT/sheet.log" || { echo "FAIL the sheet probe did not finish"; FAILURES=$((FAILURES + 1)); }
fi

echo "mac-app-store E2E failures: $FAILURES"
exit $FAILURES
