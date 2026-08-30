# Prompt to paste on a Windows or macOS agent

Copy everything below the line into a new Claude / Codex / Cursor / Grok session on the target machine.

---

Checkout `feat/startup-memory-bench` in the Nucleus repo and run the startup/memory benchmark on this machine. Read `examples/startup-bench/AGENTS.md` and `examples/startup-bench/PROTOCOL.md` first. Follow them. Do not improvise a different measurement.

Goal: compare HotSpot Serial, Leyden AOT cache + Serial, and GraalVM Native Image Serial on a real Nucleus Hello World window.

Required matrix:

```
jvm-serial,leyden-serial,ni-serial
```

n=11, `--heap 256m`, warm disk, no ProGuard. Headline number is p50. Do not quote a single run.

Skip `ni-g1` / `jvm-g1` / `leyden-g1` on Windows and macOS (Native Image G1 is Oracle+Linux only; keep this run Serial-only so the three variants share a GC).

Smoke first (`--repeats 1 --variants jvm-serial`). If that writes `ready.json` and a TTFF, run the full matrix.

Then:

```
--publish-as auto
```

Commit `examples/startup-bench/runs/<os>-<arch>.md` and `.json` (nothing under `results/`) and push the branch. Do not add Co-Authored-By.

Gradle itself must run on JDK 21 with `javac` (Kotlin DSL breaks on 25). The measured JVM is JDK 27 EA, passed with `--jdk`. Native Image is GraalVM 25.

Never measure `gradlew run`: that PID is Gradle.

---
