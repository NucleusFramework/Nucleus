#!/bin/bash
# Builds libnucleus_avf_video.dylib for the AVFoundation video sample.
#
# Outputs in src/main/resources/nucleus/native/{darwin-aarch64,darwin-x64}/.
# Prerequisites: Xcode command-line tools (clang).
#
# Everything it links against (AVFoundation, CoreMedia, CoreVideo, IOSurface,
# Metal) ships with macOS, so there is nothing else to install. The library
# stays out of CI: it is sample code, and the demo says so when it is missing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and /usr/libexec/java_home failed." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"
SRC="$SCRIPT_DIR/nucleus_avf_video.m"

FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework AVFoundation
    -framework CoreMedia
    -framework CoreVideo
    -framework IOSurface
    -framework Metal
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${FLAGS[@]}" -o "$OUT_DIR_ARM64/libnucleus_avf_video.dylib" "$SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_avf_video.dylib"

clang -arch x86_64 "${FLAGS[@]}" -o "$OUT_DIR_X64/libnucleus_avf_video.dylib" "$SRC"
strip -x "$OUT_DIR_X64/libnucleus_avf_video.dylib"

# Per the module checklist: the loader serves its cached copy otherwise.
for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built AVFoundation video helper:"
ls -lh "$OUT_DIR_ARM64/libnucleus_avf_video.dylib" "$OUT_DIR_X64/libnucleus_avf_video.dylib"