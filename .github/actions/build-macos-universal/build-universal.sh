#!/usr/bin/env bash
# Build macOS universal binary from arm64 + x64 .app bundles
# Called by action.yml with all inputs passed as environment variables
set -euo pipefail

# ── Required env vars ─────────────────────────────────────────────────────
: "${ARM64_ZIP:?}" "${X64_ZIP:?}" "${OUTPUT_DIR:?}"

# ── Optional env vars (default to empty) ──────────────────────────────────
SIGNING_IDENTITY="${SIGNING_IDENTITY:-}"
APP_STORE_IDENTITY="${APP_STORE_IDENTITY:-}"
INSTALLER_IDENTITY="${INSTALLER_IDENTITY:-}"
KEYCHAIN_PATH="${KEYCHAIN_PATH:-}"
ENTITLEMENTS_FILE="${ENTITLEMENTS_FILE:-}"
RUNTIME_ENTITLEMENTS_FILE="${RUNTIME_ENTITLEMENTS_FILE:-}"
SANDBOXED_ENTITLEMENTS_FILE="${SANDBOXED_ENTITLEMENTS_FILE:-}"
SANDBOXED_RUNTIME_ENTITLEMENTS_FILE="${SANDBOXED_RUNTIME_ENTITLEMENTS_FILE:-}"
PROVISIONING_PROFILE="${PROVISIONING_PROFILE:-}"
RUNTIME_PROVISIONING_PROFILE="${RUNTIME_PROVISIONING_PROFILE:-}"
SANDBOXED_ARM64_ZIP="${SANDBOXED_ARM64_ZIP:-}"
SANDBOXED_X64_ZIP="${SANDBOXED_X64_ZIP:-}"
ARM64_PATH="${ARM64_PATH:-}"
X64_PATH="${X64_PATH:-}"

mkdir -p "$OUTPUT_DIR"

# ── Derive universal artifact prefix from arm64 ZIP filename ──────────────
ARM64_BASENAME="$(basename "$ARM64_ZIP" .zip)"
UNIVERSAL_PREFIX="$(echo "$ARM64_BASENAME" | sed -E 's/-(arm64|aarch64)$//')-universal"
echo "==> Artifact prefix: $UNIVERSAL_PREFIX"

# ── Temp dirs ─────────────────────────────────────────────────────────────
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ARM64_DIR="$WORK/arm64"
X64_DIR="$WORK/x64"
UNIVERSAL_DIR="$WORK/universal"
mkdir -p "$ARM64_DIR" "$X64_DIR" "$UNIVERSAL_DIR"

# ── Extract ZIPs (ditto preserves macOS attributes) ───────────────────────
echo "==> Extracting arm64 ZIP: $ARM64_ZIP"
ditto -x -k "$ARM64_ZIP" "$ARM64_DIR"

echo "==> Extracting x64 ZIP: $X64_ZIP"
ditto -x -k "$X64_ZIP" "$X64_DIR"

# ── Locate .app bundles ───────────────────────────────────────────────────
ARM64_APP="$(find "$ARM64_DIR" -maxdepth 1 -name '*.app' -type d | head -1)"
X64_APP="$(find "$X64_DIR" -maxdepth 1 -name '*.app' -type d | head -1)"

if [[ -z "$ARM64_APP" || -z "$X64_APP" ]]; then
  echo "::error::Could not find .app bundles in extracted ZIPs"
  exit 1
fi

APP_NAME="$(basename "$ARM64_APP")"
UNIVERSAL_APP="$UNIVERSAL_DIR/$APP_NAME"
echo "==> App bundle: $APP_NAME"

# ── Copy arm64 .app as base (preserves symlinks/hardlinks) ────────────────
echo "==> Copying arm64 .app as universal base"
cp -a "$ARM64_APP" "$UNIVERSAL_APP"

# ── Helper: check if a file is Mach-O ────────────────────────────────────
is_macho() {
  file -b "$1" 2>/dev/null | grep -q 'Mach-O'
}

# ── Helper: check if already a universal (fat) binary ─────────────────────
is_fat() {
  file -b "$1" 2>/dev/null | grep -q 'universal'
}

