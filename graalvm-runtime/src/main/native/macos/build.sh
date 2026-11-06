#!/bin/bash
# Compiles NucleusLocaleBridge.c into per-architecture dylibs (arm64 + x86_64).
# The outputs are placed in the JAR resources so they ship with the library.
#
# Prerequisites: Xcode command-line tools (clang).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRCS=("$SCRIPT_DIR/NucleusLocaleBridge.c" "$SCRIPT_DIR/NucleusTerminationSignals.c")
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
    -framework CoreFoundation
    -mmacosx-version-min=10.13
    -Oz                     # optimize for smallest code size
    -flto                   # link-time optimization
    -fvisibility=hidden     # hide all symbols except JNIEXPORT ones
    -Wl,-dead_strip         # strip unreachable code
    -Wl,-x                  # strip local symbols at link time
)

# Compile for arm64
clang -arch arm64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_locale.dylib" "${SRCS[@]}"
strip -x "$OUT_DIR_ARM64/libnucleus_locale.dylib"

# Compile for x86_64
clang -arch x86_64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_locale.dylib" "${SRCS[@]}"
strip -x "$OUT_DIR_X64/libnucleus_locale.dylib"

# Clear the NativeLibraryLoader cache so a freshly built dylib is not shadowed
# by a stale cached copy from a previous build.
rm -f "$HOME/Library/Caches/nucleus/native/darwin-aarch64/libnucleus_locale.dylib" \
      "$HOME/Library/Caches/nucleus/native/darwin-aarch64/libnucleus_locale.dylib.fingerprint" \
      "$HOME/Library/Caches/nucleus/native/darwin-x64/libnucleus_locale.dylib" \
      "$HOME/Library/Caches/nucleus/native/darwin-x64/libnucleus_locale.dylib.fingerprint" 2>/dev/null || true

echo "Built per-architecture dylibs:"
ls -lh "$OUT_DIR_ARM64/libnucleus_locale.dylib"
ls -lh "$OUT_DIR_X64/libnucleus_locale.dylib"
