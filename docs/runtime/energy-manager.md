# Energy Manager

The `energy-manager` module provides two capabilities for Compose Desktop applications:

1. **Energy efficiency mode** — signals the OS to run your process (or a specific thread) at reduced power, ideal when minimized or unfocused.
2. **Screen-awake (caffeine)** — prevents the display and system from entering sleep, useful for presentations, media playback, or long-running tasks.

### Platform support

| Feature | Windows | macOS | Linux |
|---------|---------|-------|-------|
| Full efficiency mode | EcoQoS + `IDLE_PRIORITY_CLASS` | `PRIO_DARWIN_BG` + QoS TIER_5 | nice +19, ioprio IDLE, timerslack 100ms |
| Light efficiency mode | EcoQoS only | `task_policy_set(TIER_5)` | nice +10 |
| Thread efficiency mode | EcoQoS + `THREAD_PRIORITY_IDLE` | `QOS_CLASS_BACKGROUND` | nice +19, ioprio IDLE, timerslack 100ms |
| Screen-awake | `SetThreadExecutionState` | `IOPMAssertion` | DBus (GNOME / logind) or X11 `XScreenSaverSuspend` |

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.energy-manager:<version>")
}
```

## Full vs Light Efficiency Mode

The module provides two levels of process-level efficiency:

| | Light | Full |
|---|---|---|
| **Use case** | Window lost focus — app still functional in background | Window minimized — no UI to render, deep power saving |
| **CPU** | Deprioritized via QoS hints | Lowest priority (idle class) |
| **I/O** | Normal | Throttled |
| **Network** | Normal | Throttled (macOS) |
| **Reversibility** | Instant | Instant |

**Recommendation**: use **light mode** when the window loses focus and **full mode** when the window is minimized. This gives the best balance between responsiveness and power savings — the app remains functional in the background (network requests, file I/O) while still signaling the OS that it can be deprioritized.

## Usage

### Recommended: light mode on focus loss, full mode on minimize

```kotlin
import dev.nucleusframework.nucleus.energymanager.EnergyManager
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

@Composable
fun App(state: WindowState) {
    DecoratedWindow(state = state, onCloseRequest = ::exitApplication) {
        var isWindowFocused by remember { mutableStateOf(window.isFocused) }

        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    isWindowFocused = true
                }
                override fun windowLostFocus(e: WindowEvent?) {
                    isWindowFocused = false
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        LaunchedEffect(state.isMinimized, isWindowFocused) {
            when {
                state.isMinimized -> {
                    EnergyManager.disableLightEfficiencyMode()
                    EnergyManager.enableEfficiencyMode()
                }
                !isWindowFocused -> {
                    EnergyManager.disableEfficiencyMode()
                    EnergyManager.enableLightEfficiencyMode()
                }
                else -> {
                    EnergyManager.disableEfficiencyMode()
                    EnergyManager.disableLightEfficiencyMode()
                }
            }
        }

        // Your app content
    }
}
```

### Using efficiency mode in coroutines

The `withEfficiencyMode` and `withLightEfficiencyMode` suspend helpers make it easy to run background work at reduced power inside coroutines.

#### `withEfficiencyMode` — dedicated thread

`withEfficiencyMode` creates a **dedicated single thread** with thread-level efficiency applied (`QOS_CLASS_BACKGROUND` on macOS, `THREAD_PRIORITY_IDLE` on Windows). The thread is disposed when the block completes. This is ideal for CPU-bound background tasks that should not interfere with the UI:

```kotlin
// Inside a coroutine scope
val result = EnergyManager.withEfficiencyMode {
    // Runs on a dedicated low-priority thread
    // Other coroutines on the default dispatcher are not affected
    computeHeavyReport()
}
// Back on the original dispatcher with full priority
updateUI(result)
```

Since the efficiency is applied at the **thread level**, it does not affect other threads or coroutines in your application. The dedicated thread is automatically shut down when the block finishes.

#### `withLightEfficiencyMode` — process-level, no thread pinning

`withLightEfficiencyMode` applies **process-level** light QoS for the duration of the block. Unlike `withEfficiencyMode`, it does not create a new thread — it runs on the current dispatcher. This is useful for sections of code where the entire process can be deprioritized without throttling I/O:

```kotlin
EnergyManager.withLightEfficiencyMode {
    // Process-level QoS is reduced (CPU deprioritized, I/O normal)
    syncDataFromServer()  // network is not throttled
    writeToDatabase()     // I/O is not throttled
}
// Process-level QoS restored to default
```

#### Choosing between the two

| | `withEfficiencyMode` | `withLightEfficiencyMode` |
|---|---|---|
| **Scope** | Thread-level (dedicated thread) | Process-level |
| **I/O throttled** | No (thread QoS doesn't throttle I/O) | No |
| **CPU impact** | Only the dedicated thread | Entire process |
| **Best for** | CPU-bound background work alongside a responsive UI | Batch operations where the whole app can be deprioritized |

### Using efficiency mode with raw threads

If you manage threads manually, you can use the thread-level API directly:

```kotlin
val thread = Thread {
    EnergyManager.enableThreadEfficiencyMode()
    try {
        performBackgroundWork()
    } finally {
        EnergyManager.disableThreadEfficiencyMode()
    }
}
thread.start()
```

The thread-level mode only affects the calling thread. On macOS this sets `QOS_CLASS_BACKGROUND` via `pthread_set_qos_class_self_np`, which confines the thread to E-cores without throttling I/O or network.

### Keeping the screen awake

```kotlin
// Prevent display sleep (e.g. during a presentation)
EnergyManager.keepScreenAwake()