# ── Helper: merge two .app bundles via lipo ───────────────────────────────
merge_app_bundles() {
  local base_app="$1"
  local other_app="$2"
  local label="$3"
  local merged=0 skipped=0 base_only=0 other_only=0

  echo "==> [$label] Scanning for Mach-O binaries to merge..."

  while IFS= read -r -d '' base_file; do
    local rel="${base_file#"$base_app/"}"
    local other_file="$other_app/$rel"

    is_macho "$base_file" || continue

    if is_fat "$base_file"; then
      echo "  [SKIP] Already universal: $rel"
      ((skipped++)) || true
      continue
    fi

    if [[ ! -f "$other_file" ]]; then
      echo "  [WARN] arm64-only (no x64 counterpart): $rel"
      ((base_only++)) || true
      continue
    fi

    if ! is_macho "$other_file"; then
      echo "  [WARN] x64 counterpart is not Mach-O, keeping arm64: $rel"
      ((base_only++)) || true
      continue
    fi

    local tmp_merged="$WORK/lipo_tmp_${label}"
    if ! lipo -create "$base_file" "$other_file" -output "$tmp_merged"; then
      echo "::error::lipo -create failed for: $rel"
      exit 1
    fi
    mv "$tmp_merged" "$base_file"
    ((merged++)) || true
  done < <(find "$base_app" -type f -print0)

  while IFS= read -r -d '' other_file; do
    local rel="${other_file#"$other_app/"}"
    local base_file="$base_app/$rel"

    [[ -f "$base_file" ]] && continue
    is_macho "$other_file" || continue

    echo "  [WARN] x64-only binary, copying: $rel"
    mkdir -p "$(dirname "$base_file")"
    cp -a "$other_file" "$base_file"
    ((other_only++)) || true
  done < <(find "$other_app" -type f -print0)

  echo "==> [$label] Merge summary: merged=$merged, skipped=$skipped, arm64-only=$base_only, x64-only=$other_only"
}

# ── Merge non-sandboxed .app ──────────────────────────────────────────────
merge_app_bundles "$UNIVERSAL_APP" "$X64_APP" "non-sandboxed"

# ── Verify merged binaries ────────────────────────────────────────────────
echo "==> Verifying merged binaries with lipo -info..."
while IFS= read -r -d '' f; do
  if is_macho "$f"; then
    info="$(lipo -info "$f" 2>&1)" || true
    echo "  $info"
  fi
done < <(find "$UNIVERSAL_APP" -type f -print0)

# ── Merge sandboxed .app (if available) ───────────────────────────────────
UNIVERSAL_SANDBOXED_APP=""
if [[ -n "$SANDBOXED_ARM64_ZIP" && -n "$SANDBOXED_X64_ZIP" ]]; then
  echo ""
  echo "==> Merging sandboxed .app bundles..."
  SANDBOX_ARM64_DIR="$WORK/sandboxed-arm64"
  SANDBOX_X64_DIR="$WORK/sandboxed-x64"
  SANDBOX_UNIVERSAL_DIR="$WORK/sandboxed-universal"
  mkdir -p "$SANDBOX_ARM64_DIR" "$SANDBOX_X64_DIR" "$SANDBOX_UNIVERSAL_DIR"

  ditto -x -k "$SANDBOXED_ARM64_ZIP" "$SANDBOX_ARM64_DIR"
  ditto -x -k "$SANDBOXED_X64_ZIP" "$SANDBOX_X64_DIR"

  SANDBOX_ARM64_APP="$(find "$SANDBOX_ARM64_DIR" -maxdepth 1 -name '*.app' -type d | head -1)"
  SANDBOX_X64_APP="$(find "$SANDBOX_X64_DIR" -maxdepth 1 -name '*.app' -type d | head -1)"

  if [[ -n "$SANDBOX_ARM64_APP" && -n "$SANDBOX_X64_APP" ]]; then
    SANDBOX_APP_NAME="$(basename "$SANDBOX_ARM64_APP")"
    UNIVERSAL_SANDBOXED_APP="$SANDBOX_UNIVERSAL_DIR/$SANDBOX_APP_NAME"
    cp -a "$SANDBOX_ARM64_APP" "$UNIVERSAL_SANDBOXED_APP"
    merge_app_bundles "$UNIVERSAL_SANDBOXED_APP" "$SANDBOX_X64_APP" "sandboxed"
  else
    echo "::warning::Could not find .app in sandboxed ZIPs, skipping sandboxed merge"
  fi
