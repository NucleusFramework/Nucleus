#!/bin/bash
# Build and run the issue #441 modified-UTF-8 regression test.
# Header-only: needs jni.h, nothing else.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$SCRIPT_DIR/test_jni_utf8"

if [ -z "${JAVA_HOME:-}" ]; then
    for jdk in /usr/lib/jvm/java-*-openjdk-amd64 /usr/lib/jvm/java-*-openjdk /usr/lib/jvm/default-java /usr/lib/jvm/default; do
        if [ -f "$jdk/include/jni.h" ]; then
            JAVA_HOME="$jdk"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "ERROR: JAVA_HOME not found or jni.h missing. Install a JDK or set JAVA_HOME."
    exit 1
fi

case "$(uname -s)" in
    Darwin) JNI_MD_INCLUDE="$JAVA_HOME/include/darwin" ;;
    *)      JNI_MD_INCLUDE="$JAVA_HOME/include/linux" ;;
esac

echo "Compiling modified-UTF-8 test..."
cc -O2 -g -Wall -Wextra -Werror \
    -I "$SCRIPT_DIR" \
    -I "$JAVA_HOME/include" \
    -I "$JNI_MD_INCLUDE" \
    "$SCRIPT_DIR/test_jni_utf8.c" \
    -o "$BIN"

"$BIN"
status=$?
rm -f "$BIN"
exit $status
