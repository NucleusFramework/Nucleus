# Auto-Launch

Cross-platform auto-launch at user login for JVM desktop applications.

Nucleus auto-detects the runtime packaging at startup (via `ExecutableRuntime`) and dispatches to the correct backend:

| Packaging | API used | Detection signal |
|---|---|---|
| MSIX | `Windows.ApplicationModel.StartupTask` (WinRT) | `GetCurrentPackageFullName` succeeds |
| Win32 (MSI / NSIS) | `HKCU\...\Run` + `HKCU\...\Explorer\StartupApproved\Run` | Process is not packaged |
| macOS DMG / PKG | `SMAppService.mainApp` (macOS 13+) | Always routed to SMAppService — works for DMG (Developer ID) and PKG (Mac App Store, sandboxed) |
| macOS < 13 | — (returns `UNSUPPORTED`) | — |
| Linux (deb / rpm / AppImage / dev) | systemd user service | Not running inside Flatpak |
| Linux (Flatpak) | XDG Desktop Portal (Background) | Running inside Flatpak |

The runtime exposes a single unified API — consumers don't need to branch on packaging themselves.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.autolaunch:<version>")
}
```

```kotlin
import dev.nucleusframework.nucleus.autolaunch.AutoLaunch
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchResult
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchState
```

## Usage

```kotlin
when (val state = AutoLaunch.state()) {
    AutoLaunchState.ENABLED           -> println("Starts at login")
    AutoLaunchState.DISABLED          -> AutoLaunch.enable()
    AutoLaunchState.DISABLED_BY_USER  -> AutoLaunch.openSystemSettings()
    AutoLaunchState.DISABLED_BY_POLICY,
    AutoLaunchState.ENABLED_BY_POLICY -> { /* read-only, GPO */ }
    AutoLaunchState.UNSUPPORTED       -> { /* macOS < 13, unsupported Linux env */ }
}
```

Toggling from a Compose UI:

```kotlin
Switch(
    checked = AutoLaunch.isEnabled(),
    enabled = !AutoLaunch.isUserLocked(),
    onCheckedChange = { checked ->
        val result = if (checked) AutoLaunch.enable() else AutoLaunch.disable()
        // Show a message if result == BLOCKED_BY_USER
    },
)
```

## API

### `AutoLaunch`

| Method | Returns | Notes |
|---|---|---|
| `state()` | `AutoLaunchState` | Current auto-launch state |
| `isEnabled()` | `Boolean` | `true` for `ENABLED` or `ENABLED_BY_POLICY` |
| `isUserLocked()` | `Boolean` | `true` when state is `DISABLED_BY_USER` |
| `enable()` | `AutoLaunchResult` | See rules below |
| `disable()` | `AutoLaunchResult` | — |
| `openSystemSettings()` | `Boolean` | Opens `ms-settings:startupapps` on Windows; `SMAppService.openSystemSettingsLoginItems()` on macOS; best-effort on Linux (tries `gnome-control-center`, `systemadm`, `xdg-open ~/.config/autostart`). Returns `false` if nothing worked |
| `wasStartedAtLogin(args)` | `Boolean` | `true` if the process was launched by auto-launch. Supported on all backends (Win32, MSIX, macOS, Linux systemd, Linux Flatpak) |

### `AutoLaunchState`

| Value | Meaning |
|---|---|
| `ENABLED` | Will start at next logon |
| `DISABLED` | Not configured |
| `DISABLED_BY_USER` | **User toggled off via Task Manager / Settings — programmatic re-enable is blocked** |
| `DISABLED_BY_POLICY` | Blocked by Group Policy (MSIX only) |
| `ENABLED_BY_POLICY` | Forced on by Group Policy (MSIX only) |
| `UNSUPPORTED` | Platform or packaging not supported (macOS < 13, Linux without systemd / portal, missing JNI lib) |

### `AutoLaunchResult`

| Value | Meaning |
|---|---|
| `OK` | State changed |
| `UNCHANGED` | State already matches the request |
| `BLOCKED_BY_USER` | User has explicitly disabled via system UI — do NOT retry |
| `BLOCKED_BY_POLICY` | Group Policy blocks the change |
| `UNSUPPORTED` | Platform not supported |
| `ERROR` | Native call failed |

## MSIX auto-injection

For MSIX packages, the Windows runtime requires a `<uap5:StartupTask TaskId="...">` entry in the application manifest. The Nucleus Gradle plugin injects this automatically when you enable the extension in your `appx { }` block:

```kotlin
nucleus.application {
    nativeDistributions {
        windows {
            appx {
                applicationId = "NucleusDemo"
                publisherDisplayName = "NucleusFramework"
                displayName = "Nucleus Demo"

                // 1. Inject <desktop:Extension Category="windows.startupTask"> into Package.appxmanifest
                addAutoLaunchExtension = true

                // 2. Override the TaskId (rarely needed). electron-builder hardcodes
                //    "SlackStartup" in the generated manifest; the plugin exposes that
                //    same value at runtime via NucleusApp.startupTaskId.
                //    Only override if you also post-process AppxManifest.xml yourself.
                // startupTaskId = "MyCustomStartupId"
            }
        }
    }
}
```

With `addAutoLaunchExtension = true`, the plugin:

1. Instructs electron-builder to inject the `windows.startupTask` extension into the generated `Package.appxmanifest`.
2. Writes the TaskId used in that manifest (`"SlackStartup"` — hardcoded by electron-builder for legacy reasons) into the app metadata resource (`nucleus-app.properties`) so it's available at runtime as `NucleusApp.startupTaskId`.
3. The `autolaunch` module picks that up automatically — no code changes required.

!!! warning "The TaskId is `SlackStartup`"
    electron-builder hardcodes `TaskId="SlackStartup"` in the generated manifest (legacy from its Slack origins). This is harmless — Windows just treats it as an opaque identifier — but it means setting `startupTaskId = "MyCustom"` **alone does not change the manifest**. If you need a different TaskId, you must also post-process the generated `AppxManifest.xml`.

!!! info "Task Manager display lag (MSIX only)"
    On MSIX, toggling auto-launch via `AutoLaunch.enable()` / `disable()` **does not update the Task Manager "Startup apps" tab live**. Windows caches the display at session start and only refreshes it after the user logs off and back on (or reboots). The state is persisted correctly — `AutoLaunch.state()` returns the new value immediately, and the next login will honor the new setting. This is an OS-level Task Manager limitation, not an issue with the API. The Win32 backend (MSI / NSIS) does not have this lag.

## Win32 / MSI / NSIS behavior

No manifest is involved; the backend reads and writes two registry keys under `HKCU`:

- `Software\Microsoft\Windows\CurrentVersion\Run` — the actual launch entry (written by `enable()`).
- `Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run` — the user's Task Manager / Settings toggle state (read-only from our side unless the user explicitly disables via our own UI).

The value name defaults to `NucleusApp.appName` (or `appId` if missing). The `Run` value is written as `"<exe>" --nucleus-autostart`. You can inspect the `--nucleus-autostart` argument in your own `main()` to differentiate an auto-launched start from a manual one.

When the user toggles your app off via Task Manager → Startup, Windows records that in `StartupApproved\Run`. Nucleus reads this so `state()` returns `DISABLED_BY_USER` and `enable()` returns `BLOCKED_BY_USER` instead of silently overwriting the user's choice.

## macOS behavior

Nucleus registers the main application with `SMAppService.mainApp` — the modern ServiceManagement API that Apple recommends since Ventura, and the one used by `sindresorhus/LaunchAtLogin-Modern`. The entry appears under **System Settings → General → Login Items → Open at Login** for both DMG (Developer ID) and PKG (Mac App Store, sandboxed) distributions. No helper app, no bundled plist, no build-time plugin configuration.

The macOS JNI bridge ships as a companion module (`nucleus.service-management-macos`) and is pulled in automatically — no extra dependency to declare.

### Status mapping

| `SMAppServiceStatus` | `AutoLaunchState` | Notes |
|---|---|---|
| `enabled` | `ENABLED` | User approved; launches at next login |
| `notRegistered` / `notFound` | `DISABLED` | No record in BackgroundTaskManagement yet — call `enable()` |
| `requiresApproval` | `DISABLED_BY_USER` | User must approve in System Settings |

### First-run approval

After `enable()`, macOS may leave the service in `requiresApproval` until the user opens **System Settings → Login Items** and flips the switch. Call `AutoLaunch.openSystemSettings()` to deep-link there.

### Detection of an auto-launched start

Apple ships no public API to detect an `SMAppService.mainApp` login launch — the legacy `keyAELaunchedAsLogInItem` AppleEvent fires only for the deprecated `SMLoginItemSetEnabled`, not for modern `SMAppService` (radar FB10207829).

Nucleus uses the empirical `LaunchInstanceID` environment variable that `launchd` injects into every process it spawns as a managed job, including `SMAppService.mainApp` login items. Launches issued by the user (Finder, Dock, Spotlight, `open(1)`) don't carry it.

The CLI `args` parameter is unused on macOS and kept for API symmetry with Windows.

### macOS < 13

`SMAppService` requires macOS 13.0+ (Ventura). On older releases, `AppServiceManager.isAvailable` returns `false` and every call reports `UNSUPPORTED` — there is no legacy fallback in the runtime.

## Linux behavior

Nucleus picks the right backend automatically — nothing to configure:

- **Host installs (deb / rpm / AppImage / dev runs)** — registered as a systemd user service.
- **Flatpak** — registered via `org.freedesktop.portal.Background.RequestBackground`. The portal writes a standard autostart `.desktop` file to `~/.config/autostart/`. Whether the user sees a confirmation is backend-dependent: GNOME 45+ grants silently, other backends may show a dialog, and a dialog always appears if the app's background permission was previously denied.

`AutoLaunchConfig.backgroundReason` sets the `reason` string passed to the portal — used by backends that do surface a dialog (defaults to `"Launch <appName> at login"`).

!!! info "`openSystemSettings()` on Linux"
    Best-effort, no single desktop-neutral API. The systemd backend tries `gnome-control-center applications` → `systemadm --user` → `xdg-open ~/.config/systemd/user`. The Flatpak backend tries `gnome-control-center applications` → `xdg-open ~/.config/autostart`. Returns `true` as soon as one command launches successfully, `false` if none of them exist.

## Configuration overrides

All defaults fall back to `NucleusApp`. Override any of them **before** the first `AutoLaunch` call:

```kotlin
import dev.nucleusframework.nucleus.autolaunch.AutoLaunchConfig

