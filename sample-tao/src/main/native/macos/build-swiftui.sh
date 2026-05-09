#!/bin/bash
# Compiles `swift_view.swift` into per-architecture dylibs for the
# sample-tao SwiftUI tab. Java loads these via FFM (no JNI shim), so
# the build is just `swiftc -emit-library` — no clang ObjC step.
#
# Outputs in sample-tao/src/main/resources/nucleus/native/darwin-{x64,aarch64}/.
# Prerequisites: Xcode command-line tools (`swiftc`).
# Usage: ./build-swiftui.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

SWIFT_SRC="$SCRIPT_DIR/swift_view.swift"

build_arch() {
    local triple="$1"
    local out="$2"

    swiftc \
        -emit-library \
        -O \
        -target "$triple" \
        -parse-as-library \
        -framework Cocoa \
        -framework SwiftUI \
        "$SWIFT_SRC" \
        -o "$out"

    strip -x "$out"
}

build_arch "arm64-apple-macos12.0"  "$OUT_DIR_ARM64/libsample_tao_swiftui.dylib"
build_arch "x86_64-apple-macos12.0" "$OUT_DIR_X64/libsample_tao_swiftui.dylib"

# Java reads the dylib resource via the classpath at runtime; nothing to
# clear cache-side because FFM extracts the resource to a fresh temp file
# every time `SampleSwiftUIBridge` is initialized. No `NativeLibraryLoader`
# cache involved.

echo "Built sample-tao SwiftUI helper:"
ls -lh "$OUT_DIR_ARM64/libsample_tao_swiftui.dylib" "$OUT_DIR_X64/libsample_tao_swiftui.dylib"
