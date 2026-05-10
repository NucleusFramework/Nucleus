# Scheduler

The `scheduler` module registers background tasks with the OS so they run even when the application is closed. It provides a unified API across platforms, delegating to the native scheduler on each OS.

!!! warning "Not compatible with Mac App Store (.pkg)"
    On macOS, the scheduler writes plist files to `~/Library/LaunchAgents/` at runtime. This is **not allowed** in sandboxed App Store apps. If you distribute via `.pkg` / Mac App Store, use the [Service Management (macOS)](service-management-macos.md) module instead — it embeds agents in the app bundle and registers them via `SMAppService`, which is sandbox-compatible.

### Platform support

| Feature | Windows | macOS | Linux |
|---------|---------|-------|-------|
| Periodic tasks | Task Scheduler | launchd `StartInterval` | systemd user timers |
| Calendar tasks | Task Scheduler triggers | launchd `StartCalendarInterval` | systemd `OnCalendar=` |
| On-boot / login tasks | Task Scheduler logon trigger | launchd `RunAtLoad` | systemd `default.target` |
| Retry scheduling | One-shot task | One-shot launchd agent | One-shot systemd timer |
| Minimum interval[^min-interval] | 15 minutes | 15 minutes | 15 minutes |

[^min-interval]: Enforced at request construction — see [Minimum interval](#minimum-interval) for the exact `IllegalArgumentException` behavior.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.scheduler:<version>")
}
```

`kotlinx-serialization-json` is exposed transitively (via `api`) so you can annotate your input payloads with `@Serializable` without adding it manually. You still need the Kotlin serialization plugin in your module:

```kotlin
plugins {
    kotlin("plugin.serialization") version "<kotlin version>"
}
```

## Quick Start

### 1. Declare task IDs and a registry

Task identifiers are wrapped in the `TaskId` value class — declare them once as top-level constants and reuse them everywhere (enqueue site, registry, queries):

```kotlin
val SyncId = TaskId("sync")
val BackupId = TaskId("backup")
```

`TaskId` validates the underlying string against `[a-zA-Z0-9_-]+` in its `init` block; invalid IDs fail fast at construction.

### 2. Define a task

Implement the `DesktopTask` interface:

```kotlin
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        // Your background work here
        return TaskResult.Success
    }
}
```

### 3. Build a task registry

Map task IDs to their factories:

```kotlin
val registry = TaskRegistry.Builder()
    .register(SyncId) { SyncTask() }
    .register(BackupId) { BackupTask() }
    .build()
```

### 4. Handle scheduler invocations in `main()`

When the OS triggers a task, it re-launches your application with special arguments. You must detect this at the very top of `main()`:

```kotlin
fun main(args: Array<String>) {
    if (DesktopBootReceiver.isSchedulerInvocation(args)) {
        DesktopBootReceiver.handle(args = args, registry = registry)
        return // Don't open the UI
    }

    // Normal app startup...
}
```

!!! warning "This check must be at the top of `main()`"
    If you initialize the UI before checking for scheduler invocations, the app will open a window every time a background task fires.

### 5. Schedule tasks

```kotlin
val scheduler = DesktopTaskScheduler

// Periodic task — every hour
scheduler.enqueue(TaskRequest.periodic(SyncId, 1.hours))

// Calendar task — every day at 9:00
scheduler.enqueue(TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0))))

// Run at login
scheduler.enqueue(TaskRequest.onBoot(StartupCheckId))
```

## Usage

### Periodic tasks

Repeat at a fixed interval.

#### Minimum interval

The minimum interval is **15 minutes** — passing a smaller `Duration` to `TaskRequest.periodic(...)` throws `IllegalArgumentException` from the factory call (the validation happens when the request is built, not at `enqueue` time):

```kotlin
@Serializable
data class BackupInput(val target: String)

scheduler.enqueue(
    TaskRequest.periodic(BackupId, 30.minutes) {
        inputData(BackupInput(target = "/tmp/backup"))
        retryPolicy(RetryPolicy.ExponentialBackoff())
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)

// Throws IllegalArgumentException — the request is never built:
TaskRequest.periodic(BackupId, 1.minutes)
```

#### `runImmediately`

By default, a periodic task waits for the full interval before its first run. Pass `runImmediately()` to fire one extra trigger as soon as the OS registers the task — useful for sync-on-startup-style tasks where you want the user to see fresh data right away rather than after the first interval:

```kotlin
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        runImmediately()
    }
)
```

This is implemented natively per platform: `OnActiveSec=0` on systemd, `RunAtLoad=true` on launchd, `startDelay=0` on Windows Task Scheduler. The immediate trigger goes through the same code path as any other firing — it re-launches the app and runs through `DesktopBootReceiver.handle()`, which **does check [constraints](#constraints)** before invoking `doWork()`. If a constraint isn't satisfied at the immediate-trigger moment, the run is skipped exactly like any periodic firing.

`runImmediately` is a no-op for `calendar` and `onBoot` tasks (calendar already fires on absolute times, on-boot already fires at login).

### Calendar tasks

Fire on a calendar schedule using `CronExpression`. Times are expressed with `java.time.LocalTime` to keep validation free and avoid raw `Int` arguments:

```kotlin
import java.time.DayOfWeek
import java.time.LocalTime

