#!/bin/zsh
# Nucleus Benchmark — full matrix orchestrator (macOS).
#
# Builds every variant FIRST, then runs them strictly one at a time, sampling peak RSS and
# logging thermal state around each run. Each variant's results JSON is archived under
# results/<timestamp>/<variant>.json with peakRssMB injected.
#
# Variants: jvm-c2, jvm-c2-pg, jvm-graal (runRelease: GraalVM JIT + ProGuard), aot-os, aot-o2,
#           aot-o3, aot-o3-pgo, swiftui, tauri, flutter.
#
# Usage:  ./run-all.sh                          # full matrix
#         ONLY="aot-o3-pgo tauri" ./run-all.sh  # subset
#
# Requirements: the app auto-runs its suite on launch and writes
# ~/nucleus-benchmarks/<id>.json on completion — the script keys off that file's mtime.

set -u
SCRIPT_DIR=${0:A:h}
ROOT=${SCRIPT_DIR:h:h}            # repo root (examples/benchmark-demo -> repo)
BM=$SCRIPT_DIR
OUT=$BM/results/$(date +%Y%m%d-%H%M%S)
BIN_DIR=$BM/build/compose/tmp/main/graalvm/nativeCompile
JSON_DIR=$HOME/nucleus-benchmarks
ONLY=${ONLY:-"jvm-c2 jvm-c2-pg jvm-graal aot-os aot-o2 aot-o3 aot-o3-pgo swiftui tauri flutter"}

# Toolchains (override per machine). GRAALVM_HOME drives native-image (aot-*) and the jvm-graal
# JIT fork; JDK_C2_HOME is the HotSpot fork for the jvm-c2 baseline. When unset, everything
# falls back to the daemon JDK.
GRAALVM_HOME=${GRAALVM_HOME:-}
JDK_C2_HOME=${JDK_C2_HOME:-}

mkdir -p "$OUT/bins"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/run.log"; }
therm() { pmset -g therm 2>/dev/null | grep -E "Speed_Limit|CPU" | tr '\n' ' ' | tr -s ' '; }
wants() { [[ " $ONLY " == *" $1 "* ]]; }

log "Results: $OUT"
log "Power: $(pmset -g ps | head -1)"
log "Machine: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m) — $(sysctl -n hw.ncpu) cores"

# ─────────────────────────── PHASE 1 — BUILDS ───────────────────────────

build_native() { # $1 = variant name, $2... = extra gradle props
  local name=$1; shift
  log "build $name…"
  local blog="$OUT/build-$name.log"
  # Run native-image with the GraalVM as the *current JVM* (JAVA_HOME): Gradle prefers the
  # running JVM when it satisfies the toolchain spec, so this resolves to the real GraalVM
  # deterministically — no fighting a stale auto-provisioned plain Oracle JDK 25 (no
  # native-image) that also matches vendor=ORACLE/version=25. This mirrors `runGraalvmNative`.
  # Capture the FULL build to a per-variant log (no -q, no grep pipe swallowing the exit code):
  # a native-image failure must be loud, not a silently empty nativeCompile/ dir.
  local jhome=${GRAALVM_HOME:-${JAVA_HOME:-}}
  if ! (cd "$ROOT" && JAVA_HOME=$jhome ./gradlew :examples:benchmark-demo:nativeImageCompile "$@" \
      --no-configuration-cache --rerun) > "$blog" 2>&1; then
    log "ERROR: build $name failed (gradle) — see $blog"; tail -25 "$blog" | tee -a "$OUT/run.log"; return 1
  fi
  grep -E "Graal compiler" "$blog" | tee -a "$OUT/run.log"   # the config proof-line
  if [[ ! -x "$BIN_DIR/benchmark-demo" ]]; then
    log "ERROR: build $name — binary missing in $BIN_DIR"; tail -25 "$blog" | tee -a "$OUT/run.log"; return 1
  fi
  rm -rf "$OUT/bins/$name"
  cp -R "$BIN_DIR" "$OUT/bins/$name"   # stash the whole dir (binary + AWT dylibs)
}

log "════ PHASE 1: builds ════"
if wants jvm-c2 || wants jvm-c2-pg || wants jvm-graal; then
  log "build jvm (classes + proguard jars)…"
  (cd "$ROOT" && ./gradlew :examples:benchmark-demo:compileKotlin \
      :examples:benchmark-demo:proguardReleaseJars -q) >> "$OUT/run.log" 2>&1
fi
wants aot-os     && build_native aot-os     -Popt=s -Pnucleus.graalvm.pgo=off
wants aot-o2     && build_native aot-o2     -Popt=2 -Pnucleus.graalvm.pgo=off
wants aot-o3     && build_native aot-o3     -Pnucleus.graalvm.pgo=off
wants aot-o3-pgo && build_native aot-o3-pgo
if wants swiftui; then
  log "build swiftui…"
  (cd "$BM/ports/swiftui" && swift build -c release) >> "$OUT/run.log" 2>&1
fi
if wants tauri; then
  log "build tauri…"
  (cd "$BM/ports/tauri/src-tauri" && touch build.rs && cargo build --release) >> "$OUT/run.log" 2>&1
fi
if wants flutter; then
  log "build flutter…"
  (cd "$BM/ports/flutter" && flutter build macos --release) >> "$OUT/run.log" 2>&1
