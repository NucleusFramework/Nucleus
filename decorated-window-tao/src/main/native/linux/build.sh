#!/bin/bash
# Compiles five Linux shared libraries into per-architecture resource folders:
#   - libnucleus_tao.so              (Rust crate, Tao + JNI)
#   - libnucleus_tao_egl.so          (C, EGL helper for Skia GL backend on
#                                     X11 / Wayland)
#   - libnucleus_tao_linux_widget.so (C, GTK widget reparenting helpers used
#                                     by the GtkWidget variant of NativeView)
#   - libnucleus_tao_linux_popup.so  (C, standalone transparent popup panel —
#                                     raw X11/XWayland window for TrayApp)
#   - libnucleus_tao_linux_clipboard.so
#                                    (C, GTK clipboard so paste follows the
#                                     window's GDK backend instead of AWT/X11)
#
# Outputs are placed in src/main/resources/nucleus/native/{linux-x64,linux-aarch64}/.
#
# Prerequisites:
#   - rustup with the targets x86_64-unknown-linux-gnu and aarch64-unknown-linux-gnu
#   - GTK 3 development headers (libgtk-3-dev) — required by Tao's Linux backend
#   - GCC (or a clang that defaults to GNU libc on the target)
#   - JAVA_HOME set, with `include/jni.h` and `include/linux/`
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
OUT_DIR_X64="$RESOURCE_DIR/linux-x64"
OUT_DIR_ARM64="$RESOURCE_DIR/linux-aarch64"

mkdir -p "$OUT_DIR_X64" "$OUT_DIR_ARM64"

HOST_ARCH="$(uname -m)"

# ── 1) Rust crate (libnucleus_tao.so) ───────────────────────────────────────

if ! command -v cargo >/dev/null 2>&1; then
    echo "ERROR: cargo not found. Install rustup from https://rustup.rs/" >&2
    exit 1
fi

pushd "$NATIVE_DIR" >/dev/null

# Default target = host arch only. Cross-building aarch64 from x86_64 needs a
# proper sysroot (gtk-3.so for the target) which isn't reasonable to assume in
# every dev environment — so we build the host slot here and rely on CI to do
# the cross-arch build.
case "$HOST_ARCH" in
    x86_64)
        rustup target add x86_64-unknown-linux-gnu >/dev/null
        cargo build --release --target x86_64-unknown-linux-gnu
        cp "target/x86_64-unknown-linux-gnu/release/libnucleus_tao.so" \
           "$OUT_DIR_X64/libnucleus_tao.so"
        strip --strip-unneeded "$OUT_DIR_X64/libnucleus_tao.so" || true
        ;;
    aarch64|arm64)
        rustup target add aarch64-unknown-linux-gnu >/dev/null
        cargo build --release --target aarch64-unknown-linux-gnu
        cp "target/aarch64-unknown-linux-gnu/release/libnucleus_tao.so" \
           "$OUT_DIR_ARM64/libnucleus_tao.so"
        strip --strip-unneeded "$OUT_DIR_ARM64/libnucleus_tao.so" || true
        ;;
    *)
        echo "ERROR: unsupported host arch '$HOST_ARCH'" >&2
        exit 1
        ;;
esac

popd >/dev/null

# ── 2) JNI helper toolchain ────────────────────────────────────────────────

if [ -z "${JAVA_HOME:-}" ]; then
    # Try to derive from javac if it's on PATH (matches the pattern used by
    # the JNI module checklist documented in CLAUDE.md).
    if command -v javac >/dev/null 2>&1; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JAVA_HOME unset or missing jni.h. Set JAVA_HOME to a JDK." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_LINUX="$JAVA_HOME/include/linux"
CC="${CC:-cc}"

# ── 3) EGL helper (libnucleus_tao_egl.so) ──────────────────────────────────
# nucleus_tao_texture_linux.c (external GPU texture import for the TextureView
# composable) is linked in here rather than into its own library: it needs the
# EGL entry points and per-window attachment state nucleus_tao_egl.c owns, and
# its JNI symbols resolve through the already-loaded nucleus_tao_egl — the same
# arrangement as nucleus_tao_texture.c inside nucleus_tao_gl.dll on Windows.

