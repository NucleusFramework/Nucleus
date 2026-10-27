#!/bin/bash
# Builds the current host Linux architecture and writes the result into the
# matching resource directory under src/main/resources/nucleus/native/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESOURCE_DIR="$NATIVE_DIR/../resources/nucleus/native"
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

pushd "$NATIVE_DIR" >/dev/null
case "$HOST_ARCH" in
    x86_64)
        OUT_DIR="$RESOURCE_DIR/linux-x64"
        mkdir -p "$OUT_DIR"
        "$RUSTUP_BIN" target add x86_64-unknown-linux-gnu >/dev/null
        "$CARGO_BIN" build --release --target x86_64-unknown-linux-gnu
        cp "target/x86_64-unknown-linux-gnu/release/libnucleus_location.so" \
           "$OUT_DIR/libnucleus_location.so"
        strip --strip-unneeded "$OUT_DIR/libnucleus_location.so" || true
        ;;
    aarch64|arm64)
        OUT_DIR="$RESOURCE_DIR/linux-aarch64"
        mkdir -p "$OUT_DIR"
        "$RUSTUP_BIN" target add aarch64-unknown-linux-gnu >/dev/null
        "$CARGO_BIN" build --release --target aarch64-unknown-linux-gnu
        cp "target/aarch64-unknown-linux-gnu/release/libnucleus_location.so" \
           "$OUT_DIR/libnucleus_location.so"
        strip --strip-unneeded "$OUT_DIR/libnucleus_location.so" || true
        ;;
    *)
        echo "ERROR: unsupported host arch '$HOST_ARCH'" >&2
        exit 1
        ;;
esac
popd >/dev/null

if [ -d "$HOME/.cache/nucleus/native" ]; then
    rm -rf "$HOME/.cache/nucleus/native"
    echo "Cleared NativeLibraryLoader cache: $HOME/.cache/nucleus/native"
fi

echo "Built host Linux shared library:"
ls -lh "$OUT_DIR/libnucleus_location.so"
