#!/bin/bash
# Builds the widget-ios demo for the iOS simulator without an Xcode project: the Kotlin static
# framework (Gradle), the SwiftUI app and its widget extension (swiftc, with the AppIntents
# metadata Xcode would extract), signs both ad hoc with the App Group, installs and launches.
#
# Usage: ./build-app.sh [simulator name or UDID]   (default: the booted one, else "iPhone 17 Pro")

set -euo pipefail

DEMO="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DEMO/../.." && pwd)"
OUT="$DEMO/build/app"
APP="$OUT/WidgetIosDemo.app"
EXT="$APP/PlugIns/DemoWidgets.appex"
BUNDLE_ID="dev.nucleusframework.widgetiosdemo"
GROUP="group.$BUNDLE_ID"
MIN_OS="17.0"
TARGET="arm64-apple-ios$MIN_OS-simulator"
SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
TOOLCHAIN="$(cd "$(dirname "$(xcrun --find swiftc)")/../.." && pwd)"
FRAMEWORKS="$DEMO/build/bin/iosSimulatorArm64/debugFramework"

(cd "$ROOT" && ./gradlew :examples:widget-ios-demo:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache -q)

rm -rf "$OUT"
mkdir -p "$EXT" "$OUT/work"

# Kotlin/Native static frameworks need the C++ runtime and the frameworks the K/N runtime uses.
LINK_KOTLIN=(-F "$FRAMEWORKS" -framework WidgetIosDemo -Xlinker -lc++ -framework UIKit -framework Foundation)
RENDERER="$ROOT/widget-runtime/src/main/native/swiftui/NucleusWidgetUI.swift"
SWIFT="$ROOT/widget-ios/src/swift"

xcrun --sdk iphonesimulator swiftc -sdk "$SDK" -target "$TARGET" -parse-as-library -Onone -module-name WidgetIosDemoApp \
    "$DEMO/swift/app/DemoApp.swift" "$SWIFT/NucleusWidgetCenter.swift" \
    "${LINK_KOTLIN[@]}" -o "$APP/WidgetIosDemo"

cat > "$OUT/work/protocols.json" <<'JSON'
["AppIntent","EntityQuery","AppEntity","TransientEntity","AppEnum","AppShortcutProviding","AppShortcutsProvider","AnyResolverProviding","AppIntentsPackage","DynamicOptionsProvider","WidgetConfigurationIntent"]
JSON
EXT_SOURCES=("$RENDERER" "$SWIFT/NucleusWidgetKit.swift" "$SWIFT/NucleusWidgetCenter.swift" "$DEMO/swift/extension/DemoWidgets.swift")
# Linked like Xcode links an app extension (NSExtensionMain first), see docs/internals/macos-widgets.md.
xcrun --sdk iphonesimulator swiftc -sdk "$SDK" -target "$TARGET" -parse-as-library -application-extension -Onone -wmo -module-name DemoWidgets \
    "${EXT_SOURCES[@]}" \
    -emit-const-values-path "$OUT/work/ext.swiftconstvalues" \
    -Xfrontend -const-gather-protocols-file -Xfrontend "$OUT/work/protocols.json" \
    -Xlinker -e -Xlinker _NSExtensionMain \
    "${LINK_KOTLIN[@]}" -o "$EXT/DemoWidgets"
printf '%s\n' "${EXT_SOURCES[@]}" > "$OUT/work/sources.txt"
echo "$OUT/work/ext.swiftconstvalues" > "$OUT/work/constvals.txt"
xcrun appintentsmetadataprocessor --output "$EXT" --toolchain-dir "$TOOLCHAIN" --module-name DemoWidgets \
    --sdk-root "$SDK" --xcode-version "$(xcodebuild -version | awk '/Build version/ { print $3 }')" \
    --platform-family iOS --deployment-target "$MIN_OS" --target-triple "$TARGET" \
    --source-file-list "$OUT/work/sources.txt" --swift-const-vals-list "$OUT/work/constvals.txt" \
    --binary-file "$EXT/DemoWidgets" --force >/dev/null
test -f "$EXT/Metadata.appintents/extract.actionsdata"

cat > "$APP/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
<key>CFBundleExecutable</key><string>WidgetIosDemo</string>
<key>CFBundleName</key><string>WidgetIosDemo</string>
<key>CFBundleDisplayName</key><string>Widget demo</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>$(date +%s)</string>
<key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
<key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
<key>MinimumOSVersion</key><string>$MIN_OS</string>
<key>LSRequiresIPhoneOS</key><true/>
<key>UIDeviceFamily</key><array><integer>1</integer></array>
<key>UILaunchScreen</key><dict/>
<key>NucleusWidgetsAppGroup</key><string>$GROUP</string>
</dict></plist>
PLIST
cat > "$EXT/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>$BUNDLE_ID.widgets</string>
<key>CFBundleExecutable</key><string>DemoWidgets</string>
<key>CFBundleName</key><string>DemoWidgets</string>
<key>CFBundleDisplayName</key><string>Widget demo</string>
<key>CFBundlePackageType</key><string>XPC!</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>$(date +%s)</string>
<key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
<key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
<key>MinimumOSVersion</key><string>$MIN_OS</string>
<key>NSExtension</key><dict><key>NSExtensionPointIdentifier</key><string>com.apple.widgetkit-extension</string></dict>
<key>NucleusWidgetsAppGroup</key><string>$GROUP</string>
</dict></plist>
PLIST
cat > "$OUT/work/app.entitlements" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>com.apple.security.application-groups</key><array><string>$GROUP</string></array>
</dict></plist>
PLIST

codesign --force --sign - --entitlements "$OUT/work/app.entitlements" "$EXT"
codesign --force --sign - --entitlements "$OUT/work/app.entitlements" "$APP"

DEVICE="${1:-}"
if [ -z "$DEVICE" ]; then
    DEVICE="$(xcrun simctl list devices booted | awk -F '[()]' '/Booted/ { print $2; exit }')"
fi
if [ -z "$DEVICE" ]; then
    DEVICE="iPhone 17 Pro"
    xcrun simctl boot "$DEVICE"
fi
xcrun simctl install "$DEVICE" "$APP"
echo "Installed $APP on $DEVICE"
xcrun simctl launch "$DEVICE" "$BUNDLE_ID"
