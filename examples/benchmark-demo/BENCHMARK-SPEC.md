# Nucleus Benchmark — portability spec

The point of this benchmark is a **fair compiler shootout** in a real desktop app: the same CPU
kernels, run on different runtimes, so the numbers reflect the *compiler/runtime*, not the
language's standard library. That only works if every port does **byte-identical work**. This
file is the contract.

Reference implementation: `src/main/kotlin/benchmarkdemo/Kernels.kt` (Kotlin/Compose).
Ports: `ports/swiftui/` (Swift/SwiftUI), `ports/tauri/` (Rust/Tauri), `ports/flutter/` (Dart/Flutter).

## Runtimes being compared

| Runtime | How to build | Compiler |
|---|---|---|
| JVM (JIT) | `./gradlew :examples:benchmark-demo:run` | HotSpot C2 |
| GraalVM Native (AOT) | `packageReleaseDistributionForCurrentOS` → run the native binary | GraalVM `-O3` (level 3) |
| SwiftUI | `swift run -c release` in `ports/swiftui/` | LLVM `-O` |
| Tauri | `cargo build --release` in `ports/tauri/src-tauri/` | rustc/LLVM `-O3 + LTO` |
| Flutter | `flutter run --release` in `ports/flutter/` | Dart AOT (gen_snapshot) |

Flutter note: Dart has no shared-memory threads — the `*_mt` benches use `Isolate.run` with
per-chunk buffer copies. That copy cost is deliberately included: it is the real price of
parallelism on this platform. Numbers are only valid in `--release` (Dart AOT); debug runs the
JIT/interpreter. Kernel correctness is verifiable on the VM with `dart run bin/check.dart`.

### ISA contract — platform parity ("real shipped app" mode)
Every runtime builds what its stack actually ships to macOS users. Swift/Rust target-triple
baseline = the M1 ISA (AES/SHA/LSE/CRC32 — every ARM Mac has them). GraalVM's default
`-march=compatibility` targets bare ARMv8.0 (portability no macOS app needs), so the benchmark
sets `march = NativeImageMarch.NATIVE` (`graalvm {}` DSL) — the honest equivalent of the Swift/Rust baseline on
this platform. Measured impact of that asymmetry (M-series, -O3 + recorded PGO): composite 238 →
254, mandelbrot ×2.

### GraalVM PGO (profile-guided optimization)
Real apps ship PGO'd native images; the benchmark records a true profile (replaces Oracle
GraalVM's default ML-inferred one). The `--headless` CPU suite is the training workload — it
exercises all 12 kernels:

```bash
./gradlew :examples:benchmark-demo:runWithPgoInstrument   # instrumented build + run, records graalvm/pgo/default.iprof
./gradlew :examples:benchmark-demo:nativeImageCompile      # applies graalvm/pgo/default.iprof
```

The Nucleus plugin applies `graalvm/pgo/default.iprof` automatically whenever the file exists.

## Suite overview (Geekbench-style spread)

**CPU, single-thread (9):** `mandelbrot`, `nbody`, `raytracer`, `matmul` (SGEMM), `blur`
(image filter), `sha256` (crypto/integer), `sieve` (integer/memory), `fft`, `pi` (SuperPI-style
fixed-point bignum, 10,000 digits via Machin — self-verifying against 3.141592653…).
**CPU, multi-thread (4):** `mandelbrot_mt`, `raytracer_mt`, `matmul_mt`, `blur_mt` — same work,
row-partitioned into `cores` contiguous chunks, one fork-join region per run.
**UI (4):** `max_particles_55fps`, `max_stars_55fps`, `max_texts_55fps`, `list_load` —
framework-rendering benches, see below.

Everything runs **automatically on launch**: CPU suite → FPS bench → list bench → JSON written to
`~/nucleus-benchmarks/<runtime>.json` (`jvm` / `graalvm` / `swiftui` / `tauri` / `flutter`).

## Measurement protocol (identical in every port)

- **Warmup:** run each CPU kernel 3× and discard. Non-negotiable — without it the JVM is measured
  cold and the comparison is a lie.
- **Measure:** run 5× more, keep the **minimum** wall time (= peak throughput).
- **Throughput:** `workUnits / bestSeconds / 1e6` (millions of work-units per second, higher = faster).
- **Composite CPU score:** geometric mean of the 13 CPU throughputs.
- Scalar `f64`/integer math only. No SIMD intrinsics, no GPU.
- **Anti-DCE checksum:** every kernel returns a checksum of its output; the runner consumes it
  (`black_box` / volatile write / `isNaN` guard). GraalVM `-O3` and rustc LTO otherwise delete
  entire kernels (observed: Mandelbrot at 0.000s).

## Shared RNG — MMIX LCG (Knuth)

```
state : u64 (wrapping arithmetic)
nextDouble(): state = state*6364136223846793005 + 1442695040888963407 ; return (state>>11) * 2^-53
range(lo,hi) = lo + (hi-lo)*nextDouble()
```

## Multithreading contract

