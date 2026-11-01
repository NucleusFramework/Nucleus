#!/usr/bin/env bash
# Renders the Flathub repository of the Lab (manifest + the files it installs) into <out-dir>.
#
#   render-manifest.sh <out-dir> --release <tag>
#       Sources are the tar.gz of that GitHub release (x86_64 and aarch64): the content of
#       github.com/flathub/dev.nucleusframework.Lab.
#   render-manifest.sh <out-dir> --local <version> <x64.tar.gz> [<arm64.tar.gz>]
#       Sources are local archives, for the local repository loop (see docs/internals/flathub.md).
#       Screenshots are served from $SCREENSHOTS_URL (default http://localhost:8765).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="NucleusFramework/Nucleus"
APP_ID="dev.nucleusframework.Lab"

usage() {
  sed -n '2,10p' "$0" >&2
  exit 1
}

[ $# -ge 3 ] || usage
OUT="$1"
MODE="$2"
shift 2

# archive_entry <flatpak-arch> <url-or-path> <sha256> <url|path>
archive_entry() {
  printf '      - type: archive\n        only-arches: [%s]\n        %s: %s\n        sha256: %s\n        dest: app\n' \
    "$1" "$4" "$2" "$3"
}

ARCHIVES=""
case "$MODE" in
  --release)
    TAG="$1"
    VERSION="${TAG#v}"
    SCREENSHOTS_URL="https://raw.githubusercontent.com/$REPO/$TAG/examples/lab/app/packaging/flathub/screenshots"
    DATE="$(gh release view "$TAG" -R "$REPO" --json publishedAt -q '.publishedAt[0:10]')"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    for pair in x86_64:x64 aarch64:arm64; do
      FLATPAK_ARCH="${pair%%:*}"
      ASSET="nucleus-lab-$VERSION-linux-${pair##*:}.tar.gz"
      gh release download "$TAG" -R "$REPO" -p "$ASSET" -D "$TMP"
      SHA="$(sha256sum "$TMP/$ASSET" | cut -d' ' -f1)"
      ARCHIVES+="$(archive_entry "$FLATPAK_ARCH" "https://github.com/$REPO/releases/download/$TAG/$ASSET" "$SHA" url)"$'\n'
    done
    ;;
  --local)
    VERSION="$1"
    DATE="$(date -u +%F)"
    SCREENSHOTS_URL="${SCREENSHOTS_URL:-http://localhost:8765}"
    shift
    [ $# -ge 1 ] || usage
    ARCHES=(x86_64 aarch64)
    i=0
    # Copied next to the manifest: a sandboxed build (flathub-build) reads nothing outside it.
    mkdir -p "$OUT"
    for TAR in "$@"; do
      cp "$TAR" "$OUT/"
      SHA="$(sha256sum "$TAR" | cut -d' ' -f1)"
      ARCHIVES+="$(archive_entry "${ARCHES[$i]}" "$(basename "$TAR")" "$SHA" path)"$'\n'
      i=$((i + 1))
    done
    ;;
  *) usage ;;
esac

mkdir -p "$OUT"
cp "$HERE/$APP_ID.desktop" "$HERE/$APP_ID.png" "$HERE/$APP_ID.mime.xml" "$OUT/"
sed -e "s/@VERSION@/$VERSION/" -e "s/@DATE@/$DATE/" -e "s|@SCREENSHOTS_URL@|$SCREENSHOTS_URL|" "$HERE/$APP_ID.metainfo.xml" > "$OUT/$APP_ID.metainfo.xml"
# awk, not sed: the archive block spans several lines
awk -v archives="${ARCHIVES%$'\n'}" '$0 == "@ARCHIVES@" { print archives; next } { print }' \
  "$HERE/$APP_ID.yml.in" | grep -v '^# Template of the Flathub manifest' > "$OUT/$APP_ID.yml"
echo "Rendered $OUT/$APP_ID.yml ($VERSION)"
