#!/usr/bin/env bash
#
# Compiles the Network Extension .appex bundle. Signing is handled by Nucleus:
# the appExtensions {} DSL signs the extension with its own entitlements and seals
# the app. This script only produces the (unsigned) .appex.
#
# Usage: build.sh <output-dir>   →  <output-dir>/NetworkFilter.appex
set -euo pipefail

OUT_DIR="${1:?usage: build.sh <output-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"

APPEX="$OUT_DIR/NetworkFilter.appex"
MACOS_DIR="$APPEX/Contents/MacOS"

echo "==> Assembling $APPEX"
rm -rf "$APPEX"
mkdir -p "$MACOS_DIR"
cp "$HERE/Info.plist" "$APPEX/Contents/Info.plist"

# An app extension's executable entry point is NSExtensionMain (from Foundation),
# so there is no main() in our source; we override the entry symbol with -e.
echo "==> Compiling universal (arm64 + x86_64) executable"
clang \
    -arch arm64 -arch x86_64 \
    -mmacosx-version-min=11.0 \
    -fobjc-arc \
    -fvisibility=hidden \
    -framework Foundation \
    -framework NetworkExtension \
    -e _NSExtensionMain \
    -o "$MACOS_DIR/NetworkFilter" \
    "$HERE/FilterDataProvider.m"

echo "==> Done: $APPEX"