`threads = available cores` (Kotlin `availableProcessors`, Swift `activeProcessorCount`, Rust
`available_parallelism`). Work rows `[0,H)` split into `ceil(H/threads)`-row contiguous chunks;
chunk `t` handles rows `[t*per, min(H,(t+1)*per))`. One parallel region per kernel invocation
(join before checksum). Kotlin: plain `Thread`s; Swift: `DispatchQueue.concurrentPerform`;
Rust: `std::thread::scope`; Dart: `Isolate.run` per chunk (buffers copied — no shared memory on
this platform, the copy cost is part of the measurement). Disjoint output slices — no locks.

## CPU kernels

### K1 mandelbrot — 800×800, MAX_ITER=1000
Escape-time at seahorse valley: CENTER=(-0.743643887037151, 0.13182590420533), SPAN_Y=0.0070,
square view. workUnits = 640,000 (Mpix/s). Checksum: strided u32 sum (stride 101).

### K2 nbody — N=1500, STEPS=120 (single-thread only)
DT=0.001, G=1, SOFTENING2=0.05, SEED=0x9E3779B97F4A7C15. Init order per body: x,y,z ∈ range(-1,1),
v=0, mass ∈ range(0.5,1.5). Force: f = G·m_j / (d²+ε²)^1.5, Euler integrate. `reset()` runs inside
the timed block. workUnits = N(N-1)·STEPS = 269,820,000 (M-inter/s). Checksum: Σ(x+y+z).

### K3 raytracer — 600×600, MAX_DEPTH=4
9 spheres at (gx·1.2, 0, -3+gz·1.2), r=0.5, gx,gz ∈ {-1,0,1}, colors [0xE06C75,0x98C379,0x61AFEF]
by (gx+gz+4)%3, reflectivity 0.5; ground plane y=-0.5 checkerboard 0.9/0.3, reflectivity 0.2;
point light (5,5,0), ambient 0.15, sky gradient on miss. workUnits = 360,000 (Mrays/s).

### K4 matmul — N=512, f64, ikj loop order
A[i·N+j] = ((i·31+j)%100)·0.01, B[i·N+j] = ((i·17+j)%100)·0.01 (filled once, outside timing).
C zeroed inside the timed block, then C += A×B with i-k-j loops (cache-friendly, no blocking).
workUnits = 2N³ = 268,435,456 (MFLOP/s). Checksum: strided f64 sum over C. MT: parallel over
C's rows.

### K5 blur — 1536×1536 grayscale f64, separable Gaussian radius 8
SEED=0xB1005EED (image filled once, outside timing — convolution cost is data-independent).
Weights: w[i] = exp(-d²/(2σ²)), σ=RADIUS/2=4, normalized to sum 1. Horizontal pass img→tmp then
vertical pass tmp→img, edge clamp. workUnits = W·H = 2,359,296 (Mpix/s). MT: rows parallel per
pass, join between passes.

### K6 sha256 — hand-rolled FIPS 180-4 over 8 MiB
Input: SEED=0xFEEDFACECAFEBEEF, per byte: advance LCG, take top 8 bits (filled once, outside
timing). **Each timed run first increments data[0] (wrapping)** — digest() is a pure function of
an otherwise-constant buffer and LLVM-class compilers hoist it out of the measurement loop
(observed: 0.000s in the Rust port). Digest all 131,072 blocks + 1 standard padding block. workUnits = 8,388,608 bytes (MB/s).
Checksum: Σ of the 8 output h-words (as u32). **Cross-port reference value: 16225487432** —
asserted in every port's self-check (Kotlin also asserts equality with `MessageDigest`).

### K7 sieve — Eratosthenes, LIMIT=20,000,000
Byte array LIMIT+1, zeroed inside the timed block; mark multiples from i²; count primes 2..LIMIT.
workUnits = 20,000,000 (Mn/s). Checksum = prime count = **1,270,607** (asserted in self-checks).

### K8 fft — iterative radix-2 Cooley-Tukey, N=2^20 complex
SEED=0x0123456789ABCDEF, re ∈ range(-1,1), im=0. `reset()` runs inside the timed block (in-place
transform). Bit-reversal permute, then len=2,4,…,N passes with accumulated twiddle
(w_len = e^(-2πi/len)). workUnits = (N/2)·log2(N) = 10,485,760 butterflies (Mbf/s).
Checksum: strided Σ(re+im).

### K9 pi — 10,000 digits of π (Machin, fixed-point bignum)
π = 16·arctan(1/5) − 4·arctan(1/239), Gregory series with fixed-point numbers stored as
`DIGITS/9 + 3` words in base 10⁹ (64-bit signed word arithmetic: divSmall/mulSmall/add/sub with
carries; series stops when the term underflows to zero). Self-verifying: words must start
3 | 141592653 | 589793238, else the kernel throws. workUnits = 10,000 (Mdig/s).
Checksum: strided word sum.

## UI benches (framework-specific by design)

