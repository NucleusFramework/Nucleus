#!/bin/bash
# Compiles three macOS dylibs into per-architecture resource folders:
#   - libnucleus_tao.dylib       (Rust crate, Tao + JNI)
#   - libnucleus_tao_metal.dylib (Objective-C, CAMetalLayer + Metal frame helper)
#   - libnucleus_tao_dnd.dylib   (Objective-C, NSDraggingDestination/Source bridge)
#
# Outputs are placed in src/main/resources/nucleus/native/{darwin-aarch64,darwin-x64}/.
#
# Prerequisites:
#   - rustup with the targets aarch64-apple-darwin and x86_64-apple-darwin installed
#   - Xcode command-line tools (clang)
# Usage: ./build.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"

mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

# ── 1) Rust crate (libnucleus_tao.dylib) ────────────────────────────────────

if ! command -v cargo >/dev/null 2>&1; then
    echo "ERROR: cargo not found. Install rustup from https://rustup.rs/" >&2
    exit 1
fi

rustup target add aarch64-apple-darwin >/dev/null
rustup target add x86_64-apple-darwin  >/dev/null

pushd "$NATIVE_DIR" >/dev/null

cargo build --release --target aarch64-apple-darwin
cp "target/aarch64-apple-darwin/release/libnucleus_tao.dylib" "$OUT_DIR_ARM64/libnucleus_tao.dylib"
strip -x "$OUT_DIR_ARM64/libnucleus_tao.dylib"

cargo build --release --target x86_64-apple-darwin
cp "target/x86_64-apple-darwin/release/libnucleus_tao.dylib" "$OUT_DIR_X64/libnucleus_tao.dylib"
strip -x "$OUT_DIR_X64/libnucleus_tao.dylib"

popd >/dev/null

# ── 2) Objective-C Metal helper (libnucleus_tao_metal.dylib) ────────────────

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: JAVA_HOME not set and /usr/libexec/java_home failed." >&2
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
JNI_INCLUDE_DARWIN="$JAVA_HOME/include/darwin"
SRC="$SCRIPT_DIR/NucleusTaoMetal.m"

COMMON_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework QuartzCore
    -framework Metal
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_tao_metal.dylib" "$SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_tao_metal.dylib"

clang -arch x86_64 "${COMMON_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_tao_metal.dylib" "$SRC"
strip -x "$OUT_DIR_X64/libnucleus_tao_metal.dylib"

# ── 3) Objective-C DnD helper (libnucleus_tao_dnd.dylib) ────────────────────
# JNI exports for NSDraggingDestination/Source. Shipped as its own dylib so
# the JNI symbols survive the Rust crate's release-mode `strip = "symbols"`.

DND_SRC="$SCRIPT_DIR/dnd.m"
DND_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework AppKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -flto
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${DND_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_tao_dnd.dylib" "$DND_SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_tao_dnd.dylib"

clang -arch x86_64 "${DND_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_tao_dnd.dylib" "$DND_SRC"
strip -x "$OUT_DIR_X64/libnucleus_tao_dnd.dylib"

# ── 4) Objective-C decoration helper (libnucleus_tao_macos_deco.dylib) ──────
# JNI exports for the addChildWindow / NSScreen lookups used by DecoratedDialog.
# Mirrors `windows/nucleus_tao_windows_deco.dll`. Shipped as its own dylib so
# the JNI symbols survive the Rust crate's release-mode `strip = "symbols"`.

DECO_SRC="$SCRIPT_DIR/decoration.m"
DECO_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework AppKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${DECO_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_tao_macos_deco.dylib" "$DECO_SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_tao_macos_deco.dylib"

clang -arch x86_64 "${DECO_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_tao_macos_deco.dylib" "$DECO_SRC"
strip -x "$OUT_DIR_X64/libnucleus_tao_macos_deco.dylib"

# ── 5) Objective-C popup panel helper (libnucleus_tao_macos_popup.dylib) ───
# Phase 1 of the Compose-popup-via-NSPanel architecture. Builds a borderless
# transparent NSPanel attached as a child window of the host NSWindow. Will
# host a CAMetalLayer + ComposeScene in subsequent phases.

POPUP_SRC="$SCRIPT_DIR/popup_panel.m"
POPUP_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework AppKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${POPUP_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_tao_macos_popup.dylib" "$POPUP_SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_tao_macos_popup.dylib"

clang -arch x86_64 "${POPUP_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_tao_macos_popup.dylib" "$POPUP_SRC"
strip -x "$OUT_DIR_X64/libnucleus_tao_macos_popup.dylib"

# ── 6) Objective-C native-view helper (libnucleus_tao_macos_native_view.dylib) ─
# Minimal NSView interop primitives consumed by the `NativeView`
# composable. Same flag profile as the popup helper.

NV_SRC="$SCRIPT_DIR/native_view.m"
NV_FLAGS=(
    -dynamiclib
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE_DARWIN"
    -framework Cocoa
    -framework AppKit
    -mmacosx-version-min=10.15
    -fobjc-arc
    -Oz
    -flto
    -fvisibility=hidden
    -Wl,-dead_strip
    -Wl,-x
)

clang -arch arm64 "${NV_FLAGS[@]}" \
    -o "$OUT_DIR_ARM64/libnucleus_tao_macos_native_view.dylib" "$NV_SRC"
strip -x "$OUT_DIR_ARM64/libnucleus_tao_macos_native_view.dylib"

clang -arch x86_64 "${NV_FLAGS[@]}" \
    -o "$OUT_DIR_X64/libnucleus_tao_macos_native_view.dylib" "$NV_SRC"
strip -x "$OUT_DIR_X64/libnucleus_tao_macos_native_view.dylib"

# ── 7) Clear NativeLibraryLoader cache so fresh dylibs are picked up ───────
# NativeLibraryLoader uses the platform-standard cache directory, which is
# `~/Library/Caches/nucleus/native` on macOS (not `~/.cache/...` à la Linux).

for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built per-architecture dylibs:"
ls -lh "$OUT_DIR_ARM64"/{libnucleus_tao.dylib,libnucleus_tao_metal.dylib,libnucleus_tao_dnd.dylib,libnucleus_tao_macos_deco.dylib,libnucleus_tao_macos_popup.dylib,libnucleus_tao_macos_native_view.dylib}
ls -lh "$OUT_DIR_X64"/{libnucleus_tao.dylib,libnucleus_tao_metal.dylib,libnucleus_tao_dnd.dylib,libnucleus_tao_macos_deco.dylib,libnucleus_tao_macos_popup.dylib,libnucleus_tao_macos_native_view.dylib}
