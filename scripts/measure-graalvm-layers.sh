#!/usr/bin/env bash
# measure-graalvm-layers.sh — Measures what GraalVM layered images buy a differential update.
#
# A monolithic native image is one blob whose layout `native-image` reshuffles globally, so a
# one-line source change invalidates nearly all of its blocks and an auto-update has to refetch the
# whole executable. A layered image compiles the stable part (the JDK, and potentially the framework)
# into a shared library that a release does not rebuild, leaving a thinner application layer to
# change — so the unchanged part becomes a separate archive entry the updater reuses verbatim.
#
# This script builds both shapes of the same app, one source line apart, and reports how many bytes
# an update transfers in each case. It is a measurement harness, not part of the build.
#
# STATUS: the layered path does not currently produce a working Compose application. The application
# layer fails to compile with a permanent Graal bailout on `androidx.compose.runtime.snapshots
# .SnapshotKt.sync` — Kotlin's `synchronized` intrinsic wrapping an inlined lambda:
#
#     PermanentBailoutException: Unstructured locking: too few monitorexits exiting frame
#         at BytecodeParser.handleUnstructuredLockingForUnwindTarget
#
# A monolithic build compiles the same method without complaint. Reproduced on GraalVM CE 25.1.3 and
# 25.2.4, with the layer option verification on and off, with the package assigned to either layer,
# and with -H:-UseSharedLayerGraphs. Nothing works around it: the bailout is permanent, and
# SnapshotKt.sync is reachable from any Compose application. The delta figures below were therefore
# measured on a JDK-only base layer, whose application layer does build.
#
# Usage: scripts/measure-graalvm-layers.sh [output-dir]
#
# Requires:
#   - macOS on Apple silicon (the layer dylib is patched with install_name_tool)
#   - node/npm, to fetch electron-builder's `app-builder` (it computes the block maps the updater
#     actually matches, so measuring with anything else would measure a different algorithm)
#   - a GraalVM the plugin has already provisioned under ~/.gradle/nucleus/graalvm
#   - `RealMacArtifactDeltaTest` in :updater-runtime, which lands with PR #428
set -u

OUT="${1:-/tmp/nucleus-layer-measure}"
DEMO=examples/nucleus-demo
MAIN="$DEMO/src/main/kotlin/com/example/demo/Main.kt"
BUILD="$DEMO/build.gradle.kts"

# The JDK modules the base layer holds. Compose needs java.desktop and java.datatransfer on top of
# java.base; the rest are what the demo's dependencies reach.
BASE_MODULES=(
    java.base java.datatransfer java.desktop java.logging java.management
    java.naming java.prefs java.xml java.net.http java.sql java.instrument jdk.unsupported
)

restore() { git checkout -- "$MAIN" "$BUILD" 2>/dev/null || true; }
trap restore EXIT

die() {
    echo "!! $*" >&2
    exit 1
}

# ── Tooling ───────────────────────────────────────────────────────────────────────────────────────

resolve_native_image() {
    find "$HOME/.gradle/nucleus/graalvm" -name native-image -type f -perm -u+x 2>/dev/null |
        grep '/lib/svm/bin/' | head -1
}

resolve_app_builder() {
    local bin="$OUT/node_modules/app-builder-bin/mac/app-builder_arm64"
    if [ ! -x "$bin" ]; then
        (cd "$OUT" && npm i app-builder-bin --no-save --silent >/dev/null 2>&1)
        chmod +x "$bin" 2>/dev/null || true
    fi
    [ -x "$bin" ] && echo "$bin"
}

# ── Building ──────────────────────────────────────────────────────────────────────────────────────

# Compiles the base layer: a shared library holding the AOT-compiled JDK, plus the .nil archive that
# app-layer builds consume.
#
# Both layers have to be built from the same option set, which is why real support belongs in the
# plugin rather than in a script: it owns both invocations and can derive them from one list. Three
# ways this bites, in the order they surface:
#   - `-march` must match, or the pair is rejected ("CPU Features should be consistent across
#     layers");
#   - the same `-H:ConfigurationFileDirectories` must be passed to both, or class initialization
#     diverges ("Class initialization info not stable between layers", first seen on
#     kotlinx.coroutines.swing.Swing, whose directive comes from the Oracle metadata repository);
#   - the shared options must appear at the same position, which `-H:+LayerOptionVerification`
#     enforces. Disabling that check is not a fix: the build then proceeds into a miscompilation.
# The -H:ConfigurationFileDirectories the plugin passes to the application layer. The base layer
# needs the identical set (see above), and they only exist after one GraalVM build has run.
collect_metadata_dirs() {
    local tmp="$PWD/$DEMO/build/compose/tmp/main/graalvm" dir args=""
    for dir in libraryMetadata platformMetadata staticAnalysis projectResources; do
        [ -d "$tmp/$dir" ] && args="$args -H:ConfigurationFileDirectories=$tmp/$dir"
    done
    if [ -f "$tmp/metadataRepoDirs.txt" ]; then
        while IFS= read -r line; do
            [ -n "$line" ] && args="$args -H:ConfigurationFileDirectories=$line"
        done <"$tmp/metadataRepoDirs.txt"
    fi
    echo "$args"
}