// Allow sleep again
EnergyManager.releaseScreenAwake()

// Check current state
val active = EnergyManager.isScreenAwakeActive()
```

## API Reference

| Function | Returns | Description |
|----------|---------|-------------|
| `isAvailable()` | `Boolean` | `true` if the platform supports efficiency mode. |
| `enableEfficiencyMode()` | `Result` | Activates full process-level energy efficiency (CPU + I/O throttling). |
| `disableEfficiencyMode()` | `Result` | Restores default OS scheduling after full mode. |
| `enableLightEfficiencyMode()` | `Result` | Activates light process-level efficiency (CPU only, no I/O throttling). |
| `disableLightEfficiencyMode()` | `Result` | Restores default QoS tiers after light mode. |
| `enableThreadEfficiencyMode()` | `Result` | Activates efficiency mode for the calling thread only. |
| `disableThreadEfficiencyMode()` | `Result` | Restores default scheduling for the calling thread. |
| `withEfficiencyMode { }` | `T` | Runs a suspend block on a dedicated efficient thread. |
| `withLightEfficiencyMode { }` | `T` | Runs a suspend block with process-level light QoS. |
| `keepScreenAwake()` | `Result` | Prevents display and system sleep. |
| `releaseScreenAwake()` | `Result` | Releases the screen-awake inhibition. |
| `isScreenAwakeActive()` | `Boolean` | `true` if screen-awake is currently active. |

The `Result` data class:

| Field | Type | Description |
|-------|------|-------------|
| `success` | `Boolean` | `true` if the native call succeeded. |
| `errorCode` | `Int` | OS error code on failure, `0` on success. |
| `message` | `String` | Human-readable error description on failure. |

## How It Works

### Full process efficiency mode

#### Windows 11+ (full EcoQoS)

1. **`SetProcessInformation(ProcessPowerThrottling)`** — enables EcoQoS: reduced CPU frequency, E-core routing on hybrid processors. Triggers the **green leaf icon** in Task Manager (22H2+).
2. **`SetPriorityClass(IDLE_PRIORITY_CLASS)`** — lowers process base priority to 4.

On Windows 10 1709+, the same calls succeed but EcoQoS only applies on battery ("LowQoS").

#### macOS

1. **`setpriority(PRIO_DARWIN_BG)`** — CPU low priority, I/O throttling, network throttling, E-core confinement on Apple Silicon.
2. **`task_policy_set(TASK_BASE_QOS_POLICY)`** with `LATENCY_QOS_TIER_5` / `THROUGHPUT_QOS_TIER_5` — reinforces via Mach task QoS (timer coalescing, throughput hints).

#### Linux

1. **`setpriority(PRIO_PROCESS, 0, 19)`** — maximum nice value for lowest CPU priority.
2. **`prctl(PR_SET_TIMERSLACK, 100ms)`** — timer coalescing to reduce wakeups.
3. **`ioprio_set(IOPRIO_CLASS_IDLE)`** — I/O scheduling class idle.

All three are reversible without root on any mainstream distribution.

### Light process efficiency mode

#### macOS

**`task_policy_set(TASK_BASE_QOS_POLICY)`** with `LATENCY_QOS_TIER_5` / `THROUGHPUT_QOS_TIER_5` — deprioritizes CPU scheduling without enabling `PRIO_DARWIN_BG`. This means:

- CPU is deprioritized (timer coalescing, lower throughput QoS)
- I/O is **not** throttled
- Network is **not** throttled
- No E-core confinement

Disabled by resetting tiers to `UNSPECIFIED`.

#### Windows

EcoQoS only (no `IDLE_PRIORITY_CLASS`) — the green leaf in Task Manager with normal process priority. Disabled by clearing the `StateMask`.

#### Linux

**`setpriority(PRIO_PROCESS, 0, 10)`** — moderate CPU deprioritization (nice +10) without ioprio IDLE or timer slack. This means:

- CPU is moderately deprioritized
- I/O is **not** throttled (no ioprio change)
- Timer coalescing is **not** applied (no timerslack change)

Disabled by resetting nice to 0.

### Thread efficiency mode

| Platform | Mechanism |
|----------|-----------|
| Windows 11+ | `SetThreadInformation(ThreadPowerThrottling)` EcoQoS + `THREAD_PRIORITY_IDLE` |
| Windows 10 | `THREAD_PRIORITY_IDLE` only (no per-thread EcoQoS) |
| macOS | `pthread_set_qos_class_self_np(QOS_CLASS_BACKGROUND)` |
| Linux | Same as process-level (nice, ioprio, timerslack are per-thread on Linux) |

### Screen-awake (caffeine)

#### Windows

`SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED)` — immediate, no setup cost.

#### macOS

`IOPMAssertionCreateWithName(kIOPMAssertPreventUserIdleDisplaySleep)` via IOKit — prevents both display and system idle sleep. Released via `IOPMAssertionRelease`.

#### Linux

A composite backend tries three strategies in order:

1. **GNOME SessionManager** — DBus `Inhibit()` on the session bus with `INHIBIT_IDLE | INHIBIT_SUSPEND` flags. Released via `Uninhibit()` with the returned cookie.
2. **systemd-logind** — DBus `Inhibit("idle")` on the system bus. Stays active as long as the returned file descriptor is kept open.
3. **X11 XScreenSaverSuspend** — suspends the X11 screen saver via `libXss`.

All libraries (`libdbus-1`, `libX11`, `libXss`) are loaded at runtime via `dlopen()` — the module works even when some are not installed. Private DBus connections are used to avoid interference with the JVM's internal AT-SPI accessibility bus.

## Native Libraries

The module ships pre-built native binaries for:

- **Windows**: `nucleus_energy_manager.dll` (x64 + ARM64) — resolved dynamically via `GetProcAddress`
- **macOS**: `libnucleus_energy_manager.dylib` (x64 + arm64) — linked against IOKit/CoreFoundation
- **Linux**: `libnucleus_energy_manager.so` (x64 + aarch64) — loads `libdbus-1`, `libX11`, `libXss` via `dlopen()`

## ProGuard

When ProGuard is enabled, preserve the native bridge classes:

```proguard
-keep class dev.nucleusframework.nucleus.energymanager.** { *; }
```
