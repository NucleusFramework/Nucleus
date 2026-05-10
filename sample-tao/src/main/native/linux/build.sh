#!/bin/bash
# Compiles the sample-tao Linux WebKit2GTK helper into per-architecture
# .so files used by the texture-based PlatformView path on Linux.
#
# Outputs in sample-tao/src/main/resources/nucleus/native/linux-{x64,aarch64}/.
#
# Prerequisites:
#   - libwebkit2gtk-4.1-dev (or 4.0 fallback) + libgtk-3-dev
#   - JAVA_HOME set, with `include/jni.h` and `include/linux/`
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
OUT_DIR_X64="$RESOURCE_DIR/linux-x64"
OUT_DIR_ARM64="$RESOURCE_DIR/linux-aarch64"

mkdir -p "$OUT_DIR_X64" "$OUT_DIR_ARM64"

if [ -z "${JAVA_HOME:-}" ]; then
    if command -v javac >/dev/null 2>&1; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JAVA_HOME unset or missing jni.h. Set JAVA_HOME to a JDK." >&2
    exit 1
fi

# Pick whichever WebKit2GTK series the host has installed.
WEBKIT_PKG=""
for pkg in webkit2gtk-4.1 webkit2gtk-4.0; do
    if pkg-config --exists "$pkg"; then
        WEBKIT_PKG="$pkg"
        break
    fi
done
if [ -z "$WEBKIT_PKG" ]; then
    echo "ERROR: neither webkit2gtk-4.1 nor webkit2gtk-4.0 found via pkg-config." >&2
    echo "       Install libwebkit2gtk-4.1-dev (or 4.0)." >&2
    exit 1
fi

CC="${CC:-cc}"
JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"
SRC="$SCRIPT_DIR/sample_webview.c"

build_for() {
    local OUT_DIR="$1"
    local OUT="$OUT_DIR/libsample_tao_webview_linux.so"
    "$CC" -shared -fPIC -O2 -fvisibility=hidden \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
        $(pkg-config --cflags "$WEBKIT_PKG" gtk+-3.0) \
        "$SRC" \
        $(pkg-config --libs "$WEBKIT_PKG" gtk+-3.0) -lpthread \
        -o "$OUT"
    strip --strip-unneeded "$OUT" || true
}

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    x86_64)        build_for "$OUT_DIR_X64"   ;;
    aarch64|arm64) build_for "$OUT_DIR_ARM64" ;;
    *) echo "ERROR: unsupported host arch '$HOST_ARCH'" >&2; exit 1 ;;
esac

# Clear NativeLibraryLoader cache so freshly built .so's are picked up
# instead of a stale copy under ~/.cache/nucleus/native/.
for CACHE_DIR in "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built sample-tao Linux WebView native library (using $WEBKIT_PKG)."
case "$HOST_ARCH" in
    x86_64)        ls -lh "$OUT_DIR_X64/libsample_tao_webview_linux.so"   ;;
    aarch64|arm64) ls -lh "$OUT_DIR_ARM64/libsample_tao_webview_linux.so" ;;
esac