// Every day at 9:00
scheduler.enqueue(TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0))))

// Weekdays at 18:00
scheduler.enqueue(TaskRequest.calendar(CleanupId, CronExpression.everyWeekdayAt(LocalTime.of(18, 0))))

// Every Monday at 8:30
scheduler.enqueue(
    TaskRequest.calendar(WeeklyId, CronExpression.everyWeekdayAt(DayOfWeek.MONDAY, LocalTime.of(8, 30)))
)

// Every hour
scheduler.enqueue(TaskRequest.calendar(HeartbeatId, CronExpression.everyHour()))
```

### On-boot tasks

Run once at user login:

```kotlin
scheduler.enqueue(TaskRequest.onBoot(LoginSyncId))
```

### Input data

Attach a `@Serializable` payload at enqueue time and decode it inside `doWork()`. The scheduler stores the payload as JSON in the per-task metadata file, so a fresh process spawned by the OS can re-read it.

!!! warning "Don't put secrets in `inputData`"
    The payload is persisted as plain JSON in the per-task `.properties` file on disk
    (see [Metadata storage](#metadata-storage)). Pass references — a config key, a
    user ID, an account name — and resolve secrets at execution time from your
    keychain / secure storage / env.

```kotlin
@Serializable
data class SyncInput(
    val endpoint: String,
    val accountId: String,
    val retries: Int = 3,
    val verbose: Boolean = false,
)

// Enqueue
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        inputData(
            SyncInput(
                endpoint = "https://api.example.com",
                accountId = "primary",
            )
        )
    }
)

// In the task
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        val input = context.inputData<SyncInput>()
            ?: return TaskResult.Failure("missing input")
        // Resolve the actual credentials from secure storage at run time:
        val token = credentialStore.tokenFor(input.accountId)
            ?: return TaskResult.Failure("no token for ${input.accountId}")
        // ... use input.endpoint / input.retries / input.verbose / token
        return TaskResult.Success
    }
}
```

If you cannot rely on the reified inline overload (e.g. you only have a `KSerializer<T>` at hand), pass it explicitly:

```kotlin
TaskRequest.periodic(SyncId, 1.hours) {
    inputData(SyncInput(...), SyncInput.serializer())
}

// Reading:
val input = context.inputData(SyncInput.serializer())
```

### Task results and retry

Return `TaskResult.Success`, `TaskResult.Failure`, or `TaskResult.Retry` from `doWork()`:

```kotlin
class SyncTask : DesktopTask {
    override suspend fun doWork(context: TaskContext): TaskResult {
        return try {
            performSync()
            TaskResult.Success
        } catch (e: IOException) {
            if (context.runAttemptCount < 3) {
                TaskResult.Retry("Network error: ${e.message}")
            } else {
                TaskResult.Failure("Gave up after ${context.runAttemptCount} attempts")
            }
        }
    }
}
```

Configure retry behavior at enqueue time:

```kotlin
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        retryPolicy(RetryPolicy.ExponentialBackoff(
            initialDelay = 30.minutes,
            maxAttempts = 3
        ))
        // Or fixed delay:
        // retryPolicy(RetryPolicy.Linear(delay = 15.minutes, maxAttempts = 5))
    }
)
```

### Managing tasks

```kotlin
// Check if available on this platform
scheduler.isAvailable()

// Check if a specific task is scheduled
scheduler.isScheduled(SyncId)

// Get task info
val info: TaskInfo? = scheduler.getTaskInfo(SyncId)
info?.let {
    println("State: ${it.state}")       // SCHEDULED, RUNNING, or INACTIVE
    println("Run count: ${it.runCount}")
    println("Last result: ${it.lastResult}")
}

// List all tasks
val all: List<TaskInfo> = scheduler.getAllTasks()

// Cancel a task
scheduler.cancel(SyncId)

