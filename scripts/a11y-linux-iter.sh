#!/usr/bin/env bash
# Linux a11y iteration helper.
#
# Usage:
#   scripts/a11y-linux-iter.sh           # build native + run sample-tao
#   scripts/a11y-linux-iter.sh build     # only rebuild the Rust crate
#   scripts/a11y-linux-iter.sh run       # only run sample-tao
#   scripts/a11y-linux-iter.sh check     # cargo check (faster than build)
#   scripts/a11y-linux-iter.sh accerciser # launch accerciser inspector alongside the app
#   scripts/a11y-linux-iter.sh orca      # start Orca, then run sample-tao
#
# Logs are filtered with NUCLEUS_A11Y=trace so AccessKit + bridge messages
# surface to the console. The build path mirrors decorated-window-tao/src/main/
# native/linux/build.sh but skips the GLX helper since we're only iterating on
# a11y.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/decorated-window-tao/src/main/native"
RES_DIR="$ROOT_DIR/decorated-window-tao/src/main/resources/nucleus/native"
HOST_ARCH="$(uname -m)"

case "$HOST_ARCH" in
    x86_64) ARCH_DIR="$RES_DIR/linux-x64"; CARGO_TARGET="x86_64-unknown-linux-gnu" ;;
    aarch64|arm64) ARCH_DIR="$RES_DIR/linux-aarch64"; CARGO_TARGET="aarch64-unknown-linux-gnu" ;;
    *) echo "Unsupported arch: $HOST_ARCH"; exit 1 ;;
esac

mkdir -p "$ARCH_DIR"

cmd="${1:-all}"

build_rust() {
    # Always kill any prior sample-tao instance first — they hold the .so
    # mapped, which prevents `cp` from atomically replacing it.
    pkill -f "sample-tao" 2>/dev/null || true
    sleep 1
    echo "[a11y-iter] cargo build --release --target $CARGO_TARGET"
    pushd "$NATIVE_DIR" >/dev/null
    cargo build --release --target "$CARGO_TARGET"
    cp "target/$CARGO_TARGET/release/libnucleus_tao.so" "$ARCH_DIR/libnucleus_tao.so"
    strip --strip-unneeded "$ARCH_DIR/libnucleus_tao.so" 2>/dev/null || true
    popd >/dev/null
    # Critical sync: Gradle's processResources copies src/main/resources →
    # build/resources/main/. If we only update src/, the JAR Gradle bundles
    # for `:sample-tao:run` keeps the stale .so. NativeLibraryLoader extracts
    # from the JAR (loaded via classpath), so the running JVM sees the old
    # binary — we lose every iteration of debug logging until processResources
    # is forced to rerun. Just copy directly to bypass the cache.
    BUILD_RES="$ROOT_DIR/decorated-window-tao/build/resources/main/nucleus/native/${ARCH_DIR##*/}"
    if [ -d "$BUILD_RES" ]; then
        cp "$ARCH_DIR/libnucleus_tao.so" "$BUILD_RES/libnucleus_tao.so"
    fi
    # CRITICAL: NativeLibraryLoader caches extracted .so under ~/.cache/nucleus/
    # so a stale copy hides our fresh build until the cache is dropped.
    rm -rf "$HOME/.cache/nucleus/native/" 2>/dev/null || true
    echo "[a11y-iter] Cleared NativeLibraryLoader cache + synced build/resources"
}

cargo_check() {
    pushd "$NATIVE_DIR" >/dev/null
    cargo check --release --target "$CARGO_TARGET"
    popd >/dev/null
}

teardown() {
    # Kill the app, gradle, and the at-spi daemons that cache our app's
    # registration. Without this, successive runs accumulate stale "java"
    # entries in the AT-SPI registry and pyatspi probes the wrong process.
    pkill -9 -f "MainKt|sample-tao" 2>/dev/null || true
    pkill -9 -f "GradleWrapperMain|GradleDaemon" 2>/dev/null || true
    sleep 2
    killall -9 at-spi2-registryd at-spi-bus-launcher 2>/dev/null || true
    sleep 2
}

run_sample() {
    cd "$ROOT_DIR"
    teardown
    busctl --user set-property org.a11y.Bus /org/a11y/bus \
        org.a11y.Status IsEnabled b true 2>/dev/null || true
    # Always run with a fresh JVM — the gradle daemon caches our .so via JNI
    # and won't reload after a `cargo build`. `--no-daemon` forces a new VM
    # every time, costing ~5s startup but giving us deterministic behavior.
    GDK_BACKEND=x11 \
    NUCLEUS_A11Y=trace \
    RUST_LOG="${RUST_LOG:-info,accesskit_unix=debug,nucleus_tao=debug}" \
        ./gradlew :sample-tao:run --no-daemon --console=plain
    teardown
}

case "$cmd" in
    check) cargo_check ;;
    build) build_rust ;;
    run) run_sample ;;
    accerciser)
        if ! command -v accerciser >/dev/null 2>&1; then
            echo "accerciser is not installed. apt install accerciser"; exit 1
        fi
        accerciser &
        ACC_PID=$!
        trap "kill $ACC_PID 2>/dev/null || true" EXIT
        run_sample
        ;;
    orca)
        if ! command -v orca >/dev/null 2>&1; then
            echo "orca is not installed. apt install orca"; exit 1
        fi
        orca --replace &
        ORCA_PID=$!
        trap "kill $ORCA_PID 2>/dev/null || true" EXIT
        run_sample
        ;;
    all|"")
        build_rust
        run_sample
        ;;
    *)
        echo "Unknown command: $cmd"
        echo "Usage: $0 [check|build|run|accerciser|orca|all]"
        exit 1
        ;;
esac
