# Service Management (macOS)

The `service-management-macos` module provides a Kotlin binding for macOS `SMAppService` (macOS 13.0+). Combined with the Nucleus Gradle plugin's `launchAgents` DSL, it lets you:

- **Launch your app at login** (one-liner, no helper app needed)
- **Run background agents** even when the app is closed (periodic tasks, sync, notifications)
- **Register launch daemons** for privileged operations

The workflow is: **declare agents in Gradle** → **plugin embeds plists in the app bundle** → **register at runtime with `AppServiceManager`** → **macOS handles scheduling**.

!!! note "macOS 13+ only"
    `SMAppService` was introduced in macOS 13 (Ventura). On older systems, `AppServiceManager.isAvailable` returns `false`.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.service-management-macos:<version>")
}
```

## Quick Start: Launch at Login

The simplest use case — make your app start at user login with no helper app or plist:

```kotlin
// Register
AppServiceManager.register(AppService.MainApp)

// Unregister
AppServiceManager.unregister(AppService.MainApp)
```

No Gradle DSL needed for this — `AppService.MainApp` uses `SMAppService.mainApp` directly.

## Full Example: Background Agent

This is the typical workflow for a background task that runs every 15 minutes, even when the app is closed.

### Step 1 — Declare the agent in `build.gradle.kts`

The `launchAgents` DSL generates a plist and embeds it in `Contents/Library/LaunchAgents/` at packaging time:

```kotlin
nucleus.application {
    mainClass = "com.myapp.MainKt"

    nativeDistributions {
        packageName = "MyApp"
        macOS {
            bundleID = "com.myapp"

            launchAgents {
                agent("com.myapp.background-sync") {
                    // Arguments passed to main() when the agent fires
                    arguments("--sync")
                    // Interval in seconds (900 = 15 minutes)
                    startInterval(900)
                }
            }
        }
    }
}
```

The plugin automatically resolves the executable path from `packageName` — no need to set `bundleProgram` manually.

This generates `com.myapp.background-sync.plist` inside the packaged `.app` bundle.

### Step 2 — Handle the agent flag in `main()`

When launchd fires the agent, it launches your app with the arguments you declared:

```kotlin
fun main(args: Array<String>) {
    if ("--sync" in args) {
        // Running as a background agent — do work and exit
        performSync()
        return
    }

    // Normal UI startup
    application { /* ... */ }
}
```

### Step 3 — Activate the agent at runtime

The plist is in the bundle but **inactive** — macOS requires your app to explicitly activate it, and the user must approve it. This is how Apple enforces consent for background items since macOS 13.

Call `register()` to tell macOS to enable the agent (typically from a settings toggle in your UI):

```kotlin
val agent = AppService.Agent("com.myapp.background-sync")

// Activate — macOS will show a system notification asking the user to approve
AppServiceManager.register(agent)
```

The first time you call `register()`, macOS shows a system notification: _"MyApp wants to run in the background"_. The user can approve it immediately, or later via **System Settings → General → Login Items**.

Check the current state to know if the agent is active:

```kotlin
when (AppServiceManager.status(agent)) {
    AppServiceStatus.ENABLED -> {
        // User approved — the agent runs on schedule even when the app is closed
    }
    AppServiceStatus.REQUIRES_APPROVAL -> {
        // User hasn't approved yet — open System Settings for them
        AppServiceManager.openSystemSettingsLoginItems()
    }
    AppServiceStatus.NOT_REGISTERED -> { /* register() was never called */ }
    AppServiceStatus.NOT_FOUND -> { /* plist missing from the bundle */ }
}
```

To deactivate:

```kotlin
AppServiceManager.unregister(agent)
```

!!! note "Label must match the DSL"
    Use the same label in `AppService.Agent()` as in the Gradle DSL `agent()` declaration.

## Launch Agent DSL Reference

### `launchAgents { }` block

Declared inside `macOS { }` in your `nativeDistributions` block:

```kotlin
macOS {
    launchAgents {
        agent("com.myapp.sync") { /* ... */ }
        agent("com.myapp.cleanup") { /* ... */ }
    }
}
```

### Agent definition

| Method | Description |
|--------|-------------|
| `bundleProgram(path)` | Path to the executable relative to the app bundle root. Auto-resolved from `packageName` if omitted. |
| `arguments(vararg args)` | Additional arguments passed to the program. The bundle program path is automatically prepended in `ProgramArguments`. |
| `startInterval(seconds)` | Run at a fixed interval in seconds. Minimum recommended: 900 (15 min). |
| `runAtLoad(enabled)` | Run immediately when loaded by launchd. Default: `false`. |
| `keepAlive(enabled)` | Restart automatically if the process exits. Default: `false`. |
| `processType(type)` | Process type: `"Background"` (default), `"Standard"`, or `"Adaptive"`. |
| `calendar { }` | Add a calendar-based schedule. Can be called multiple times for an array of intervals. |

### Calendar intervals

```kotlin
agent("com.myapp.daily-report") {
    arguments("--report")
    calendar { hour = 9; minute = 30 }
}

