#!/bin/bash
# Compiles NucleusSpellcheckBridge.m into per-architecture dylibs (arm64 + x86_64).
# The outputs are placed in the JAR resources so they ship with the library.
#
# Prerequisites: Xcode command-line tools (clang).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/NucleusSpellcheckBridge.m"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"
LIB_NAME="libnucleus_spellcheck.dylib"

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
    -framework Cocoa
    -mmacosx-version-min=10.13
    -fobjc-arc
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/$LIB_NAME" "$SRC"
strip -x "$OUT_DIR_ARM64/$LIB_NAME"

clang -arch x86_64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_X64/$LIB_NAME" "$SRC"
strip -x "$OUT_DIR_X64/$LIB_NAME"

echo "Built per-architecture dylibs:"
ls -lh "$OUT_DIR_ARM64/$LIB_NAME"
ls -lh "$OUT_DIR_X64/$LIB_NAME"
