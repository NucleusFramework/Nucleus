#!/usr/bin/env python3
"""Nucleus startup/memory harness. Spec: PROTOCOL.md. No third-party deps."""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
MAIN_CLASS = "startupbench.MainKt"
NATIVE_IMAGE_NAME = "startup-bench.exe" if os.name == "nt" else "startup-bench"
NATIVE_COMPILE_DIR = (
    ROOT / "examples" / "startup-bench" / "build" / "compose" / "tmp" / "main" / "graalvm" / "nativeCompile"
)
DEFAULT_HEAP = "256m"
SAMPLE_INTERVAL_S = 0.1
STARTED_TIMEOUT_S = 45.0
READY_TIMEOUT_S = 90.0
SETTLED_TIMEOUT_S = 90.0

GC_FLAGS = {
    "serial": ["-XX:+UseSerialGC"],
    "g1": ["-XX:+UseG1GC"],
    "parallel": ["-XX:+UseParallelGC"],
    "z": ["-XX:+UseZGC"],
}

ADD_OPENS = [
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
]

KB_RE = re.compile(r"^(\S+):\s+(\d+)\s+kB", re.MULTILINE)


def kb_map(text: str) -> dict[str, int]:
    return {m.group(1): int(m.group(2)) for m in KB_RE.finditer(text)}


def parse_smaps_rollup(text: str) -> dict[str, int]:
    raw = kb_map(text)
    uss = raw.get("Private_Clean", 0) + raw.get("Private_Dirty", 0)
    return {
        "rss_kb": raw.get("Rss", 0),
        "pss_kb": raw.get("Pss", 0),
        "uss_kb": uss,
        "rss_anon_kb": raw.get("Anonymous", 0),
        "rss_file_kb": raw.get("Pss_File", 0),
        "private_dirty_kb": raw.get("Private_Dirty", 0),
        "shared_clean_kb": raw.get("Shared_Clean", 0),
        "swap_kb": raw.get("Swap", 0),
        "swappss_kb": raw.get("SwapPss", 0),
    }


def parse_aot_log(text: str) -> dict[str, Any]:
    lower = text.lower()
    failed = any(
        needle in lower
        for needle in (
            "unable to use aot cache",
            "unable to map shared spaces",
            "unable to use aot code cache",
            "gc used during dump time",
        )
    )
    mapped = ("opened aot cache" in lower) or ("mapped" in lower and "aot" in lower)
    code_loaded = "loaded" in lower and "aot code" in lower
    code_rejected = "unable to use aot code cache" in lower
    class_space = None
    match = re.search(r"Reserved class_space_rs.*?(\d+)\) bytes", text)
    if match:
        class_space = int(match.group(1))
    return {
        "cache_ok": mapped and not failed,
        "failed": failed,
        "code_loaded": code_loaded and not code_rejected,
        "code_rejected": code_rejected,
        "class_space_reserved_bytes": class_space,
        "excerpt": "\n".join(text.splitlines()[:12]),
    }


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return float("nan")
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    idx = (len(sorted_vals) - 1) * p
    lo = math.floor(idx)
    hi = math.ceil(idx)
    if lo == hi:
        return sorted_vals[lo]
    frac = idx - lo
    return sorted_vals[lo] * (1.0 - frac) + sorted_vals[hi] * frac


def summarize(vals: list[float]) -> dict[str, float]:
    clean = [v for v in vals if v is not None and not math.isnan(v)]
    if not clean:
        return {"n": 0}
    ordered = sorted(clean)
    n = len(ordered)
    mean = statistics.fmean(ordered)
    stdev = statistics.stdev(ordered) if n > 1 else 0.0
    return {
        "n": n,
        "min": ordered[0],
        "p10": percentile(ordered, 0.10),
        "p50": percentile(ordered, 0.50),
        "p90": percentile(ordered, 0.90),
        "max": ordered[-1],
        "mean": mean,
        "stdev": stdev,
    }


