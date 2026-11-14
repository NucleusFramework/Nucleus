#!/usr/bin/env bash
# Usage: bash run_position_test.sh <JDK home> <output directory>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TEST_JAVA_HOME="$1"
OUT_DIR="$2"
mkdir -p "$OUT_DIR"
GIO_CFLAGS=$(pkg-config --cflags gio-2.0)
GIO_LIBS=$(pkg-config --libs gio-2.0)

# shellcheck disable=SC2086
gcc -Wall -Wextra \
    -I"$TEST_JAVA_HOME/include" -I"$TEST_JAVA_HOME/include/linux" \
    $GIO_CFLAGS \
    "$SCRIPT_DIR/position_test.c" $GIO_LIBS -lpthread \
    -o "$OUT_DIR/position_test"
"$OUT_DIR/position_test"
