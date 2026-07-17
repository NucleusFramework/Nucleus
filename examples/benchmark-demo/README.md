# Nucleus Benchmark

One Geekbench-style benchmark suite, four runtimes, comparable numbers — inside a real graphical
desktop app. JVM JIT vs GraalVM `-O3` AOT vs SwiftUI (LLVM) vs Tauri (rustc/LLVM), all running the
*same* kernels defined byte-for-byte in [`BENCHMARK-SPEC.md`](BENCHMARK-SPEC.md).

**Fully automatic:** launch any of the apps and the whole suite runs by itself — 12 CPU benches,
then an animation FPS bench (20k particles) and a big-list load bench — and writes its results to
`~/nucleus-benchmarks/<runtime>.json` at the end.

## The suite

| Category | Benches |
|---|---|
| CPU · 1 thread | mandelbrot, nbody, raytracer, matmul (SGEMM), blur (Gaussian), sha256, sieve, fft |
| CPU · all cores | mandelbrot_mt, raytracer_mt, matmul_mt, blur_mt |
| UI / framework | max_particles_55fps (ramp jusqu'à la chute sous 55 fps), list_load (50,000 rows) |

Scalar `f64`/integer math, hand-rolled algorithms only (no stdlib sort/hash/crypto) — we measure
the compiler, not the library. Composite CPU score = geometric mean of throughputs, **higher =
faster**. `list_load` is in ms, lower = better.

## Run the full matrix in one command

```bash
./run-all.sh                          # builds all 10 variants, runs them one at a time
ONLY="aot-o3-pgo tauri" ./run-all.sh  # subset
```

Variants: `jvm-c2`, `jvm-c2-pg` (C2 + ProGuard), `jvm-graal` (GraalVM JIT + ProGuard),
`aot-os|o2|o3|o3-pgo`, `swiftui`, `tauri`, `flutter`. All builds happen up front, then each app
runs **alone**, with peak RSS sampled every second and `pmset -g therm` logged around every run.
Everything lands in `results/<timestamp>/` — one JSON per variant (with `peakRssMB` injected) plus
a summary table at the end.

## Run each runtime

```bash
# JVM (HotSpot C2 JIT)
./gradlew :examples:benchmark-demo:run
./gradlew :examples:benchmark-demo:run --args="--headless"     # CLI: CPU suite + self-checks

# GraalVM Native Image, compiled at -O3 (level 3) — the AOT contender
./gradlew :examples:benchmark-demo:packageReleaseDistributionForCurrentOS
# → run the produced native binary

# SwiftUI (macOS, LLVM release)
cd ports/swiftui && swift run -c release
cd ports/swiftui && swift run -c release BenchmarkDemo --headless

# Tauri (Rust/LLVM release)
cd ports/tauri/src-tauri && cargo build --release && ./target/release/benchmark-demo

# Flutter (Dart AOT — release only, debug numbers are meaningless)
cd ports/flutter && flutter run --release -d macos
cd ports/flutter && dart run bin/check.dart      # kernel correctness check on the Dart VM
```

Each app writes `~/nucleus-benchmarks/{jvm,graalvm,swiftui,tauri}.json` (same schema, see spec) —
diff them to compare runtimes.

## Sample numbers (Apple Silicon, 10 cores — illustrative, not a leaderboard)

| Composite CPU (geo-mean of 12) | Score |
|---|---:|
| GraalVM `-O3` + `march=native` + recorded PGO | **254.5** |
| JVM JIT (HotSpot C2) | ~253 |
| Tauri (rustc `-O3`+LTO) | ~245–259 |
| SwiftUI (LLVM release) | ~247 |

Four-way tie — that *is* the demo. But GraalVM only gets there through its build staircase, each
step measured on this machine:

| GraalVM configuration (all `march=native`, 13-bench suite) | Composite CPU |
|---|---:|
| `-O2` + ML-inferred profile (out-of-the-box default) | 86.0 |
| `-O3` + ML-inferred profile (level 3 alone: **+16%**) | 100.0 |
| `-O3` + recorded PGO (the profile is worth **+31%** more) | **130.6** |
| JVM JIT reference | 137.6 |

Level 3 alone is real but modest; the recorded profile is the big lever — together they take AOT
from **-37% vs the JIT to -5%**. Toggle builds with `-Popt=2` (level) and `-Pnucleus.graalvm.pgo=off|instrument`
(profile); the graphics score is insensitive to all of it (rendering lives in native Skia).

PGO flow (profile at `graalvm/pgo/default.iprof`, applied automatically when present) — the plugin's
built-in `runWithPgoInstrument` task builds the instrumented image, runs it, and records the
profile on exit:

```bash
./gradlew :examples:benchmark-demo:runWithPgoInstrument --no-configuration-cache --rerun
# let the suite run in the window, close it at "Done" → writes graalvm/pgo/default.iprof
./gradlew :examples:benchmark-demo:nativeImageCompile --no-configuration-cache --rerun
```

## Compiler traps this suite defends against (see spec)

- **Dead-code elimination:** every kernel returns a checksum the runner consumes (`black_box` /
  volatile / `isNaN` guard). rustc LTO deleted the whole Mandelbrot kernel before this (0.000s).
- **Loop-invariant hoisting:** sha256 is a pure function of a constant buffer — LLVM computed it
  once and reused the result across all timed runs (`inf MB/s`). Each run now mutates one input
  byte first.
- **Cold-JIT bias:** 3 warmup runs discarded before the 5 timed runs, per kernel.
- **Async canvas submission:** WebKit canvas2d rasterizes behind `requestAnimationFrame`, so the
  render ramps measured command submission instead of presented frames (inflated WebView scores).
  Fixed with a per-frame 1×1 `getImageData` fence; render ramp warmup window + double-fail
  confirmation defend against startup jank and GC hitches.
- **Cross-port drift:** self-checks assert the sieve's prime count (π(2×10⁷) = 1,270,607) and the
  SHA-256 digest sum (16225487432) against the Kotlin/JDK reference in every port.
