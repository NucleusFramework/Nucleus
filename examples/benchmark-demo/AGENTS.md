# Agent playbook — Nucleus cross-runtime benchmark

Instructions for any agent (Claude, Codex, Cursor…) tasked with running, extending, or analyzing
this benchmark: a Geekbench-style suite comparing 5 desktop runtimes (JVM, GraalVM native, SwiftUI,
Tauri/Rust, Flutter/Dart) across 13 CPU kernels + 3 render ramps + 1 list bench.

## Architecture

- `BENCHMARK-SPEC.md` = **THE CONTRACT**. Every port must do bit-identical work (constants, LCG
  seeds, protocol). Any kernel change goes into all 5 implementations + the spec, never just one.
- Kotlin reference: `src/main/kotlin/benchmarkdemo/{Kernels,BenchmarkRunner,Main}.kt`
- Ports: `ports/swiftui/Sources/BenchmarkDemo/`, `ports/tauri/src-tauri/src/kernels.rs`
  (+ `ports/tauri/src/index.html` for the UI), `ports/flutter/lib/{kernels,main}.dart`
- Results: each app auto-runs its suite on launch and writes `~/nucleus-benchmarks/<id>.json`
  (`jvm|graalvm|swiftui|tauri|flutter`) at the end. A run's completion is detected by that file's
  **mtime**, never by the process. Note: all JVM variants (c2, c2-pg, graal) write `jvm.json`, and
  all native variants (os/o2/o3/o3-pgo) write `graalvm.json`, so the orchestrator copies each
  result out before the next run overwrites it.
- Orchestrator: `./run-all.sh` (`ONLY` variable) — builds first, strictly sequential runs, peak
  RSS sampled, thermal state logged, archived under `results/<ts>/`.

## The variants

| variant | command |
|---|---|
| jvm-c2 | `./gradlew :examples:benchmark-demo:run` (C2, no ProGuard) |
| jvm-c2-pg | `runRelease` with `-PrunJavaHome=<jdk>` (C2 + ProGuard) |
| jvm-graal | `runRelease` with `-PrunJavaHome=<graalvm>` (GraalVM JIT + ProGuard) |
| aot-os / o2 / o3 / o3-pgo | `nativeImageCompile` + `-Popt=s\|2\|(none)` and `-Ppgo=off\|(auto)` |
| swiftui | `swift build -c release` then `.build/release/BenchmarkDemo` |
| tauri | `cargo build --release` then `target/release/benchmark-demo` |
| flutter | `flutter build macos --release` then the binary inside the `.app` |

The `run`/`runRelease` fork's JVM is set by `-PrunJavaHome=<home>` (wired to
`nucleus.application.javaHome` in `build.gradle.kts`). This lets jvm-c2 run on stock HotSpot and
jvm-graal on GraalVM **without moving the Gradle daemon** off its stable build JDK — the forked
process is what gets measured. `run-all.sh` reads `JDK_C2_HOME` and `GRAALVM_HOME` for this.

## Non-negotiable build rules

- **GraalVM native: ALWAYS `--no-configuration-cache --rerun`.** The Gradle configuration cache
  serves phantom builds (BUILD SUCCESSFUL in 2s without recompiling) when `-Ppgo`/`-Popt` change.
  Verify the `Graal compiler: optimization level: X, target machine: Y, PGO: Z` line in the output
  — the only proof of the config actually compiled.
- **Native builds MUST run with `JAVA_HOME=<graalvm>` (current JVM = GraalVM).** Gradle prefers the
  running JVM when it satisfies the toolchain spec, so this resolves to the real GraalVM
  deterministically. Otherwise a stale Gradle-auto-provisioned *plain* Oracle JDK 25 in
  `~/.gradle/jdks` (no `native-image`) also matches `vendor=ORACLE/version=25` and gets picked
  non-deterministically, failing the compile. `auto-detect=false` hides it from the `javaToolchains`
  report but does NOT exclude it from selection. This mirrors `runGraalvmNative`.
- **`packageGraalvmNative` needs `--rerun-tasks`, not `--rerun`.** `--rerun` only forces the package
  task, not its `nativeImageCompile` dependency, so every opt level would bundle the same stale
  binary. Use `--rerun-tasks` (global) to force the whole graph.
- Each native build overwrites `build/compose/tmp/main/graalvm/nativeCompile/` — to compare
  variants, copy the **whole dir** (binary + AWT dylibs), never the binary alone.
- PGO flow: build `-Ppgo=instrument` → run the binary with cwd = `pgo/` → close the window at
  "Done" → `default.iprof` is written on process exit → rebuild (the profile is auto-detected).
  Train in **GUI** (not headless) to cover the render paths.
- `march = NativeImageMarch.NATIVE` in the graalvm DSL: ISA parity with what Swift/Rust ship on macOS ARM.
  `compatibility` = bare ARMv8.0, ~-15% composite.

## Measurement protocol

- **Never two apps at once**: render ramps fight over the compositor and produce zeros. Strictly
  sequential. Run only one benchmark app; keep other GUI apps closed.
