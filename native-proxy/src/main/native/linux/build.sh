#!/bin/bash
# Compiles nucleus_proxy_linux.c into per-architecture shared libraries.
# libgio is loaded at runtime via dlopen — only libdl/libpthread are linked.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/nucleus_proxy_linux.c"
RESOURCE_DIR="$SCRIPT_DIR/../../resources/nucleus/native"
LIB_NAME="libnucleus_proxy.so"

if [ -z "${JAVA_HOME:-}" ]; then
    for jdk in /usr/lib/jvm/java-*-openjdk-* /usr/lib/jvm/default-java; do
        if [ -d "$jdk/include" ]; then
            JAVA_HOME="$jdk"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and could not auto-detect a JDK." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"
if [ ! -d "$JNI_INCLUDE" ]; then
    echo "ERROR: JNI headers not found at $JNI_INCLUDE" >&2
    exit 1
fi

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    x86_64)  HOST_SUBDIR="linux-x64" ;;
    aarch64) HOST_SUBDIR="linux-aarch64" ;;
    *) echo "ERROR: Unsupported architecture: $HOST_ARCH" >&2; exit 1 ;;
esac

COMMON_FLAGS=(
    -shared -fPIC
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX"
    -ldl -lpthread
    -O2 -fvisibility=hidden -s
    -Wall -Wextra -Wno-unused-parameter
)

OUT_DIR="$RESOURCE_DIR/$HOST_SUBDIR"
mkdir -p "$OUT_DIR"
gcc "${COMMON_FLAGS[@]}" -o "$OUT_DIR/$LIB_NAME" "$SRC"
echo "Built $HOST_SUBDIR:"
ls -lh "$OUT_DIR/$LIB_NAME"

CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/nucleus/native/$HOST_SUBDIR"
if [ -f "$CACHE_DIR/$LIB_NAME" ]; then
    rm -f "$CACHE_DIR/$LIB_NAME"
    echo "Cleared cached $CACHE_DIR/$LIB_NAME"
fi

if [ "$HOST_ARCH" = "x86_64" ] && command -v aarch64-linux-gnu-gcc &>/dev/null; then
    CROSS_DIR="$RESOURCE_DIR/linux-aarch64"
    mkdir -p "$CROSS_DIR"
    aarch64-linux-gnu-gcc "${COMMON_FLAGS[@]}" -o "$CROSS_DIR/$LIB_NAME" "$SRC" \
        && echo "Built linux-aarch64 (cross):" && ls -lh "$CROSS_DIR/$LIB_NAME" \
        || echo "WARNING: aarch64 cross-compilation failed (non-fatal)."
fi
