# Media Control

OS-level media controls (play/pause, next/previous, seek, metadata) for your desktop app, exposed through a single cross-platform Kotlin API:

- **Linux** — [MPRIS D-Bus specification](https://specifications.freedesktop.org/mpris-spec/latest/). Integrates with GNOME Shell, KDE Plasma, `playerctl`, sound indicators, lock screens.
- **macOS** — `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (MediaPlayer.framework). Integrates with Control Center, the Now Playing menu-bar widget, and media keys.
- **Windows** — `SystemMediaTransportControls` (WinRT `Windows.Media`). Integrates with the Windows 10/11 media overlay, SoundBar, lock screen, and hardware media keys.

!!! info "Native backends"
    Linux uses GLib/GIO (`libgio-2.0`) for D-Bus. macOS uses `MediaPlayer.framework` via an Objective-C JNI bridge. Windows uses WinRT `SystemMediaTransportControls` via a C++/WRL JNI bridge. No JNA, no reflection, no Java D-Bus libraries.

!!! note "Platform differences"
    The events emitted by the OS differ per backend. Linux (MPRIS) can emit every `MediaControlEvent` variant. macOS (Remote Command Center) only emits `Play`, `Pause`, `Toggle`, `Next`, `Previous`, `Stop` and `SetPosition`. Windows (SMTC) emits `Play`, `Pause`, `Next`, `Previous`, `Stop`, `SetPosition` and relative `SeekBy` (fast-forward / rewind, ±10 s). `SetVolume`, `OpenUri`, `Raise` and `Quit` are Linux-only. `MediaControlService.setVolume(…)` is a no-op on macOS and Windows (system volume is managed separately from SMTC / Now Playing).

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.media-control:<version>")
}
```

Depends on `core-runtime` (for `NativeLibraryLoader` and `NucleusApp`) and `kotlinx-serialization-json` (event deserialization).

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.media.control.*

if (MediaControlService.isAvailable()) {
    // 1. Configure once — typically at app startup
    MediaControlService.configure()

    // 2. Listen to OS events (play/pause from headphones, media keys, lock screen…)
    MediaControlService.attach { event ->
        when (event) {
            MediaControlEvent.Play     -> player.play()
            MediaControlEvent.Pause    -> player.pause()
            MediaControlEvent.Toggle   -> player.togglePlayPause()
            MediaControlEvent.Next     -> player.skipToNext()
            MediaControlEvent.Previous -> player.skipToPrevious()
            MediaControlEvent.Stop     -> player.stop()
            is MediaControlEvent.SeekBy      -> player.seekBy(event.offsetMs)
            is MediaControlEvent.SetPosition -> player.seekTo(event.positionMs)
            is MediaControlEvent.SetVolume   -> player.setVolume(event.volume)
            is MediaControlEvent.OpenUri     -> player.open(event.uri)
            MediaControlEvent.Raise    -> window.toFront()
            MediaControlEvent.Quit     -> app.exit()
        }
    }

    // 3. Push state to the system media center
    MediaControlService.setMetadata(MediaMetadata(
        title = "Nocturne Op. 9 No. 2",
        artist = "Frédéric Chopin",
        album = "Nocturnes",
        duration = 270_000,
    ))
    MediaControlService.setPlaybackState(MediaPlaybackState(
        status = MediaPlaybackStatus.PLAYING,
        positionMs = 0,
    ))
}

// On shutdown
MediaControlService.detach()
```

---

## API Reference

### `MediaControlService`

Singleton entry point. All methods are safe no-ops on platforms where the native backend fails to load.

| Property / Method | Returns | Description |
|---|---|---|
| `isAvailable()` | `Boolean` | `true` if the native backend loaded on the current OS (Linux, macOS, or Windows). |
| `configure(dbusName, displayName)` | `Unit` | Configure the player identity. On Linux, registers the MPRIS bus name. On Windows, derives a stable AUMID from the identifier. On macOS, no-op. |
| `setMetadata(metadata)` | `Unit` | Update the metadata shown by the system media center. |
| `setPlaybackState(state)` | `Unit` | Update playback status and position. |
| `setVolume(volume)` | `Unit` | Update the player volume (0.0–1.0). **macOS / Windows: no-op.** |
| `attach(callback)` | `Unit` | Listen for control events from the OS. Replaces any previous listener. |
| `detach()` | `Unit` | Detach the listener and release native resources. |

#### `configure`

```kotlin
fun configure(
    dbusName: String = "org.mpris.MediaPlayer2.${NucleusApp.appId}",
    displayName: String = NucleusApp.appName ?: "Nucleus App",
)
```

- `dbusName`: on Linux, the full D-Bus bus name following the MPRIS spec format (each running instance must use a unique suffix). Ignored on macOS and Windows.
- `displayName`: on Linux, the human-readable name shown in system media centers (MPRIS `Identity` property). On Windows, must match the basename of the Start Menu shortcut the installer created (used to patch that shortcut's AUMID — see below). On macOS, the identity is derived from the host app bundle (`CFBundleName` / `CFBundleIdentifier`).

On Windows, the AUMID is resolved automatically (not from `dbusName`): it reads `NucleusApp.aumid` (`"com.app.<packageName>"` injected by the Nucleus Gradle plugin — identical to the value stamped on the Start Menu shortcut). For APPX/MSIX packages, the bridge skips the explicit AUMID call so Windows uses the package manifest identity.

---

### `MediaMetadata`

| Property | Type | Description |
|---|---|---|
| `title` | `String?` | Track title. |
| `artist` | `String?` | Artist name. |
| `album` | `String?` | Album name. |
| `coverUrl` | `String?` | Cover art URL. Local files must use the `file://` scheme. Both backends also accept `http(s)://`. |
| `duration` | `Long?` | Track duration in **milliseconds**. |

Backend mapping:

- **Linux** — MPRIS `Metadata` dict: `mpris:trackid`, `mpris:length`, `mpris:artUrl`, `xesam:title`, `xesam:artist`, `xesam:album`. Time values are converted to microseconds internally as required by the spec.
- **macOS** — `MPNowPlayingInfoCenter.nowPlayingInfo` keys: `MPMediaItemPropertyTitle`, `MPMediaItemPropertyArtist`, `MPMediaItemPropertyAlbumTitle`, `MPMediaItemPropertyPlaybackDuration`, `MPMediaItemPropertyArtwork`. Artwork is loaded asynchronously on a background queue and merged once decoded.
- **Windows** — `SystemMediaTransportControlsDisplayUpdater.MusicProperties` (`Title`, `Artist`, `AlbumTitle`) and `Thumbnail` via `RandomAccessStreamReference.CreateFromUri`. Duration is written to `SystemMediaTransportControlsTimelineProperties` (hundreds-of-nanoseconds units). Cover art accepts `http(s)://` and `file://` URIs; Windows fetches them on its own background thread.

!!! tip "Track changes"
    Each call to `setMetadata` starts a new track: on Linux it bumps `mpris:trackid`, on macOS it replaces `nowPlayingInfo` entirely (clearing any stale artwork from the previous track), and on Windows it calls `DisplayUpdater.Update()` which publishes the new metadata atomically. Use it on track boundaries rather than on minor metadata updates.

---

### `MediaPlaybackState` / `MediaPlaybackStatus`

```kotlin
enum class MediaPlaybackStatus { STOPPED, PAUSED, PLAYING }

data class MediaPlaybackState(
    val status: MediaPlaybackStatus,
    val positionMs: Long? = null,
)
```

Call `setPlaybackState` whenever playback state changes and periodically during playback to keep the displayed position in sync.

- **Linux** — a new `positionMs` emits an MPRIS `Seeked` signal so clients (applets, lock screens) resync their progress UI.
- **macOS** — `status` maps to `MPNowPlayingPlaybackState` (Playing/Paused/Stopped) on `MPNowPlayingInfoCenter.defaultCenter`. `positionMs` is written to `MPNowPlayingInfoPropertyElapsedPlaybackTime` (seconds).
- **Windows** — `status` maps to `MediaPlaybackStatus` (Playing/Paused/Stopped) on the SMTC. `positionMs` is written to `SystemMediaTransportControlsTimelineProperties.Position` and published via `UpdateTimelineProperties`. The SMTC media overlay only appears when `status` is `PLAYING` or `PAUSED`.

---

### `MediaControlEvent`

Sealed hierarchy of events sent by the OS to your app.

| Event | Payload | Linux (MPRIS) | macOS (RemoteCommand) | Windows (SMTC) |
|---|---|:---:|:---:|:---:|
| `Play` | — | ✅ | ✅ | ✅ |
| `Pause` | — | ✅ | ✅ | ✅ |
| `Toggle` | — | ✅ | ✅ | — |
| `Next` | — | ✅ | ✅ | ✅ |
| `Previous` | — | ✅ | ✅ | ✅ |
| `Stop` | — | ✅ | ✅ | ✅ |
| `SetPosition(positionMs)` | `Long` (ms) | ✅ | ✅ | ✅ |
| `SeekBy(offsetMs)` | `Long` (signed, ms) | ✅ | — | ✅ (±10 s, fast-forward / rewind) |
| `SetVolume(volume)` | `Double` (0.0–1.0) | ✅ | — | — |
| `OpenUri(uri)` | `String` | ✅ | — | — |
| `Raise` | — | ✅ | — | — |
| `Quit` | — | ✅ | — | — |

Platform-specific notes: the macOS Remote Command Center does not expose a per-app volume channel, a relative seek amount, or Raise/Quit/OpenUri commands. Windows SMTC exposes `FastForward` / `Rewind` buttons as fixed-step `SeekBy` events (±10 s), but has no `Toggle`, `SetVolume`, `OpenUri`, `Raise`, or `Quit`. Design your callback as an exhaustive `when` anyway: events absent on a given OS are simply ignored.

!!! info "Threading"
    The callback is dispatched on the Swing EDT — safe to mutate Compose/Swing state directly.

---

## How It Works

### Linux (MPRIS)

A dedicated thread hosts a `GMainLoop` that owns two D-Bus objects at `/org/mpris/MediaPlayer2`:

- `org.mpris.MediaPlayer2` — root interface with `Identity`, `Raise`, `Quit`.
- `org.mpris.MediaPlayer2.Player` — playback interface with `Play`, `Pause`, `Seek`, metadata, volume, etc.

State changes pushed via `setMetadata` / `setPlaybackState` / `setVolume` emit MPRIS `PropertiesChanged` signals from the main-loop thread, so clients (media applets, `playerctl`, lock screens) update in real time without polling.

### macOS (MediaPlayer.framework)

- `setMetadata` / `setPlaybackState` mutate `MPNowPlayingInfoCenter.defaultCenter` on the main queue. macOS propagates the change to Control Center and the Now Playing menu-bar widget automatically.
- `attach` installs block handlers on `MPRemoteCommandCenter.sharedCommandCenter` for `playCommand`, `pauseCommand`, `togglePlayPauseCommand`, `stopCommand`, `nextTrackCommand`, `previousTrackCommand`, and `changePlaybackPositionCommand`. Each handler emits a JSON event to Kotlin via JNI. Targets are retained so `detach` can remove them cleanly.
- Artwork URLs are decoded asynchronously on a global dispatch queue using `NSImage initWithContentsOfURL:`. A monotonic counter ensures stale artwork (from a previous track) cannot overwrite fresh metadata.

!!! info "Event loop requirement (macOS)"
    The Remote Command Center only delivers events while the main AppKit run loop is processing — which is the case by default in any Compose Desktop / Swing app. No extra setup is needed.

### Windows (SystemMediaTransportControls)

SMTC requires a window handle (HWND) and a stable `AppUserModelID`. Since the public API exposes neither, the bridge:

- Creates a hidden top-level helper window (`WS_OVERLAPPEDWINDOW`, 1×1, never shown) on a dedicated background thread that pumps Windows messages.
- Calls `RoInitialize(RO_INIT_SINGLETHREADED)` on that thread so WinRT events marshal correctly through the STA message pump.
- Calls `SetCurrentProcessExplicitAppUserModelID` with `NucleusApp.aumid` during `configure`. Without this, SMTC silently refuses to display anything because the process has no stable identity (the default AUMID is the `javaw.exe` path). On APPX/MSIX the call is skipped — the package manifest provides the identity.
- Patches the Start Menu shortcut `{displayName}.lnk` (user or system-wide) to carry the same AUMID in its `System.AppUserModel.ID` property. This is how SMTC resolves the AUMID to the app icon and display name for classic installers (NSIS, `jpackage`). The Nucleus Gradle plugin already stamps the correct AUMID on the shortcut when packaging, so this is a safety no-op for Nucleus-packaged apps.
- Binds SMTC to the helper window via `ISystemMediaTransportControlsInterop.GetForWindow`, then subscribes to `ButtonPressed` and `PlaybackPositionChangeRequested` events. Events are forwarded to Kotlin as JSON through a WRL `Callback<ITypedEventHandler>`.

!!! info "No audio session required for display"
    `DisplayUpdater.Update()` makes the metadata visible in the Windows 10/11 media overlay (the small widget above the volume flyout, Xbox Game Bar, lock screen) as soon as `status` is `PLAYING` or `PAUSED`. An actual audio output stream is not required.

---

## System Integration

### Linux

The MPRIS2 specification is supported by most modern Linux desktops:

| Desktop / Tool | Metadata | Transport | Seek | Volume |
|---|---|---|---|---|
| GNOME Shell (Media player indicator) | Yes | Yes | Yes | Yes |
| KDE Plasma (Media Player applet) | Yes | Yes | Yes | Yes |
| `playerctl` CLI | Yes | Yes | Yes | Yes |
| `sound-theme-freedesktop` indicators | Yes | Yes | — | Yes |
| Lock screens (GDM, SDDM) | Yes | Yes | — | — |

Testing from the terminal:

```bash
# Show current metadata
playerctl --player=<dbusName> metadata

# Control playback
playerctl --player=<dbusName> play-pause
playerctl --player=<dbusName> next
playerctl --player=<dbusName> position 60

# Watch PropertiesChanged signals in real time
dbus-monitor --session "type='signal',interface='org.freedesktop.DBus.Properties'"
```

### macOS

| Surface | Metadata | Transport | Seek | Artwork |
|---|---|---|---|---|
| Control Center (Now Playing widget, macOS 11+) | Yes | Yes | Yes | Yes |
| Menu-bar Now Playing icon | Yes | Yes | Yes | Yes |
| Media keys (F7/F8/F9, headset buttons, Touch Bar) | — | Yes | — | — |

Requirements:

- macOS 10.13.2 or newer (deployment target of the shipped dylib).
- The app must run with an active AppKit run loop (any Compose Desktop / Swing app does).
- No entitlements or Info.plist keys are required. `MPNowPlayingInfoCenter` works for both sandboxed and non-sandboxed apps.

### Windows

| Surface | Metadata | Transport | Seek | Artwork |
|---|---|---|---|---|
| Volume flyout media overlay (Windows 10/11) | Yes | Yes | Yes | Yes |
| Lock screen Now Playing | Yes | Yes | — | Yes |
| Hardware media keys (Play/Pause/Next/Prev, FF/RW) | — | Yes | ±10 s | — |
| Xbox Game Bar widget | Yes | Yes | Yes | Yes |
| SoundBar / headset transport buttons | — | Yes | — | — |

Requirements:

- Windows 10 version 1809 (build 17763) or newer.
- A stable `AppUserModelID` — set automatically by `configure()` from `NucleusApp.aumid` (injected by the Nucleus plugin). For packaged apps (`.msix`/`.appx`) detected via `ExecutableRuntime.isAppX()`, the manifest AUMID is used instead.
- No audio session or special entitlements required. SMTC works for any JVM desktop app.

!!! info "Nucleus-packaged apps"
    When packaging with the Nucleus Gradle plugin, both the AUMID (`"com.app.<packageName>"`) and the shortcut basename (`appName`) are injected into `NucleusApp` at build time — so `configure()` with its default arguments just works, for both NSIS `.exe` and `.appx`/`.msix` targets. If you change the AUMID later, a shell re-login (or `taskkill /f /im explorer.exe && start explorer`) is needed for the Windows icon cache to refresh.

Testing from PowerShell:

```powershell
# List current media sessions (Windows 10+)
Get-MediaSessionInfo   # or use Windows media overlay (click the small icon above volume)

# Simulate media-key presses
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.SendKeys]::SendWait("^{MEDIA_PLAY_PAUSE}")
```

---

## Native Library

Ships pre-built native binaries for Linux (x86_64 + aarch64), macOS (arm64 + x86_64), and Windows (x64 + arm64). `isAvailable` returns `false` on platforms where the bundled binary fails to load.

| Platform | Binary | Linked against | Build requirements |
|---|---|---|---|
| Linux | `libnucleus_media_control_linux.so` | `libgio-2.0` (GLib/GIO) | `libglib2.0-dev` (Debian/Ubuntu) / `glib2-devel` (Fedora) |
| macOS | `libnucleus_media_control_macos.dylib` | `Foundation`, `AppKit`, `MediaPlayer` | Xcode Command Line Tools (clang) |
| Windows | `nucleus_media_control_windows.dll` | `ole32`, `runtimeobject`, `user32`, `shell32` | MSVC 2019+ with Windows 10 SDK |

Each JVM hosts a single long-lived background worker: on Linux a dedicated thread with its own `GMainContext`, on macOS GCD blocks dispatched to the main queue, and on Windows a dedicated STA-initialized thread running a classic `GetMessage` pump.

## ProGuard

```proguard
-keep class dev.nucleusframework.nucleus.media.control.linux.NativeLinuxBridge {
    native <methods>;
    static ** on*(...);
}
-keep class dev.nucleusframework.nucleus.media.control.macos.NativeMacOsBridge {
    native <methods>;
    static ** on*(...);
}
-keep class dev.nucleusframework.nucleus.media.control.windows.NativeWindowsBridge {
    native <methods>;
    static ** on*(...);
}
```

## GraalVM

JNI reflection metadata is shipped automatically via `reachability-metadata.json` under `META-INF/native-image/`. No additional configuration is required.
