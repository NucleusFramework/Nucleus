#!/bin/bash

# Build libnucleus_tray.so – C-based Linux system tray library with JNI bridge.
# Dependencies: libsystemd-dev (for sd-bus), JDK (for jni.h)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${NATIVE_LIBS_OUTPUT_DIR:-$SCRIPT_DIR/../../resources/nucleus/native}"

echo "Building LinuxTray library (C + sd-bus + JNI)..."
echo "Output dir: $OUTPUT_DIR"

# Detect JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    # Try common locations
    for jdk in /usr/lib/jvm/java-*-openjdk-amd64 /usr/lib/jvm/java-*-openjdk /usr/lib/jvm/default-java; do
        if [ -d "$jdk" ] && [ -f "$jdk/include/jni.h" ]; then
            JAVA_HOME="$jdk"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JAVA_HOME not found or jni.h missing. Install a JDK or set JAVA_HOME."
    exit 1
fi
echo "Using JAVA_HOME: $JAVA_HOME"

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"

# Verify sd-bus is available
if ! pkg-config --exists libsystemd 2>/dev/null; then
    echo "ERROR: libsystemd-dev not found. Install it: sudo apt install libsystemd-dev"
    exit 1
fi

SDBUS_CFLAGS=$(pkg-config --cflags libsystemd)
SDBUS_LIBS=$(pkg-config --libs libsystemd)

# Detect host architecture
UNAME_ARCH="$(uname -m)"
case "$UNAME_ARCH" in
    x86_64)  ARCH="x64" ;;
    aarch64) ARCH="aarch64" ;;
    *)       echo "ERROR: Unsupported architecture: $UNAME_ARCH"; exit 1 ;;
esac
PLATFORM_DIR="linux-$ARCH"
echo "Detected platform: $PLATFORM_DIR"

mkdir -p "$OUTPUT_DIR/$PLATFORM_DIR"

# Compile sni.c (includes stb_image implementation)
echo "Compiling sni.c..."
gcc -c -o "$SCRIPT_DIR/sni.o" \
    -fPIC -O2 -Wall -Wextra -Wno-unused-parameter \
    -I "$SCRIPT_DIR" \
    $SDBUS_CFLAGS \
    "$SCRIPT_DIR/sni.c"

# Compile jni_bridge.c
echo "Compiling jni_bridge.c..."
gcc -c -o "$SCRIPT_DIR/jni_bridge.o" \
    -fPIC -O2 -Wall -Wextra -Wno-unused-parameter \
    -I "$SCRIPT_DIR" \
    -I "$SCRIPT_DIR/../shared" \
    -I "$JNI_INCLUDE" \
    -I "$JNI_INCLUDE_LINUX" \
    "$SCRIPT_DIR/jni_bridge.c"

# Link into shared library
echo "Linking libnucleus_tray.so..."
gcc -shared -o "$OUTPUT_DIR/$PLATFORM_DIR/libnucleus_tray.so" \
    "$SCRIPT_DIR/sni.o" \
    "$SCRIPT_DIR/jni_bridge.o" \
    $SDBUS_LIBS \
    -lpthread -lm -ldl

# Strip debug symbols for smaller binary
strip --strip-unneeded "$OUTPUT_DIR/$PLATFORM_DIR/libnucleus_tray.so"

# Clean up object files
rm -f "$SCRIPT_DIR/sni.o" "$SCRIPT_DIR/jni_bridge.o"

# Content-addressed Nucleus cache: evict every copy of this library so a
# same-size rebuild cannot keep serving the stale fingerprint directory.
CACHE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}"
for CACHE_DIR in "$CACHE_BASE/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        find "$CACHE_DIR" -name "libnucleus_tray.so" -delete
        echo "Cleared cached library under: $CACHE_DIR"
    fi
done

echo "Build completed: $OUTPUT_DIR/$PLATFORM_DIR/libnucleus_tray.so"
ls -lh "$OUTPUT_DIR/$PLATFORM_DIR/libnucleus_tray.so"