// Cancel all tasks
scheduler.cancelAll()
```

### Existing task policy

Three modes control what happens when `enqueue` is called for a [`TaskId`](#taskid) that is already registered with the OS:

| Policy | OS-level schedule | Persisted `TaskData` / `Constraints` |
|--------|-------------------|--------------------------------------|
| `KEEP` (default) | unchanged | unchanged |
| `UPDATE_DATA` | unchanged | refreshed with the new request |
| `REPLACE` | torn down and re-created | refreshed with the new request |

`KEEP` is a strict no-op — the new request is ignored entirely. This is the safe default for "ensure this task exists" calls made on every app startup; you can call `enqueue` repeatedly without surprising side effects.

`UPDATE_DATA` keeps the existing trigger in place but pushes a fresh payload (e.g. an updated config) into the metadata file. Use it when only the data changed, not the schedule.

`REPLACE` tears down the OS-level task and re-creates it from the new request. Use it when the schedule itself (interval, cron expression, `runImmediately`) has changed.

```kotlin
// Default: ensure the task exists, but never touch it if it already does
scheduler.enqueue(TaskRequest.periodic(SyncId, 1.hours))

// Refresh inputData on an existing task without re-creating the schedule
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        inputData(SyncInput(endpoint = "https://api.example.com", accountId = "primary"))
        existingTaskPolicy(ExistingTaskPolicy.UPDATE_DATA)
    }
)

// Re-create the task with a new interval
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 2.hours) {
        existingTaskPolicy(ExistingTaskPolicy.REPLACE)
    }
)
```
### Constraints

Constraints let you declare conditions that must be met before a task executes — similar to Android's WorkManager constraints. Constraints are checked **at execution time**: the OS still triggers the process on schedule, but `doWork()` is only called when all constraints are satisfied.

The [`system-info`](system-info.md) module is included as a transitive dependency — no extra configuration needed.

#### Basic usage

```kotlin
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        constraints {
            requiredNetworkType = NetworkType.CONNECTED
            requiresBatteryNotLow = true
        }
    }
)
```

#### Available constraints

| Constraint | Type | Default | Description |
|------------|------|---------|-------------|
| `requiredNetworkType` | `NetworkType` | `NOT_REQUIRED` | Network connectivity requirement. |
| `requiresBatteryNotLow` | `Boolean` | `false` | Battery must be above 15 %. Devices without a battery satisfy this. |
| `requiresCharging` | `Boolean` | `false` | Device must be plugged in (charging or full). |
| `requiresDeviceIdle` | `Boolean` | `false` | User must be idle for at least 5 minutes. |
| `minimumStorageBytes` | `Long?` | `null` | Minimum available disk space (in bytes) on the app partition, or `null` for no requirement. |

#### Network types

| Value | Description |
|-------|-------------|
| `NOT_REQUIRED` | No network requirement (default). |
| `CONNECTED` | Any active network connection. |
| `UNMETERED` | Unmetered (non-cellular / non-tethered) connection only. |

#### Behavior when constraints are not met

| Task type | Behavior |
|-----------|----------|
| **Periodic** | Silently skipped — the next trigger re-checks constraints. |
| **Calendar / On-boot** | A retry is scheduled with backoff (5 minutes). |

In both cases, the metadata store records the skip with a `ConstraintsNotMet` result for observability.

#### Examples

```kotlin
// Only sync when connected to Wi-Fi and charging
scheduler.enqueue(
    TaskRequest.periodic(CloudSyncId, 2.hours) {
        constraints {
            requiredNetworkType = NetworkType.UNMETERED
            requiresCharging = true
        }
    }
)

// Heavy backup only when idle with at least 1 GB free
scheduler.enqueue(
    TaskRequest.calendar(NightlyBackupId, CronExpression.everyDayAt(LocalTime.of(3, 0))) {
        constraints {
            requiresDeviceIdle = true
            minimumStorageBytes = 1_073_741_824 // 1 GB
            requiresBatteryNotLow = true
        }
    }
)