agent("com.myapp.weekly-cleanup") {
    arguments("--cleanup")
    // Multiple calls create an array of intervals
    calendar { weekday = 1; hour = 18; minute = 0 } // Monday
    calendar { weekday = 5; hour = 18; minute = 0 } // Friday
}
```

| Property | Type | Description |
|----------|------|-------------|
| `month` | `Int?` | Month of the year (1-12). |
| `day` | `Int?` | Day of the month (1-31). |
| `weekday` | `Int?` | Day of the week (0 = Sunday, 1 = Monday, ..., 6 = Saturday). |
| `hour` | `Int?` | Hour of the day (0-23). |
| `minute` | `Int?` | Minute of the hour (0-59). |

Omitted fields act as wildcards.

## Compose Desktop Integration

A typical settings screen with a toggle for launch-at-login and background agent registration:

```kotlin
@Composable
fun ServiceManagementSettings() {
    val available = AppServiceManager.isAvailable

    if (!available) {
        Text("Service management requires macOS 13+")
        return
    }

    // Launch at Login toggle
    var loginStatus by remember { mutableStateOf(AppServiceManager.status(AppService.MainApp)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Launch at login", modifier = Modifier.weight(1f))
        Switch(
            checked = loginStatus == AppServiceStatus.ENABLED,
            onCheckedChange = { enabled ->
                if (enabled) AppServiceManager.register(AppService.MainApp)
                else AppServiceManager.unregister(AppService.MainApp)
                loginStatus = AppServiceManager.status(AppService.MainApp)
            }
        )
    }

    // Background agent toggle
    val agent = AppService.Agent("com.myapp.background-sync")
    var agentStatus by remember { mutableStateOf(AppServiceManager.status(agent)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Background sync", modifier = Modifier.weight(1f))
        Switch(
            checked = agentStatus == AppServiceStatus.ENABLED,
            onCheckedChange = { enabled ->
                if (enabled) AppServiceManager.register(agent)
                else AppServiceManager.unregister(agent)
                agentStatus = AppServiceManager.status(agent)
            }
        )
    }

    if (agentStatus == AppServiceStatus.REQUIRES_APPROVAL) {
        TextButton(onClick = { AppServiceManager.openSystemSettingsLoginItems() }) {
            Text("Approve in System Settings")
        }
    }
}
```

## Handling User Approval

Starting with macOS 13, background items require explicit user approval. The first time you register a service, macOS shows a system notification. If the user dismisses it, the status becomes `REQUIRES_APPROVAL` — the service is registered but will not run until approved.

```kotlin
val status = AppServiceManager.status(service)
if (status == AppServiceStatus.REQUIRES_APPROVAL) {
    // Open System Settings → General → Login Items for the user
    AppServiceManager.openSystemSettingsLoginItems()
}
```

## App Bundle Layout

After packaging, the Gradle plugin places generated plists at the correct location:

```
MyApp.app/
└── Contents/
    ├── MacOS/
    │   └── MyApp                          ← main executable
    └── Library/
        ├── LoginItems/
        │   └── com.myapp.helper.app/      ← LoginItem helpers
        ├── LaunchAgents/
        │   └── com.myapp.sync.plist       ← generated by launchAgents { } DSL
        └── LaunchDaemons/
            └── com.myapp.daemon.plist     ← Daemon plists
```

## API Reference

### `AppServiceManager`

| Member | Type / Returns | Description |
|--------|---------------|-------------|
| `isAvailable` | `Boolean` | `true` if SMAppService is available (macOS 13.0+ and native lib loaded). |
| `register(service)` | `Result<Unit>` | Registers a service. Returns failure with error description on error. |
| `unregister(service, callback)` | `Unit` | Unregisters a service asynchronously. Callback receives error or `null`. |
| `status(service)` | `AppServiceStatus` | Current registration status of a service. |
| `openSystemSettingsLoginItems()` | `Boolean` | Opens the Login Items pane in System Settings. |

### `AppService`

| Variant | Parameter | Description |
|---------|----------|-------------|
| `MainApp` | — | The app itself as a login item. No plist needed. |
| `LoginItem(bundleIdentifier)` | Bundle ID of the helper | Helper app in `Contents/Library/LoginItems/`. |
| `Agent(plistName)` | Plist filename | Launch agent in `Contents/Library/LaunchAgents/`. |
| `Daemon(plistName)` | Plist filename | Launch daemon in `Contents/Library/LaunchDaemons/`. |

### `AppServiceStatus`

| Value | Description |
|-------|-------------|
| `NOT_REGISTERED` | Service has not been registered, or was unregistered. |
| `ENABLED` | Service is registered and eligible to run. |
| `REQUIRES_APPROVAL` | Service requires user approval in System Settings. |
| `NOT_FOUND` | Framework could not locate the service (plist missing or identifier wrong). |

## ProGuard

When ProGuard is enabled, preserve the native bridge classes:

```proguard
-keep class dev.nucleusframework.nucleus.servicemanagement.** { *; }
```