def read_json(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def wait_for(path: Path, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if path.is_file() and path.stat().st_size > 0:
            # Atomic rename can leave a brief empty/partial file; require parseable JSON
            # when the name ends with .json.
            if path.suffix == ".json":
                if read_json(path) is not None:
                    return True
            else:
                return True
        time.sleep(0.02)
    return False


def sample_linux(pid: int) -> dict[str, Any] | None:
    rollup = Path(f"/proc/{pid}/smaps_rollup")
    status = Path(f"/proc/{pid}/status")
    try:
        if not status.exists():
            return None
        out: dict[str, Any] = {"pid": pid}
        if rollup.exists():
            parsed = parse_smaps_rollup(rollup.read_text(encoding="utf-8", errors="replace"))
            out.update(parsed)
        else:
            raw = kb_map(status.read_text(encoding="utf-8", errors="replace"))
            out["rss_kb"] = raw.get("VmRSS", 0)
            out["rss_anon_kb"] = raw.get("RssAnon", 0)
            out["rss_file_kb"] = raw.get("RssFile", 0)
        maps = Path(f"/proc/{pid}/smaps")
        aot_rss = 0
        if maps.exists():
            in_aot = False
            for line in maps.read_text(encoding="utf-8", errors="replace").splitlines():
                if line and line[0] != " " and not line.startswith("Vm"):
                    in_aot = ".aot" in line
                elif in_aot and line.startswith("Rss:"):
                    parts = line.split()
                    if len(parts) >= 2 and parts[1].isdigit():
                        aot_rss += int(parts[1])
        out["aot_rss_kb"] = aot_rss
        return out
    except OSError:
        return None


def sample_windows(pid: int) -> dict[str, Any] | None:
    import ctypes
    from ctypes import wintypes

    class PROCESS_MEMORY_COUNTERS_EX(ctypes.Structure):
        _fields_ = [
            ("cb", wintypes.DWORD),
            ("PageFaultCount", wintypes.DWORD),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
            ("PrivateUsage", ctypes.c_size_t),
        ]

    PROCESS_QUERY_INFORMATION = 0x0400
    PROCESS_VM_READ = 0x0010
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    handle = kernel32.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid)
    if not handle:
        return None
    try:
        counters = PROCESS_MEMORY_COUNTERS_EX()
        counters.cb = ctypes.sizeof(counters)
        ok = psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb)
        if not ok:
            return None
        private_ws = _windows_private_working_set(pid)
        return {
            "pid": pid,
            "working_set_kb": counters.WorkingSetSize // 1024,
            "peak_working_set_kb": counters.PeakWorkingSetSize // 1024,
            "private_bytes_kb": counters.PrivateUsage // 1024,
            "private_working_set_kb": None if private_ws is None else private_ws // 1024,
            "rss_kb": counters.WorkingSetSize // 1024,
            "uss_kb": None if private_ws is None else private_ws // 1024,
        }
    finally:
        kernel32.CloseHandle(handle)


def _windows_private_working_set(pid: int) -> int | None:
    # Task Manager "Memory" column. WMI raw counter; may be 0 on the first query.
    cmd = [
        "powershell",
        "-NoProfile",
        "-Command",
        f"(Get-CimInstance Win32_PerfRawData_PerfProc_Process | "
        f"Where-Object {{$_.IDProcess -eq {pid}}} | "
        f"Select-Object -First 1).WorkingSetPrivate",
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    text = proc.stdout.strip()
    if text.isdigit():
        return int(text)
    return None


def sample_macos(pid: int) -> dict[str, Any] | None:
    try:
        proc = subprocess.run(
            ["ps", "-o", "rss=", "-p", str(pid)],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    text = proc.stdout.strip()
    if not text.isdigit():
        return None
    rss_kb = int(text)
    out: dict[str, Any] = {"pid": pid, "rss_kb": rss_kb}
    footprint = shutil.which("footprint")
    if footprint:
        try:
            fp = subprocess.run(
                [footprint, "-p", str(pid)],
                capture_output=True,
                text=True,
                timeout=2,
                check=False,
            )
            out["footprint_raw"] = fp.stdout.strip()[:500]
        except (OSError, subprocess.TimeoutExpired):
            pass
    return out


def sample_pid(pid: int) -> dict[str, Any] | None:
    if sys.platform.startswith("linux"):
        return sample_linux(pid)
    if sys.platform == "darwin":
        return sample_macos(pid)
    if sys.platform == "win32":
        return sample_windows(pid)
    return None


def peak_rss(samples: list[dict[str, Any]]) -> float | None:
    values = [s["rss_kb"] for s in samples if s.get("rss_kb")]
    return max(values) / 1024.0 if values else None


def closest_sample(samples: list[dict[str, Any]], t_mono: float) -> dict[str, Any] | None:
    if not samples:
        return None
    return min(samples, key=lambda s: abs(s.get("t_mono", 0.0) - t_mono))


def drop_page_cache() -> bool:
    if not sys.platform.startswith("linux"):
        return False
    try:
        subprocess.run(["sync"], check=False)
        path = Path("/proc/sys/vm/drop_caches")
        path.write_text("3\n", encoding="ascii")
        return True
    except OSError:
        proc = subprocess.run(
            ["sudo", "-n", "sh", "-c", "sync; echo 3 > /proc/sys/vm/drop_caches"],
            check=False,
        )
        return proc.returncode == 0


def default_jdk() -> Path | None:
    ordered: list[Path] = []
    if os.environ.get("MEASURE_JAVA_HOME"):
        ordered.append(Path(os.environ["MEASURE_JAVA_HOME"]))
    ordered.append(Path.home() / ".jdks" / "openjdk-ea-27")
    if os.environ.get("JAVA_HOME"):
        ordered.append(Path(os.environ["JAVA_HOME"]))
    for path in ordered:
        exe = path / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.is_file():
            return path
    return None


def java_exe(jdk: Path) -> Path:
    name = "java.exe" if os.name == "nt" else "java"
    exe = jdk / "bin" / name
    if not exe.is_file():
        raise SystemExit(f"java not found at {exe}")
    return exe


def jcmd_exe(jdk: Path) -> Path | None:
    name = "jcmd.exe" if os.name == "nt" else "jcmd"
    exe = jdk / "bin" / name
    return exe if exe.is_file() else None


def gradlew() -> str:
    return str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))


