# Startup & memory bench

End-to-end protocol for **cold start** and **RAM** of a Nucleus Hello World across
HotSpot, Leyden AOT cache, and GraalVM Native Image. Spec: [`PROTOCOL.md`](PROTOCOL.md).

This is not the CPU shootout in `examples/benchmark-demo`.

```bash
# smoke (3 repeats, JVM Serial vs G1, JDK 27 EA if present)
python3 examples/startup-bench/run.py --repeats 3 --variants jvm-serial,jvm-g1

# quoteable Leyden vs HotSpot on JDK 27, Serial + G1, 11 repeats
python3 examples/startup-bench/run.py \
  --jdk ~/.jdks/openjdk-ea-27 \
  --variants jvm-serial,jvm-g1,leyden-serial,leyden-g1 \
  --repeats 11

# Native Image (Serial; G1 needs Oracle GraalVM on Linux)
python3 examples/startup-bench/run.py --jdk ~/.jdks/openjdk-ea-27 \
  --variants jvm-serial,leyden-serial,ni-serial --repeats 11

# same, then the in-process search workload after first frame
python3 examples/startup-bench/run.py --workload search --repeats 11 \
  --variants leyden-serial,ni-serial
```

Self-check of the parsers (no GUI):

```bash
python3 examples/startup-bench/test_harness.py
```

Scratch output: `examples/startup-bench/results/<timestamp>/` (gitignored).

Published numbers for another OS: `--publish-as auto` writes `examples/startup-bench/runs/<os>-<arch>.md`. Agents on Windows/macOS should follow [`PROMPT.md`](PROMPT.md) / [`AGENTS.md`](AGENTS.md).
