#!/bin/bash
# Compiles nucleus_energy_manager.c into per-architecture dylibs (x64 + arm64).
# The outputs are placed in the JAR resources so they ship with the library.
#
# Prerequisites: Xcode Command Line Tools (clang).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_energy_manager.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"

# Detect JAVA_HOME for JNI headers
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and /usr/libexec/java_home failed." >&2
    exit 1
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JNI headers not found at $JAVA_HOME/include" >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

# Create output directories
mkdir -p "$OUT_DIR_X64" "$OUT_DIR_ARM64"

# ---- Compile x64 ----
echo
echo "=== Building x86_64 dylib ==="
clang -shared -O2 -Wall -Wextra -std=c11 \
    -arch x86_64 \
    -mmacosx-version-min=10.10 \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN" \
    -framework IOKit -framework CoreFoundation \
    "$SRC" \
    -o "$OUT_DIR_X64/libnucleus_energy_manager.dylib"
echo "  -> $OUT_DIR_X64/libnucleus_energy_manager.dylib"

# ---- Compile arm64 ----
echo
echo "=== Building arm64 dylib ==="
clang -shared -O2 -Wall -Wextra -std=c11 \
    -arch arm64 \
    -mmacosx-version-min=11.0 \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN" \
    -framework IOKit -framework CoreFoundation \
    "$SRC" \
    -o "$OUT_DIR_ARM64/libnucleus_energy_manager.dylib"
echo "  -> $OUT_DIR_ARM64/libnucleus_energy_manager.dylib"

# Clear NativeLibraryLoader cache to avoid serving stale cached copy
CACHE_DIR="${HOME}/.cache/nucleus/native"
if [ -d "$CACHE_DIR" ]; then
    find "$CACHE_DIR" -name "libnucleus_energy_manager.dylib" -delete 2>/dev/null || true
    echo "Cleared NativeLibraryLoader cache"
fi

echo
echo "Built dylibs:"
[ -f "$OUT_DIR_X64/libnucleus_energy_manager.dylib" ] && echo "  $OUT_DIR_X64/libnucleus_energy_manager.dylib"
[ -f "$OUT_DIR_ARM64/libnucleus_energy_manager.dylib" ] && echo "  $OUT_DIR_ARM64/libnucleus_energy_manager.dylib"
