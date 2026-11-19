#!/bin/bash
# Compiles nucleus_tray_macos.m into per-architecture dylibs (arm64 + x86_64).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_tray_macos.m"
OUTPUT_DIR="${NATIVE_LIBS_OUTPUT_DIR:-$SCRIPT_DIR/../../resources/nucleus/native}"
LIB_NAME="libnucleus_tray.dylib"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JNI headers not found (set JAVA_HOME to a JDK)." >&2
    exit 1
fi

COMMON_FLAGS=(
    -dynamiclib
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin"
    -I"$SCRIPT_DIR/../shared"
    -framework Cocoa
    -mmacosx-version-min=11.0
    -fobjc-arc
    -Wall -Wextra -Wno-unused-parameter
    -Oz
    -fvisibility=hidden
    -Wl,-dead_strip
)

for arch in arm64 x86_64; do
    case "$arch" in
        arm64) out="$OUTPUT_DIR/darwin-aarch64" ;;
        x86_64) out="$OUTPUT_DIR/darwin-x64" ;;
    esac
    mkdir -p "$out"
    clang -arch "$arch" "${COMMON_FLAGS[@]}" -o "$out/$LIB_NAME" "$SRC"
    strip -x "$out/$LIB_NAME"
done

# Content-addressed Nucleus cache: evict every copy of this library so a
# same-size rebuild cannot keep serving the stale fingerprint directory.
for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        find "$CACHE_DIR" -name "$LIB_NAME" -delete
    fi
done

echo "Built $LIB_NAME (arm64, x86_64)."
