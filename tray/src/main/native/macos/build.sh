#!/bin/bash

# Exit on error
set -e

echo "Building MacTray library (Swift + JNI bridge)..."

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${NATIVE_LIBS_OUTPUT_DIR:-$SCRIPT_DIR/../../resources/nucleus/native}"
echo "Output dir for mac is: $OUTPUT_DIR"

# Detect JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME not found. Install a JDK or set JAVA_HOME."
    exit 1
fi
echo "Using JAVA_HOME: $JAVA_HOME"

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
    echo "ERROR: jni.h not found at $JNI_INCLUDE/jni.h"
    exit 1
fi

mkdir -p "$OUTPUT_DIR/darwin-aarch64"
mkdir -p "$OUTPUT_DIR/darwin-x64"

build_arch() {
    local ARCH=$1
    local TARGET=$2
    local OUT_DIR=$3

    echo "Building for $ARCH..."

    # 1. Compile the JNI bridge (Objective-C) -> bridge.o
    clang -c -o "$SCRIPT_DIR/bridge_${ARCH}.o" \
        -arch "$ARCH" \
        -mmacosx-version-min=11.0 \
        -I "$JNI_INCLUDE" \
        -I "$JNI_INCLUDE_DARWIN" \
        -I "$SCRIPT_DIR" \
        -I "$SCRIPT_DIR/../shared" \
        -fobjc-arc \
        "$SCRIPT_DIR/MacTrayBridge.m"

    # 2. Compile Swift + link with the bridge object -> dylib
    swiftc -emit-library -o "$OUT_DIR/libnucleus_tray.dylib" \
        -module-name MacTray \
        -swift-version 5 \
        -target "$TARGET" \
        -O -whole-module-optimization \
        -framework Foundation \
        -framework Cocoa \
        -framework ApplicationServices \
        -Xlinker -rpath -Xlinker @executable_path/../Frameworks \
        -Xlinker -rpath -Xlinker @loader_path/Frameworks \
        "$SCRIPT_DIR/tray.swift" \
        "$SCRIPT_DIR/bridge_${ARCH}.o"

    # Clean up intermediate object
    rm -f "$SCRIPT_DIR/bridge_${ARCH}.o"
}

build_arch "arm64"  "arm64-apple-macosx11.0"  "$OUTPUT_DIR/darwin-aarch64"
build_arch "x86_64" "x86_64-apple-macosx11.0" "$OUTPUT_DIR/darwin-x64"

# Content-addressed Nucleus cache: evict every copy of this library so a
# same-size rebuild cannot keep serving the stale fingerprint directory.
for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        find "$CACHE_DIR" -name "libnucleus_tray.dylib" -delete
        echo "Cleared cached library under: $CACHE_DIR"
    fi
done

echo "Build completed successfully."