# Every package in the uberjar that is not the application's own, as `package=` selectors. This is
# the split worth having — the framework stops changing between releases — and it is what plugin
# support would compute, since the plugin knows which packages the project itself produces. `path=`
# cannot do it: the plugin hands native-image a single flattened uberjar.
library_package_spec() {
    local uber="$1"
    python3 - "$uber" <<'PYTHON'
import re, sys, zipfile
uber = sys.argv[1]
# The application's own packages stay in the application layer.
APP_PREFIXES = ("com/example/demo",)
IDENT = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")

def is_package(path):
    # META-INF/versions/<n>/... are multi-release entries, not packages, and native-image rejects
    # them outright ("could not find requested packages").
    if path.startswith(("META-INF/", "WEB-INF/")):
        return False
    return all(IDENT.match(part) for part in path.split("/"))

packages = {
    name.rsplit("/", 1)[0]
    for name in zipfile.ZipFile(uber).namelist()
    if name.endswith(".class") and "/" in name
}
libraries = sorted(
    p for p in packages
    if is_package(p) and not p.startswith(APP_PREFIXES)
)
print(",".join("package=" + p.replace("/", ".") for p in libraries))
PYTHON
}

build_base_layer() {
    local ni="$1" march="$2" spec="base.nil" module
    for module in "${BASE_MODULES[@]}"; do spec="$spec,module=$module"; done
    # Opt in with LAYER_SPLIT=packages once the bailout above is fixed upstream.
    if [ "${LAYER_SPLIT:-modules}" = "packages" ]; then
        spec="$spec,$(library_package_spec "$(ls "$PWD/$DEMO"/build/compose/jars/*.jar | head -1)")"
    fi

    echo "=== base layer (-march=$march) ==="
    local metadata
    metadata="$(collect_metadata_dirs)"
    # shellcheck disable=SC2086  # word splitting is how the -H: flags are passed
    (cd "$OUT" && "$ni" -H:+UnlockExperimentalVMOptions "-march=$march" \
        --enable-native-access=ALL-UNNAMED $metadata \
        "-H:LayerCreate=$spec" -o libbase 2>&1 | grep -E "Generating|Finished|Failed|Error:")
    [ -f "$OUT/libbase.dylib" ] || die "no base layer produced"
    [ -f "$OUT/base.nil" ] || die "no .nil archive produced"
}

# Adds the layer options to the demo's graalvm block. buildArgs reaches native-image verbatim, which
# is the only hook a layered build needs from the plugin today.
inject_layer_option() {
    python3 - "$BUILD" "$1" <<'PY'
import sys
path, nil = sys.argv[1], sys.argv[2]
anchor = '        imageName = "nucleus-sample"\n'
source = open(path).read()
if anchor not in source:
    sys.exit("anchor not found in " + path)
injected = anchor + (
    "        buildArgs.addAll(\n"
    '            "-H:+UnlockExperimentalVMOptions",\n'
    f'            "-H:LayerUse={nil}",\n'
    "        )\n"
)
open(path, "w").write(source.replace(anchor, injected, 1))
PY
    grep -q LayerUse "$BUILD" || die "could not inject the layer option"
}

# Packages the demo's GraalVM ZIP, as an application layer when a .nil is given and as a monolith
# otherwise.
package_zip() {
    local version="$1" layer="${2:-}"
    restore
    [ -n "$layer" ] && inject_layer_option "$layer"
    RELEASE_VERSION="$version" ./gradlew ":examples:nucleus-demo:packageGraalvmZip" \
        --no-configuration-cache --console=plain >"$OUT/gradle.log" 2>&1 || {
        grep -E "Error:|Failed generating|What went wrong" -A3 "$OUT/gradle.log" | head -20
        die "build failed (full log: $OUT/gradle.log)"
    }
}

# Copies the ZIP the plugin produced, plus the block map electron-builder wrote beside it.
collect() {
    local dest="$1" label="$2" ab="$3" zip
    zip="$(ls "$DEMO/build/compose/binaries/main/graalvm-zip"/*.zip 2>/dev/null | head -1)"
    [ -n "$zip" ] || die "no ZIP produced"
    mkdir -p "$dest"
    cp "$zip" "$dest/$label.zip"
    if [ -f "$zip.blockmap" ]; then
        cp "$zip.blockmap" "$dest/$label.zip.blockmap"
    else
        "$ab" blockmap --input "$dest/$label.zip" --output "$dest/$label.zip.blockmap" >/dev/null
    fi
    echo "++ $label: $(stat -f%z "$dest/$label.zip") bytes"
}

# Repackages the bundle so it also carries the base-layer dylib, which the plugin does not know to
# ship. The executable references it by absolute path, so the reference is rewritten to @loader_path
# and the bundle re-signed — what plugin support for layers would have to do itself.
repackage_with_base_layer() {
    local dest="$1" label="$2" ab="$3" app name macos absolute
    app="$(ls -d "$DEMO/build/compose/binaries/main/graalvm-app"/*.app 2>/dev/null | head -1)"
    [ -n "$app" ] || die "no app bundle to repackage"
    name="$(basename "$app")"

    mkdir -p "$dest/$label-staged"
    ditto "$app" "$dest/$label-staged/$name"
    macos="$dest/$label-staged/$name/Contents/MacOS"
    cp "$OUT/libbase.dylib" "$macos/"
    absolute="$(otool -L "$macos/nucleus-sample" | awk '/libbase\.dylib/ {print $1}' | head -1)"
    if [ -n "$absolute" ] && [ "$absolute" != "@loader_path/libbase.dylib" ]; then
        install_name_tool -change "$absolute" @loader_path/libbase.dylib "$macos/nucleus-sample"
    fi
    codesign --force --deep --sign - "$dest/$label-staged/$name" >/dev/null 2>&1
    mkdir -p "$dest"
    (cd "$dest/$label-staged" && zip -q -r -9 "$dest/$label.zip" .)
    "$ab" blockmap --input "$dest/$label.zip" --output "$dest/$label.zip.blockmap" >/dev/null
    echo "++ $label (with base layer): $(stat -f%z "$dest/$label.zip") bytes"
}

patch_source() {
    sed -i '' 's/title = "Nucleus Demo",/title = "Nucleus Demo v2",/' "$MAIN"
    grep -q 'title = "Nucleus Demo v2"' "$MAIN" || die "the source patch did not apply"
}

# ── Measuring ─────────────────────────────────────────────────────────────────────────────────────

# Runs the real updater over the pair: it resolves the block maps, issues range requests against a
# loopback host that counts what it sends, and reports the bytes that crossed the wire.
measure() {
    local dir="$1" label="$2"
    echo "=== $label ==="
    ./gradlew :updater-runtime:test --no-configuration-cache --console=plain -i \
        --tests '*RealMacArtifactDeltaTest*' \
        "-Dnucleus.e2e.mac.old=$dir/v1.zip" \
        "-Dnucleus.e2e.mac.new=$dir/v2.zip" 2>&1 |
        grep -E "artifact:|differential:|transferred:" | head -4
}

# ── Run ───────────────────────────────────────────────────────────────────────────────────────────

[ -f "$BUILD" ] || die "run this from the repository root"
mkdir -p "$OUT"

NI="$(resolve_native_image)"
[ -n "$NI" ] || die "no provisioned native-image found; run a GraalVM task once first"
AB="$(resolve_app_builder)"
[ -n "$AB" ] || die "could not obtain app-builder (needs npm)"

# The plugin uses -march=native on Apple silicon and compatibility elsewhere.
MARCH=compatibility
[ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ] && MARCH=native

# The monolithic pair comes first: it produces the uberjar and the metadata directories that the
# base layer has to be built against.
echo "=== monolithic pair ==="
package_zip 1.0.0
collect "$OUT/mono" v1 "$AB"
patch_source
package_zip 1.0.1
collect "$OUT/mono" v2 "$AB"

echo "=== layered pair ==="
restore
build_base_layer "$NI" "$MARCH"
package_zip 1.0.0 "$OUT/base.nil"
repackage_with_base_layer "$OUT/layered" v1 "$AB"
patch_source
package_zip 1.0.1 "$OUT/base.nil"
repackage_with_base_layer "$OUT/layered" v2 "$AB"
restore

measure "$OUT/mono" monolithic
measure "$OUT/layered" layered

echo "=== done: artifacts under $OUT ==="
