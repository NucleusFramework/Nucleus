#!/usr/bin/env bash
# Regenerates the pinned electron-builder toolchain lock file shipped inside the Gradle plugin.
#
# The plugin provisions electron-builder with `npm ci --ignore-scripts` against this lock file, so
# the lock file is the integrity boundary for ~275 npm packages pulled onto the machine that holds
# the code-signing certificates. Run this ONLY on a trusted machine with a clean npm config, and
# review the diff before committing.
#
# Usage:
#   scripts/update-electron-builder-lock.sh            # re-resolve the currently pinned version
#   scripts/update-electron-builder-lock.sh 26.16.0    # bump to a new version
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
resource_dir="$repo_root/plugin-build/plugin/src/main/resources/nucleus/electron-builder"
tool_manager="$repo_root/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/desktop/application/internal/electronbuilder/ElectronBuilderToolManager.kt"

pinned_version="$(sed -nE 's/.*ELECTRON_BUILDER_VERSION = "([^"]+)".*/\1/p' "$tool_manager")"
version="${1:-$pinned_version}"

if [[ -z "$version" ]]; then
  echo "Could not read ELECTRON_BUILDER_VERSION from $tool_manager" >&2
  exit 1
fi

# This refuses only when certificate verification has been *switched off* — not merely because a
# corporate proxy terminates TLS. A proxy with its CA properly installed leaves verification on
# (NODE_TLS_REJECT_UNAUTHORIZED unset) and is fine: the hashes below cover tarball bytes, which such
# a proxy does not rewrite. With verification off there is nothing left to tell a scanning proxy from
# an attacker, and these hashes are what every later `npm ci` trusts.
if [[ -n "${NODE_TLS_REJECT_UNAUTHORIZED:-}" && "${NODE_TLS_REJECT_UNAUTHORIZED}" != "1" ]]; then
  echo "Refusing to resolve the tree with certificate verification disabled" >&2
  echo "(NODE_TLS_REJECT_UNAUTHORIZED=${NODE_TLS_REJECT_UNAUTHORIZED}). The integrity hashes would" >&2
  echo "record whatever the connection served, unverified. Unset it — installing your proxy's CA" >&2
  echo "certificate (NODE_EXTRA_CA_CERTS) keeps the proxy working with verification on." >&2
  exit 1
fi

# Resolve against the public registry regardless of local npm config: the lock file must record
# registry.npmjs.org tarballs, so an internal mirror must not leak into `resolved` URLs. A mirror
# stays usable at install time — npm rewrites the host and still checks the recorded hash.
registry="https://registry.npmjs.org/"
configured_registry="$(npm config get registry)"
if [[ "$configured_registry" != "$registry" ]]; then
  echo "note: npm is configured for $configured_registry; resolving against $registry for this run."
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

cat > "$workdir/package.json" <<JSON
{
  "name": "nucleus-electron-builder-toolchain",
  "version": "1.0.0",
  "private": true,
  "description": "Pinned electron-builder toolchain used by the Nucleus Gradle plugin.",
  "license": "MIT",
  "dependencies": {
    "electron-builder": "$version"
  }
}
JSON

echo "Resolving electron-builder $version against $registry ..."
(cd "$workdir" && npm install --package-lock-only --ignore-scripts --no-audit --no-fund --registry="$registry")

echo "Verifying the resolved tree installs and runs ..."
(cd "$workdir" && npm ci --ignore-scripts --no-audit --no-fund --no-progress --registry="$registry" >/dev/null)
installed="$(cd "$workdir" && node node_modules/electron-builder/cli.js --version)"
if [[ "$installed" != "$version" ]]; then
  echo "Installed CLI reports $installed, expected $version" >&2
  exit 1
fi

mkdir -p "$resource_dir"
cp "$workdir/package.json" "$workdir/package-lock.json" "$resource_dir/"

echo
echo "Updated $resource_dir"
echo "  electron-builder: $version ($(grep -c '"integrity":' "$resource_dir/package-lock.json") locked packages)"
if [[ "$version" != "$pinned_version" ]]; then
  echo
  echo "Now set ELECTRON_BUILDER_VERSION = \"$version\" in:"
  echo "  $tool_manager"
  echo "(ElectronBuilderToolchainLockTest fails until it matches.)"
fi
echo
echo "Review the diff before committing: git diff -- $resource_dir"