def compile_fixture() -> Path:
    cmd = [
        gradlew(),
        ":examples:startup-bench:classes",
        ":examples:startup-bench:writeStartupClasspath",
        "--no-configuration-cache",
    ]
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, env=os.environ.copy(), check=True)
    cp_file = ROOT / "examples" / "startup-bench" / "build" / "startup-classpath.txt"
    if not cp_file.is_file():
        raise SystemExit(f"classpath file missing: {cp_file}")
    return cp_file


def default_graalvm() -> Path | None:
    ordered: list[Path] = []
    if os.environ.get("GRAALVM_HOME"):
        ordered.append(Path(os.environ["GRAALVM_HOME"]))
    ordered.append(Path.home() / ".jdks" / "graalvm-jdk-25")
    ordered.append(Path.home() / ".sdkman" / "candidates" / "java" / "current")
    name = "native-image.exe" if os.name == "nt" else "native-image"
    for path in ordered:
        if (path / "bin" / name).is_file():
            return path
    return None


def is_oracle_graalvm(home: Path) -> bool:
    java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file():
        return False
    proc = subprocess.run([str(java), "-version"], capture_output=True, text=True)
    return "oracle graalvm" in (proc.stderr or proc.stdout).lower()


def native_image_name() -> str:
    return NATIVE_IMAGE_NAME


def native_stamp(dir_path: Path) -> Path:
    return dir_path / "nucleus-startup-gc.txt"


def build_native(
    *,
    gc: str,
    dest: Path,
    heap: str,
    graalvm: Path | None,
    oracle: bool,
    rebuild: bool,
    log_path: Path,
) -> Path:
    """Compile (if needed) and copy the nativeCompile dir to dest. Returns the binary."""
    src_bin = NATIVE_COMPILE_DIR / native_image_name()
    stamped = native_stamp(NATIVE_COMPILE_DIR).read_text(encoding="utf-8").strip() if native_stamp(NATIVE_COMPILE_DIR).is_file() else None
    reusable = src_bin.is_file() and not rebuild and (stamped == gc or (stamped is None and gc == "serial"))
    if not reusable:
        cmd = [
            gradlew(),
            ":examples:startup-bench:nativeImageCompile",
            f"-Pgc={gc}",
            f"-PmaxHeap={heap}",
            "--no-configuration-cache",
            "--rerun",
        ]
        if oracle:
            cmd.append("-PgraalvmDistribution=oracle")
        env = os.environ.copy()
        # Prefer the GraalVM as the current JVM so Gradle cannot pick a plain JDK 25
        # that matches the toolchain spec but has no native-image (see AGENTS.md).
        if graalvm is not None:
            env["JAVA_HOME"] = str(graalvm)
            env["GRAALVM_HOME"] = str(graalvm)
        print(f"build native gc={gc} oracle={oracle}…", flush=True)
        print("+", " ".join(cmd), flush=True)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("wb") as log:
            proc = subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        if proc.returncode != 0:
            raise SystemExit(
                f"nativeImageCompile failed for gc={gc}; see {log_path}\n{_tail(log_path, 4000)}"
            )
        log_text = log_path.read_text(encoding="utf-8", errors="replace")
        if gc == "g1" and "Falling back to the Serial GC" in log_text:
            raise SystemExit(
                "ni-g1 was not baked — native-image fell back to Serial GC. "
                "G1 needs Oracle GraalVM on Linux. "
                f"See {log_path}"
            )
        if not src_bin.is_file():
            raise SystemExit(f"native binary missing at {src_bin}; see {log_path}")
        native_stamp(NATIVE_COMPILE_DIR).write_text(gc + "\n", encoding="utf-8")
    else:
        print(f"reusing native image at {src_bin} (gc={stamped or 'serial'})", flush=True)
        if not native_stamp(NATIVE_COMPILE_DIR).is_file():
            native_stamp(NATIVE_COMPILE_DIR).write_text(gc + "\n", encoding="utf-8")

    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(NATIVE_COMPILE_DIR, dest)
    binary = dest / native_image_name()
    if not binary.is_file():
        raise SystemExit(f"copied native dir is missing {binary.name}")
    binary.chmod(binary.stat().st_mode | 0o111)
    return binary