// Pre-built Constraints object
val syncConstraints = Constraints(
    requiredNetworkType = NetworkType.CONNECTED,
    requiresBatteryNotLow = true,
)
scheduler.enqueue(
    TaskRequest.periodic(SyncId, 1.hours) {
        constraints(syncConstraints)
    }
)
```

## API Reference

### `TaskId`

`@JvmInline value class TaskId(val value: String)` — a strongly-typed identifier validated against `[a-zA-Z0-9_-]+` in its `init` block. Used as a filename / systemd unit / launchd label / Windows Task Scheduler task name, so the character set is intentionally narrow.

### `DesktopTaskScheduler`

| Method | Returns | Description |
|--------|---------|-------------|
| `isAvailable()` | `Boolean` | `true` if the platform has a supported scheduler backend. |
| `enqueue(request)` | `Boolean` | Registers a task with the OS. Returns `true` on success. |
| `cancel(taskId)` | `Boolean` | Removes a scheduled task. Returns `true` if found. |
| `cancelAll()` | `Unit` | Removes all tasks for this application. |
| `isScheduled(taskId)` | `Boolean` | `true` if the task is currently registered with the OS. |
| `getTaskInfo(taskId)` | `TaskInfo?` | Runtime info about a task, or `null` if not found. |
| `getAllTasks()` | `List<TaskInfo>` | All tasks registered by this application. |

### `TaskRequest`

Created via factory methods (all take a `TaskId`):

| Factory | Parameters | Description |
|---------|-----------|-------------|
| `periodic(taskId, interval, configure)` | interval: `Duration` (min 15 min) | Repeats at a fixed interval. |
| `calendar(taskId, expression, configure)` | expression: `CronExpression` | Fires on a calendar schedule. |
| `onBoot(taskId, configure)` | — | Runs at user login. |

Builder DSL:

| Method | Description |
|--------|-------------|
| `inputData(value)` | Attach a `@Serializable` payload (reified inline — type resolved at the call site). |
| `inputData(value, serializer)` | Attach a `@Serializable` payload with an explicit `KSerializer<T>`. |
| `retryPolicy(policy)` | Set the retry strategy (`ExponentialBackoff` or `Linear`). |
| `existingTaskPolicy(policy)` | `KEEP` (default), `UPDATE_DATA`, or `REPLACE` — see [Existing task policy](#existing-task-policy). |
| `runImmediately(enabled)` | Fire one extra trigger as soon as the OS registers the task (periodic only — see [`runImmediately`](#runimmediately)). Default: `false`. |
| `constraints { ... }` | Set execution constraints via DSL (see [Constraints](#constraints)). |
| `constraints(constraints)` | Set a pre-built `Constraints` object. |

### `CronExpression`

All factory methods take a `java.time.LocalTime` for the time-of-day component.

| Factory | Expression | Description |
|---------|-----------|-------------|
| `everyDayAt(time)` | `*-*-* HH:MM:00` | Every day at the given time. |
| `everyWeekdayAt(time)` | `Mon..Fri *-*-* HH:MM:00` | Monday through Friday. |
| `everyWeekdayAt(day, time)` | `Mon *-*-* HH:MM:00` | Specific day of the week. |
| `everyHour()` | `*-*-* *:00:00` | Wall-clock top of each hour (00:00, 01:00, 02:00, …). **Not** "1 hour after enqueue" — for a fixed delay since enqueue, use `TaskRequest.periodic(id, 1.hours)`. |

### `DesktopTask`

| Method | Returns | Description |
|--------|---------|-------------|
| `doWork(context)` | `TaskResult` | Suspend function performing the background work. |

### `TaskContext`

| Property / Method | Type | Description |
|-------------------|------|-------------|
| `taskId` | `TaskId` | The unique task identifier. |
| `rawInputData` | `TaskData` | Opaque container holding the serialized payload. Prefer the typed `inputData<T>()` extension below; access this directly only when you need the raw [`TaskData`](#taskdata). |
| `runAttemptCount` | `Int` | 1-based attempt counter (increments on retry). |
| `inputData<T>()` (extension) | `T?` | Decodes the payload using the contextually-resolved serializer. Returns `null` when no payload was attached. |
| `inputData(serializer)` (extension) | `T?` | Decodes the payload using an explicit `KSerializer<T>`. |

### `TaskData`

Opaque container for a `@Serializable` payload — wraps a JSON `String?`.

| Method | Returns | Description |
|--------|---------|-------------|
| `isEmpty()` / `isNotEmpty()` | `Boolean` | Whether a payload was attached. |
| `decode<T>()` (extension) | `T?` | Decodes the payload using the contextually-resolved serializer. |
| `decode(serializer)` | `T?` | Decodes with an explicit `KSerializer<T>`. |
| `TaskData.EMPTY` (companion) | `TaskData` | A `TaskData` with no payload. |
| `TaskData.of(value)` (companion, reified) | `TaskData` | Encodes `value` using the contextually-resolved serializer. |
| `TaskData.of(value, serializer)` (companion) | `TaskData` | Encodes `value` with an explicit `KSerializer<T>`. |

### `TaskResult`

| Variant | Description |
|---------|-------------|
| `Success` | Task completed successfully. |
| `Failure(message)` | Permanent failure — will not be retried. `message` is required (pass `""` if you really have nothing to say). |
| `Retry(message)` | Temporary failure — retried per `RetryPolicy`. `message` is required. |

### `LastTaskResult`

Typed outcome of the last run, exposed by [`TaskInfo.lastResult`](#taskinfo). Distinct from `TaskResult` because it carries a `ConstraintsNotMet` state for runs that were skipped before `doWork()` was invoked.

| Variant | Description |
|---------|-------------|
| `Success` | The last run returned `TaskResult.Success`. |
| `Failure(message)` | The last run returned `TaskResult.Failure`. |
| `Retry(message)` | The last run returned `TaskResult.Retry`. |
| `ConstraintsNotMet(unsatisfied)` | The task was skipped because the listed constraints were not satisfied. |

### `TaskInfo`

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `TaskId` | The unique task identifier. |
| `state` | `TaskState` | `SCHEDULED`, `RUNNING`, or `INACTIVE`. |
| `lastRunMs` | `Long?` | Epoch millis of the last execution. |
| `nextRunMs` | `Long?` | Epoch millis of the next execution (if known). |
| `runCount` | `Int` | Total number of completed executions. |
| `lastResult` | `LastTaskResult?` | Typed outcome of the last run, or `null` if the task has never run. |

### `RetryPolicy`

| Variant | Parameters | Default |
|---------|-----------|---------|
| `ExponentialBackoff` | `initialDelay: Duration`, `maxAttempts: Int` | 30 min, 3 attempts |
| `Linear` | `delay: Duration`, `maxAttempts: Int` | 15 min, 3 attempts |

### `Constraints`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `requiredNetworkType` | `NetworkType` | `NOT_REQUIRED` | Required network connectivity. |
| `requiresBatteryNotLow` | `Boolean` | `false` | Battery above 15 % (no battery = satisfied). |
| `requiresCharging` | `Boolean` | `false` | Device must be plugged in. |
| `requiresDeviceIdle` | `Boolean` | `false` | User idle ≥ 5 minutes. |
| `minimumStorageBytes` | `Long?` | `null` | Minimum free disk space (in bytes) on the app partition, or `null` for no requirement. |

| Constant / Method | Description |
|--------------------|-------------|
| `Constraints.NONE` | No constraints — the task always executes. |
| `hasConstraints()` | Returns `true` if at least one constraint is set. |

### `NetworkType`

| Value | Description |
|-------|-------------|
| `NOT_REQUIRED` | No network requirement. |
| `CONNECTED` | Any active network connection. |
| `UNMETERED` | Unmetered connection only. |

## How It Works

### Execution flow

When a task fires, the OS re-launches your application binary with arguments:

```
/path/to/MyApp --nucleus-scheduler-run <taskId>
```

`DesktopBootReceiver.isSchedulerInvocation()` detects these arguments and `handle()` resolves the task from the registry, loads its context from the metadata store, checks any [constraints](#constraints) against the current system state, and — if all constraints are satisfied — calls `doWork()`. If the raw string from the command line does not match the `TaskId` regex, the invocation is rejected before any task lookup.

```mermaid
flowchart TD
    Trigger([OS scheduler fires<br/>systemd / launchd / Task Scheduler])

    Trigger -->|Linux / Windows| Wrapper{Wrapper script:<br/>app binary still exists?}
    Trigger -->|macOS| Binary

    Wrapper -- no --> SelfDestruct["Self-destruct:<br/>unregister timer/task<br/>+ delete metadata + script"]
    Wrapper -- yes --> Binary

    Binary["App binary launches with<br/>--nucleus-scheduler-run &lt;taskId&gt;"]
    Binary --> CheckArg{isSchedulerInvocation args?}
    CheckArg -- false --> NormalUI[Normal UI startup]
    CheckArg -- true --> ParseId{Valid TaskId regex?}

    ParseId -- no --> Reject[log warning + exit]
    ParseId -- yes --> LoadCtx["Load TaskContext +<br/>Constraints from metadata"]

    LoadCtx --> ConstraintCheck{All constraints<br/>satisfied?}
    ConstraintCheck -- no --> RecordSkip["record ConstraintsNotMet<br/>(calendar/onBoot also schedule retry)"]
    ConstraintCheck -- yes --> DoWork["task.doWork(context)"]

    DoWork --> Result{TaskResult}
    Result -- Success --> RecSuccess[recordSuccess]
    Result -- Failure --> RecFail[recordFailure]
    Result -- Retry --> RecRetry["recordRetry +<br/>scheduleRetry"]