fi

# ── Helper: granular codesign (inside-out) ────────────────────────────────
sign_app_bundle() {
  local app_path="$1"
  local identity="$2"
  local ent_file="$3"
  local runtime_ent_file="$4"
  local kc_path="$5"
  local label="$6"

  echo ""
  echo "==> [$label] Signing .app bundle: $(basename "$app_path")"
  echo "    Identity: $identity"

  xattr -cr "$app_path"

  local kc_args=()
  [[ -n "$kc_path" ]] && kc_args=(--keychain "$kc_path")

  local ent_args=()
  if [[ -n "$ent_file" && -f "$ent_file" ]]; then
    ent_args=(--entitlements "$ent_file")
    echo "    App entitlements: $ent_file"
  fi

  local runtime_ent_args=()
  if [[ -n "$runtime_ent_file" && -f "$runtime_ent_file" ]]; then
    runtime_ent_args=(--entitlements "$runtime_ent_file")
    echo "    Runtime entitlements: $runtime_ent_file"
  fi

  echo "  [1/7] Signing .dylib files..."
  find "$app_path" -name '*.dylib' -type f | while IFS= read -r lib; do
    codesign --force --sign "$identity" --options runtime --timestamp \
      ${runtime_ent_args[@]+"${runtime_ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$lib"
  done

  echo "  [2/7] Signing .jnilib files..."
  find "$app_path" -name '*.jnilib' -type f | while IFS= read -r lib; do
    codesign --force --sign "$identity" --options runtime --timestamp \
      ${runtime_ent_args[@]+"${runtime_ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$lib"
  done

  echo "  [3/7] Signing main executables..."
  if [[ -d "$app_path/Contents/MacOS" ]]; then
    find "$app_path/Contents/MacOS" -type f -perm +111 | while IFS= read -r exe; do
      codesign --force --sign "$identity" --options runtime --timestamp \
        ${ent_args[@]+"${ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$exe"
    done
  fi

  echo "  [4/7] Signing runtime executables..."
  if [[ -d "$app_path/Contents/runtime/Contents/Home/bin" ]]; then
    find "$app_path/Contents/runtime/Contents/Home/bin" -type f -perm +111 | while IFS= read -r exe; do
      codesign --force --sign "$identity" --options runtime --timestamp \
        ${runtime_ent_args[@]+"${runtime_ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$exe"
    done
  fi

  echo "  [5/7] Signing frameworks..."
  find "$app_path" -name '*.framework' -type d | while IFS= read -r fw; do
    codesign --force --sign "$identity" --options runtime --timestamp \
      ${kc_args[@]+"${kc_args[@]}"} "$fw"
  done

  echo "  [6/7] Signing runtime bundle..."
  if [[ -d "$app_path/Contents/runtime" ]]; then
    codesign --force --sign "$identity" --options runtime --timestamp \
      ${runtime_ent_args[@]+"${runtime_ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$app_path/Contents/runtime"
  fi

  echo "  [7/7] Signing .app bundle..."
  codesign --force --sign "$identity" --options runtime --timestamp \
    ${ent_args[@]+"${ent_args[@]}"} ${kc_args[@]+"${kc_args[@]}"} "$app_path"

  echo "  Verifying signature..."
  codesign --verify --deep --strict --verbose=2 "$app_path"
  echo "==> [$label] Signing complete"
}

# ── Sign non-sandboxed .app (Developer ID or ad-hoc) ─────────────────────
if [[ -n "$SIGNING_IDENTITY" ]]; then
  sign_app_bundle "$UNIVERSAL_APP" "$SIGNING_IDENTITY" \
    "$ENTITLEMENTS_FILE" "$RUNTIME_ENTITLEMENTS_FILE" \
    "$KEYCHAIN_PATH" "Developer ID"
else
  echo "==> Ad-hoc signing universal .app bundle"
  codesign --force --deep --sign - "$UNIVERSAL_APP"
fi

# ── Sign sandboxed .app (App Store or ad-hoc) ─────────────────────────────
if [[ -n "$UNIVERSAL_SANDBOXED_APP" ]]; then
  if [[ -n "$APP_STORE_IDENTITY" ]]; then
    if [[ -n "$PROVISIONING_PROFILE" && -f "$PROVISIONING_PROFILE" ]]; then
      echo "==> Installing provisioning profile into sandboxed .app"
      cp "$PROVISIONING_PROFILE" "$UNIVERSAL_SANDBOXED_APP/Contents/embedded.provisionprofile"
    fi
    if [[ -n "$RUNTIME_PROVISIONING_PROFILE" && -f "$RUNTIME_PROVISIONING_PROFILE" ]]; then
      echo "==> Installing runtime provisioning profile"
      cp "$RUNTIME_PROVISIONING_PROFILE" "$UNIVERSAL_SANDBOXED_APP/Contents/runtime/Contents/embedded.provisionprofile"
    fi

    # Augment entitlements with application-identifier for TestFlight / App Store (error 90886)
    SANDBOX_ENT="${SANDBOXED_ENTITLEMENTS_FILE:-$ENTITLEMENTS_FILE}"
    SB_BUNDLE_ID="$(defaults read "$UNIVERSAL_SANDBOXED_APP/Contents/Info" CFBundleIdentifier 2>/dev/null || echo "")"
    if [[ -n "$SANDBOX_ENT" && -f "$SANDBOX_ENT" && -n "$SB_BUNDLE_ID" ]]; then
      SB_TEAM_ID="$(echo "$APP_STORE_IDENTITY" | grep -oE '\([A-Z0-9]+\)$' | tr -d '()')"
      if [[ -n "$SB_TEAM_ID" ]] && ! grep -q 'com.apple.application-identifier' "$SANDBOX_ENT"; then
        SB_APP_IDENTIFIER="$SB_TEAM_ID.$SB_BUNDLE_ID"
        AUGMENTED_SANDBOX_ENT="$WORK/entitlements-appstore-sandbox.plist"
        sed "s|</dict>|    <key>com.apple.application-identifier</key>\\
    <string>$SB_APP_IDENTIFIER</string>\\
    <key>com.apple.developer.team-identifier</key>\\
    <string>$SB_TEAM_ID</string>\\
</dict>|" "$SANDBOX_ENT" > "$AUGMENTED_SANDBOX_ENT"
        echo "==> Augmented sandboxed entitlements with application-identifier: $SB_APP_IDENTIFIER"
        SANDBOX_ENT="$AUGMENTED_SANDBOX_ENT"
      fi
    fi

    sign_app_bundle "$UNIVERSAL_SANDBOXED_APP" "$APP_STORE_IDENTITY" \
      "$SANDBOX_ENT" "${SANDBOXED_RUNTIME_ENTITLEMENTS_FILE:-$RUNTIME_ENTITLEMENTS_FILE}" \
      "$KEYCHAIN_PATH" "App Store"
  else
    echo "==> Ad-hoc signing sandboxed universal .app bundle"
    codesign --force --deep --sign - "$UNIVERSAL_SANDBOXED_APP"
  fi
fi

# ── Create ZIP ────────────────────────────────────────────────────────────
ZIP_OUT="$OUTPUT_DIR/${UNIVERSAL_PREFIX}.zip"
echo "==> Creating ZIP: $ZIP_OUT"
ditto -c -k --keepParent "$UNIVERSAL_APP" "$ZIP_OUT"

# ── Read packaging metadata from build artifacts ─────────────────────────
METADATA_FILE="$(find "$ARM64_PATH" -name 'packaging-metadata.json' -type f | head -1)"
if [[ -z "$METADATA_FILE" ]]; then
  METADATA_FILE="$(find "$X64_PATH" -name 'packaging-metadata.json' -type f | head -1)"
fi

if [[ -n "$METADATA_FILE" ]]; then
  echo "==> Found packaging metadata: $METADATA_FILE"
  METADATA_DIR="$(dirname "$METADATA_FILE")"
  PRODUCT_NAME="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('productName') or '')")"
  APP_ID="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('appId') or '')")"
  COPYRIGHT="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('copyright') or '')")"
  ARTIFACT_NAME_TMPL="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('artifactName') or '')")"
  COMPRESSION="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('compression') or '')")"
  CATEGORY="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('category') or '')")"
  MIN_SYS_VERSION="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('minimumSystemVersion') or '')")"
  SIGN="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(str(m.get('sign', False)).lower())")"
  INSTALL_LOCATION="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); print(m.get('installLocation') or '')")"
  DMG_SIGN="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(str(d.get('sign', False)).lower())")"
  DMG_BACKGROUND_REL="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('background') or '')")"
  DMG_BG_COLOR="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('backgroundColor') or '')")"
  DMG_BADGE_ICON_REL="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('badgeIcon') or '')")"
  DMG_ICON_REL="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('icon') or '')")"
  DMG_ICON_SIZE="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('iconSize'); print(v if v is not None else '')")"
  DMG_ICON_TEXT_SIZE="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('iconTextSize'); print(v if v is not None else '')")"
  DMG_TITLE="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('title') or '')")"
  DMG_FORMAT="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); print(d.get('format') or '')")"
  DMG_WIN_X="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('windowX'); print(v if v is not None else '')")"
  DMG_WIN_Y="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('windowY'); print(v if v is not None else '')")"
  DMG_WIN_W="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('windowWidth'); print(v if v is not None else '')")"
  DMG_WIN_H="$(python3 -c "import json; m=json.load(open('$METADATA_FILE')); d=m.get('dmg',{}); v=d.get('windowHeight'); print(v if v is not None else '')")"
  # Read DMG contents entries (icon positions) as JSON lines: "x y type name path"
  DMG_CONTENTS_JSON="$(python3 -c "