- CPU kernels: 3 discarded warmups + best-of-5. Ramps: 1st window = warmup, failure confirmed over
  2 consecutive windows < 55 fps, geometric growth +25%.
- Built-in self-checks (the app crashes if wrong): π(2×10⁷) = 1,270,607 primes,
  digestSum SHA-256 = 16225487432, digits of π = 3.141592653 589793238.
- Kernel verification without GUI: Kotlin `--headless`, Dart `dart run bin/check.dart`, standalone
  Rust `rustc -O` on `kernels.rs`.

## RAM & app-weight measurement

- **Tauri RAM is multi-process.** WKWebView runs the canvas/JS in separate processes
  (`com.apple.WebKit.WebContent`, `.GPU`, `.Networking`). `run-all.sh`'s `pgrep` pattern only catches
  the main `benchmark-demo` binary and undercounts massively (177 MB main-only vs 794 MB for the full
  tree). To measure Tauri RAM, sum the WebKit process tree (assuming no other WebKit app is open).
- **App weight is apples-to-oranges.** Self-contained runtimes (JVM jlink, GraalVM native, Flutter)
  ship their runtime, so the `.app` is the honest weight. System-runtime ports (swiftui → SwiftUI/
  AppKit, tauri → WebKit) exclude the OS-provided engine, so their tiny binaries (0.3 / 5.6 MB) are
  not comparable to the bundles.

## Pitfalls already hit (do NOT rediscover)

1. **DCE**: an AOT compiler deletes a kernel whose output isn't observed (Mandelbrot Rust measured
   at 0.000 s). Each kernel returns a checksum consumed by the runner (`black_box`/volatile/`isNaN`).
2. **Hoisting**: a pure function with constant input is computed once for all 8 runs (sha256 at
   `inf` MB/s). The input is mutated by one byte on each run.
3. **Async canvas**: WebKit rasterizes behind rAF → the JS port forces completion with
   `getImageData(0,0,1,1)` per frame. Without it, WebView scores are inflated ×10.
4. **Allocations in the render loop**: never a boxed list per frame (Compose was measuring its GC).
   Flat `FloatArray` + native API.
5. **macOS sandbox**: the Flutter entitlements must keep `app-sandbox = false`, otherwise the JSON
   ends up in `~/Library/Containers/.../nucleus-benchmarks/`.
6. **Dart isolates**: entrypoints must be top-level (`compute`) — a State method closure captures the
   widget tree ("object is unsendable", app frozen at 0/13).
7. **Known PGO regression**: mandelbrot does ~1.9 with a profile vs ~3.3 without. Reproducible,
   documented — do NOT "fix" it.
8. **`pkill -f`**: never a pattern that matches the calling script's command line (self-kill).
9. **GraalVM toolchain ambiguity** (see build rules): run native builds with `JAVA_HOME=<graalvm>`.
10. **Silent native build failure**: piping gradle through `grep` swallows the exit code — a failed
    `nativeImageCompile` left a silently empty `nativeCompile/` dir. `build_native` now captures a
    full per-variant log, checks the exit code, and verifies the binary exists.
11. **Flutter needs a working Xcode**: an Xcode ↔ Command Line Tools version mismatch breaks
    `xcodebuild` plugin loading (`swift`/`cargo` are unaffected). Fix with
    `sudo xcodebuild -runFirstLaunch` or align the two versions.

## Results analysis

- Composite CPU = geo-mean of the 13 throughputs; graphics = 1000 × geo-mean of the ramps
  normalized to their START. Higher = better; `list_load` in ms = lower is better.
- Composites are only comparable within an identical suite (adding a bench changes the scale).
- Reference points (Apple M4, 10 cores, to catch an aberrant measurement):
  jvm-graal ≈ 147, tauri ≈ 142, jvm-c2-pg ≈ 140, swiftui ≈ 139, jvm-c2 ≈ 138, aot-o3-pgo ≈ 133,
  aot-o3 ≈ 101, aot-o2 ≈ 87, aot-os ≈ 80, flutter ≈ 57. Graphics: tauri/swiftui ≈ 15k, flutter/
  aot-o3 ≈ 14–15k, jvm ≈ 13k. RAM: flutter 196, native 269–326, jvm 616–695, tauri 794 (WebKit
  tree). App size: aot-o3 174 / aot-o3-pgo 129 / jvm 119–160 / flutter 37 MB.
- Graal compiler signature (JIT as well as AOT+PGO): strong blur/sieve, dull fft/sha. Flutter: weak
  CPU (raytracer ÷4), excellent rendering.
- Always report the exact config with a number (level, PGO, march, JVM used — the JSON's
  `runtimeLabel` field is authoritative) and flag any deviation > 10% vs the reference points.
- If a number looks too good, look for the measurement pitfall before celebrating.
- The full, precise cross-runtime results (per-kernel exact figures, ramps, RAM, app sizes) live in
  [`RESULTS.md`](RESULTS.md).
