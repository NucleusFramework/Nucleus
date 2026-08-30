# Agent playbook — Nucleus startup / memory bench

Contract: [`PROTOCOL.md`](PROTOCOL.md). Pasteable mission: [`PROMPT.md`](PROMPT.md).

This is **not** `examples/benchmark-demo` (warm CPU throughput). Here we measure cold start and RAM of a real Nucleus window across HotSpot, Leyden, and GraalVM Native Image.

## Mission

On **this** Windows or macOS machine:

1. Check out `feat/startup-memory-bench`.
2. Smoke: one `jvm-serial` run.
3. Full matrix, n=11, Serial only: `jvm-serial,leyden-serial,ni-serial`.
4. Publish with `--publish-as auto`, commit `runs/<os>-<arch>.{md,json}`, push.

Do not change the fixture, the probe, or the harness to "make numbers look better". If a Leyden run fails the AOT log check, it is a failed run.

## Variants (this assignment)

| id | what |
|---|---|
| `jvm-serial` | JDK 27 EA HotSpot, `-XX:+UseSerialGC`, no AOT cache |
| `leyden-serial` | same JDK, train once, run with `-XX:AOTCache` |
| `ni-serial` | GraalVM 25 native-image, Serial GC baked, `-Xmx256m` at runtime |

Skip `*-g1` on Windows and macOS. Native Image G1 is Oracle GraalVM + Linux only; the harness fails rather than silently measuring Serial under a G1 label.

## Toolchains

Two JDKs. Mixing them is the usual way this goes wrong.

| role | version | how the harness sees it |
|---|---|---|
| Gradle daemon / Kotlin DSL | **JDK 21** with `javac` | `JAVA_HOME` when you invoke `gradlew` / `run.py` |
| Measured HotSpot / Leyden | **JDK 27 EA** | `--jdk` / `MEASURE_JAVA_HOME` |
| Native Image | **GraalVM 25** (`native-image` on PATH of that home) | `--graalvm` / `GRAALVM_HOME` |

Kotlin DSL crashes on JDK 25. A JRE-only 21 (no `javac`) fails the Gradle toolchain.

Find homes rather than guessing:

- Windows: `%USERPROFILE%\.jdks\`, `C:\Program Files\Microsoft\jdk-21*`, `C:\Program Files\Java\`, `C:\Program Files\GraalVM\`
- macOS: `~/Library/Java/JavaVirtualMachines/`, `/Library/Java/JavaVirtualMachines/`, `~/.jdks/`, `/opt/homebrew/opt/openjdk@21`

JDK 27 EA: https://jdk.java.net/27/ — unpack, point `--jdk` at that tree (`bin/java` or `bin/java.exe` must exist).

GraalVM: the Nucleus plugin can auto-download Community Edition when a native-image task runs. If `native-image` is already installed, pass `--graalvm <home>`. Native builds must use that GraalVM as `JAVA_HOME` for the Gradle process that compiles the image (the harness does this).

Windows native-image also needs Visual Studio 2022 Build Tools with the C++ workload. macOS needs Xcode Command Line Tools (`xcode-select --install`).

Python 3.9+:

```text
Windows:  py -3 examples/startup-bench/run.py ...
macOS:    python3 examples/startup-bench/run.py ...
```

No pip packages. Stdlib only.

## Commands

Repo root. Close other GUI apps. AC power. One bench process at a time.

Smoke:

```bash
# Windows
py -3 examples/startup-bench/run.py --jdk %MEASURE_JAVA_HOME% --repeats 1 --variants jvm-serial

# macOS
python3 examples/startup-bench/run.py --jdk "$MEASURE_JAVA_HOME" --repeats 1 --variants jvm-serial
```

Expect `ok`, a TTFF around 0.2–2 s, and RSS/working-set in the hundreds of MB. If `timeout waiting for started.json`, read that run's `stderr.log` (Leyden train failures, missing display, wrong java).

Full matrix:

```bash
py -3 examples/startup-bench/run.py --jdk <jdk27> --graalvm <graalvm25> \
  --variants jvm-serial,leyden-serial,ni-serial \
  --repeats 11 --heap 256m --publish-as auto