import json, sys
m = json.load(open('$METADATA_FILE'))
for c in m.get('dmg', {}).get('contents', []):
    print(json.dumps(c))
")"
  DMG_BACKGROUND=""
  [[ -n "$DMG_BACKGROUND_REL" && -f "$METADATA_DIR/$DMG_BACKGROUND_REL" ]] && DMG_BACKGROUND="$METADATA_DIR/$DMG_BACKGROUND_REL"
  DMG_BADGE_ICON=""
  [[ -n "$DMG_BADGE_ICON_REL" && -f "$METADATA_DIR/$DMG_BADGE_ICON_REL" ]] && DMG_BADGE_ICON="$METADATA_DIR/$DMG_BADGE_ICON_REL"
  DMG_ICON=""
  [[ -n "$DMG_ICON_REL" && -f "$METADATA_DIR/$DMG_ICON_REL" ]] && DMG_ICON="$METADATA_DIR/$DMG_ICON_REL"
else
  echo "==> No packaging metadata found, using defaults from Info.plist"
  PRODUCT_NAME=""
  APP_ID="$(defaults read "$UNIVERSAL_APP/Contents/Info" CFBundleIdentifier 2>/dev/null || echo "")"
  COPYRIGHT=""
  ARTIFACT_NAME_TMPL=""
  COMPRESSION=""
  CATEGORY=""
  MIN_SYS_VERSION=""
  SIGN="false"
  INSTALL_LOCATION=""
  DMG_SIGN="false"
  DMG_BACKGROUND=""
  DMG_BG_COLOR=""
  DMG_BADGE_ICON=""
  DMG_ICON=""
  DMG_ICON_SIZE=""
  DMG_ICON_TEXT_SIZE=""
  DMG_TITLE=""
  DMG_FORMAT=""
  DMG_WIN_X=""
  DMG_WIN_Y=""
  DMG_WIN_W=""
  DMG_WIN_H=""
  DMG_CONTENTS_JSON=""