build_egl() {
    local OUT_DIR="$1"
    local OUT="$OUT_DIR/libnucleus_tao_egl.so"
    "$CC" -shared -fPIC -O2 -fvisibility=hidden \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
        "$SCRIPT_DIR/nucleus_tao_egl.c" \
        "$SCRIPT_DIR/nucleus_tao_texture_linux.c" -ldl -lm -lpthread \
        -o "$OUT"
    strip --strip-unneeded "$OUT" || true
}

case "$HOST_ARCH" in
    x86_64)        build_egl "$OUT_DIR_X64"   ;;
    aarch64|arm64) build_egl "$OUT_DIR_ARM64" ;;
esac

# ── 4) GTK widget embedding helper (libnucleus_tao_linux_widget.so) ────────

build_widget() {
    local OUT_DIR="$1"
    local OUT="$OUT_DIR/libnucleus_tao_linux_widget.so"
    "$CC" -shared -fPIC -O2 -fvisibility=hidden \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
        "$SCRIPT_DIR/nucleus_tao_linux_widget.c" -ldl \
        -o "$OUT"
    strip --strip-unneeded "$OUT" || true
}

case "$HOST_ARCH" in
    x86_64)        build_widget "$OUT_DIR_X64"   ;;
    aarch64|arm64) build_widget "$OUT_DIR_ARM64" ;;
esac

# ── 5) Standalone popup panel helper (libnucleus_tao_linux_popup.so) ───────
# Compiled against the system X11/XI2 headers (present wherever libgtk-3-dev
# is) but linked with -ldl only: libX11/libXi/libxkbcommon/libEGL are
# dlopen-ed at runtime, so the .so ships standalone like its siblings.

build_popup() {
    local OUT_DIR="$1"
    local OUT="$OUT_DIR/libnucleus_tao_linux_popup.so"
    "$CC" -shared -fPIC -O2 -fvisibility=hidden \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
        "$SCRIPT_DIR/nucleus_tao_linux_popup.c" -ldl -lpthread \
        -o "$OUT"
    strip --strip-unneeded "$OUT" || true
}

case "$HOST_ARCH" in
    x86_64)        build_popup "$OUT_DIR_X64"   ;;
    aarch64|arm64) build_popup "$OUT_DIR_ARM64" ;;
esac

# ── 6) GTK clipboard helper (libnucleus_tao_linux_clipboard.so) ────────────
# Its own library rather than a slot in nucleus_tao_linux_widget: the clipboard
# is needed by every Tao window (Compose's LocalClipboard), the widget helper
# only by apps embedding a foreign GtkWidget.

build_clipboard() {
    local OUT_DIR="$1"
    local OUT="$OUT_DIR/libnucleus_tao_linux_clipboard.so"
    "$CC" -shared -fPIC -O2 -fvisibility=hidden \
        -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_LINUX" \
        "$SCRIPT_DIR/nucleus_tao_linux_clipboard.c" -ldl \
        -o "$OUT"
    strip --strip-unneeded "$OUT" || true
}

case "$HOST_ARCH" in
    x86_64)        build_clipboard "$OUT_DIR_X64"   ;;
    aarch64|arm64) build_clipboard "$OUT_DIR_ARM64" ;;
esac

# ── 7) Clear NativeLibraryLoader cache so fresh .so's are picked up ────────
# Per the Linux module checklist in CLAUDE.md: skipping this serves the stale
# cached copy out of ~/.cache/nucleus/native/<arch>/.

for CACHE_DIR in "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built Linux native libraries:"
case "$HOST_ARCH" in
    x86_64) ls -lh "$OUT_DIR_X64"/{libnucleus_tao.so,libnucleus_tao_egl.so,libnucleus_tao_linux_widget.so,libnucleus_tao_linux_popup.so,libnucleus_tao_linux_clipboard.so} ;;
    aarch64|arm64) ls -lh "$OUT_DIR_ARM64"/{libnucleus_tao.so,libnucleus_tao_egl.so,libnucleus_tao_linux_widget.so,libnucleus_tao_linux_popup.so,libnucleus_tao_linux_clipboard.so} ;;
esac
