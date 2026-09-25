#!/bin/bash
# Compiles NucleusScreenCapture.m into per-architecture dylibs (arm64 + x86_64).
# The outputs are placed in the JAR resources so they ship with the library.
#
# ScreenCaptureKit is weak-linked: it is used from macOS 14 on, and the library still loads
# (on the Core Graphics path) where the framework is missing.
#
# Prerequisites: Xcode command-line tools (clang) with a macOS 14+ SDK.
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/NucleusScreenCapture.m"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

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

COMMON_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Foundation
    -framework CoreFoundation
    -framework CoreGraphics
    -framework CoreVideo
    -weak_framework ScreenCaptureKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Wall
    -Werror=unguarded-availability-new
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

# Compile for arm64
clang -arch arm64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_screencapture.dylib" "$SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_screencapture.dylib"

# Compile for x86_64
clang -arch x86_64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_screencapture.dylib" "$SRC"
strip -x "$OUT_DIR_X64/libnucleus_screencapture.dylib"

echo "Built per-architecture dylibs:"
ls -lh "$OUT_DIR_ARM64/libnucleus_screencapture.dylib"
ls -lh "$OUT_DIR_X64/libnucleus_screencapture.dylib"
