# App Metadata (`NucleusApp`)

Access application metadata injected by the Nucleus Gradle plugin at runtime.

## Installation

`NucleusApp` is part of `core-runtime`:

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.core-runtime:<version>")
}
```

## Usage

```kotlin
import dev.nucleusframework.nucleus.core.runtime.NucleusApp

// Application identifier (matches packageName in the Nucleus DSL)
val appId: String = NucleusApp.appId

// Optional metadata (null if not configured in the DSL)
val version: String? = NucleusApp.version
val vendor: String? = NucleusApp.vendor
val description: String? = NucleusApp.description

// Check if the plugin injected metadata
if (NucleusApp.isConfigured) {
    println("Running as packaged app: $appId v$version")
}
```

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `appId` | `String` | Application identifier. Falls back to main class name or `"NucleusApp"` if not injected. |
| `version` | `String?` | Application version from `packageVersion` in the DSL. `null` if not configured. |
| `vendor` | `String?` | Application vendor from `vendor` in the DSL. `null` if not configured. |
| `description` | `String?` | Application description from `description` in the DSL. `null` if not configured. |
| `isConfigured` | `Boolean` | `true` if the Nucleus plugin injected metadata (via system property or classpath resource). |

All properties are `@JvmStatic` and lazily initialized.

## How It Works

The Nucleus Gradle plugin injects metadata through two mechanisms:

### 1. System properties (during `run`)

When running via `./gradlew run`, the plugin adds JVM arguments:

```
-Dnucleus.app.id=MyApp
```

### 2. Classpath resource (in packaged builds)

At build time, the plugin generates a `nucleus/nucleus-app.properties` file that is included in the application's classpath:

```properties
app.id=MyApp
app.version=1.2.3
app.vendor=My Company
app.description=My awesome desktop app
```

### Resolution order

For each property, `NucleusApp` checks (first non-null wins):

1. System property (`nucleus.app.id`, `nucleus.app.version`, etc.)
2. Classpath resource (`nucleus/nucleus-app.properties`)
3. Legacy fallback (for `appId` only): main class from `sun.java.command`, then `"NucleusApp"`

## Use Cases

### About dialog

```kotlin
@Composable
fun AboutDialog() {
    Column {
        Text("${NucleusApp.appId}")
        NucleusApp.version?.let { Text("Version $it") }
        NucleusApp.vendor?.let { Text("By $it") }
    }
}
```

### Conditional logic based on packaging

```kotlin
if (NucleusApp.isConfigured) {
    // Running as packaged app — enable auto-update, telemetry, etc.
    initAutoUpdater()
} else {
    // Running in dev mode (./gradlew run)
    enableDevTools()
}
```

### Used by other Nucleus modules

`NucleusApp.appId` is consumed automatically by:

- **`SingleInstanceManager`** — uses `appId` as the default lock file identifier (via `AppIdProvider`)
- **`taskbar-progress`** — uses `appId` to resolve the Linux `.desktop` file for D-Bus progress
- **`updater-runtime`** — uses `appId` to determine the update marker storage directory
- **GraalVM `graalvm-runtime`** — uses `appId` to set the correct `WM_CLASS` on Linux native image
