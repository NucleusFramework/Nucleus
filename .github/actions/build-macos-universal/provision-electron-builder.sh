#!/usr/bin/env bash
# Provisions the same Node.js and the same pinned electron-builder toolchain the Nucleus plugin
# uses, for the universal repack — which runs electron-builder outside the plugin.
#
# - Node.js: the newest release of $NODE_LINE from nodejs.org, verified against the release's
#   SHASUMS256.txt, installed with the plugin's layout (NodeToolchainProvisioner):
#   <install-base>/node-<line>-darwin-<arch>/<archive top dir>/ plus a `.nucleus-provisioned`
#   marker naming that top dir. A cache restored from a plugin run is therefore reused as is.
# - electron-builder: `npm ci --ignore-scripts` against the lock file embedded in the plugin, so
#   every transitive package is checked against its recorded integrity hash (no `npx --yes`).
#
# Writes NODE_BIN and ELECTRON_BUILDER_CLI to $GITHUB_ENV.
set -euo pipefail

: "${NODE_LINE:?}" "${TOOLCHAIN_DIR:?}" "${TOOL_DIR:?}"
# The plugin's default cache location (<gradle-user-home>/nucleus/nodejs).
NODE_INSTALL_BASE="${NODE_INSTALL_BASE:-$HOME/.gradle/nucleus/nodejs}"

case "$(uname -m)" in
  arm64 | aarch64) arch="arm64" ;;
  x86_64) arch="x64" ;;
  *) echo "::error::Unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

install_dir="$NODE_INSTALL_BASE/node-$NODE_LINE-darwin-$arch"
marker="$install_dir/.nucleus-provisioned"

if [[ -f "$marker" && -x "$install_dir/$(cat "$marker")/bin/node" ]]; then
  node_home="$install_dir/$(cat "$marker")"
  echo "==> Reusing Node.js at $node_home"
else
  dist="https://nodejs.org/dist"
  # Same rule as the plugin: a pinned x.y.z as is, otherwise the newest release of that major.
  if [[ "$NODE_LINE" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    version="v${NODE_LINE#v}"
  else
    version="$(curl -fsSL "$dist/index.json" |
      jq -r --arg major "$NODE_LINE" \
        '[.[] | select(.version | startswith("v" + $major + "."))]
         | sort_by(.version | ltrimstr("v") | split(".") | map(tonumber)) | last | .version')"
  fi
  if [[ -z "$version" || "$version" == "null" ]]; then
    echo "::error::No Node.js release matches '$NODE_LINE'" >&2
    exit 1
  fi

  archive="node-$version-darwin-$arch.tar.gz"
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT

  echo "==> Downloading Node.js $version from $dist/$version/$archive"
  curl -fsSL -o "$work/$archive" "$dist/$version/$archive"
  curl -fsSL -o "$work/SHASUMS256.txt" "$dist/$version/SHASUMS256.txt"
  (cd "$work" && grep "  $archive\$" SHASUMS256.txt | shasum -a 256 -c -)

  tar -xzf "$work/$archive" -C "$work"
  top_dir="node-$version-darwin-$arch"
  rm -rf "$install_dir"
  mkdir -p "$install_dir"
  mv "$work/$top_dir" "$install_dir/"
  echo "$top_dir" > "$marker"
  node_home="$install_dir/$top_dir"
  echo "==> Node.js $version installed to $node_home"
fi

node_bin="$node_home/bin/node"
# npm's launcher resolves `node` through PATH.
export PATH="$node_home/bin:$PATH"

echo "==> Provisioning electron-builder from the plugin's lock file (npm ci --ignore-scripts)"
rm -rf "$TOOL_DIR"
mkdir -p "$TOOL_DIR"
cp "$TOOLCHAIN_DIR/package.json" "$TOOLCHAIN_DIR/package-lock.json" "$TOOL_DIR/"
(cd "$TOOL_DIR" && npm ci --ignore-scripts --no-audit --no-fund --no-progress --loglevel=error)

cli="$TOOL_DIR/node_modules/electron-builder/cli.js"
if [[ ! -f "$cli" ]]; then
  echo "::error::electron-builder CLI missing at $cli after npm ci" >&2
  exit 1
fi
echo "==> electron-builder $("$node_bin" "$cli" --version)"

{
  echo "NODE_BIN=$node_bin"
  echo "ELECTRON_BUILDER_CLI=$cli"
} >> "$GITHUB_ENV"