def gc_of(variant: str) -> str:
    if variant.endswith("g1"):
        return "g1"
    if variant.endswith("serial"):
        return "serial"
    raise SystemExit(f"unknown variant {variant}")


def is_leyden(variant: str) -> bool:
    return variant.startswith("leyden-")


def is_native(variant: str) -> bool:
    return variant.startswith("ni-")


def jvm_args(
    *,
    gc: str,
    heap: str,
    run_dir: Path,
    aot_cache: Path | None,
    aot_mode: str,
    compatibility: bool,
    nmt: bool,
) -> list[str]:
    args = [
        f"-Xmx{heap}",
        *GC_FLAGS[gc],
        *ADD_OPENS,
        f"-Xlog:aot=info:file={run_dir / 'aot.log'}::filesize=10M",
        f"-Xlog:gc=info:file={run_dir / 'gc.log'}::filesize=10M",
    ]
    if nmt:
        args.append("-XX:NativeMemoryTracking=summary")
    if compatibility:
        args.extend(["-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching"])
    if aot_mode == "train":
        if aot_cache is None:
            raise SystemExit("train requires aot cache path")
        args.extend([f"-XX:AOTCacheOutput={aot_cache}", "-Dnucleus.aot.mode=training"])
    elif aot_mode == "on":
        if aot_cache is None:
            raise SystemExit("leyden run requires aot cache path")
        args.extend([f"-XX:AOTCache={aot_cache}", "-Dnucleus.aot.mode=runtime"])
    return args


def _copy_smaps(pid: int | None, dest: Path) -> None:
    if pid is None:
        return
    src = Path(f"/proc/{pid}/smaps_rollup")
    try:
        if src.exists():
            dest.write_text(src.read_text(encoding="utf-8", errors="replace"), encoding="utf-8")
    except OSError:
        pass


