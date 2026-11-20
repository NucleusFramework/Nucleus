#!/usr/bin/env bash
#
# Converts a source PNG icon into .icns (macOS) and .ico (Windows) formats.
#
# Requirements:
#   - macOS (uses sips + iconutil for .icns)
#   - Python 3 with Pillow (pip3 install Pillow) for .ico
#
# Usage:
#   ./convert-icon.sh [source.png]
#
# If no argument is given, defaults to Icon.png in the same directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$SCRIPT_DIR/Icon.png}"

if [[ ! -f "$SRC" ]]; then
  echo "Error: source file not found: $SRC" >&2
  exit 1
fi

echo "Source: $SRC"

# ── .icns (macOS) ────────────────────────────────────────────────────────────
ICONSET="$(mktemp -d)/Icon.iconset"
mkdir -p "$ICONSET"

for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$SRC" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null 2>&1
  double=$((size * 2))
  sips -z "$double" "$double" "$SRC" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null 2>&1
done

ICNS_OUT="$SCRIPT_DIR/Icon.icns"
iconutil -c icns "$ICONSET" -o "$ICNS_OUT"
rm -rf "$(dirname "$ICONSET")"
echo "Created: $ICNS_OUT ($(du -h "$ICNS_OUT" | cut -f1 | xargs))"

# ── .ico (Windows) ───────────────────────────────────────────────────────────
ICO_OUT="$SCRIPT_DIR/Icon.ico"
python3 -c "
from PIL import Image
img = Image.open('$SRC').convert('RGBA')
# Make the image square by centering on a transparent canvas
w, h = img.size
side = max(w, h)
square = Image.new('RGBA', (side, side), (0, 0, 0, 0))
square.paste(img, ((side - w) // 2, (side - h) // 2), img)
sizes = [(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)]
square.save('$ICO_OUT', format='ICO', sizes=sizes)
"
echo "Created: $ICO_OUT ($(du -h "$ICO_OUT" | cut -f1 | xargs))"

# ── AppX assets (Windows Store tiles) ────────────────────────────────────────
APPX_DIR="$SCRIPT_DIR/appx"
mkdir -p "$APPX_DIR"

python3 -c "
from PIL import Image

src = Image.open('$SRC').convert('RGBA')

# Simple square tiles – just resize
for name, size in [('StoreLogo.png', 50), ('Square44x44Logo.png', 44), ('Square150x150Logo.png', 150)]:
    resized = src.resize((size, size), Image.LANCZOS)
    resized.save('$APPX_DIR/' + name, format='PNG')
    print(f'  {name} ({size}x{size})')

# Wide tile – center the icon on a 310x150 canvas
wide_w, wide_h = 310, 150
icon_size = wide_h  # fit within the height
icon = src.resize((icon_size, icon_size), Image.LANCZOS)
canvas = Image.new('RGBA', (wide_w, wide_h), (0, 0, 0, 0))
offset_x = (wide_w - icon_size) // 2
canvas.paste(icon, (offset_x, 0), icon)
canvas.save('$APPX_DIR/Wide310x150Logo.png', format='PNG')
print(f'  Wide310x150Logo.png ({wide_w}x{wide_h})')
"
echo "Created AppX assets in $APPX_DIR"