fi

# Override artifactName to force universal suffix
if [[ -n "$ARTIFACT_NAME_TMPL" ]]; then
  UNIVERSAL_ARTIFACT_NAME="$(echo "$ARTIFACT_NAME_TMPL" | sed 's/\${arch}/universal/g')"
else
  UNIVERSAL_ARTIFACT_NAME='${name}-${version}-${os}-universal.${ext}'
fi

# ── Generate electron-builder configs ─────────────────────────────────────
generate_eb_config() {
  local target_format="$1"
  local config_file="$2"

  {
    echo "directories:"
    echo "  output: ."
    [[ -n "$PRODUCT_NAME" ]] && echo "productName: \"$PRODUCT_NAME\""
    [[ -n "$APP_ID" ]] && echo "appId: \"$APP_ID\""
    [[ -n "$COPYRIGHT" ]] && echo "copyright: \"$COPYRIGHT\""
    [[ -n "$COMPRESSION" ]] && echo "compression: \"$COMPRESSION\""
    echo "artifactName: \"$UNIVERSAL_ARTIFACT_NAME\""
    echo "mac:"
    echo "  target:"
    echo "    - target: $target_format"
    [[ -n "$CATEGORY" ]] && echo "  category: \"$CATEGORY\""
    [[ -n "$MIN_SYS_VERSION" ]] && echo "  minimumSystemVersion: \"$MIN_SYS_VERSION\""
    if [[ "$SIGN" != "true" ]]; then
      echo "  identity: null"
      echo "  hardenedRuntime: false"
      echo "  gatekeeperAssess: false"
    fi
    if [[ "$target_format" == "dmg" ]]; then
      echo "dmg:"
      echo "  sign: $DMG_SIGN"
      [[ -n "$DMG_BACKGROUND" ]] && echo "  background: \"$DMG_BACKGROUND\""
      [[ -n "$DMG_BG_COLOR" ]] && echo "  backgroundColor: \"$DMG_BG_COLOR\""
      [[ -n "$DMG_BADGE_ICON" ]] && echo "  badgeIcon: \"$DMG_BADGE_ICON\""
      [[ -n "$DMG_ICON" ]] && echo "  icon: \"$DMG_ICON\""
      [[ -n "$DMG_ICON_SIZE" ]] && echo "  iconSize: $DMG_ICON_SIZE"
      [[ -n "$DMG_ICON_TEXT_SIZE" ]] && echo "  iconTextSize: $DMG_ICON_TEXT_SIZE"
      [[ -n "$DMG_TITLE" ]] && echo "  title: \"$DMG_TITLE\""
      [[ -n "$DMG_FORMAT" ]] && echo "  format: $DMG_FORMAT"
      if [[ -n "$DMG_WIN_X" || -n "$DMG_WIN_Y" || -n "$DMG_WIN_W" || -n "$DMG_WIN_H" ]]; then
        echo "  window:"
        [[ -n "$DMG_WIN_X" ]] && echo "    x: $DMG_WIN_X"
        [[ -n "$DMG_WIN_Y" ]] && echo "    y: $DMG_WIN_Y"
        [[ -n "$DMG_WIN_W" ]] && echo "    width: $DMG_WIN_W"
        [[ -n "$DMG_WIN_H" ]] && echo "    height: $DMG_WIN_H"
      fi
      # Emit DMG contents entries (icon positions)
      if [[ -n "$DMG_CONTENTS_JSON" ]]; then
        echo "  contents:"
        while IFS= read -r entry_json; do
          [[ -z "$entry_json" ]] && continue
          local cx cy ctype cname cpath
          cx="$(echo "$entry_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['x'])")"
          cy="$(echo "$entry_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['y'])")"
          ctype="$(echo "$entry_json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('type') or '')")"
          cname="$(echo "$entry_json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('name') or '')")"
          cpath="$(echo "$entry_json" | python3 -c "import json,sys; print(json.load(sys.stdin).get('path') or '')")"
          echo "    - x: $cx"
          echo "      y: $cy"
          [[ -n "$ctype" ]] && echo "      type: $ctype"
          [[ -n "$cname" ]] && echo "      name: \"$cname\""
          [[ -n "$cpath" ]] && echo "      path: \"$cpath\""
        done <<< "$DMG_CONTENTS_JSON"
      fi
    elif [[ "$target_format" == "pkg" ]]; then
      echo "pkg:"
      [[ -n "$INSTALL_LOCATION" ]] && echo "  installLocation: \"$INSTALL_LOCATION\""
      echo "  isRelocatable: false"
      [[ "$SIGN" != "true" ]] && echo "  identity: null"
    fi
  } > "$config_file"

  echo "==> Generated electron-builder config for $target_format:"
  cat "$config_file"
}

