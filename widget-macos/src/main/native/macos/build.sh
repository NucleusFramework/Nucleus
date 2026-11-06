#!/bin/bash
# Builds nucleus.widget-macos's natives, for arm64 and x86_64:
#  - libnucleus_widget_macos.dylib: the JNI bridge (nucleus_widget_macos.m) linked with the
#    Swift WidgetKit bridge (WidgetKitBridge.swift), WidgetCenter being Swift-only;
#  - the generic widget extension (extension/*.swift), with the AppIntents metadata Xcode would
#    generate (`Metadata.appintents`, without which WidgetKit cannot run the action intent). The
#    Gradle plugin assembles it into each app's `.appex`.
#
# Outputs in src/main/resources/nucleus/native/darwin-{aarch64,x64}/ (the extension in its
# `nucleus-widget-extension/` subdirectory, which no native-libraries manifest lists).
# Prerequisites: Xcode (swiftc, and appintentsmetadataprocessor, which the command-line tools lack).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
BUILD_DIR="$SCRIPT_DIR/build"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "$JAVA_HOME/include" ]; then
    echo "ERROR: JNI headers not found (set JAVA_HOME)." >&2
    exit 1
fi

SDK="$(xcrun --sdk macosx --show-sdk-path)"
TOOLCHAIN="$(cd "$(dirname "$(xcrun --find swiftc)")/../.." && pwd)"
METADATA_PROCESSOR="$(xcrun --find appintentsmetadataprocessor 2>/dev/null || true)"
if [ -z "$METADATA_PROCESSOR" ]; then
    echo "ERROR: appintentsmetadataprocessor not found: select a full Xcode with xcode-select." >&2
    exit 1
fi
XCODE_BUILD="$(xcodebuild -version | awk '/Build version/ { print $3 }')"

LIB_NAME="libnucleus_widget_macos.dylib"
EXTENSION_NAME="NucleusWidgets"
EXTENSION_SOURCES=("$SCRIPT_DIR/extension/NucleusWidgets.swift" "$SCRIPT_DIR/extension/NucleusWidgetBundles.swift")
# Interactive widgets (Button / Toggle with an AppIntent) and containerBackground: macOS 14.
EXTENSION_MIN_OS="14.0"
# Swift Concurrency (the async WidgetCenter calls) ships in the OS from macOS 12.
LIB_MIN_OS="12.0"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# Protocols whose conformances the metadata processor reads, as Xcode passes them.
cat > "$BUILD_DIR/const-protocols.json" <<'JSON'
["AppIntent","EntityQuery","AppEntity","TransientEntity","AppEnum","AppShortcutProviding","AppShortcutsProvider","AnyResolverProviding","AppIntentsPackage","DynamicOptionsProvider","WidgetConfigurationIntent"]
JSON
printf '%s\n' "${EXTENSION_SOURCES[@]}" > "$BUILD_DIR/sources.txt"

build_arch() {
    local arch="$1" out_dir="$2"
    local work="$BUILD_DIR/$arch"
    mkdir -p "$work" "$out_dir"

    clang -c -arch "$arch" -mmacosx-version-min="$LIB_MIN_OS" -fobjc-arc -Oz -fvisibility=hidden \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
        "$SCRIPT_DIR/nucleus_widget_macos.m" -o "$work/jni.o"
    swiftc -emit-library -swift-version 5 -O -parse-as-library -module-name NucleusWidgetKitBridge \
        -target "$arch-apple-macos$LIB_MIN_OS" \
        "$SCRIPT_DIR/WidgetKitBridge.swift" "$work/jni.o" \
        -framework Foundation -framework WidgetKit \
        -Xlinker -dead_strip -o "$out_dir/$LIB_NAME"
    strip -x "$out_dir/$LIB_NAME"

    local extension_dir="$out_dir/nucleus-widget-extension"
    rm -rf "$extension_dir"
    mkdir -p "$extension_dir"
    # Linked like Xcode links an app extension: Foundation's NSExtensionMain sets up the
    # ExtensionKit context, then calls main. Entered at main directly, ExtensionFoundation traps
    # ("Failed to create running extension") as soon as WidgetKit starts.
    swiftc -swift-version 5 -O -wmo -parse-as-library -application-extension -module-name "$EXTENSION_NAME" \
        -target "$arch-apple-macos$EXTENSION_MIN_OS" \
        "${EXTENSION_SOURCES[@]}" \
        -Xlinker -e -Xlinker _NSExtensionMain \
        -emit-const-values-path "$work/extension.swiftconstvalues" \
        -Xfrontend -const-gather-protocols-file -Xfrontend "$BUILD_DIR/const-protocols.json" \
        -o "$extension_dir/$EXTENSION_NAME"
    strip -x "$extension_dir/$EXTENSION_NAME"
    echo "$work/extension.swiftconstvalues" > "$work/const-values.txt"
    "$METADATA_PROCESSOR" --output "$extension_dir" --toolchain-dir "$TOOLCHAIN" \
        --module-name "$EXTENSION_NAME" --sdk-root "$SDK" --xcode-version "$XCODE_BUILD" \
        --platform-family macOS --deployment-target "$EXTENSION_MIN_OS" \
        --target-triple "$arch-apple-macos$EXTENSION_MIN_OS" \
        --source-file-list "$BUILD_DIR/sources.txt" --swift-const-vals-list "$work/const-values.txt" \
        --binary-file "$extension_dir/$EXTENSION_NAME" --force
    if [ ! -f "$extension_dir/Metadata.appintents/extract.actionsdata" ]; then
        echo "ERROR: no AppIntents metadata was produced for $arch." >&2
        exit 1
    fi
}

build_arch arm64 "$RESOURCE_DIR/darwin-aarch64"
build_arch x86_64 "$RESOURCE_DIR/darwin-x64"
rm -rf "$BUILD_DIR"

echo "Built:"
ls -lh "$RESOURCE_DIR"/darwin-*/"$LIB_NAME" "$RESOURCE_DIR"/darwin-*/nucleus-widget-extension/"$EXTENSION_NAME"