fi
log "builds done"

# ─────────────────────────── PHASE 2 — RUNS ────────────────────────────

run_variant() { # $1=variant  $2=json id  $3=pgrep pattern  $4...=launch cmd
  local name=$1 jsonid=$2 pattern=$3; shift 3
  log "════ RUN $name — therm: $(therm)"
  local ref
  ref=$(date +%s)
  "$@" > /dev/null 2>&1 &
  local launcher_pid=$!
  local peak=0 rss pid
  while :; do
    if [[ -f "$JSON_DIR/$jsonid.json" ]] && (( $(stat -f %m "$JSON_DIR/$jsonid.json") >= ref )); then
      break
    fi
    if ! kill -0 "$launcher_pid" 2>/dev/null && ! pgrep -f "$pattern" > /dev/null; then
      log "ERROR: $name exited without writing its JSON"; return 1
    fi
    for pid in $(pgrep -f "$pattern"); do
      rss=$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ')
      [[ -n "$rss" && "$rss" -gt "$peak" ]] && peak=$rss
    done
    sleep 1
  done
  cp "$JSON_DIR/$jsonid.json" "$OUT/$name.json"
  python3 - "$OUT/$name.json" "$name" "$peak" <<'PY'
import json, sys
p, name, peak = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = json.load(open(p))
d["variant"] = name
d["peakRssMB"] = round(peak / 1024, 1)
json.dump(d, open(p, "w"), indent=1)
PY
  log "$name OK — composite $(python3 -c "import json;print(round(json.load(open('$OUT/$name.json'))['compositeCpuScore'],1))") — RSS max $((peak / 1024)) MB — therm: $(therm)"
  pkill -f "$pattern" 2>/dev/null
  sleep 2
}

wants jvm-c2     && run_variant jvm-c2     jvm     "benchmarkdemo.MainKt" \
                      sh -c "cd '$ROOT' && ./gradlew :examples:benchmark-demo:run ${JDK_C2_HOME:+-PrunJavaHome=$JDK_C2_HOME} -q"
wants jvm-c2-pg  && run_variant jvm-c2-pg  jvm     "benchmarkdemo.MainKt" \
                      sh -c "cd '$ROOT' && ./gradlew :examples:benchmark-demo:runRelease ${JDK_C2_HOME:+-PrunJavaHome=$JDK_C2_HOME} -q"
wants jvm-graal  && run_variant jvm-graal  jvm     "benchmarkdemo.MainKt" \
                      sh -c "cd '$ROOT' && ./gradlew :examples:benchmark-demo:runRelease ${GRAALVM_HOME:+-PrunJavaHome=$GRAALVM_HOME} -q"
wants aot-os     && run_variant aot-os     graalvm "bins/aot-os/benchmark-demo"     "$OUT/bins/aot-os/benchmark-demo"
wants aot-o2     && run_variant aot-o2     graalvm "bins/aot-o2/benchmark-demo"     "$OUT/bins/aot-o2/benchmark-demo"
wants aot-o3     && run_variant aot-o3     graalvm "bins/aot-o3/benchmark-demo"     "$OUT/bins/aot-o3/benchmark-demo"
wants aot-o3-pgo && run_variant aot-o3-pgo graalvm "bins/aot-o3-pgo/benchmark-demo" "$OUT/bins/aot-o3-pgo/benchmark-demo"
wants swiftui    && run_variant swiftui    swiftui "release/BenchmarkDemo" \
                      "$BM/ports/swiftui/.build/release/BenchmarkDemo"
wants tauri      && run_variant tauri      tauri   "release/benchmark-demo" \
                      "$BM/ports/tauri/src-tauri/target/release/benchmark-demo"
wants flutter    && run_variant flutter    flutter "benchmark_flutter" \
                      "$BM/ports/flutter/build/macos/Build/Products/Release/benchmark_flutter.app/Contents/MacOS/benchmark_flutter"

# ─────────────────────────── PHASE 3 — SUMMARY ───────────────────────────

log "════ SUMMARY ════"
python3 - "$OUT" <<'PY' | tee -a "$OUT/run.log"
import json, sys, glob, os

out = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(out, "*.json"))):
    d = json.load(open(f))
    ui = {u["name"]: u["value"] for u in d.get("ui", [])}
    rows.append((
        d.get("variant", "?"),
        d.get("compositeCpuScore", 0),
        d.get("compositeGraphicsScore"),
        d.get("peakRssMB"),
        ui.get("list_load"),
        {b["name"]: b["throughputM"] for b in d.get("cpu", [])},
    ))

rows.sort(key=lambda r: -r[1])
print(f"{'variant':14}{'CPU':>8}{'GFX':>8}{'RSS MB':>8}{'list ms':>9}")
for v, cpu, gfx, rss, lst, _ in rows:
    print(f"{v:14}{cpu:8.1f}{gfx or 0:8.0f}{rss or 0:8.0f}{(lst or 0):9.1f}")

benches = list(rows[0][5].keys()) if rows else []
print()
print(f"{'bench':15}" + "".join(f"{v[:10]:>11}" for v, *_ in rows))
for b in benches:
    print(f"{b:15}" + "".join(f"{r[5].get(b, 0):11.1f}" for r in rows))
PY

log "Done. Everything is in $OUT"