generate_package_json() {
  echo "{ \"name\": \"$NPM_NAME\", \"version\": \"$BUNDLE_VERSION\", \"private\": true }" > "$1"
}

# ── Shared vars ───────────────────────────────────────────────────────────
NPM_NAME="$(echo "${PRODUCT_NAME:-app}" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9._-]/-/g' | sed 's/^-//;s/-$//')"
BUNDLE_VERSION="$(defaults read "$UNIVERSAL_APP/Contents/Info" CFBundleShortVersionString 2>/dev/null || echo "1.0.0")"

# ── Helper: update nucleus.executable.type in .cfg files ──────────────────
update_executable_type() {
  local app_dir="$1"
  local exec_type="$2"
  local cfg_option="java-options=-Dnucleus.executable.type=$exec_type"
  local prefix="java-options=-Dnucleus.executable.type="

  find "$app_dir" -name '*.cfg' -not -name 'jvm.cfg' -type f | while IFS= read -r cfg; do
    if grep -q "$prefix" "$cfg"; then
      sed -i '' "s|${prefix}.*|${cfg_option}|" "$cfg"
      echo "  [CFG] Updated executable type to '$exec_type' in $(basename "$cfg")"
    fi
  done
}

run_electron_builder() {
  local format="$1"
  local eb_dir="$WORK/eb-$format"
  local app_copy_dir="$WORK/app-$format"
  local app_copy="$app_copy_dir/$(basename "$UNIVERSAL_APP")"
  mkdir -p "$eb_dir" "$app_copy_dir"

  # Work on a copy so we never modify the signed original
  echo "==> [$format] Copying .app for packaging..." >&2
  cp -a "$UNIVERSAL_APP" "$app_copy"

  generate_package_json "$eb_dir/package.json"
  generate_eb_config "$format" "$eb_dir/electron-builder.yml" >&2

  update_executable_type "$app_copy" "$format" >&2

  # Re-sign after cfg modification to preserve seal integrity
  if [[ -n "$SIGNING_IDENTITY" ]]; then
    sign_app_bundle "$app_copy" "$SIGNING_IDENTITY" \
      "$ENTITLEMENTS_FILE" "$RUNTIME_ENTITLEMENTS_FILE" \
      "$KEYCHAIN_PATH" "$format" >&2
  else
    echo "==> [$format] Ad-hoc re-signing after cfg update..." >&2
    codesign --force --deep --sign - "$app_copy"
  fi

  CSC_IDENTITY_AUTO_DISCOVERY=false \
  npx --yes electron-builder \
    --prepackaged "$app_copy" \
    --config "$eb_dir/electron-builder.yml" \
    --config.electronVersion=33.0.0 \
    --mac "$format" \
    --publish never \
    --project "$eb_dir" >&2 \
  || { echo "::error::electron-builder $format creation failed" >&2; exit 1; }

  local out_file="$(find "$eb_dir" -name "*.$format" -type f | head -1)"
  if [[ -n "$out_file" ]]; then
    cp "$out_file" "$OUTPUT_DIR/"
    echo "$OUTPUT_DIR/$(basename "$out_file")"
  fi
}

