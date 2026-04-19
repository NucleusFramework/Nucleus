#!/bin/bash
# Compiles nucleus_clipboard_linux.c into a shared library for the current architecture.
#
# The library uses dlopen at runtime to bind libxcb + libxcb-xfixes, so there
# are no link-time X11/XCB dependencies — the .so loads cleanly on headless
# hosts and falls back to the Wayland delegate automatically.
#
# Prerequisites: gcc, JDK (for jni.h).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_clipboard_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"

# Detect architecture
ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  RESOURCE_ARCH="linux-x64" ;;
    aarch64) RESOURCE_ARCH="linux-aarch64" ;;
    *)       echo "ERROR: Unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

OUT_DIR="$RESOURCE_DIR/$RESOURCE_ARCH"

# Detect JAVA_HOME for JNI headers
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-21-openjdk-arm64 \
                     /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-arm64 \
                     /usr/lib/jvm/java /usr/lib/jvm/default-java; do
        if [ -d "$candidate/include" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and no JDK found in common locations." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"

if [ ! -d "$JNI_INCLUDE" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"

echo "Compiling for $ARCH ($RESOURCE_ARCH)..."

gcc -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
    -O2 \
    -fvisibility=hidden \
    -ffunction-sections \
    -fdata-sections \
    -Wl,--gc-sections \
    -Wl,-s \
    -pthread \
    -ldl \
    -o "$OUT_DIR/libnucleus_clipboard_linux.so" \
    "$SRC"

echo "Built Linux shared library:"
ls -lh "$OUT_DIR/libnucleus_clipboard_linux.so"

# Clear NativeLibraryLoader cache so the new .so is picked up at next run
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}/nucleus/native"
CACHED="$CACHE_BASE/$RESOURCE_ARCH/libnucleus_clipboard_linux.so"
if [ -f "$CACHED" ]; then
    rm -f "$CACHED"
    echo "Cleared cache: $CACHED"
fi
