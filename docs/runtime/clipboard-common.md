# Clipboard (Common)

Rich cross-platform clipboard with a reactive change watcher. Reads and writes text, HTML, RTF, images, and file lists behind a single Kotlin façade — the module discovers the right platform backend at runtime via `ServiceLoader`.

!!! info "macOS-only in V1"
    The SPI is cross-platform — the façade and all types live in `clipboard-common` — but only `clipboard-macos` is available today. When no backend is on the classpath, every method degrades to a no-op (`null` / `false` / empty flow) so calling code keeps working on Windows and Linux until those backends land.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.clipboard-common:<version>")
    // Add the platform backend(s) you ship. On macOS:
    implementation("io.github.kdroidfilter:nucleus.clipboard-macos:<version>")
}
```

The common module pulls in `core-runtime` and `kotlinx-coroutines-core` transitively. Backend discovery happens lazily on the first call to a `Clipboard` method.

## Quick Start

```kotlin
import io.github.kdroidfilter.nucleus.clipboard.*

// Simple read / write
val selection = Clipboard.readText()
Clipboard.writeText("Hello from Nucleus")

// Atomic multi-format write — HTML + plain-text fallback + RTF
Clipboard.write {
    html = "<b>Hello</b> from <i>Nucleus</i>"
    text = "Hello from Nucleus"
    rtf  = """{\rtf1\ansi \b Hello\b0  from Nucleus.}"""
}

// React to user copies
Clipboard.watch()
    .filter { ClipboardFormat.Text in it.formats }
    .onEach { event ->
        val text = Clipboard.readText() ?: return@onEach
        println("User copied: $text")
    }
    .launchIn(scope)
