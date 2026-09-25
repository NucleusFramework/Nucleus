#!/bin/bash
# Builds macOS dylibs for both supported resource architectures and writes them
# into src/main/resources/nucleus/native/{darwin-aarch64,darwin-x64}.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
OUT_DIR_ARM64="$RESOURCE_DIR/darwin-aarch64"
OUT_DIR_X64="$RESOURCE_DIR/darwin-x64"
find_rust_tool() {
    local tool="$1"
    local homebrew_rustup="/opt/homebrew/opt/rustup/bin/$tool"
    local cargo_home_tool="$HOME/.cargo/bin/$tool"
    if command -v "$tool" >/dev/null 2>&1; then
        command -v "$tool"
    elif [ -x "$homebrew_rustup" ]; then
        echo "$homebrew_rustup"
    elif [ -x "$cargo_home_tool" ]; then
        echo "$cargo_home_tool"
    else
        return 1
    fi
}

CARGO_BIN="$(find_rust_tool cargo)" || {
    echo "ERROR: cargo not found. Install rustup from https://rustup.rs/" >&2
    exit 1
}
RUSTUP_BIN="$(find_rust_tool rustup)" || {
    echo "ERROR: rustup not found. Install rustup from https://rustup.rs/" >&2
    exit 1
}
RUSTC_BIN="$("$RUSTUP_BIN" which rustc)"
TOOLCHAIN_BIN_DIR="$(dirname "$RUSTC_BIN")"
export PATH="$TOOLCHAIN_BIN_DIR:$(dirname "$RUSTUP_BIN"):$(dirname "$CARGO_BIN"):$PATH"

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
    arm64|aarch64|x86_64)
        ;;
    *)
        echo "ERROR: unsupported host arch '$HOST_ARCH'" >&2
        exit 1
        ;;
esac

pushd "$NATIVE_DIR" >/dev/null
mkdir -p "$OUT_DIR_ARM64" "$OUT_DIR_X64"

"$RUSTUP_BIN" target add aarch64-apple-darwin x86_64-apple-darwin >/dev/null

"$CARGO_BIN" build --release --target aarch64-apple-darwin
cp "target/aarch64-apple-darwin/release/libnucleus_share.dylib" \
   "$OUT_DIR_ARM64/libnucleus_share.dylib"
strip -x "$OUT_DIR_ARM64/libnucleus_share.dylib" || true

"$CARGO_BIN" build --release --target x86_64-apple-darwin
cp "target/x86_64-apple-darwin/release/libnucleus_share.dylib" \
   "$OUT_DIR_X64/libnucleus_share.dylib"
strip -x "$OUT_DIR_X64/libnucleus_share.dylib" || true

popd >/dev/null

for CACHE_DIR in "$HOME/Library/Caches/nucleus/native" "$HOME/.cache/nucleus/native"; do
    if [ -d "$CACHE_DIR" ]; then
        rm -rf "$CACHE_DIR"
        echo "Cleared NativeLibraryLoader cache: $CACHE_DIR"
    fi
done

echo "Built macOS dylibs:"
ls -lh "$OUT_DIR_ARM64/libnucleus_share.dylib"
ls -lh "$OUT_DIR_X64/libnucleus_share.dylib"
