#!/bin/bash
# Builds libnucleus_gst_video.so for the GStreamer video sample.
#
# Prerequisites (Debian/Ubuntu):
#   sudo apt install libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev
# and, to actually decode something:
#   sudo apt install gstreamer1.0-plugins-{base,good,bad} gstreamer1.0-libav
#
# This library is deliberately outside CI: it is sample code, and CI has no
# GStreamer. The sample tells you to run this script when the library is missing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$SCRIPT_DIR/../../resources/nucleus/native/linux-$( [ "$(uname -m)" = "aarch64" ] && echo aarch64 || echo x64 )"
mkdir -p "$OUT_DIR"

if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JAVA_HOME unset or missing jni.h" >&2
    exit 1
fi

PKGS="gstreamer-1.0 gstreamer-app-1.0 gstreamer-video-1.0 gstreamer-gl-1.0"
if ! pkg-config --exists $PKGS; then
    echo "ERROR: GStreamer development files missing. Install libgstreamer1.0-dev and libgstreamer-plugins-base1.0-dev." >&2
    exit 1
fi

OUT="$OUT_DIR/libnucleus_gst_video.so"
"${CC:-cc}" -shared -fPIC -O2 -Wall -Wextra \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    $(pkg-config --cflags $PKGS) \
    "$SCRIPT_DIR/nucleus_gst_video.c" \
    $(pkg-config --libs $PKGS) -lEGL \
    -o "$OUT"
strip --strip-unneeded "$OUT" || true

# Per the module checklist: the loader serves its cached copy otherwise.
rm -rf "$HOME/.cache/nucleus/native"
echo "Built $OUT"