### Ramp harness (all three render ramps)
1 s windows (fps = (frames-1)/elapsed). The **first window is warmup** — it absorbs startup jank
(canvas allocation, shader warmup) and is never judged. While a window averages ≥ 55 fps, grow
the active count **geometrically**: `active = min(MAX, active + max(STEP, active/4))`
(+25%/window, STEP as the minimum increment). A failing window is **re-run once at the same
count** — only two consecutive sub-threshold windows end the ramp (immunity to GC/compositor
hitches). **Metric = largest sustained count.**

**Async-pipeline fairness:** frame callbacks must be backpressured by the *presented* frame.
Compose `withFrameNanos`, Flutter `Ticker` and SwiftUI `TimelineView` are; WebKit canvas2d is
NOT — it records commands and rasterizes asynchronously on the GPU, letting rAF tick at full
speed while rendering lags. The JS port therefore forces completion with a 1×1
`getImageData` readback per frame. Symmetrically, ports must not add avoidable per-frame
allocation churn (e.g. Compose: flat `FloatArray` + native Skia `drawPoints`, never a boxed
`List<Offset>` of 500k points — that measures the GC, not the renderer).

**Graphics score** (`compositeGraphicsScore`), Geekbench-style anchor:
`1000 × cbrt((particles/25000) × (stars/200) × (texts/500))` — each ramp normalized to its START
count, so a machine that only sustains the starting workloads scores 1000. Higher = better.
Render ramps are only meaningful **run one app at a time** — concurrent windows fight for the
compositor and poison each other's fps.

### max_particles_55fps — animation capacity ramp
START=25,000, STEP=25,000, MAX=500,000 particles.
**Metric = largest sustained count** (particles, higher = better). Particle state: positions ∈
[0,1)², velocities ∈ [-0.2,0.2) units/s, LCG seed 42, full 500k capacity filled once at start
(init order: x,y,vx,vy per particle). Per frame: p += v·dt (dt = real frame delta, capped at
1/30), elastic wall bounce; draw 3px squares (#2563EB on #EEF0F3), one batched draw call/path;
canvas ≈600×400. Rendering: Compose `Canvas` + `drawPoints`, SwiftUI `TimelineView(.animation)`
+ `Canvas`, JS `requestAnimationFrame` + canvas2d. The ramp escapes the vsync cap: every port
keeps adding CPU work (physics + draw-list building on the UI thread) until frames start dropping.

### max_stars_55fps — vector path torture ramp
Same 1s-window/55fps ramp harness: START=200, STEP=200, MAX=200,000 rotating 12-segment stars
(outer radius 14px, inner 6px), rebuilt as a fresh path and filled **every frame** at 50% alpha
(#2563EB on #EEF0F3) — path construction + tessellation + blending torture. Init LCG seed 7:
x,y ∈ [0,1), ω ∈ [-2,2) rad/s; θ_i = ω_i·t, t advanced by the real frame delta (cap 1/30).

### max_texts_55fps — glyph/text pipeline ramp
Same harness: START=500, STEP=500, MAX=200,000 text labels; 100 distinct strings `"Bench#{i%100}"`
(~12px system font, #334155), layout cached per unique string where the framework allows, drawn
at x_i, (y0_i + 0.03·t) mod 1. Init LCG seed 11: x, y0 ∈ [0,1). Expected result, not a bug: the
WebView tends to win this ramp legitimately — browser glyph-atlas text is the most optimized text
path in existence, while SwiftUI must re-resolve per frame and Compose/Flutter pay per-draw
paragraph machinery. Repeated UI strings are exactly the workload browsers are tuned for.

### list_load — big-list load time
Build 50,000 fresh strings `"Item {i} — payload {(i·2654435761) mod 2^32}"` (generation is inside
the timing), hand them to the framework's list (Compose `LazyColumn`, SwiftUI `List`, DOM divs),
measure until the next rendered frame (Compose `withFrameNanos`; SwiftUI run-loop hop ×2 —
approximation; JS double `requestAnimationFrame`). Best of 3, milliseconds, **lower is better**.
Compose/SwiftUI virtualize natively, the DOM does not — that platform difference is part of what's
being measured.

## Results JSON

Written automatically at the end of every run to `~/nucleus-benchmarks/<runtime>.json`:

```json
{
  "schema": 1,
  "runtime": "jvm|graalvm|swiftui|tauri|flutter",
  "runtimeLabel": "…", "os": "…", "cpus": 10, "timestampMs": 1752680000000,
  "cpu": [
    {"name": "mandelbrot", "threads": 1, "unit": "Mpix/s",
     "workUnits": 640000, "bestSeconds": 0.147, "throughputM": 4.36}
  ],
  "compositeCpuScore": 259.5,
  "compositeGraphicsScore": 68400,
  "ui": [
    {"name": "max_particles_55fps", "unit": "particles", "value": 250000, "lowerIsBetter": false},
    {"name": "list_load", "unit": "ms", "value": 34.2, "lowerIsBetter": true}
  ]
}
```

Headless mode (`--headless`, JVM/Swift): CPU suite + self-checks only, `ui: []`.