# ── Create DMG via electron-builder ───────────────────────────────────────
echo ""
echo "==> Creating DMG via electron-builder..."
DMG_OUT="$(run_electron_builder dmg)"
[[ -n "$DMG_OUT" ]] && echo "==> DMG created: $DMG_OUT" || echo "::warning::electron-builder did not produce a DMG"

# ── Create PKG ────────────────────────────────────────────────────────────
echo ""
if [[ -n "$INSTALLER_IDENTITY" && -n "$UNIVERSAL_SANDBOXED_APP" ]]; then
  echo "==> Creating App Store PKG via productbuild..."

  # Work on a copy so we never modify the signed sandboxed original
  PKG_APP_COPY_DIR="$WORK/app-pkg-sandboxed"
  PKG_APP_COPY="$PKG_APP_COPY_DIR/$(basename "$UNIVERSAL_SANDBOXED_APP")"
  mkdir -p "$PKG_APP_COPY_DIR"
  cp -a "$UNIVERSAL_SANDBOXED_APP" "$PKG_APP_COPY"

  update_executable_type "$PKG_APP_COPY" "pkg"

  # Re-sign after cfg modification
  if [[ -n "$APP_STORE_IDENTITY" ]]; then
    # Augment entitlements with application-identifier for TestFlight / App Store (error 90886)
    PKG_ENT="${SANDBOXED_ENTITLEMENTS_FILE:-$ENTITLEMENTS_FILE}"
    if [[ -n "$PKG_ENT" && -f "$PKG_ENT" && -n "$APP_ID" ]]; then
      TEAM_ID="$(echo "$APP_STORE_IDENTITY" | grep -oE '\([A-Z0-9]+\)$' | tr -d '()')"
      if [[ -n "$TEAM_ID" ]] && ! grep -q 'com.apple.application-identifier' "$PKG_ENT"; then
        APP_IDENTIFIER="$TEAM_ID.$APP_ID"
        AUGMENTED_ENT="$WORK/entitlements-appstore-pkg.plist"
        sed "s|</dict>|    <key>com.apple.application-identifier</key>\\
    <string>$APP_IDENTIFIER</string>\\
    <key>com.apple.developer.team-identifier</key>\\
    <string>$TEAM_ID</string>\\