```

### Metadata storage

Task input data and run history are persisted per-platform:

| Platform | Location |
|----------|----------|
| macOS | `~/Library/Application Support/nucleus/scheduler/<appId>/` |
| Linux | `~/.local/share/nucleus/scheduler/<appId>/` (or `$XDG_DATA_HOME`) |
| Windows | `%LOCALAPPDATA%\nucleus\scheduler\<appId>\` |

Each task gets a single `<taskId>.properties` file. The serialized input payload is stored as a JSON string under the reserved `_inputDataJson` key, the typed `LastTaskResult` is stored as JSON under `_lastResult`, alongside run-count, attempt count, timestamps and other internal bookkeeping keys (all prefixed with `_`).

### Platform details

#### macOS (launchd)

Generates plist files in `~/Library/LaunchAgents/` with label `dev.nucleusframework.nucleus.<appId>.<taskId>`. Managed via a JNI bridge (`MacOSLaunchdSchedulerJni`) that uses Foundation and ServiceManagement APIs for plist writing, job status queries, and next-fire-time computation. Falls back to `launchctl` shell commands when the native library is unavailable.

#### Linux (systemd)

Creates systemd user service and timer units in `~/.config/systemd/user/` (respects `$XDG_CONFIG_HOME`). Managed via a JNI D-Bus bridge (`LinuxSystemdSchedulerJni`) that talks directly to `org.freedesktop.systemd1.Manager` through GLib/GIO — no subprocess invocation. Calendar tasks map directly to `OnCalendar=` expressions. Unit names follow the pattern `nucleus-<appId>-<taskId>.service` / `.timer`. The `.service`'s `ExecStart=` points to the [self-destructing wrapper script](#orphan-cleanup-after-uninstall), not the application binary directly.

#### Windows (Task Scheduler)

Registers tasks under `\Nucleus\<appId>\` via a JNI bridge (`WindowsTaskSchedulerJni`) that calls the Task Scheduler 2.0 COM API (`ITaskService`, `ITaskFolder`, `ITaskDefinition`) — no `schtasks.exe` subprocess. Supports periodic, daily, weekly, logon, and one-shot triggers natively. The task action runs the [self-destructing wrapper script](#orphan-cleanup-after-uninstall) via `wscript.exe` (Windows-subsystem host — no console window flashes when the task fires) instead of invoking the application binary directly.

### Orphan cleanup after uninstall

Users uninstall apps without thinking about background scheduled tasks. Without explicit cleanup, the OS would keep firing schedules pointing to a missing executable forever. Nucleus handles this differently per platform:

- **Linux** and **Windows** — the scheduler does *not* register the application binary directly. It writes a tiny wrapper script (`<taskId>.sh` in `nucleus/scheduler/<appId>/scripts/` on Linux, `<taskId>.vbs` in `%LOCALAPPDATA%\nucleus\scheduler\<appId>\scripts\` on Windows) and registers *that* with systemd / Task Scheduler. The wrapper checks whether the application binary still exists before invoking it. If it's gone, the wrapper **self-destructs**: it disables and deletes the systemd `.timer` / `.service` units (Linux) or removes the COM tasks under `\Nucleus\<appId>\` (Windows) via the same Schedule.Service API used to create them, deletes the persisted metadata, and finally removes itself. Net result: the next time the OS triggers a task whose app has been uninstalled, the schedule cleans itself up and stops firing.
- **macOS** — **no automatic cleanup.** Unlike the Linux/Windows wrapper trick, the agent's `ProgramArguments` points directly at the application binary so the entry stays visible under its real name in System Settings → "Allow in the Background". The cost: when the user trashes the .app bundle, the orphaned `.plist` in `~/Library/LaunchAgents/` is **never** reclaimed by macOS, and launchd keeps attempting to spawn the missing binary forever — throttled by `ThrottleInterval` (10 s by default), logging `cannot spawn` to `system.log` on every attempt. SMAppService (macOS 13+) does not eliminate this either, and Apple ships no guidance for "graceful uninstall of a LaunchAgent" — the macOS ecosystem treats orphaned LaunchAgents as a known limitation that an explicit cleanup step has to handle. The mitigation Nucleus offers is **in-app**: call `DesktopTaskScheduler.cancelAll()` from your app's settings ("Disable background tasks") or from any in-app sign-out / reset flow — this unloads the agents and removes the plists cleanly while the binary is still around. If the user does a plain drag-to-trash without that step, the orphan leaks; the leftover plist then has to be removed manually:

  ```bash
  rm ~/Library/LaunchAgents/dev.nucleusframework.nucleus.<appId>.<taskId>.plist
  ```

  The failure mode in the meantime is the well-known throttled-log-spam — not a crash and not a security or correctness issue.

## Testing

The `scheduler-testing` module provides two levels of test support, inspired by Android's `work-testing`.

### Installation

```kotlin
dependencies {
    testImplementation("dev.nucleusframework:nucleus.scheduler-testing:<version>")
}
```

### Level 1 — Unit-test a task in isolation

`TestTaskRunner` executes a `DesktopTask.doWork()` with a fabricated `TaskContext`, no scheduler involved:

```kotlin
@Serializable
data class SyncInput(val endpoint: String)

