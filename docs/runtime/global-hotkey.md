# Global Hotkey

System-wide keyboard shortcuts that fire even when the application does not have focus — for media players, screenshot tools, accessibility shortcuts, and more.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.global-hotkey:<version>")
}
```

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.globalhotkey.GlobalHotKeyManager
import dev.nucleusframework.nucleus.globalhotkey.HotKeyModifier
import java.awt.event.KeyEvent

GlobalHotKeyManager.initialize()

val handle = GlobalHotKeyManager.register(
    keyCode = KeyEvent.VK_F12,
    modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
) { _, _ ->
    println("Hotkey pressed!")
}

// Later:
GlobalHotKeyManager.unregister(handle)
GlobalHotKeyManager.shutdown()
```

## Usage

### Lifecycle

Call `initialize()` once at startup (e.g., in `DisposableEffect`) and `shutdown()` on disposal. Both are safe to call multiple times.

```kotlin
DisposableEffect(Unit) {
    GlobalHotKeyManager.initialize()
    onDispose { GlobalHotKeyManager.shutdown() }
}
```

### Registering a Hotkey

`register()` returns a `Long` handle (≥ 0 on success, -1 on failure). Keep the handle to unregister later.

```kotlin
val handle = GlobalHotKeyManager.register(
    keyCode = KeyEvent.VK_K,
    modifiers = HotKeyModifier.CONTROL + HotKeyModifier.SHIFT,
) { keyCode, modifiers ->
    // Called on a background thread — dispatch to UI if needed
    println("Pressed: keyCode=$keyCode modifiers=$modifiers")
}

if (handle < 0) {
    println("Failed: ${GlobalHotKeyManager.lastError}")
}
```

### Combining Modifiers

Use the `+` operator to build a modifier bitmask:

```kotlin
// Single modifier
HotKeyModifier.CONTROL

// Two modifiers
HotKeyModifier.CONTROL + HotKeyModifier.SHIFT

// Three modifiers
HotKeyModifier.CONTROL + HotKeyModifier.ALT + HotKeyModifier.SHIFT

// No modifier (bare key)
0
```

!!! warning "Avoid Ctrl+Alt+Fn on Linux"
    Combinations involving `Ctrl+Alt+Fn` (e.g., `Ctrl+Alt+F1`) trigger virtual terminal switching at the kernel level and **cannot** be captured by an application. Use `Ctrl+Shift` instead.

### Media Keys

Register media keys (Play/Pause, Stop, Next, Previous) without specifying a modifier:

```kotlin
val handle = GlobalHotKeyManager.register(MediaKey.PLAY_PAUSE) { _, _ ->
    println("Play/Pause pressed")
}
```

!!! note "Media keys are not supported on macOS"
    Carbon's `RegisterEventHotKey` does not expose media key codes. Use `Ctrl+Shift+<key>` as an alternative on macOS.

### Unregistering

```kotlin
GlobalHotKeyManager.unregister(handle)
```

On the portal (Wayland) backend, unregistering triggers a full rebind of remaining shortcuts to keep the portal session in sync.

### Error Handling

Check `isAvailable` before calling `initialize()`, and inspect `lastError` on any failure:

```kotlin
if (!GlobalHotKeyManager.isAvailable) {
    println("Global hotkeys not supported on this platform")
    return
}

if (!GlobalHotKeyManager.initialize()) {
    println("Init failed: ${GlobalHotKeyManager.lastError}")
    return
}
```

## Compose Desktop Integration

On Wayland, `register()` and `unregister()` block until the portal responds (CreateSession + BindShortcuts D-Bus round-trips). Call them on `Dispatchers.IO` to avoid freezing the UI thread:

```kotlin
val scope = rememberCoroutineScope()

Button(onClick = {
    scope.launch(Dispatchers.IO) {
        val handle = GlobalHotKeyManager.register(KeyEvent.VK_K,
            HotKeyModifier.CONTROL + HotKeyModifier.SHIFT
        ) { _, _ -> /* ... */ }

        withContext(Dispatchers.Main) {
            if (handle >= 0) { /* add to registered list */ }
            else println("Failed: ${GlobalHotKeyManager.lastError}")
        }
    }
}) { Text("Register") }
```

!!! tip "Dependency for `Dispatchers.Main`"
    Compose Desktop requires `kotlinx-coroutines-swing` on the classpath to provide the main dispatcher:
    ```kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:<version>")
    ```

## API Reference

### `GlobalHotKeyManager`

Thread-safe singleton.

| Member | Description |
|--------|-------------|
| `isAvailable: Boolean` | Whether the native library is loaded and functional on this platform |
| `lastError: String?` | Last error from a native operation, or `null` if the last operation succeeded |
| `initialize(): Boolean` | Initialize the subsystem. Returns `true` on success |
| `register(keyCode: Int, modifiers: Int, listener: HotKeyListener): Long` | Register a hotkey. Returns a handle ≥ 0 on success, -1 on failure |
| `register(mediaKey: MediaKey, listener: HotKeyListener): Long` | Register a media key. Returns a handle ≥ 0 on success, -1 on failure |
| `unregister(handle: Long): Boolean` | Unregister a previously registered hotkey |
| `shutdown()` | Unregister all hotkeys and stop the native event loop |

