#!/bin/bash
# Compiles the sample-tao WKWebView helper into per-architecture dylibs.
#
# Outputs in sample-tao/src/main/resources/nucleus/native/darwin-{x64,aarch64}/.
# Prerequisites: Xcode command-line tools (clang).
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
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
SRC="$SCRIPT_DIR/sample_webview.m"

FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework WebKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${FLAGS[@]}" -o "$OUT_DIR_ARM64/libsample_tao_webview.dylib" "$SRC"
strip -x "$OUT_DIR_ARM64/libsample_tao_webview.dylib"

clang -arch x86_64 "${FLAGS[@]}" -o "$OUT_DIR_X64/libsample_tao_webview.dylib" "$SRC"
strip -x "$OUT_DIR_X64/libsample_tao_webview.dylib"

for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built sample-tao WebView helper:"
ls -lh "$OUT_DIR_ARM64/libsample_tao_webview.dylib" "$OUT_DIR_X64/libsample_tao_webview.dylib"