val result = TestTaskRunner.runTask(
    task = SyncTask(),
    taskId = TaskId("sync"),
    inputData = TaskData.of(SyncInput(endpoint = "https://test.api")),
    runAttemptCount = 1,
)
assertEquals(TaskResult.Success, result)
```

### Level 2 — In-memory scheduler for integration tests

`TestDesktopTaskScheduler` replaces the real platform backend so you can enqueue, query, and execute tasks entirely in memory. It supports **virtual time** and **execution history**.

```kotlin
val SyncId = TaskId("sync")

val registry = TaskRegistry.Builder()
    .register(SyncId) { SyncTask() }
    .build()

TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()

    // Enqueue through the real API — routed to in-memory backend
    DesktopTaskScheduler.enqueue(TaskRequest.periodic(SyncId, 2.hours))
    assertTrue(DesktopTaskScheduler.isScheduled(SyncId))

    // Advance virtual time — automatically triggers periodic tasks
    val results = testScheduler.advanceTimeBy(6.hours, registry)
    assertEquals(3, results.size) // fired at 2h, 4h, 6h

    // Inspect execution history
    val history = testScheduler.getExecutionHistory(SyncId)
    assertEquals(3, history.size)
    assertEquals(TaskResult.Success, history.last().result)
} // .close() restores the platform-default backend
```

#### Testing calendar and on-boot tasks

`advanceTimeBy` only triggers **periodic** tasks — calendar and on-boot tasks depend on absolute time or OS events, not intervals. Use `runTask()` directly for those:

```kotlin
TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()

    // Calendar task
    DesktopTaskScheduler.enqueue(
        TaskRequest.calendar(ReportId, CronExpression.everyDayAt(LocalTime.of(9, 0)))
    )
    val result = testScheduler.runTask(ReportId, registry)
    assertEquals(TaskResult.Success, result)

    // On-boot task
    DesktopTaskScheduler.enqueue(TaskRequest.onBoot(StartupCheckId))
    val bootResult = testScheduler.runTask(StartupCheckId, registry)
    assertEquals(TaskResult.Success, bootResult)
}
```

#### Retry tracking

When `doWork()` returns `TaskResult.Retry`, the `runAttemptCount` is automatically incremented for the next execution. On `Success` or `Failure`, it resets to 1:

```kotlin
TestDesktopTaskScheduler().use { testScheduler ->
    testScheduler.install()
    DesktopTaskScheduler.enqueue(TaskRequest.periodic(FlakyId, 1.hours))

    // advanceTimeBy triggers the task each hour
    testScheduler.advanceTimeBy(3.hours, registry)

    val history = testScheduler.getExecutionHistory(FlakyId)
    assertEquals(1, history[0].runAttemptCount) // attempt 1 → Retry
    assertEquals(2, history[1].runAttemptCount) // attempt 2 → Retry
    assertEquals(3, history[2].runAttemptCount) // attempt 3 → Success
}
```

#### Testing constraints

Pass a `TestConstraintChecker` to the `TestDesktopTaskScheduler` constructor — both lifecycles are then co-managed by the same `use { }` block (`install` registers both, `close` uninstalls both):

```kotlin
val constraints = TestConstraintChecker()

