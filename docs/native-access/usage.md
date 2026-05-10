# Usage & Patterns

## Example: Take a Screenshot on macOS

Here's a real-world example: capturing the screen using macOS's CoreGraphics API. This is a platform API with no JVM equivalent — the kind of thing that would normally require JNI C glue.

**Native side** (`src/nativeMain/kotlin/com/example/screen/SystemDesktop.kt`):

```kotlin
// suspend — runs off the main thread, returns PNG bytes
actual suspend fun captureScreen(): ByteArray = memScoped {
    if (!CGPreflightScreenCaptureAccess()) {
        CGRequestScreenCaptureAccess()
        return@memScoped ByteArray(0)
    }

    val rect = alloc<CGRect>().apply {
        origin.x = CGRectInfinite.origin.x
        origin.y = CGRectInfinite.origin.y
        size.width = CGRectInfinite.size.width
        size.height = CGRectInfinite.size.height
    }
    val cgImage = CGWindowListCreateImage(
        rect.readValue(),
        kCGWindowListOptionOnScreenOnly,
        kCGNullWindowID,
        kCGWindowImageDefault,
    ) ?: return@memScoped ByteArray(0)

    // Encode as PNG — NSBitmapImageRep handles all the pixel format details
    val bitmapRep = NSBitmapImageRep(cGImage = cgImage)
    CGImageRelease(cgImage)

    val pngData = bitmapRep.representationUsingType(
        NSBitmapImageFileTypePNG,
        properties = emptyMap<Any?, Any>(),
    ) ?: return@memScoped ByteArray(0)

    ByteArray(pngData.length.toInt()) { i ->
        (pngData.bytes!!.reinterpret<ByteVar>() + i)!!.pointed.value
    }
}
```

**JVM + Compose side** — the plugin generates the proxy, you just use it:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.screen.SystemDesktop
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ScreenshotViewer() {
    val desktop = remember { SystemDesktop() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = {
                capturing = true
                scope.launch {
                    val bytes = desktop.captureScreen()   // suspend — UI never blocks
                    if (bytes.isNotEmpty()) {
                        bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    }
                    capturing = false
                }
            },
            enabled = !capturing,
        ) {
            Text(if (capturing) "Capturing…" else "Capture Screen")
        }

        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Screenshot",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

**No C. No JNI headers. No build scripts. No `System.loadLibrary` call.** The `.dylib` is compiled by the plugin, bundled in the JAR, and extracted automatically at runtime. The `suspend` on the native side maps transparently to a coroutine on the JVM — the UI stays responsive while CoreGraphics does the work.

!!! tip "Full working example"
    The [systeminfo example](https://github.com/kdroidFilter/NucleusNativeAccess/tree/main/examples/systeminfo) in the NucleusNativeAccess repo implements this pattern for all three platforms (CoreGraphics on macOS, XDG ScreenCast + PipeWire on Linux, GDI on Windows), plus native notifications, a system tray menu, and real-time memory updates via `Flow`.

The same pattern works for any other platform API:

=== "macOS"
    ```kotlin
    // Access NSWorkspace, IOKit, CoreBluetooth, AVFoundation, Metal…
    import platform.AppKit.*
    import platform.IOKit.*
    ```

=== "Windows"
    ```kotlin
    // Access Win32, WinRT, DirectX, COM interfaces…
    import platform.windows.*
    ```

=== "Linux"
    ```kotlin
    // Access POSIX, D-Bus, GTK, libnotify…
    import platform.posix.*
    import platform.linux.*
    ```

## Using Top-Level Functions

You don't have to wrap everything in a class. Top-level functions are grouped into a singleton `object` named after `nativeLibName` (first letter uppercased):

```kotlin
// build.gradle.kts
kotlinNativeExport {
    nativeLibName = "utils"          // → object Utils { … }
    nativePackage = "com.example.utils"
}
```

```kotlin
// nativeMain — top-level function
package com.example.utils

fun currentProcessId(): Int = platform.posix.getpid()
```

```kotlin
// jvmMain — generated object
import com.example.utils.Utils

val pid = Utils.currentProcessId()
```

## Object Lifecycle

Generated proxy classes implement `AutoCloseable`. Native memory is freed on `close()`, or automatically when garbage collected (via Java `Cleaner`):

```kotlin
// Preferred — explicit, deterministic
ScreenCapture().use { capture ->
    val bytes = capture.captureScreen()
    // ...
}

// Also valid — Cleaner will release when GC runs
val capture = ScreenCapture()
val bytes = capture.captureScreen()
```

## Coroutines and Flows

Suspend functions and `Flow` are transparent — no callbacks, no `CompletableFuture`, just coroutines on both sides:

```kotlin
// nativeMain
suspend fun fetchData(query: String): String {
    delay(100)
    return "result: $query"
}

fun eventStream(max: Int): Flow<Int> = flow {
    for (i in 1..max) { delay(10); emit(i) }
}
```

```kotlin
// jvmMain — identical API
val result = MyLib.fetchData("hello")      // suspends, doesn't block

MyLib.eventStream(100)
    .take(5)       // cancels the native Flow automatically at 5 elements
    .collect { println(it) }
```

Cancellation is bidirectional: cancelling the JVM `Job` cancels the native coroutine, and vice versa.