</dict>|" "$PKG_ENT" > "$AUGMENTED_ENT"
        echo "==> Augmented entitlements with application-identifier: $APP_IDENTIFIER"
        PKG_ENT="$AUGMENTED_ENT"
      fi
    fi

    sign_app_bundle "$PKG_APP_COPY" "$APP_STORE_IDENTITY" \
      "$PKG_ENT" "${SANDBOXED_RUNTIME_ENTITLEMENTS_FILE:-$RUNTIME_ENTITLEMENTS_FILE}" \
      "$KEYCHAIN_PATH" "App Store PKG"
  else
    codesign --force --deep --sign - "$PKG_APP_COPY"
  fi

  PKG_NAME="${UNIVERSAL_PREFIX}.pkg"

  PB_KC_ARGS=()
  if [[ -n "$KEYCHAIN_PATH" ]]; then
    PB_KC_ARGS=(--keychain "$KEYCHAIN_PATH")
  fi

  productbuild \
    --component "$PKG_APP_COPY" "${INSTALL_LOCATION:-/Applications}" \
    --sign "$INSTALLER_IDENTITY" \
    ${PB_KC_ARGS[@]+"${PB_KC_ARGS[@]}"} \
    "$OUTPUT_DIR/$PKG_NAME"

  echo "==> Verifying PKG signature..."
  pkgutil --check-signature "$OUTPUT_DIR/$PKG_NAME"

  PKG_OUT="$OUTPUT_DIR/$PKG_NAME"
  echo "==> App Store PKG created: $PKG_OUT"
else
  echo "==> Creating PKG via electron-builder..."
  PKG_OUT="$(run_electron_builder pkg)"
  [[ -n "$PKG_OUT" ]] && echo "==> PKG created: $PKG_OUT" || echo "::warning::electron-builder did not produce a PKG"
fi

# ── Outputs ───────────────────────────────────────────────────────────────
echo ""
echo "==> Universal artifacts created:"
ls -lh "$OUTPUT_DIR"

echo "zip=$ZIP_OUT" >> "$GITHUB_OUTPUT"
echo "dmg=$DMG_OUT" >> "$GITHUB_OUTPUT"
echo "pkg=$PKG_OUT" >> "$GITHUB_OUTPUT"