```

Native-image compile is tens of minutes on a laptop. Leave it running. Reuse the binary on later repeats (`run.py` copies `nativeCompile/` and skips rebuild unless `--rebuild-native`).

`--publish-as auto` writes `examples/startup-bench/runs/<os>-<arch>.md` and `.json` (git-tracked). `results/` is local scratch and gitignored — do not commit it.

## What "ok" means

- `started.json` then `ready.json` appeared (first Compose frame, not window-mapped).
- Leyden runs: `aot.log` contains `Opened AOT cache` and does **not** contain `Unable to use AOT cache` / `Unable to map shared spaces` / GC dump-time mismatch.
- Native Image: `vmName` is `Substrate VM`. GC is Serial (`--gc=serial` / default). Binary + sibling `.dll`/`.dylib` copied as a directory, not the exe alone.
- Heap pin is `-Xmx256m` on every variant. Do **not** add `-Xms256m`.

## Windows RAM columns

| harness field | Windows API | Task Manager |
|---|---|---|
| `rss_kb` / `working_set_kb` | Working Set | Details → Working set |
| `uss_kb` / `private_working_set_kb` | Working Set Private (WMI) | Processes → Memory |
| `private_bytes_kb` | PrivateUsage | Commit — **not RAM** |

WMI Private Working Set is sometimes empty on the first query. If USS is `—`, still report Working Set. Never call Commit "RAM".

Leyden's `.aot` and the NI executable are file-backed maps. Private can look worse than Working Set / PSS. Publish the whole row.

## macOS RAM columns

`ps` RSS is the RSS analogue. `footprint -p <pid>` is recorded in `footprint_raw` when the tool exists. There is no PSS. Do not invent one.

If the native binary is killed at launch (Gatekeeper):

```bash
xattr -dr com.apple.quarantine examples/startup-bench/build/compose/tmp/main/graalvm/nativeCompile
```

## Non-negotiable

- n=11 for published numbers. n=1 or n=3 is smoke only; say so if that is all you got.
- Never `gradlew run` as the measured process.
- Never two GUI benches at once.
- Leyden train and run use the same GC (Serial here).
- Classpath is jars only (the harness already jars the fixture). Exploded `build/classes` makes Leyden exit with `Error: non-empty directory`.
- Native: `--no-configuration-cache --rerun` on `nativeImageCompile`. Copy the **whole** `nativeCompile/` dir.
- Gradle `JAVA_HOME` stays on 21. `--jdk` is only the measured VM.
- Do not enable ProGuard (JDK 27 EA does not have a working shrinker here).
- Do not mix JDK 25/26/27 in one table.

## After the run

1. Open `examples/startup-bench/runs/<os>-<arch>.md`. Check failed=0 on every variant. If Leyden failed, paste the `aot.log` excerpt in the commit body; do not average it in.
2. Stage only `runs/<os>-<arch>.md` and `runs/<os>-<arch>.json` (plus any harness fixes you actually needed).
3. Commit, no AI attribution, push `feat/startup-memory-bench`.

```text
bench(startup): <windows|macos> <arch> Serial JVM / Leyden / NI n=11
```

4. In the PR or chat, paste the markdown table. Mention JDK 27 build string, GraalVM build string, CPU, RAM, and that the disk series was warm.

## Pitfalls already hit (do not rediscover)

1. Measuring Gradle instead of the app (`gradlew run`).
2. Leyden train on exploded class directories.
3. G1 vs Serial mismatch between train and run.
4. `-Xms` equal to `-Xmx`, which hides Serial's smaller commit.
5. Calling Task Manager Memory (private WS) "total RAM".
6. Native Image G1 silently becoming Serial off Linux/Oracle.
7. First NI run RSS includes paging in the ~100 MB executable; p50 over 11 is the number, not run 1.
8. Kotlin DSL on JDK 25.
9. `JAVA_HOME` pointing at a plain Oracle JDK 25 with no `native-image` while compiling NI.

## Linux reference (already measured, n=3 smoke — not a target)

Hello World, `-Xmx256m`, warm disk, JDK 27 EA + GraalVM CE Serial:

| variant | TTFF p50 | RSS p50 | PSS p50 | USS p50 |
|---|---:|---:|---:|---:|
| jvm-serial | 847 ms | 255 MB | 148 MB | 132 MB |
| leyden-serial | 377 ms | 238 MB | 135 MB | 120 MB |
| ni-serial | 227 ms | 192 MB | 102 MB | 92 MB |

A Windows or Mac number that is 10× off this shape (seconds vs milliseconds, or gigabytes vs hundreds of MB) is a broken run, not a platform difference. Read `stderr.log` / `aot.log` before celebrating.