def try_nmt(jdk: Path, pid: int, dest: Path) -> None:
    exe = jcmd_exe(jdk)
    if exe is None:
        return
    proc = subprocess.run(
        [str(exe), str(pid), "VM.native_memory", "summary"],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode == 0 and proc.stdout.strip():
        dest.write_text(proc.stdout, encoding="utf-8")


def terminate(proc: subprocess.Popen[bytes]) -> None:
    if proc.poll() is not None:
        return
    try:
        if os.name == "nt":
            proc.terminate()
        else:
            os.killpg(proc.pid, signal.SIGTERM)
    except (OSError, ProcessLookupError):
        pass
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            if os.name == "nt":
                proc.kill()
            else:
                os.killpg(proc.pid, signal.SIGKILL)
        except (OSError, ProcessLookupError):
            pass


def run_once(
    *,
    cmd: list[str],
    env: dict[str, str],
    run_dir: Path,
    jdk: Path,
    nmt: bool,
    drop_cache: bool,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    started = run_dir / "started.json"
    ready = run_dir / "ready.json"
    settled = run_dir / "settled.json"
    for leftover in (started, ready, settled, run_dir / "workload.json"):
        if leftover.exists():
            leftover.unlink()

    if drop_cache:
        dropped = drop_page_cache()
        if not dropped:
            print("warn: could not drop page cache (need root on Linux)", flush=True)

    stdout_path = run_dir / "stdout.log"
    stderr_path = run_dir / "stderr.log"
    t0 = time.perf_counter()
    out_f = stdout_path.open("wb")
    err_f = stderr_path.open("wb")
    popen_kwargs: dict[str, Any] = {
        "cwd": str(ROOT),
        "env": env,
        "stdout": out_f,
        "stderr": err_f,
    }
    if os.name != "nt":
        popen_kwargs["start_new_session"] = True
    proc = subprocess.Popen(cmd, **popen_kwargs)
    result: dict[str, Any] = {
        "exit_code": None,
        "ok": False,
        "error": None,
        "exec_to_started_ms": None,
        "exec_to_ready_ms": None,
        "samples": [],
        "first_frame_os": None,
        "idle_os": None,
        "after_gc_os": None,
        "peak_rss_mb": None,
        "aot": None,
        "ready": None,
        "settled": None,
        "workload": None,
        "nmt": None,
    }
    app_pid: int | None = None
    samples: list[dict[str, Any]] = []
    t_ready: float | None = None
    try:
        if not wait_for(started, STARTED_TIMEOUT_S):
            result["error"] = "timeout waiting for started.json"
            result["exit_code"] = proc.poll()
            result["stderr_tail"] = _tail(stderr_path)
            return result
        t_started = time.perf_counter()
        result["exec_to_started_ms"] = (t_started - t0) * 1000.0
        started_json = read_json(started)
        if started_json and "pid" in started_json:
            app_pid = int(started_json["pid"])
        else:
            app_pid = proc.pid

        while True:
            if ready.is_file() and t_ready is None and read_json(ready) is not None:
                t_ready = time.perf_counter()
                result["exec_to_ready_ms"] = (t_ready - t0) * 1000.0
                result["ready"] = read_json(ready)
                result["first_frame_os"] = sample_pid(app_pid)
                _copy_smaps(app_pid, run_dir / "smaps_rollup_first.txt")
                if nmt:
                    try_nmt(jdk, app_pid, run_dir / "nmt-first.txt")
            snap = sample_pid(app_pid)
            if snap is not None:
                snap["t_mono"] = time.perf_counter()
                samples.append(snap)
            now = time.perf_counter()
            if (
                t_ready is not None
                and result["idle_os"] is None
                and now - t_ready >= 5.0
            ):
                result["idle_os"] = snap or sample_pid(app_pid)
                _copy_smaps(app_pid, run_dir / "smaps_rollup_idle.txt")
                if nmt:
                    try_nmt(jdk, app_pid, run_dir / "nmt-idle.txt")
            if proc.poll() is not None:
                break
            if t_ready is None and (now - t0) > READY_TIMEOUT_S:
                result["error"] = "timeout waiting for ready.json"
                result["stderr_tail"] = _tail(stderr_path)
                break
            if t_ready is not None and (now - t_ready) > SETTLED_TIMEOUT_S:
                break
            if settled.is_file() and result["settled"] is None and read_json(settled) is not None:
                result["settled"] = read_json(settled)
                result["after_gc_os"] = sample_pid(app_pid)
            time.sleep(SAMPLE_INTERVAL_S)

        result["exit_code"] = proc.wait(timeout=15) if proc.poll() is None else proc.poll()
    except Exception as exc:  # noqa: BLE001 — record and kill, don't crash the matrix
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        terminate(proc)
        result["samples"] = samples[-5:]  # keep the tail in the per-run JSON; peaks computed below
        result["peak_rss_mb"] = peak_rss(samples)
        if result["idle_os"] is None and t_ready is not None:
            result["idle_os"] = closest_sample(samples, t_ready + 5.0)
        aot_log = run_dir / "aot.log"
        if aot_log.is_file():
            result["aot"] = parse_aot_log(aot_log.read_text(encoding="utf-8", errors="replace"))
        result["workload"] = read_json(run_dir / "workload.json")
        if result["settled"] is None:
            result["settled"] = read_json(settled)
        if result["ready"] is None:
            result["ready"] = read_json(ready)
        if nmt and app_pid is not None:
            nmt_path = run_dir / "nmt-first.txt"
            result["nmt"] = nmt_path.read_text(encoding="utf-8")[:4000] if nmt_path.is_file() else None

        out_f.close()
        err_f.close()

    aot = result.get("aot") or {}
    result["ok"] = (
        result["error"] is None
        and result["ready"] is not None
        and result["exec_to_ready_ms"] is not None
        and not aot.get("failed", False)
    )
    return result


def _tail(path: Path, n: int = 2500) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")[-n:]
    except OSError:
        return ""


def format_mb(sample: dict[str, Any] | None, key: str) -> str:
    if not sample or sample.get(key) is None:
        return "—"
    return f"{sample[key] / 1024.0:.1f}"


def default_run_name() -> str:
    plat = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
    arch = platform.machine().lower()
    arch = {"amd64": "x64", "x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(arch, arch)
    return f"{plat}-{arch}"


def publish_run(out_dir: Path, name: str) -> None:
    dest_dir = SCRIPT_DIR / "runs"
    dest_dir.mkdir(parents=True, exist_ok=True)
    md_src = out_dir / "summary.md"
    json_src = out_dir / "summary.json"
    md_dest = dest_dir / f"{name}.md"
    json_dest = dest_dir / f"{name}.json"
    header = (
        f"<!-- published from {out_dir.name} on {platform.platform()} -->\n"
        f"<!-- python {sys.version.split()[0]} -->\n\n"
    )
    md_dest.write_text(header + md_src.read_text(encoding="utf-8"), encoding="utf-8")
    shutil.copyfile(json_src, json_dest)
    print(f"Published {md_dest} and {json_dest}", flush=True)


def write_summary(out_dir: Path, report: dict[str, Any]) -> None:
    lines = [
        f"# Startup bench {report['id']}",
        "",
        f"- JDK: `{report['jdk']}`",
        f"- heap: `{report['heap']}`",
        f"- mode: `{report['mode']}`",
        f"- disk cache: `{report['cache']}`",
        f"- repeats: {report['repeats']}",
        f"- workload: `{report['workload']}`",
        "",
        "| variant | TTFF p50 ms | TTFF p90 | RSS p50 MB | PSS p50 MB | USS/private p50 MB | search p50 ms | failed |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for name, variant in report["variants"].items():
        stats = variant["stats"]
        lines.append(
            "| {name} | {ttff} | {ttff90} | {rss} | {pss} | {uss} | {search} | {failed} |".format(
                name=name,
                ttff=_fmt(stats.get("exec_to_ready_ms", {}).get("p50")),
                ttff90=_fmt(stats.get("exec_to_ready_ms", {}).get("p90")),
                rss=_fmt(stats.get("idle_rss_mb", {}).get("p50")),
                pss=_fmt(stats.get("idle_pss_mb", {}).get("p50")),
                uss=_fmt(stats.get("idle_uss_mb", {}).get("p50")),
                search=_fmt(stats.get("search_p50_ms", {}).get("p50")),
                failed=variant["failed_runs"],
            )
        )
    lines.append("")
    lines.append("Headline number is p50. Full distributions are in `summary.json`.")
    lines.append("")
    (out_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out_dir / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def _fmt(value: Any) -> str:
    if value is None:
        return "—"
    try:
        return f"{float(value):.1f}"
    except (TypeError, ValueError):
        return "—"


def mb_from_kb(sample: dict[str, Any] | None, key: str) -> float | None:
    if not sample or sample.get(key) is None:
        return None
    return sample[key] / 1024.0


def collect_stats(runs: list[dict[str, Any]]) -> dict[str, Any]:
    ok = [r for r in runs if r.get("ok")]
    def col(getter) -> list[float]:
        return [v for v in (getter(r) for r in ok) if v is not None]

    return {
        "exec_to_ready_ms": summarize(col(lambda r: r.get("exec_to_ready_ms"))),
        "ttff_from_jvm_ms": summarize(
            col(lambda r: (r.get("ready") or {}).get("timings", {}).get("ttffFromJvmStartMs"))
        ),
        "idle_rss_mb": summarize(col(lambda r: mb_from_kb(r.get("idle_os"), "rss_kb"))),
        "idle_pss_mb": summarize(col(lambda r: mb_from_kb(r.get("idle_os"), "pss_kb"))),
        "idle_uss_mb": summarize(
            col(
                lambda r: mb_from_kb(r.get("idle_os"), "uss_kb")
                or mb_from_kb(r.get("idle_os"), "private_working_set_kb")
            )
        ),
        "first_rss_mb": summarize(col(lambda r: mb_from_kb(r.get("first_frame_os"), "rss_kb"))),
        "peak_rss_mb": summarize(col(lambda r: r.get("peak_rss_mb"))),
        "heap_idle_mb": summarize(
            col(
                lambda r: ((r.get("settled") or {}).get("heapAtIdle") or {}).get("usedBytes", 0)
                / (1024 * 1024)
                if (r.get("settled") or {}).get("heapAtIdle")
                else None
            )
        ),
        "search_p50_ms": summarize(
            col(lambda r: ((r.get("workload") or {}).get("latencyMs") or {}).get("p50"))
        ),
    }


def machine_info(jdk: Path) -> dict[str, Any]:
    info: dict[str, Any] = {
        "platform": sys.platform,
        "jdk": str(jdk),
    }
    try:
        ver = subprocess.run(
            [str(java_exe(jdk)), "-version"],
            capture_output=True,
            text=True,
            check=False,
        )
        info["java_version"] = (ver.stderr or ver.stdout).strip()
    except OSError:
        pass
    if sys.platform.startswith("linux"):
        cpu = Path("/proc/cpuinfo")
        if cpu.exists():
            for line in cpu.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("model name"):
                    info["cpu"] = line.split(":", 1)[1].strip()
                    break
        mem = Path("/proc/meminfo")
        if mem.exists():
            for line in mem.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("MemTotal:"):
                    info["mem_total_kb"] = int(line.split()[1])
                    break
    return info


def train_aot(
    *,
    java: Path,
    classpath: str,
    gc: str,
    heap: str,
    cache: Path,
    compatibility: bool,
    nmt: bool,
    env_base: dict[str, str],
) -> None:
    cache.parent.mkdir(parents=True, exist_ok=True)
    if cache.exists():
        cache.unlink()
    train_dir = cache.parent / f"train-{gc}"
    train_dir.mkdir(parents=True, exist_ok=True)
    args = jvm_args(
        gc=gc,
        heap=heap,
        run_dir=train_dir,
        aot_cache=cache,
        aot_mode="train",
        compatibility=compatibility,
        nmt=nmt,
    )
    env = env_base.copy()
    env["NUCLEUS_STARTUP_PROBE_DIR"] = str(train_dir)
    env["NUCLEUS_STARTUP_EXIT_AFTER_MS"] = "none"
    cmd = [str(java), *args, "-cp", classpath, MAIN_CLASS]
    print(f"train {gc} → {cache}", flush=True)
    with (train_dir / "stdout.log").open("wb") as out, (train_dir / "stderr.log").open("wb") as err:
        proc = subprocess.run(cmd, cwd=ROOT, env=env, stdout=out, stderr=err, timeout=120)
    if proc.returncode != 0:
        tail = _tail(train_dir / "stderr.log") + "\n" + _tail(train_dir / "aot.log")
        raise SystemExit(f"AOT training failed (exit {proc.returncode}):\n{tail}")
    if not cache.is_file() or cache.stat().st_size == 0:
        raise SystemExit(
            f"AOT cache was not written: {cache}\n" + _tail(train_dir / "aot.log")
        )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jdk", type=Path, default=None, help="JDK used for the measured JVM / Leyden")
    p.add_argument("--graalvm", type=Path, default=None, help="GraalVM home for native-image compiles")
    p.add_argument("--heap", default=DEFAULT_HEAP, help="Pinned -Xmx (do not also pin -Xms)")
    p.add_argument(
        "--variants",
        default="jvm-serial,jvm-g1,leyden-serial,leyden-g1",
        help="Comma-separated variant ids (see PROTOCOL.md)",
    )
    p.add_argument("--repeats", type=int, default=3)
    p.add_argument("--mode", choices=("classpath", "dist", "native"), default="classpath")
    p.add_argument("--cache", choices=("warm", "cold"), default="warm")
    p.add_argument("--workload", choices=("none", "search"), default="none")
    p.add_argument("--proguard", action="store_true")
    p.add_argument("--native-aot-code", action="store_true", help="Do not pass -XX:-AOTAdapterCaching")
    p.add_argument("--rebuild-native", action="store_true", help="Force native-image recompile even if a matching binary exists")
    p.add_argument("--no-nmt", action="store_true")
    p.add_argument("--out", type=Path, default=None)
    p.add_argument(
        "--publish-as",
        default=None,
        help="Copy summary.md/json into runs/<name>.md (git-tracked). Use 'auto' for <os>-<arch>.",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    jdk = (args.jdk or default_jdk() or Path(os.environ.get("JAVA_HOME", ""))).expanduser()
    if not (jdk / "bin").is_dir():
        raise SystemExit(
            "No JDK. Pass --jdk /path/to/jdk-27 or set MEASURE_JAVA_HOME. "
            f"Looked at {jdk}"
        )
    java = java_exe(jdk)
    variants = [v.strip() for v in args.variants.split(",") if v.strip()]
    if args.mode == "dist":
        raise SystemExit("mode=dist is specified in PROTOCOL.md but not implemented yet; use classpath (JVM/Leyden) or include ni-* variants")

    needs_jvm = any(not is_native(v) for v in variants)
    native_variants = [v for v in variants if is_native(v)]
    graalvm = (args.graalvm or default_graalvm())
    if graalvm is not None:
        graalvm = graalvm.expanduser()
    oracle = graalvm is not None and is_oracle_graalvm(graalvm)
    if any(gc_of(v) == "g1" for v in native_variants) and not oracle:
        raise SystemExit(
            "ni-g1 requires Oracle GraalVM on Linux. Pass --graalvm /path/to/oracle-graalvm "
            "(GRAALVM_HOME is checked automatically)."
        )

    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or (SCRIPT_DIR / "results" / stamp)
    out_dir.mkdir(parents=True, exist_ok=True)

    classpath = ""
    if needs_jvm:
        cp_file = compile_fixture()
        classpath = cp_file.read_text(encoding="utf-8").strip()
    compatibility = not args.native_aot_code
    nmt = not args.no_nmt
    env_base = os.environ.copy()
    env_base.pop("_JAVA_OPTIONS", None)
    env_base.pop("JAVA_TOOL_OPTIONS", None)
    env_base.pop("JDK_JAVA_OPTIONS", None)

    report: dict[str, Any] = {
        "id": stamp,
        "schema": 1,
        "jdk": str(jdk),
        "graalvm": str(graalvm) if graalvm else None,
        "oracle_graalvm": oracle,
        "heap": args.heap,
        "mode": args.mode,
        "cache": args.cache,
        "repeats": args.repeats,
        "workload": args.workload,
        "proguard": args.proguard,
        "compatibility": compatibility,
        "nmt": nmt,
        "machine": machine_info(jdk),
        "variants": {},
    }

    caches: dict[str, Path] = {}
    if any(is_leyden(v) for v in variants):
        for variant in variants:
            if not is_leyden(variant):
                continue
            gc = gc_of(variant)
            if gc in caches:
                continue
            cache = out_dir / "aot" / f"{gc}.aot"
            train_aot(
                java=java,
                classpath=classpath,
                gc=gc,
                heap=args.heap,
                cache=cache,
                compatibility=compatibility,
                nmt=nmt,
                env_base=env_base,
            )
            caches[gc] = cache

    native_bins: dict[str, Path] = {}
    native_gcs_done: set[str] = set()
    for variant in native_variants:
        gc = gc_of(variant)
        if gc in native_gcs_done:
            native_bins[variant] = native_bins[f"ni-{gc}"]
            continue
        binary = build_native(
            gc=gc,
            dest=out_dir / "bins" / f"ni-{gc}",
            heap=args.heap,
            graalvm=graalvm,
            oracle=oracle or gc == "g1",
            rebuild=args.rebuild_native,
            log_path=out_dir / "bins" / f"build-ni-{gc}.log",
        )
        native_gcs_done.add(gc)
        native_bins[variant] = binary
        native_bins[f"ni-{gc}"] = binary

    for variant in variants:
        gc = gc_of(variant)
        leyden = is_leyden(variant)
        native = is_native(variant)
        runs = []
        failed = 0
        for i in range(1, args.repeats + 1):
            run_dir = out_dir / variant / f"{i:02d}"
            run_dir.mkdir(parents=True, exist_ok=True)
            env = env_base.copy()
            env["NUCLEUS_STARTUP_PROBE_DIR"] = str(run_dir)
            env["NUCLEUS_STARTUP_EXIT_AFTER_MS"] = "8000"
            env["NUCLEUS_STARTUP_IDLE_MS"] = "5000"
            if args.workload == "search":
                env["NUCLEUS_STARTUP_WORKLOAD"] = "search"
                env["NUCLEUS_STARTUP_IDLE_MS"] = "15000"
                env["NUCLEUS_STARTUP_EXIT_AFTER_MS"] = "20000"
            if native:
                binary = native_bins[variant]
                cmd = [str(binary), f"-Xmx{args.heap}"]
                libdir = str(binary.parent)
                if os.name != "nt":
                    env["LD_LIBRARY_PATH"] = libdir + os.pathsep + env.get("LD_LIBRARY_PATH", "")
                    if sys.platform == "darwin":
                        env["DYLD_LIBRARY_PATH"] = libdir + os.pathsep + env.get("DYLD_LIBRARY_PATH", "")
            else:
                jargs = jvm_args(
                    gc=gc,
                    heap=args.heap,
                    run_dir=run_dir,
                    aot_cache=caches.get(gc) if leyden else None,
                    aot_mode="on" if leyden else "off",
                    compatibility=compatibility,
                    nmt=nmt,
                )
                cmd = [str(java), *jargs, "-cp", classpath, MAIN_CLASS]
            print(f"run {variant} #{i}/{args.repeats}", flush=True)
            one = run_once(
                cmd=cmd,
                env=env,
                run_dir=run_dir,
                jdk=jdk,
                nmt=nmt and not native,
                drop_cache=args.cache == "cold",
            )
            (run_dir / "result.json").write_text(json.dumps(one, indent=2) + "\n", encoding="utf-8")
            runs.append(one)
            status = "ok" if one["ok"] else f"FAIL ({one.get('error') or 'aot'})"
            ttff = one.get("exec_to_ready_ms")
            rss = mb_from_kb(one.get("idle_os"), "rss_kb")
            pss = mb_from_kb(one.get("idle_os"), "pss_kb")
            print(
                f"  {status}  ttff={_fmt(ttff)} ms  rss={_fmt(rss)} MB  pss={_fmt(pss)} MB",
                flush=True,
            )
            if not one["ok"]:
                failed += 1
        report["variants"][variant] = {
            "gc": gc,
            "leyden": leyden,
            "native": native,
            "failed_runs": failed,
            "runs": runs,
            "stats": collect_stats(runs),
        }

    write_summary(out_dir, report)
    print(f"\nWrote {out_dir / 'summary.md'}", flush=True)
    print((out_dir / "summary.md").read_text(encoding="utf-8"))
    if args.publish_as:
        name = default_run_name() if args.publish_as == "auto" else args.publish_as
        publish_run(out_dir, name)
    return 0 if all(v["failed_runs"] == 0 for v in report["variants"].values()) else 1


if __name__ == "__main__":
    sys.exit(main())
