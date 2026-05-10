# Deep Links

Handle custom URL protocol links (`myapp://action?param=value`).

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.core-runtime:<version>")
}
```

```kotlin
import dev.nucleusframework.nucleus.core.runtime.DeepLinkHandler
```

`DeepLinkHandler` is a Kotlin `object` (singleton).

## Setup

1. Register the protocol in the DSL:

```kotlin
nativeDistributions {
    protocol("MyApp", "myapp")
}
```

2. Handle incoming links in your app:

```kotlin
fun main(args: Array<String>) {
    DeepLinkHandler.register(args) { uri ->
        println("Received deep link: $uri")
        // Handle: myapp://open?file=document.txt
    }

    // The current URI is also available as a property
    val currentUri = DeepLinkHandler.uri

    // Launch the UI...
}
```

## API Reference

| Member | Type / Signature | Description |
|--------|-----------------|-------------|
| `uri` | `URI?` (read-only, volatile) | The most recent deep link URI |
| `register(args, onDeepLink)` | `fun register(args: Array<String>, onDeepLink: (URI) -> Unit)` | Register a deep link handler with CLI args |
| `writeUriTo(path)` | `fun writeUriTo(path: Path)` | Write the current URI to a file (for IPC) |
| `readUriFrom(path)` | `fun readUriFrom(path: Path)` | Read a URI from a file (for IPC) |

## Integration with Single Instance

Deep links work with `SingleInstanceManager` to forward URLs to the primary instance:

```kotlin
fun main(args: Array<String>) {
    DeepLinkHandler.register(args) { uri ->
        handleDeepLink(uri)
    }

    application {
        var restoreRequested by remember { mutableStateOf(false) }

        val isSingle = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = {
                    // New instance: write our deep link URI for the primary to read
                    // `this` is the Path to the restore request file
                    DeepLinkHandler.writeUriTo(this)
                },
                onRestoreRequest = {
                    // Primary instance: read the URI from the new instance
                    // `this` is the Path to the restore request file
                    DeepLinkHandler.readUriFrom(this)
                    restoreRequested = true
                },
            )
        }

        if (!isSingle) {
            exitApplication()
            return@application
        }

        // Handle the initial deep link if launched with one
        DeepLinkHandler.uri?.let { handleDeepLink(it) }

        Window(onCloseRequest = ::exitApplication) {
            LaunchedEffect(restoreRequested) {
                if (restoreRequested) {
                    window.toFront()
                    restoreRequested = false
                }
            }
            App()
        }
    }
}
```

## Platform Behavior

| Platform | Mechanism |
|----------|-----------|
| macOS | Apple Events (`setOpenURIHandler`) — works even when app is already running |
| Windows | CLI argument via registry handler — new process forwards to primary instance |
| Linux | CLI argument via `.desktop` MimeType — new process forwards to primary instance |
