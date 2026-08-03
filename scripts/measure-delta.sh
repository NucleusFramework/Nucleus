#!/usr/bin/env bash
# Measures real differential-update yield by packaging two consecutive versions of a demo,
# with a one-line source change between them, and saving each artifact plus its block map.
# Not part of the build: a throwaway harness for RealArtifactDeltaTest.
set -u

OUT="${1:-/tmp/delta-measure}"
NUCLEUS_MAIN=examples/nucleus-demo/src/main/kotlin/com/example/demo/Main.kt
JEWEL_MAIN=examples/jewel-demo/src/main/kotlin/jewelsample/Main.kt

restore() {
    git checkout -- "$NUCLEUS_MAIN" "$JEWEL_MAIN" 2>/dev/null || true
}
trap restore EXIT

# Runs Gradle, keeping its real exit status (a pipe to tail would always report success).
build() {
    echo "=== $* ==="
    local log
    log="$(mktemp)"
    ./gradlew "$@" --no-configuration-cache --console=plain >"$log" 2>&1
    local status=$?
    tail -4 "$log"
    [ $status -eq 0 ] || grep -E "^e: |error:|What went wrong" -A3 "$log" | head -20
    rm -f "$log"
    return $status
}

# Copies the NSIS installer and its block map produced by <project> into <dest>.
collect() {
    local project="$1" dest="$2" label="$3"
    mkdir -p "$dest"
    # Newest, not first: several variants (main, main-release, graalvm) keep an installer
    # of the same name around, and the first match is often a stale one.
    local exe
    exe="$(find "examples/$project/build" -path '*nsis*' -name '*.exe' -type f -printf '%T@\t%p\n' |
        sort -rn | head -1 | cut -f2-)"
    if [ -z "$exe" ]; then
        echo "!! no NSIS installer found for $project ($label)"
        return 1
    fi
    cp "$exe" "$dest/$label.exe"
    if [ -f "$exe.blockmap" ]; then
        cp "$exe.blockmap" "$dest/$label.exe.blockmap"
    else
        echo "!! no block map next to $exe"
        return 1
    fi
}

pair() {
    local project="$1" task="$2" main="$3" needle="$4" dir="$5"
    restore
    build ":examples:$project:$task" || return 1
    collect "$project" "$OUT/$dir" v1 || return 1

    sed -i "s/$needle/$needle patched/" "$main"
    grep -q "$needle patched" "$main" || { echo "!! patch did not apply to $main"; return 1; }
    build ":examples:$project:$task" || return 1
    collect "$project" "$OUT/$dir" v2 || return 1
    restore

    # The whole measurement is meaningless if the two artifacts are the same bytes.
    if cmp -s "$OUT/$dir/v1.exe" "$OUT/$dir/v2.exe"; then
        echo "!! $dir: v1 and v2 are identical — the patched build did not reach the installer"
        return 1
    fi
    echo "++ $dir ready: $(stat -c%s "$OUT/$dir/v1.exe") vs $(stat -c%s "$OUT/$dir/v2.exe") bytes"
}

mkdir -p "$OUT"

# GraalVM native image, -O3 (jewel-demo).
pair jewel-demo packageGraalvmNsis "$JEWEL_MAIN" 'Jewel standalone sample' native

# JVM without ProGuard, to isolate what obfuscation costs (nucleus-demo).
pair nucleus-demo packageNsis "$NUCLEUS_MAIN" 'Nucleus Demo' jvm-no-proguard

echo "=== done ==="
find "$OUT" -type f -printf '%s\t%p\n' | sort -k2
