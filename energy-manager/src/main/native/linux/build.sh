#!/bin/bash
# Compiles nucleus_energy_manager.c into per-architecture .so files.
# The outputs are placed in the JAR resources so they ship with the library.
#
# Prerequisites: gcc, JDK with JNI headers.
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_energy_manager.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_X64="$RESOURCE_DIR/linux-x64"
OUT_DIR_AARCH64="$RESOURCE_DIR/linux-aarch64"

# Detect JAVA_HOME for JNI headers
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and auto-detection failed." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"

if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

COMMON_FLAGS=(
    -shared -fPIC
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX"
    -O2
    -fvisibility=hidden
    -Wl,--strip-all
    -ldl
)

ARCH=$(uname -m)

if [ "$ARCH" = "x86_64" ]; then
    mkdir -p "$OUT_DIR_X64"
    gcc "${COMMON_FLAGS[@]}" \
        -o "$OUT_DIR_X64/libnucleus_energy_manager.so" "$SRC"
    echo "Built:"
    ls -lh "$OUT_DIR_X64/libnucleus_energy_manager.so"
elif [ "$ARCH" = "aarch64" ]; then
    mkdir -p "$OUT_DIR_AARCH64"
    gcc "${COMMON_FLAGS[@]}" \
        -o "$OUT_DIR_AARCH64/libnucleus_energy_manager.so" "$SRC"
    echo "Built:"
    ls -lh "$OUT_DIR_AARCH64/libnucleus_energy_manager.so"
else
    echo "WARNING: Unsupported architecture $ARCH, building for current arch anyway."
    mkdir -p "$RESOURCE_DIR/linux-$ARCH"
    gcc "${COMMON_FLAGS[@]}" \
        -o "$RESOURCE_DIR/linux-$ARCH/libnucleus_energy_manager.so" "$SRC"
fi

# Clear NativeLibraryLoader cache to avoid serving stale cached copy
CACHE_DIR="${HOME}/.cache/nucleus/native"
if [ -d "$CACHE_DIR" ]; then
    find "$CACHE_DIR" -name "libnucleus_energy_manager.so" -delete 2>/dev/null || true
    echo "Cleared NativeLibraryLoader cache"
fi
