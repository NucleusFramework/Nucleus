# Clipboard (macOS)

macOS `NSPasteboard` backend for the [cross-platform clipboard API](clipboard-common.md). Reads and writes text, HTML, RTF, PNG/TIFF images, and file URLs via a single JNI bridge over the general pasteboard.

!!! info "Use via `Clipboard`"
    This page documents the macOS-specific behavior. The public API is `io.github.kdroidfilter.nucleus.clipboard.Clipboard` — same on every platform. See [Clipboard (Common)](clipboard-common.md) for the full surface.

## Installation

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:nucleus.clipboard-common:<version>")
    implementation("io.github.kdroidfilter:nucleus.clipboard-macos:<version>")
}
```

Depends on `clipboard-common` and `core-runtime` (pulled in transitively). The backend registers itself via `META-INF/services/io.github.kdroidfilter.nucleus.clipboard.internal.ClipboardBackend` — adding the dependency is enough, no manual wiring.

## Format mapping

The backend publishes and reads UTI-typed items on `NSPasteboard.generalPasteboard`. Writes use a single `NSPasteboardItem` carrying every non-null representation so consumers can pick the richest format they understand.

| Common API | UTI on read (priority) | UTI on write |
|---|---|---|
| `readText` / `writeText` | `public.utf8-plain-text` → `NSPasteboardTypeString` | `public.utf8-plain-text` |
| `readHtml` / `writeHtml` | `public.html` (UTF-8 BOM stripped) | `public.html` |
| `readRtf` / `writeRtf` | `public.rtf` | `public.rtf` |
| `readImageBytes` / `writeImage` | `public.png` → `public.tiff` (native TIFF → PNG transcode) | `public.png` **and** `public.tiff` on the same item |
| `readFiles` / `writeFiles` | `public.file-url` NSURLs → legacy `NSFilenamesPboardType` | `public.file-url` NSURLs |

### Why PNG **and** TIFF on write?

Web and Chromium-based apps (Slack, Discord, Figma, Electron) only understand `public.png`. Native AppKit apps (Pages, Keynote, Preview) prefer `public.tiff`. Publishing both on the same item keeps both paths happy without forcing callers to know the difference.

### Why strip HTML BOMs?

Firefox writes an UTF-16 BOM, Chromium writes an UTF-8 BOM on their `public.html` payloads. The backend strips the leading `\uFEFF` on read so callers get clean HTML regardless of source.

## Change watcher

macOS has no native push notification for pasteboard changes. The watcher polls `NSPasteboard.changeCount`, a cheap Mach IPC call exposed by the JNI bridge:

- Default poll interval: **250 ms** (configurable via `Clipboard.watch(pollInterval)`).
- Typical cost: ~0.1 % CPU.
- Events carry only `formats` (derived from `-types`) and `changeCount` — **no content bytes are read** during polling.

!!! tip "Self-writes bump changeCount"
    Every `Clipboard.writeXxx` increments `changeCount`, which means the watcher will observe your own writes. Deduplicate with the counter if that matters for your use case.

## macOS 15.4+ pasteboard privacy

macOS 15.4 introduced a developer-preview pasteboard-privacy prompt. The backend is designed to stay outside its scope by default:

- **Watcher & `availableFormats()`** — use `changeCount` and `-types`, both of which return metadata only. **No prompt.**
- **`readXxx` methods** — read actual bytes; will trigger the prompt when `defaults write <bundle-id> EnablePasteboardPrivacyDeveloperPreview -bool yes` is active, or on macOS 16 when the prompt is enabled by default.
- **`Clipboard.setAccessBehavior(...)`** — maps to `NSPasteboard.accessBehavior` (macOS 15.4+, guarded by `respondsToSelector:`). Older macOS versions silently ignore the call.

```kotlin
// At startup — opt the app into "ask every time" policy on macOS 15.4+.
Clipboard.setAccessBehavior(AccessBehavior.AskEveryTime)
```

## Concealed / transient content

macOS uses community-defined marker UTIs (documented at [nspasteboard.org](https://nspasteboard.org)) to flag sensitive clipboard items. **V1 does not emit these markers automatically** — a `writeConcealedText` helper is planned for V2. Until then, apps that handle secrets should fall back to the platform-specific `notification-macos` patterns or consider clearing the clipboard after a delay.

## Thread safety

`NSPasteboard` is thread-safe on any thread — the backend runs all I/O on `Dispatchers.IO`. No main-thread hop is required. `NSPasteboardItemDataProvider` (delayed rendering) is planned for V2 and will run on the main thread when it lands.

## Native Library

Ships pre-built dylibs for both macOS architectures:

- `libnucleus_clipboard.dylib` — linked against `AppKit.framework`
- Minimum deployment target: **macOS 10.13**
- Exports 11 `Java_...` symbols (read / write / watch / clear / access behavior)
- `NSPasteboard.accessBehavior` is resolved via `respondsToSelector:` — calling `setAccessBehavior` on macOS < 15.4 is a no-op

`isAvailable` returns `false` on non-macOS platforms and all methods degrade to no-ops — the façade falls through to `NoOpBackend`.

## ProGuard

Auto-injected by the Nucleus Gradle plugin. If you override `configurationFiles`, add:

```proguard
-keep class io.github.kdroidfilter.nucleus.clipboard.macos.NativeMacClipboardBridge {
    native <methods>;
}

# Preserve the ServiceLoader entry
-keep class io.github.kdroidfilter.nucleus.clipboard.macos.MacClipboardBackend
```

## GraalVM

Reachability metadata is included in the JAR at
`META-INF/native-image/io.github.kdroidfilter/nucleus.clipboard-macos/reachability-metadata.json`
and auto-discovered. The `META-INF/services/` entry is picked up by GraalVM automatically.

No additional configuration is needed when building with `runGraalvmNative`.

## Troubleshooting

**`Clipboard.isAvailable` returns `false` on macOS**

- Confirm the `clipboard-macos` dependency is on the runtime classpath (it is not pulled in by `clipboard-common`).
- Enable fine-grained JUL logging on `BackendFactory` — the selected backend and any probe failures are logged at `FINE`.

**Images copied from Safari / Preview paste as transparent black rectangles**

This is a known macOS quirk when the source emits a malformed TIFF alpha channel. Re-copying from the source often fixes it. Chromium sanitizes `DIBv5` writes on Windows for the same reason — a similar sanitizer may be added in a later release.

**`readFiles()` returns an empty list even though the Finder shows items**

Some Finder operations copy aliases that resolve to `x-coredata://` URIs rather than `file://`. These are not returned by `readFiles()`; call `availableFormats()` to check that `ClipboardFormat.Files` is advertised before falling back to an error message.
