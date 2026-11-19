#!/bin/bash
# Build and run the issue #436 IconPixmap pyramid regression test.
# Header-only: does not need sd-bus or a session bus.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$SCRIPT_DIR/test_sni_pixmap"

echo "Compiling pixmap pyramid test..."
gcc -O2 -g -Wall -Wextra -Werror \
    -I "$SCRIPT_DIR" \
    "$SCRIPT_DIR/test_sni_pixmap.c" \
    -o "$BIN"

"$BIN"
status=$?
rm -f "$BIN"
exit $status