TestDesktopTaskScheduler(constraintChecker = constraints).use { testScheduler ->
    testScheduler.install()

    DesktopTaskScheduler.enqueue(
        TaskRequest.periodic(SyncId, 1.hours) {
            constraints {
                requiredNetworkType = NetworkType.CONNECTED
            }
        }
    )

    // Network is down — fires are skipped (one ConstraintsNotMet record per fire)
    constraints.networkConnected = false
    val skipped = testScheduler.advanceTimeBy(2.hours, registry)
    assertEquals(2, skipped.size)
    assertTrue(skipped.all { it.result is LastTaskResult.ConstraintsNotMet })

    // Network is back — task actually executes
    constraints.networkConnected = true
    val ran = testScheduler.advanceTimeBy(1.hours, registry)
    assertEquals(1, ran.size)
    assertEquals(LastTaskResult.Success, ran.first().result)
}
```

!!! note "`ConstraintChecker` is `@InternalSchedulerApi`"
    The interface that `TestConstraintChecker` implements (`ConstraintChecker`) is annotated `@InternalSchedulerApi` and exists purely as a test seam. It is **not** meant for production gating — for things like maintenance windows, gate at the `enqueue` / `cancel` call site instead.

`TestConstraintChecker` exposes mutable properties matching each constraint:

| Property | Type | Default | Maps to |
|----------|------|---------|---------|
| `networkConnected` | `Boolean` | `true` | `NetworkType.CONNECTED` |
| `networkUnmetered` | `Boolean` | `true` | `NetworkType.UNMETERED` |
| `batteryLevel` | `Float?` | `1.0f` | `requiresBatteryNotLow` (threshold: 15 %) |
| `isCharging` | `Boolean` | `false` | `requiresCharging` |
| `idleTimeSeconds` | `Long` | `0` | `requiresDeviceIdle` (threshold: 300 s) |
| `availableStorageBytes` | `Long` | `MAX_VALUE` | `minimumStorageBytes` |

### `TestTaskRunner`

| Method | Returns | Description |
|--------|---------|-------------|
| `runTask(task, taskId?, inputData?, runAttemptCount?)` | `TaskResult` | Calls `doWork()` with a controlled `TaskContext`. `taskId` defaults to `TaskId("test-task")`; `inputData` defaults to `TaskData.EMPTY`. |

### `TestDesktopTaskScheduler`

| Method / Property | Returns | Description |
|-------------------|---------|-------------|
| `install()` | `Unit` | Swaps the `DesktopTaskScheduler` backend with this in-memory implementation. |
| `uninstall()` | `Unit` | Restores the platform-default backend. Also called by `close()`. |
| `constraintChecker` | `TestConstraintChecker?` | Optional checker passed to the constructor. When non-null, `install()` / `close()` co-manage its lifecycle. When null, all constraints are treated as satisfied. |
| `runTask(taskId, registry)` | `TaskResult?` | Executes the task immediately. Returns `null` when constraints are not satisfied (a `LastTaskResult.ConstraintsNotMet` `ExecutionRecord` is appended to the history in that case). |
| `advanceTimeBy(duration, registry)` | `List<ExecutionRecord>` | Advances virtual time and triggers every periodic task whose interval has elapsed. Includes records for fires skipped due to unsatisfied constraints (`result is LastTaskResult.ConstraintsNotMet`). |
| `getExecutionHistory(taskId)` | `List<ExecutionRecord>` | Full execution history for a task. |
| `getAllExecutionHistory()` | `List<ExecutionRecord>` | Execution history across all tasks, sorted chronologically. |
| `getEnqueuedRequest(taskId)` | `TaskRequest?` | Returns the enqueued request for assertions. |
| `getEnqueuedRequests()` | `List<TaskRequest>` | Returns all enqueued requests. |
| `currentVirtualTimeMs` | `Long` | The current virtual time in milliseconds. |

### `ExecutionRecord`

Returned by `advanceTimeBy()`, `getExecutionHistory()`, and `getAllExecutionHistory()`.

| Property | Type | Description |
|----------|------|-------------|
| `taskId` | `TaskId` | The task that fired. |
| `result` | `LastTaskResult` | Typed outcome — `Success`, `Failure`, `Retry` (when `doWork()` ran) or `ConstraintsNotMet` (when execution was skipped before `doWork()`). |
| `runAttemptCount` | `Int` | The 1-based attempt number at the time of the firing. |
| `virtualTimeMs` | `Long` | The virtual time (in milliseconds) at which the firing occurred. |

### `TestConstraintChecker`

| Method / Property | Type | Description |
|-------------------|------|-------------|
| `networkConnected` | `Boolean` | Simulated network connectivity (default: `true`). |
| `networkUnmetered` | `Boolean` | Simulated unmetered status (default: `true`). |
| `batteryLevel` | `Float?` | Simulated battery level 0.0–1.0, `null` = no battery (default: `1.0f`). |
| `isCharging` | `Boolean` | Simulated charging state (default: `false`). |
| `idleTimeSeconds` | `Long` | Simulated idle time in seconds (default: `0`). |
| `availableStorageBytes` | `Long` | Simulated disk space in bytes (default: `MAX_VALUE`). |
| `install()` / `uninstall()` | `Unit` | Lifecycle hooks. Usually called for you when you pass the checker to `TestDesktopTaskScheduler(constraintChecker = ...)`; call them manually only if you need to install the checker outside of a scheduler `use { }` block. |

All standard `DesktopTaskScheduler` methods (`enqueue`, `cancel`, `isScheduled`, `getTaskInfo`, `getAllTasks`) work as expected after `install()`.
