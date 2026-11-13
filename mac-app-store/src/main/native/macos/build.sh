#!/bin/bash
# Compiles the StoreKit 2 bridge (*.swift), its JNI shim (NucleusMacStoreJni.m) and the
# StoreKit Testing helpers (NucleusStoreKitTest.m)
# into per-architecture dylibs (arm64 + x86_64). StoreKit 2 is Swift-only, hence the Swift
# half; the shim keeps JNI in C like every other Nucleus bridge.
# Requires macOS 12.0+ at run time (StoreKit 2).
#
# Prerequisites: Xcode command-line tools (swiftc, clang).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"
LIB_NAME="libnucleus_macstore.dylib"

# Detect JAVA_HOME for JNI headers
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and /usr/libexec/java_home failed." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

if [ ! -d "$JNI_INCLUDE" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

# Intermediates stay out of the source tree, which the Gradle task tracks as its inputs.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

build_arch() {
    local arch="$1"
    local out="$2"
    local triple="$arch-apple-macos12.0"

    swiftc \
        -parse-as-library \
        -emit-object \
        -whole-module-optimization \
        -O \
        -swift-version 5 \
        -module-name NucleusMacStore \
        -target "$triple" \
        -import-objc-header "$SCRIPT_DIR/nucleus_macstore.h" \
        "$SCRIPT_DIR"/*.swift \
        -o "$WORK_DIR/MacStore-$arch.o"

    clang -c \
        -arch "$arch" \
        -mmacosx-version-min=12.0 \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN" \
        -fobjc-arc \
        -Oz \
        -fvisibility=hidden \
        "$SCRIPT_DIR/NucleusMacStoreJni.m" \
        -o "$WORK_DIR/NucleusMacStoreJni-$arch.o"

    clang -c \
        -arch "$arch" \
        -mmacosx-version-min=12.0 \
        -fobjc-arc \
        -Oz \
        -fvisibility=hidden \
        "$SCRIPT_DIR/NucleusStoreKitTest.m" \
        -o "$WORK_DIR/NucleusStoreKitTest-$arch.o"

    swiftc \
        -emit-library \
        -target "$triple" \
        "$WORK_DIR/MacStore-$arch.o" \
        "$WORK_DIR/NucleusMacStoreJni-$arch.o" \
        "$WORK_DIR/NucleusStoreKitTest-$arch.o" \
        -framework AppKit \
        -framework Foundation \
        -framework StoreKit \
        -framework Security \
        -Xlinker -dead_strip \
        -Xlinker -install_name -Xlinker "@rpath/$LIB_NAME" \
        -o "$out"

    strip -x "$out"
}

build_arch arm64 "$OUT_DIR_ARM64/$LIB_NAME"
build_arch x86_64 "$OUT_DIR_X64/$LIB_NAME"

# Purge NativeLibraryLoader cache so the JVM picks up the fresh dylib
CACHE_BASE="$HOME/Library/Caches/nucleus/native"
rm -f "$CACHE_BASE/darwin-aarch64/$LIB_NAME" "$CACHE_BASE/darwin-x64/$LIB_NAME" 2>/dev/null || true
echo "Cleared NativeLibraryLoader cache"

echo "Built per-architecture dylibs:"
ls -lh "$OUT_DIR_ARM64/$LIB_NAME"
ls -lh "$OUT_DIR_X64/$LIB_NAME"