### `HotKeyModifier`

| Value | Key |
|-------|-----|
| `ALT` | Alt (Option on macOS) |
| `CONTROL` | Control |
| `SHIFT` | Shift |
| `META` | Windows key / Command (macOS) |

### `MediaKey`

| Value | Key |
|-------|-----|
| `PLAY_PAUSE` | Play / Pause toggle |
| `STOP` | Stop playback |
| `NEXT_TRACK` | Next track |
| `PREV_TRACK` | Previous track |

### `HotKeyListener`

```kotlin
fun interface HotKeyListener {
    fun onHotKey(keyCode: Int, modifiers: Int)
}
```

The callback is invoked on a platform-specific background thread. Dispatch to the UI thread as needed.

## Platform Details

### Windows

Uses Win32 `RegisterHotKey` / `UnregisterHotKey` on a dedicated message loop thread. `MOD_NOREPEAT` is set by default to suppress key-repeat events.

### macOS

Uses Carbon `RegisterEventHotKey` / `UnregisterEventHotKey`. The event handler runs on the main run loop thread via `InstallApplicationEventHandler`.

### Linux — X11

Uses `XGrabKey` / `XUngrabKey` on the root window. The implementation registers the hotkey with all 16 combinations of lock modifiers (CapsLock, NumLock, ScrollLock) so that hotkeys fire regardless of lock key state.

### Linux — Wayland

Uses the `org.freedesktop.portal.GlobalShortcuts` XDG Desktop Portal via GIO/GDBus.

**Requirements:**

- The application must have a valid `.desktop` file with a reverse-DNS name (e.g., `dev.nucleusframework.MyApp.desktop`)
- The application must be launched from that `.desktop` file (or have `GIO_LAUNCHED_DESKTOP_FILE` set)
- GNOME validates the `app_id` against `g_application_id_is_valid` — a plain name like `MyApp` will be rejected

**Step 1 — Set a reverse-DNS `packageName` in your Nucleus build config:**

`packageName` is used as the `.desktop` filename on Linux. It **must** follow reverse-DNS notation and pass `g_application_id_is_valid`. A plain name like `MyApp` or `my-app` is rejected by GNOME.

```kotlin
nucleus.application {
    nativeDistributions {
        appName    = "MyApp"                       // human-readable display name
        packageName = "dev.nucleusframework.MyApp" // becomes dev.nucleusframework.MyApp.desktop
    }
}
```

!!! warning "`appName` is required"
    Without `appName`, the application title shown in GNOME Shell and window decorations
    will fall back to the full `packageName` (`dev.nucleusframework.MyApp`).
    Always set both: `packageName` for the reverse-DNS identity, `appName` for the display name.

**Step 2 — Launch from the `.desktop` file:**

The portal uses `GIO_LAUNCHED_DESKTOP_FILE` (set automatically by the desktop environment when the app is started from its launcher entry) to identify the calling application. If you launch the app directly from a terminal, this variable is not set and GNOME will reject the session with response code 2.

For development, you can set it manually:

```bash
GIO_LAUNCHED_DESKTOP_FILE=/usr/share/applications/dev.nucleusframework.MyApp.desktop \
GIO_LAUNCHED_DESKTOP_FILE_PID=$$ \
./my-app
```

Or install the app once (via DEB/AppImage/Flatpak) so the `.desktop` file is registered, then launch from the application menu.

**How the portal session works:**

The portal backend uses a dedicated GLib thread permanently attached to the JVM. Because GNOME only allows one `BindShortcuts` call per portal session, the implementation automatically closes and recreates the session on every `register()` / `unregister()` call, then rebinds the full shortcut list. On Wayland, `register()` blocks the calling thread until GNOME responds — use `Dispatchers.IO` as shown above.

## Platform Support Matrix

| Feature | Windows | macOS | Linux X11 | Linux Wayland |
|---------|---------|-------|-----------|---------------|
| Regular hotkeys | ✅ | ✅ | ✅ | ✅ |
| Media keys | ✅ | ❌ | ✅ | ✅ |
| No-modifier (bare key) | ✅ | ✅ | ✅ | ✅ |
| Key-repeat suppression | ✅ (MOD_NOREPEAT) | ✅ | ✅ | portal-dependent |

## ProGuard

If you use ProGuard/R8, keep the JNI bridge classes:

```proguard
-keep class dev.nucleusframework.nucleus.globalhotkey.windows.NativeWindowsHotKeyBridge { *; }
-keep class dev.nucleusframework.nucleus.globalhotkey.macos.NativeMacOsHotKeyBridge { *; }
-keep class dev.nucleusframework.nucleus.globalhotkey.linux.NativeLinuxHotKeyBridge { *; }
```