AutoLaunchConfig.taskId            = "MyCustomMsixTaskId"                 // MSIX
AutoLaunchConfig.executablePath    = "C:\\Program Files\\MyApp\\MyApp.exe" // Win32, Linux systemd (full path), Linux Flatpak (basename used)
AutoLaunchConfig.autostartArgument = "--autostart"                         // Win32 + Linux Flatpak, pass null to omit
AutoLaunchConfig.registryValueName = "MyApp"                               // Win32 HKCU\...\Run key
AutoLaunchConfig.backgroundReason  = "Keep MyApp ready at login"           // Linux Flatpak portal prompt
```

## Detecting an auto-launched start

```kotlin
fun main(args: Array<String>) {
    if (AutoLaunch.wasStartedAtLogin(args)) {
        // Started automatically — e.g. skip splash, start minimized in tray, etc.
    }
}
```

Detection is transparent across packaging types and uses the **signal that is deterministic for each backend**:

| Backend | Mechanism |
|---|---|
| Win32 (MSI / NSIS) | Looks for the marker argument (`--nucleus-autostart` by default, configurable via `AutoLaunchConfig.autostartArgument`) written into the `HKCU\...\Run` command line |
| macOS `SMAppService.mainApp` | Reads the `LaunchInstanceID` env var that `launchd` injects into processes it spawns as login items. User-initiated launches (Finder, Dock, Spotlight) don't carry it |
| MSIX packaged desktop | Walks up the process tree (skipping self-spawned jpackage launcher chains) and checks if the external ancestor is `sihost.exe` — the Shell Infrastructure Host that launches MSIX startup-task activations. Manual launches (Start menu, Explorer, taskbar) come from `explorer.exe` or `runtimebroker.exe` |
| Linux (systemd) | `true` when the process was spawned by systemd at login; `false` when launched manually from a terminal or `.desktop` entry |
| Linux (Flatpak) | `true` when launched by the portal's autostart entry; `false` on a manual `flatpak run` |

Note on macOS: `LaunchInstanceID` is undocumented — Apple provides no public API to detect an `SMAppService.mainApp` login launch (feedback FB10207829, unresolved since June 2022). Treat this signal as empirical but reliable in practice across current macOS releases.

!!! warning "Dev-mode short-circuit"
    `wasStartedAtLogin()` returns `false` unconditionally when [`ExecutableRuntime.isDev()`](executable-type.md) is `true` (Gradle `:run`, IDE launches). Backends rely on env vars or AppleEvents inherited from the parent shell — notably macOS's `LaunchInstanceID` inherited from Terminal — which would otherwise produce false positives.

    **Production builds must configure** either the `nucleus.executable.type` system property or the `.nucleus-executable-type` marker file. Otherwise `ExecutableRuntime` defaults to `DEV` and `wasStartedAtLogin()` will always return `false`, masking real login launches. The Nucleus Gradle plugin writes the marker automatically for packaged distributions — only custom packaging needs manual setup.

## Rules to respect

Three invariants are enforced by the API — trying to work around them is counter-productive:

1. **Never loop on `BLOCKED_BY_USER`.** Both on MSIX (`DisabledByUser`) and Win32 (`StartupApproved` flag `0x03`), an explicit user disable is final until the user manually re-enables it via system UI. Calling `enable()` again is a no-op on MSIX and would disrespect an explicit choice on Win32. The backend detects this and returns `BLOCKED_BY_USER` without touching anything.

2. **Silent updates must not reset auto-launch state.** The Win32 backend only writes to `Run` when `enable()` is called. It never touches `StartupApproved` unless you call `disable()` (which removes both entries to avoid ghost items in Task Manager). This means an updater that reinstalls your app will not accidentally override a user's "off" choice.

3. **MSIX requires the manifest extension.** `AutoLaunch` on MSIX will return `UNSUPPORTED` / `ERROR` if `<uap5:StartupTask>` is missing from `Package.appxmanifest`. Use `addAutoLaunchExtension = true` (see above) and let the plugin handle it.

## GraalVM

The module ships `reachability-metadata.json` declaring `NativeAutoLaunchBridge` as JNI-accessible. No additional configuration is required for native-image builds.
