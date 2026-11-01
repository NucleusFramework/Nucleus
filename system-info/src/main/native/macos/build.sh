#!/bin/bash
# Compiles all nucleus_system_info_*.c files into a single shared library.
# Output: libnucleus_system_info.dylib placed in JAR resources.
#
# Prerequisites: Xcode command line tools, JDK with JNI headers.
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"

if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

# Collect all C source files
SOURCES=(
    "$SCRIPT_DIR/nucleus_system_info_os.c"
    "$SCRIPT_DIR/nucleus_system_info_memory.c"
    "$SCRIPT_DIR/nucleus_system_info_cpu.c"
    "$SCRIPT_DIR/nucleus_system_info_disk.c"
    "$SCRIPT_DIR/nucleus_system_info_component.c"
    "$SCRIPT_DIR/nucleus_system_info_network.c"
    "$SCRIPT_DIR/nucleus_system_info_process.c"
    "$SCRIPT_DIR/nucleus_system_info_user.c"
    "$SCRIPT_DIR/nucleus_system_info_hardware.c"
    "$SCRIPT_DIR/nucleus_system_info_gpu.c"
    "$SCRIPT_DIR/nucleus_system_info_battery.c"
    "$SCRIPT_DIR/nucleus_system_info_idle.c"
    "$SCRIPT_DIR/nucleus_system_info_connectivity.c"
)

COMMON_FLAGS=(
    -shared -fPIC
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -O2
    -fvisibility=hidden
    -framework IOKit
    -framework CoreFoundation
    -framework DiskArbitration
    -framework Network
)

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

clang -arch arm64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_system_info.dylib" "${SOURCES[@]}"

clang -arch x86_64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_system_info.dylib" "${SOURCES[@]}"

echo "Built macOS libraries:"
ls -lh "$OUT_DIR_ARM64/libnucleus_system_info.dylib"
ls -lh "$OUT_DIR_X64/libnucleus_system_info.dylib"

# Clear NativeLibraryLoader cache to avoid serving stale cached copy
CACHE_DIR="${HOME}/.cache/nucleus/native"
if [ -d "$CACHE_DIR" ]; then
    find "$CACHE_DIR" -name "libnucleus_system_info.dylib" -delete 2>/dev/null || true
    echo "Cleared NativeLibraryLoader cache"
fi
