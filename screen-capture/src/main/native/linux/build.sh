#!/bin/bash
# Compiles nucleus_screencapture_linux.c into libnucleus_screencapture.so for the host architecture.
# The output is placed in the JAR resources so it ships with the library.
#
# Every runtime library (libX11, libXrandr, libXfixes, libXcomposite, libdbus-1, gdk-pixbuf) is
# dlopen'd, so the .so links against none of them; only the headers are needed.
#
# Prerequisites: gcc, JDK with JNI headers, libx11-dev, libxrandr-dev, libxfixes-dev,
# libxcomposite-dev, libdbus-1-dev.
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_screencapture_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"

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

DBUS_CFLAGS=$(pkg-config --cflags dbus-1)

case "$(uname -m)" in
    x86_64) OUT_DIR="$RESOURCE_DIR/linux-x64" ;;
    aarch64) OUT_DIR="$RESOURCE_DIR/linux-aarch64" ;;
    *)
        echo "WARNING: Unsupported architecture $(uname -m), building for current arch anyway."
        OUT_DIR="$RESOURCE_DIR/linux-$(uname -m)"
        ;;
esac
mkdir -p "$OUT_DIR"

gcc -shared -fPIC \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
    $DBUS_CFLAGS \
    -O2 -Wall -Wextra -Wno-unused-parameter \
    -fvisibility=hidden \
    -Wl,--strip-all \
    -o "$OUT_DIR/libnucleus_screencapture.so" "$SRC" \
    -ldl -lpthread

echo "Built:"
ls -lh "$OUT_DIR/libnucleus_screencapture.so"
