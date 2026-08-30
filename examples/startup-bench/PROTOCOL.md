# Nucleus startup & memory protocol

Contract for comparing **HotSpot**, **HotSpot + Leyden AOT cache**, and **GraalVM Native
Image** on a real Nucleus window, not a `main` that prints "Hello". The CPU suite in
`examples/benchmark-demo` is the opposite measurement (warm throughput). Do not mix the two.

Reference fixture: this module (`startupbench.MainKt`). Orchestrator: `./run.py`.
In-app probe: `StartupProbe` in `nucleus-application` (opt-in, no public API).

## Why Task Manager lies

Windows Task Manager's "Memory" column is **Private Working Set**. Linux `ps` RSS is
**all resident pages**, including file-backed maps. Leyden's `.aot` file and a native-image
binary are both mapped files:

| What you meant | Windows | Linux | macOS |
|---|---|---|---|
| "What Task Manager shows" | Private Working Set | USS (`Private_Clean + Private_Dirty`) | anonymous RSS |
| "RAM this process occupies, counting shared file maps once" | Working Set | **PSS** (`smaps_rollup`) | `phys_footprint` / `footprint -p` |
| Java heap live / reserved | MXBean used / committed | same | same |
| Virtual commit (not RAM) | Private Bytes / Commit Size | `VmSize` | — |

A Leyden run can look **+50 MB** in Task Manager and **−20 MB** in PSS/Working Set against
Native Image: the AOT cache is file-backed and shareable, so it is mostly absent from
private working set and fully present in RSS/PSS for a single process. **Always publish
the full row, never one column.**

Native Image G1 exists only on Oracle GraalVM **and** Linux. On Windows the native-image
side of this matrix is Serial (or Epsilon). JVM-side G1 vs Serial is valid everywhere.

## Variants

Pin every axis that is not the one under test.

| id | runtime | GC | AOT cache | notes |
|---|---|---|---|---|
| `jvm-g1` | HotSpot | `-XX:+UseG1GC` | off | JDK 27 default collector |
| `jvm-serial` | HotSpot | `-XX:+UseSerialGC` | off | |
| `leyden-g1` | HotSpot | G1 | trained + loaded | same GC at train and run |
| `leyden-serial` | HotSpot | Serial | trained + loaded | same GC at train and run |
| `ni-serial` | Native Image | `--gc=serial` (default) | n/a | |
| `ni-g1` | Native Image | `--gc=G1` | n/a | Oracle GraalVM, Linux only |

ProGuard is a **separate** axis (`--proguard`). JDK 27 EA has no working ProGuard as of
this protocol — ship and measure without it, and do not compare a ProGuard'd JDK 25
image to an unshrunk JDK 27 run.

Do not put JDK 25, 26 and 27 in the same table without labelling the version. JDK 27
defaults `UseCompactObjectHeaders=true`; JDK 25 does not. That alone moves heap RSS.

## Non-negotiable pins

1. **Same `-Xmx` on every variant.** Default `256m` for this Hello World. Do **not** set
   `-Xms` to the same value: that forces commit and hides Serial's smaller footprint,
   which is the thing being measured.
2. **Same GC at Leyden train and run.** Mismatch → cache refused (`GC used during dump
   time (G1) is not the same as runtime (Serial)`) or a crash. Confirm in `aot.log`.
3. **Same classpath / same distributable / same native binary** across the repeats of
   one variant. Rebuild between variants, not between repeats.
4. **Adapter caching.** Default of this harness is Nucleus `COMPATIBILITY`:
   `-XX:-AOTAdapterCaching`. That is what a shipped cache uses. `--native-aot-code`
   keeps the JDK default (faster on the training CPU, illegal-instruction crash on a
   narrower one before JDK 27).
5. **One GUI app at a time.** Compositor contention poisons first-frame time.

## Clocks

| metric | how | meaning |
|---|---|---|
| `exec_to_ready_ms` | harness `perf_counter` around `execve` → `ready.json` appears | what a user feels, including JVM boot |
| `ttffFromJvmStartMs` | `firstFrameEpochMs - RuntimeMXBean.startTime` | JVM start → first presented Compose frame |
| `ttffFromMainMs` | `nanoTime` from `nucleusApplication` entry → first frame | app init after the VM is up |

**First frame** is the first `withFrameNanos` after the first `DecoratedWindow` composes.
It is not native `onWindowReady` (mapped but possibly still blank) and not "main returned".

`started.json` is written at `nucleusApplication` entry (PID + GC). Use it to attach
samplers; do not treat it as first frame.

## Memory samples

The harness samples the **app PID** (from `started.json`, never the Gradle wrapper) every
100 ms from attach until process exit.

Required snapshots:

1. **first-frame** — sample closest to `ready.json`
2. **idle** — 5 s after first frame, no forced GC (default `NUCLEUS_STARTUP_IDLE_MS=5000`)
3. **after-gc** — `System.gc()` + 1.5 s (in-app `settled.json`; OS sample continues)

Each OS sample must include at least:

- Linux: `rss`, `pss`, `uss`, `rss_anon`, `rss_file`, `aot_rss` (maps matching `*.aot`)
- Windows: `working_set`, `private_working_set`, `private_bytes` (commit), `peak_working_set`
- macOS: RSS plus `footprint` when `/usr/bin/footprint` exists

Plus in-app MXBeans: heap used/committed, non-heap, pool breakdown.

Optional but required for a "2 GB on JDK 26" post-mortem: save `smaps_rollup` (or
Windows full counters) at first-frame and idle, and `jcmd <pid> VM.native_memory summary`
when `-XX:NativeMemoryTracking=summary` is on.

## Repeats and reporting

- **n = 11** for a number you would quote. **n = 3** is a smoke test, not a result.
- Two cache series, never mixed:
  - **warm disk**: OS page cache hot (normal second-launch). Discard nothing; report all 11.
  - **cold disk**: `sync && echo 3 > /proc/sys/vm/drop_caches` before every run (needs
    root). On Windows there is no equivalent in this harness — reboot or Sysinternals
    RAMMap "Empty Standby List", then say so in the report.
- Report **min, p10, p50 (median), p90, mean, stdev**. The headline number is the
  **median**. Never a single run, never "best of".
- A Leyden run whose `aot.log` contains `Unable to use AOT cache` / `Unable to map shared
  spaces` / `Unable to use AOT Code Cache` is a **failed run**, not a slow one. Record
  the log excerpt; do not average it with valid runs.

## Workload (search)

Startup RAM of Hello World and search latency are different questions.

- Default: no workload. Idle RAM is an empty Nucleus window.
- `--workload search` runs the in-process BM25 inverted index in this fixture
  **after** first frame and writes `workload.json` (`latencyMs.p50/p95/p99`). That is
  compiler/GC cost of a search-shaped loop, not Lucene.
- A production app (Lucene included) uses the same probe env vars and writes the same
  `workload.json` schema after *its* queries. Do not add Lucene to this fixture: it
  would move the Hello World baseline and needs native-image metadata.

Warmup queries are discarded inside the app. The harness still reports cold
`exec_to_ready_ms` separately — do not "help" Leyden by ignoring the first launch.

## AOT cache validity checklist

Parse `-Xlog:aot=info`:

- Cache mapped (`Mapped … from AOT` / `Opened AOT cache …`)
- GC match (no "dump time … is not the same as runtime")
- Code cache: loaded vs `Unable to use AOT Code Cache` (adapters, CPU features)
- JDK 25 + cached adapters + narrower CPU = `SIGILL` / `EXCEPTION_ILLEGAL_INSTRUCTION`
  (Nucleus issue #400). JDK 27 rejects the code region instead of executing it.

Training must actually paint the window (`aotTraining` in the fixture exits after 8 s).
A cache trained on a headless crash is not a cache.

## Launch modes

| mode | command | when the number counts |
|---|---|---|
| `classpath` | harness `exec`s `java -cp …` (not `gradlew run`) | GC / Leyden on a given JDK, fast loop. Classpath is **jars only** — Leyden refuses exploded `build/classes` directories (`Error: non-empty directory`) |
| `dist` | packaged `createDistributable` + optional `generateAotCache` | **what you ship** (jlink, `.cfg`, launcher) |
| `native` (`ni-serial` / `ni-g1`) | `nativeImageCompile`, copy the whole `nativeCompile/` dir (binary + `.so`/`.dll`), `exec` the image with `-Xmx` only | Native Image. GC is baked at compile time. `ni-g1` is Oracle GraalVM + Linux; elsewhere the plugin falls back to Serial and this harness **fails** rather than mislabel it. |

`gradlew run` is forbidden as a measured process: the wrapper PID is Gradle, and TTFF
includes configuration. The probe still works under `run` for debugging the JSON files.

## Machine state

Record in the report JSON: JDK `java.runtime.version`, `os.name`/`os.arch`, CPU model,
RAM, GC flags, `-Xmx`, AOT flags, ProGuard on/off, launch mode, cache series (warm/cold).
Run on AC power. Quit other GUI apps. Do not compare a laptop on battery to a desktop.

## Failure taxonomy (observed)

| symptom | usual cause |
|---|---|
| JDK 25 Leyden crash | AOT code cache without CPU-feature check; or GC mismatch |
| JDK 26 RSS ≈ 2 GB | inspect `smaps_rollup` + NMT: mapped cache, G1 regions, codecache. Do not call it "heap" until the dump says so. `aot.log` `Reserved class_space_rs … (1073741824) bytes` is a **virtual reservation**, not RSS — only Anonymous/`Private_Dirty` counts as RAM |
| JDK 27 Hello World works, ProGuard fails | shrinker, not Leyden |
| Task Manager disagrees with "total RAM" | private vs working set, see table above |
| Serial saves ~100 MB vs G1 on Hello World | G1 remembered sets / regions / concurrent threads. Re-check with pinned `-Xmx` and USS/PSS, not commit size |
