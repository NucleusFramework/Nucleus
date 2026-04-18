# Native library warmup

Nucleus modules that rely on JNI load their shared library on first access via
`NativeLibraryLoader` (lazy, cached). On **macOS** the first `dlopen` of a
given dylib pays a one-time cost — typically 100–300 ms — due to AMFI
code-signature validation on the freshly extracted file. Subsequent loads (and
all loads on Windows/Linux) are effectively free.

If the first access happens on the AWT event dispatch thread — for example
inside a Composable that opens a new screen — that cost turns into a visible
frame stutter when the user navigates to the feature for the first time.

## The fix

Each JNI-backed façade exposes a `preload()` function whose only job is to
trigger class-loading of the internal bridge object, which in turn runs
`System.load()` on the **calling** thread. Call it from a background daemon
thread early in `main()`:

```kotlin
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()

    Thread({
        AutoLaunch.preload()
        EnergyManager.preload()
        GlobalHotKeyManager.preload()
        MediaControlService.preload()
        NotificationCenter.preload()
        TaskbarProgress.preload()
        preloadDarkModeDetector()
        preloadSystemColor()
        if (Platform.Current == Platform.MacOS) {
            MacOsDockMenu.preload()
            preloadNativeMenuBar()
        }
    }, "nucleus-native-warmup").apply { isDaemon = true; start() }

    application { /* ... */ }
}
```

Only list the modules your app actually uses. `preload()` is idempotent, safe
on every platform (no-op when the native lib isn't available) and does no
significant work beyond the initial `System.load()`.

## Available preload entry points

| Module                  | Call                              |
|-------------------------|-----------------------------------|
| `autolaunch`            | `AutoLaunch.preload()`            |
| `darkmode-detector`     | `preloadDarkModeDetector()`       |
| `energy-manager`        | `EnergyManager.preload()`         |
| `global-hotkey`         | `GlobalHotKeyManager.preload()`   |
| `launcher-macos`        | `MacOsDockMenu.preload()`         |
| `media-control`         | `MediaControlService.preload()`   |
| `menu-macos`            | `preloadNativeMenuBar()`          |
| `notification-macos`    | `NotificationCenter.preload()`    |
| `system-color`          | `preloadSystemColor()`            |
| `taskbar-progress`      | `TaskbarProgress.preload()`       |

## Do I need this on Windows or Linux?

Usually no. `LoadLibrary` on Windows and `dlopen` on Linux don't carry the
code-signature validation overhead AMFI imposes on macOS. Calling `preload()`
from a background thread is still harmless on those platforms — the warmup
block above is portable and just returns fast.

## Why not do it automatically?

Auto-warming would need to either:

- hook into an unrelated lifecycle (e.g. `GraalVmInitializer.initialize()`),
  which couples modules that have nothing to do with each other, or
- discover preloaders via `ServiceLoader`, adding a registration surface per
  module and GraalVM reachability-metadata to match.

Neither matches the pattern used by the wider JVM ecosystem — sqlite-jdbc,
JNA, Netty-native, JavaCPP all load on first class access and let the
application decide when that first access happens. Nucleus follows the same
convention: `preload()` is the explicit, opt-in hook.