```

## API Reference

### `Clipboard`

Singleton façade. All suspending methods may be called from any coroutine context — the backend hops to its own thread internally.

| Property / Method | Description |
|---|---|
| `isAvailable: Boolean` | `true` when a platform backend is loaded and operational. `false` on unsupported platforms or when the native library failed to load. |
| `backendName: String` | Backend name for diagnostics (e.g. `"macOS NSPasteboard"` or `"no-op"`). |
| `setAccessBehavior(behavior)` | Applies a privacy policy for background reads. Maps to `NSPasteboard.accessBehavior` on macOS 15.4+, no-op elsewhere. |

#### Read

| Method | Returns | Description |
|---|---|---|
| `readText()` | `String?` | UTF-8 plain text, or `null` if the clipboard has no text. |
| `readHtml()` | `String?` | UTF-8 HTML fragment. Leading BOMs emitted by Firefox / Chromium are stripped. |
| `readRtf()` | `String?` | UTF-8 RTF payload. |
| `readImageBytes()` | `ByteArray?` | PNG-encoded bytes. Backends transcode TIFF/DIB to PNG when only a raster format is available. Decode with your preferred library (`ImageIO.read`, Skia, Coil, ...). |
| `readFiles()` | `List<Path>` | Absolute file paths read from file-URL items. Empty list when no files are advertised. |
| `availableFormats()` | `Set<ClipboardFormat>` | Advertised formats **without reading content bytes**. Safe to call under restrictive pasteboard-privacy policies (macOS 15.4+). |

#### Write

| Method | Returns | Description |
|---|---|---|
| `writeText(text)` | `Boolean` | Publishes UTF-8 text. |
| `writeHtml(html, plainTextFallback)` | `Boolean` | Publishes an HTML fragment with an optional plain-text representation for consumers that do not understand HTML. |
| `writeRtf(rtf, plainTextFallback)` | `Boolean` | Same for RTF. |
| `writeImage(png)` | `Boolean` | Publishes a PNG image. Backends also publish a platform-native raster (TIFF on macOS) on the same item to maximise consumer compatibility. |
| `writeFiles(paths)` | `Boolean` | Publishes a list of file URLs. |
| `write { }` | `Boolean` | Atomic multi-format write — see [`ClipboardWriteScope`](#clipboardwritescope). |
| `clear()` | `Boolean` | Clears the clipboard. |

#### Watch

```kotlin
fun watch(pollInterval: Duration = 250.milliseconds): Flow<ClipboardEvent>
```

Cold [Flow] that emits a `ClipboardEvent` whenever the clipboard contents change.

- The event carries only **format metadata** and a monotonic `changeCount` — **no content bytes** are read. Call a `readXxx` method on demand to fetch the payload.
- The baseline is captured at `collect` time, so the flow does **not** emit the current clipboard state — only subsequent changes.
- Self-writes via `Clipboard.writeXxx` bump the counter too; deduplicate with `changeCount` if needed.
- `pollInterval` only affects backends that must poll (macOS, where `NSPasteboard` has no push notification). Push-based backends (Windows `WM_CLIPBOARDUPDATE`, X11 XFixes, Wayland `data-control`) emit immediately and ignore the value.

!!! info "Why polling on macOS?"
    `NSPasteboard` has no push notification — Apple confirmed this in May 2025. Reading `changeCount` is a cheap Mach IPC call, so a 250 ms poll costs roughly 0.1 % CPU. The only way to detect clipboard changes on macOS is to poll this counter.

---

### `ClipboardEvent`

```kotlin
data class ClipboardEvent(
    val formats: Set<ClipboardFormat>,
    val changeCount: Long,
)
```

| Property | Description |
|---|---|
| `formats` | Formats currently advertised on the clipboard. |
| `changeCount` | Backend-provided monotonic counter. Use it to deduplicate self-writes or to correlate a write with the resulting event. |

---

### `ClipboardFormat`

```kotlin
enum class ClipboardFormat { Text, Html, Rtf, Image, Files }
```

Content categories advertised on the clipboard. Used by [`availableFormats`](#read) and [`ClipboardEvent.formats`](#clipboardevent).

---

### `ClipboardWriteScope`

Builder for a multi-format write. Any non-null property is published atomically on the same clipboard item, so consumers can pick the richest representation they understand.

```kotlin
Clipboard.write {
    html  = "<p>Selected <b>text</b></p>"
    text  = "Selected text"
    rtf   = """{\rtf1\ansi Selected \b text\b0 .}"""
    imagePng = renderPreview().encodeToPng()
    files = listOf(Path.of("/tmp/export.csv"))
}
```

| Property | Type | Description |
|---|---|---|
| `text` | `String?` | UTF-8 plain text. Written as `public.utf8-plain-text` on macOS. |
| `html` | `String?` | UTF-8 HTML fragment. Written as `public.html` on macOS (no CF_HTML wrapper). |
| `rtf` | `String?` | UTF-8 RTF payload. Written as `public.rtf` on macOS. |
| `imagePng` | `ByteArray?` | PNG-encoded image bytes. On macOS, a TIFF representation is transcoded and published on the same item. |
| `files` | `List<Path>?` | Absolute file paths. Written as `public.file-url` NSURLs on macOS. |

At least one property must be set — `write { }` with everything `null` returns `false` without touching the clipboard.

---

### `AccessBehavior`

```kotlin
enum class AccessBehavior { AlwaysAllow, AskEveryTime, AlwaysDeny }
```

Platform privacy policy for background reads. Maps 1:1 to `NSPasteboard.AccessBehavior` on macOS 15.4+. No-op on platforms without a privacy model. Call at startup:

```kotlin
Clipboard.setAccessBehavior(AccessBehavior.AskEveryTime)
```

## Sensitive-content note

Background polling of `changeCount` and `availableFormats()` never reads payload bytes and therefore does **not** trigger the macOS 15.4+ pasteboard-privacy prompt. The prompt is only triggered on explicit `readXxx` calls when the user has enabled `EnablePasteboardPrivacyDeveloperPreview` or when `AccessBehavior.AskEveryTime` is set.

## Architecture

```
Clipboard (façade, common)
  └─ BackendFactory (ServiceLoader<ClipboardBackend>)
       ├─ MacClipboardBackend        → NSPasteboard via JNI         (clipboard-macos)
       ├─ WindowsClipboardBackend    → AddClipboardFormatListener   (future)
       ├─ X11ClipboardBackend        → XFixes + ICCCM selections    (future)
       └─ WaylandClipboardBackend    → ext-data-control-v1          (future)
```

Adding a backend is a matter of implementing `io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardBackend` and registering it in `META-INF/services/`. The first backend whose `isAvailable()` returns `true` wins; if none match, a `NoOpBackend` keeps every method well-defined.

## ProGuard

No additional rules needed for `clipboard-common` itself. Backend modules ship their own (see [Clipboard macOS ProGuard rules](clipboard-macos.md#proguard)).

## GraalVM

No additional reachability metadata is needed for `clipboard-common`. Backend modules ship their own.
