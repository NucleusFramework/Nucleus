# Freedesktop Icons

Type-safe Kotlin constants for the [freedesktop Icon Naming Specification](https://specifications.freedesktop.org/icon-naming/latest/). Shared dependency for `notification-linux` and `launcher-linux`.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.freedesktop-icons:<version>")
}
```

!!! info "Transitive dependency"
    If you already depend on `notification-linux` or `launcher-linux`, `freedesktop-icons` is included transitively via `api()` — no separate dependency needed.

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon

// Standard icon from the spec (typesafe)
val icon = FreedesktopIcon.Status.DIALOG_INFORMATION
val action = FreedesktopIcon.Action.DOCUMENT_OPEN
val device = FreedesktopIcon.Device.PRINTER

// Custom icon name, file path, or URI
val custom = FreedesktopIcon.Custom("my-app-icon")
val filePath = FreedesktopIcon.Custom("/home/user/icon.png")
val fileUri = FreedesktopIcon.Custom("file:///home/user/icon.png")

// Country flag (ISO 3166-1 alpha-2)
val flag = FreedesktopIcon.flag("fr")  // "flag-fr"
```

## API Reference

### `FreedesktopIcon`

Sealed interface. All subtypes expose a `value: String` property containing the icon name sent over D-Bus.

| Type | Description |
|---|---|
| `FreedesktopIcon.Custom(value)` | Inline value class for arbitrary icon names, file paths, or `file://` URIs |
| `FreedesktopIcon.flag(countryCode)` | Returns a `Custom("flag-<code>")` icon |

### Icon Contexts

All 338 standard names from the [freedesktop Icon Naming Specification](https://specifications.freedesktop.org/icon-naming/latest/) are available as enum constants, grouped by context.

| Enum | Count | Examples |
|---|---|---|
| `FreedesktopIcon.Status` | 57 | `DIALOG_INFORMATION`, `DIALOG_WARNING`, `DIALOG_ERROR`, `MAIL_UNREAD`, `BATTERY_LOW`, `NETWORK_ERROR`, `SOFTWARE_UPDATE_AVAILABLE`, `WEATHER_CLEAR` |
| `FreedesktopIcon.Action` | 94 | `DOCUMENT_SAVE`, `EDIT_COPY`, `MAIL_SEND`, `MEDIA_PLAYBACK_START`, `SYSTEM_SHUTDOWN`, `ZOOM_IN`, `WINDOW_NEW`, `APPLICATION_EXIT` |
| `FreedesktopIcon.Device` | 27 | `PRINTER`, `PHONE`, `CAMERA_PHOTO`, `COMPUTER`, `DRIVE_HARDDISK`, `NETWORK_WIRELESS` |
| `FreedesktopIcon.Emblem` | 13 | `DOWNLOADS`, `FAVORITE`, `IMPORTANT`, `SHARED`, `SYNCHRONIZED` |
| `FreedesktopIcon.Emote` | 21 | `FACE_SMILE`, `FACE_SAD`, `FACE_COOL`, `FACE_ANGRY`, `FACE_WINK` |
| `FreedesktopIcon.Application` | 20 | `UTILITIES_TERMINAL`, `SYSTEM_FILE_MANAGER`, `ACCESSORIES_TEXT_EDITOR` |
| `FreedesktopIcon.Category` | 19 | `APPLICATIONS_GAMES`, `APPLICATIONS_INTERNET`, `PREFERENCES_SYSTEM` |
| `FreedesktopIcon.MimeType` | 15 | `TEXT_HTML`, `IMAGE_X_GENERIC`, `VIDEO_X_GENERIC`, `APPLICATION_X_EXECUTABLE` |
| `FreedesktopIcon.Place` | 9 | `FOLDER`, `USER_HOME`, `USER_TRASH`, `NETWORK_SERVER` |
| `FreedesktopIcon.Animation` | 1 | `PROCESS_WORKING` |

Icon names are **cross-desktop** — they are resolved from the active icon theme (Adwaita, Breeze, Papirus, Yaru, etc.) on GNOME, KDE, XFCE, and any freedesktop-compliant environment.

!!! tip "Browse all available icons"
    Run `gtk4-icon-browser` or `gtk3-icon-browser` to visually browse all icons in your current theme.

## Usage in Notifications

```kotlin
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.nucleus.notification.linux.*

LinuxNotificationCenter.notify(
    Notification(
        summary = "Download complete",
        appIcon = FreedesktopIcon.Emblem.DOWNLOADS,
        hints = NotificationHints(
            imagePath = FreedesktopIcon.Emblem.DOWNLOADS,
        ),
    )
)
```

## Usage in Launcher Quicklists

```kotlin
import dev.nucleusframework.nucleus.freedesktop.icons.FreedesktopIcon
import dev.nucleusframework.nucleus.launcher.linux.DbusmenuItem

val items = listOf(
    DbusmenuItem(id = 1, label = "New Window", icon = FreedesktopIcon.Action.WINDOW_NEW),
    DbusmenuItem(id = 2, label = "Open File", icon = FreedesktopIcon.Action.DOCUMENT_OPEN),
    DbusmenuItem.separator(id = 3),
    DbusmenuItem(id = 4, label = "Quit", icon = FreedesktopIcon.Action.APPLICATION_EXIT),
)
```
