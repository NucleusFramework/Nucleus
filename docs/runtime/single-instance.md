# Single Instance

Enforce that only one instance of your application runs at a time.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.core-runtime:<version>")
}
```

```kotlin
import dev.nucleusframework.nucleus.core.runtime.SingleInstanceManager
```

`SingleInstanceManager` is a Kotlin `object` (singleton). It uses file-based locking to ensure single-instance behavior across platforms.

## Usage

```kotlin
fun main() {
    application {
        var restoreRequested by remember { mutableStateOf(false) }

        val isSingle = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    // Called on a NEW instance when the restore request file is created
                    // `this` is the Path to the restore request file
                    // You can write deep link data here for the primary instance to read
                },
                onRestoreRequest = {
                    // Called on the PRIMARY instance when another instance tries to start
                    // `this` is the Path to the restore request file
                    restoreRequested = true
                },
            )
        }

        if (!isSingle) {
            // Another instance is already running — this process will exit
            exitApplication()
            return@application
        }

        // Launch the UI — we are the primary instance
        Window(onCloseRequest = ::exitApplication) {
            // Bring window to front when another instance tries to start
            LaunchedEffect(restoreRequested) {
                if (restoreRequested) {
                    window.toFront()
                    window.requestFocus()
                    restoreRequested = false
                }
            }
            App()
        }
    }
}
```

## Configuration

Configure before the first call to `isSingleInstance()`:

```kotlin
SingleInstanceManager.configuration = SingleInstanceManager.Configuration(
    lockFilesDir = Paths.get(System.getProperty("java.io.tmpdir")),  // Default
    lockIdentifier = "com.example.myapp",  // Defaults to auto-detected app ID
)
```

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lockFilesDir` | `Path` | System temp dir | Directory for lock files |
| `lockIdentifier` | `String` | Auto-detected | Unique application identifier |
| `lockFileName` | `String` | `"$lockIdentifier.lock"` | Lock file name (derived) |
| `restoreRequestFileName` | `String` | `"$lockIdentifier.restore_request"` | Restore request file name (derived) |
| `lockFilePath` | `Path` | Derived | Full path to lock file (derived) |
| `restoreRequestFilePath` | `Path` | Derived | Full path to restore request file (derived) |

## How It Works

1. Creates a lock file in the configured directory
2. Uses `java.nio.channels.FileLock` for atomic locking
3. If the lock is already held, sends a restore request via the filesystem
4. The primary instance watches for restore request files and invokes the callback
5. Cross-platform: works on macOS, Windows, and Linux
