# Nucleus Share

Native share sheet for Kotlin Multiplatform, inspired by
[`robius-share`](https://github.com/project-robius/robius/tree/main/crates/share).

| Platform | Share UI | Implementation |
|---|---|---|
| Android | Android Sharesheet (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` chooser) | Kotlin |
| iOS | `UIActivityViewController` | Kotlin/Native |
| macOS | `NSSharingServicePicker` | Rust (JNI) |
| Windows | Share UI (`DataTransferManager` desktop interop) | Rust (JNI) |
| Linux | XDG desktop portal: "Open With" for one file or URL, "save files" for a mixed payload, `xdg-open` fallback | Rust (JNI) |

```kotlin
implementation("dev.nucleusframework:nucleus.share:<version>")
```

```kotlin
scope.launch {
    try {
        ShareSheet.share {
            title = "Share"
            subject = "Nucleus"
            text("Cross-platform native share sheet")
            url("https://github.com/NucleusFramework/Nucleus")
            file("/path/to/report.pdf", mimeType = "application/pdf")
        }
    } catch (e: ShareException) {
        println("${e.error}: ${e.message}")
    }
}
```

`share` returns once the UI is on screen (on Android and Linux, once the request is
dispatched). It does not report which target was picked or whether the user cancelled.

## Parent window

Without a parent, the app's frontmost window (desktop) or resumed activity (Android) is used.

With `nucleus-application`, share from the window itself — it resolves the platform parent,
including the Wayland export and its lifetime:

```kotlin
val window = LocalNucleusWindow.current
val density = LocalDensity.current
var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
Button(
    onClick = { scope.launch { window.share(anchor) { url("https://…") } } },
    modifier = Modifier.onGloballyPositioned { anchor = it.shareAnchor(density) },
) { Text("Share") }
```

Otherwise, name it yourself:

```kotlin
// Desktop, with a Tao window
val parent = when (Platform.Current) {
    Platform.Windows -> ShareParent.Windows(taoWindow.nativeHandle)
    Platform.MacOS -> ShareParent.MacOs(taoWindow.nsWindowHandle!!, anchor = buttonBoundsInPoints)
    // A Wayland export is closed once the portal dialog is gone
    else -> taoWindow.xdgPortalParent()?.let { ShareParent.Linux(it.portalParent, keepAlive = it as? AutoCloseable) }
        ?: ShareParent.Auto
}
ShareSheet.share(request, parent)

// Android
ShareSheet.share(request, activity)
```

On macOS, `anchor` is where the picker points to (the button that triggered it), in points
from the top-left corner of the window content.

## Platform notes

- **Android**: files are copied to `cacheDir/nucleus-share` and served read-only by the
  library's own content provider (`${applicationId}.nucleus.share`, merged from the library
  manifest; no AndroidX). Copies older than a day are purged on the next share. `content://`
  URIs are forwarded as is. `minSdk` 21.
- **iOS**: `title` and `subject` are ignored; on iPad the popover is centred on the presenter.
- **macOS**: `title` and `subject` are ignored.
- **Windows**: text and links are combined into the payload's text, the first link is also its
  web link; `content://` URIs are unsupported (`ShareError.UnsupportedItem`).
- **Linux**: there is no share sheet; see the table above. A `ShareParent.Linux.keepAlive`
  is closed once the portal dialog is gone (right away when none was shown).
- MIME type hints only matter on Android.

Demo: `./gradlew :examples:share-demo:run`.
