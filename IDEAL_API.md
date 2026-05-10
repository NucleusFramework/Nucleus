# Building a Nucleus App

Two real pain points today:

1. `fun main()` requires six ordered init calls (`GraalVmInitializer`, scheduler dispatch, AOT training, autolaunch prime, jump list AUMID, single instance) that fail silently when one is missing or out of order.
2. Strings like the updater's `owner/repo`, the deep-link scheme, and the Windows AUMID live in both `build.gradle.kts` and the Kotlin source, with nothing linking the two.

The ideal API solves those two problems and nothing else:

- `args.nucleusApplication { … }` — one extension on `Array<String>` that runs the bootstrap in the right order.
- `NucleusGenerated` — a Kotlin object produced by the plugin so every string declared in Gradle becomes a typed constant.

Everything else stays exactly as it is today. Each OS integration keeps its native shape: Windows jump lists have categories and CLI args, macOS dock menus use in-process callbacks, Linux Unity entries expose badge/progress/quicklist on the same `.desktop` file. No cross-platform abstraction, no lowest-common-denominator — the point of Nucleus is that it lets you reach each OS *as it is*.

---

## Your First App

**`build.gradle.kts`**

```kotlin
plugins {
    id("io.github.kdroidfilter.nucleus") version "1.4.0"
}

nucleus {
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "Hello"
        packageVersion = "1.0.0"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("io.github.kdroidfilter:nucleus.application-runtime:1.4.0")
}
```

**`Main.kt`**

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {
    content {
        Window(onCloseRequest = ::exitApplication, title = "Hello") {
            Text("Hello!")
        }
    }
}
```

---

## `args.nucleusApplication { … }`

Everything in the scope is optional except `content { }`.

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {

    // Deep link: OS hands the URI to your app via CLI args. The framework
    // parses it and dispatches to the matching pattern.
    onDeepLink("/open/{id}") { id ->
        AppState.openDocument(id)
    }

    // File association: CLI arg is a file path, framework delivers it as Path.
    onFileOpen(NucleusGenerated.FileTypes.MyAppDoc) { path ->
        AppState.openFile(path)
    }

    // Started at login detection (Win32/MSIX/SMAppService/systemd/Flatpak portal).
    onStartup { source ->
        if (source == StartupSource.Autostart) AppState.startMinimized = true
    }

    // Background service triggered by OS scheduler (Task Scheduler / launchd / systemd).
    service(SyncId, trigger = Trigger.Calendar("0 3 * * *")) {
        SyncClient.runOnce()
        ServiceResult.Success
    }

    // Post-update event, delivered once then cleared.
    onUpdateApplied { event ->
        Notifications.send { title = "Updated to v${event.newVersion}" }
    }

    // AOT training preload — only runs during the training build, auto-exits.
    onAotTraining {
        preloadHotPaths()
    }

    // Required — the Compose root.
    content {
        MaterialDecoratedWindow(onCloseRequest = ::exitApplication, title = "My App") {
            App()
        }
    }
}
```

### Available blocks

| Block | Fires on | Argument |
|---|---|---|
| `onDeepLink(pathPattern)` | Incoming deep link URI via CLI args | Path variables + query |
| `onFileOpen(fileType)` | File association opened | `Path` |
| `onStartup` | App start, after bootstrap | `StartupSource` (Manual, Autostart, PostUpdate) |
| `onNewIntent` | Second-instance relay delivers a new URI/file | `Activation` |
| `onUpdateApplied` | Once after an auto-update | `UpdateEvent` |
| `onAotTraining` | Only during the AOT training build | — |
| `onSystemSuspend` / `onSystemResume` | OS sleep / wake | — |
| `onLowPower` | Battery / thermal change | `PowerState` |
| `service(id, trigger) { … }` | OS scheduler fires the task | `Activation`, returns `ServiceResult` |
| `content { … }` | Required — the Compose root | `ApplicationScope` |

All handler lambdas run on the AWT Event Dispatch Thread.

---

## The Manifest

`nucleus { }` in `build.gradle.kts` stays distribution-focused. Only `intentFilters` and `publish` affect runtime typing.

```kotlin
nucleus {
    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "MyApp"
        packageVersion = "1.0.0"
        vendor = "My Company"
        macOS { bundleID = "com.example.myapp" }
    }

    intentFilters {
        protocol("myapp")
        fileAssociation("application/x-myapp", extension = "myapp",
                        displayName = "MyApp Document", icon = "icons/doc.ico")
    }

    publish {
        github { owner = "myorg"; repo = "myapp" }
    }

    enableAotCache = true
}
```

Everything declared here surfaces as a typed constant in `NucleusGenerated`.

---

## `NucleusGenerated`

Generated at build time by the plugin. One source of truth for every string that used to live in two places.

```kotlin
NucleusGenerated.appId                    // "MyApp"
NucleusGenerated.version                  // "1.0.0"
NucleusGenerated.BundleIds.macOS          // "com.example.myapp"

NucleusGenerated.Schemes.MyApp            // Scheme("myapp")
NucleusGenerated.FileTypes.MyAppDoc       // FileType("application/x-myapp", "myapp")

NucleusGenerated.Update.provider          // GitHubProvider("myorg", "myapp")
NucleusGenerated.Update.channel           // "latest"

NucleusGenerated.Windows.aumid            // "MyCompany.MyApp"
NucleusGenerated.Windows.startupTaskId    // "SlackStartup"
```

The updater picks this up by default:

```kotlin
Updater.autoCheckOnStart(httpClient = NativeHttpClient.create())
```

Build a deep-link URI without hardcoding the scheme:

```kotlin
val openA = NucleusGenerated.Schemes.MyApp.uri("/open/a")   // "myapp://open/a"
```

---

## OS Integrations — Kept Native

Each platform's API stays faithful to the OS. No unified abstraction.

### Windows — jump lists, badge, overlay, thumbnail toolbar

Jump list items launch a **new process** with CLI arguments — that's how Windows works. The framework's single-instance relay forwards the args to your running instance, where `onDeepLink` receives them.

```kotlin
WindowsJumpList.set {
    category("Recent") {
        item("Project A", icon = StockIcon.FOLDER,
             arguments = NucleusGenerated.Schemes.MyApp.uri("/open/a"))
        item("Project B", icon = StockIcon.FOLDER,
             arguments = NucleusGenerated.Schemes.MyApp.uri("/open/b"))
    }
    tasks {
        item("New Window", icon = StockIcon.DESKTOP_PC,
             arguments = NucleusGenerated.Schemes.MyApp.uri("/new-window"))
    }
}

WindowsBadge.setCount(5)                    // MSIX only
WindowsOverlayIcon.set(window, StockIcon.WARNING)
WindowsThumbnailToolbar.setButtons(window, listOf(/* … */)) { id -> /* … */ }
```

### macOS — dock menu with in-process callbacks

The dock menu uses an `NSMenu` delegate — clicks fire **in-process** lambdas. No CLI arg round-trip.

```kotlin
MacOsDockMenu.set {
    item("New Window") { AppState.newWindow() }
    item("Preferences…") { AppState.openSettings() }
    divider()
    submenu("Recent") {
        item("Project A") { AppState.openDocument("a") }
        item("Project B") { AppState.openDocument("b") }
    }
}
```

### Linux — Unity LauncherEntry: quicklist + badge + progress + urgency

A single `LinuxLauncher` entry covers quick list, badge count, progress bar, and urgency — they all live on the same `.desktop` file via `com.canonical.Unity.LauncherEntry`. Quick list clicks fire in-process lambdas via `com.canonical.dbusmenu`.

```kotlin
LinuxLauncher.quickList {
    item("New Window") { AppState.newWindow() }
    item("Open Recent") { AppState.openRecent() }
}
LinuxLauncher.badgeCount = 5
LinuxLauncher.progress = 0.3
LinuxLauncher.urgency = Urgency.Attention
```

### Everything else — unchanged singletons

```kotlin
Updater.checkForUpdates()
AutoLaunch.enable()
Notifications.send { title = "Hi"; button("Open") { /* in-process lambda */ } }
Tray.TrayApp(icon = …) { /* … */ }
Taskbar.setProgress(0.3)
MediaControl.setMetadata(…)
Hotkeys.register("Ctrl+Alt+O") { /* … */ }
Preferences.put("last-sync", now.toString())
Logger.info("Ready")
Energy.keepScreenAwake()

val isDark by DarkMode.isActive.collectAsState()
val accent by SystemColor.accent.collectAsState()
```

---

## Recipes

### Deep link + file open hit the same handler

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {
    onDeepLink("/open/{id}") { id -> AppState.openDocument(id) }
    onFileOpen(NucleusGenerated.FileTypes.MyAppDoc) { path ->
        AppState.openDocument(readId(path))
    }

    content {
        Window(onCloseRequest = ::exitApplication, title = "Docs") { DocsApp() }
    }
}

// The Windows jump list triggers the same path via CLI args + relay:
WindowsJumpList.set {
    category("Recent") {
        item("Project A", arguments = NucleusGenerated.Schemes.MyApp.uri("/open/a"))
    }
}
```

### Nightly sync

```kotlin
val SyncId = ServiceId("sync")

fun main(args: Array<String>) = args.nucleusApplication {
    service(SyncId, trigger = Trigger.Calendar("0 3 * * *")) {
        val updated = SyncClient.runOnce()
        Notifications.send { title = "Sync complete"; message = "${updated.size} items" }
        ServiceResult.Success
    }

    content { Window(onCloseRequest = ::exitApplication, title = "App") { App() } }
}
```

### Taskbar progress during a long operation

```kotlin
suspend fun export(src: Path, dst: Path) {
    Taskbar.setProgress(0.0)
    Energy.keepScreenAwake()
    try {
        copy(src, dst) { done, total -> Taskbar.setProgress(done.toDouble() / total) }
    } finally {
        Taskbar.clearProgress()
        Energy.releaseScreenAwake()
    }
}
```

### Tray-only app

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {
    content {
        Tray.TrayApp(icon = Icons.Default.Dashboard, tooltip = "Dashboard") {
            QuickDashboard()
        }
    }
}
```

### AOT training preload

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {
    onAotTraining {
        preloadNavigationScreens()
        preloadFontsAndImages()
    }

    content { Window(onCloseRequest = ::exitApplication, title = "App") { App() } }
}
```

---

## Bootstrap Order (Reference)

What `args.nucleusApplication { }` runs before `content { }` opens:

1. `GraalVmInitializer.initialize()` — fonts, charset, HiDPI, `java.home`.
2. If launched by a scheduler → dispatch the matching `service(id)` and exit.
3. If `AotRuntime.isTraining()` → start the auto-exit timer and call `onAotTraining`.
4. Prime `AutoLaunch.wasStartedAtLogin(args)`.
5. Windows non-APPX → set the jump list AUMID.
6. Acquire single-instance lock. On secondary → hand off args to primary, exit.
7. Parse deep-link / file-open args into the initial activation.
8. Read the post-update marker → call `onUpdateApplied` if present.
9. Call `onStartup`, then the matching `onDeepLink` / `onFileOpen` handler if there was one.
10. `content { }` runs.

Later activations (second-instance relays, new deep links, jump list clicks on Windows) arrive through `onNewIntent` and the matching `onDeepLink` / `onFileOpen` handler.

---

## Configuring Subsystems

The defaults are driven by the manifest (appId for lock identifier, `publish { github }` for updater provider, etc.) and work for most apps. When you need to override, each subsystem has its own block inside `args.nucleusApplication { }`. All blocks are optional.

### `updater { }`

```kotlin
args.nucleusApplication {
    updater {
        // provider defaults to NucleusGenerated.Update.provider — only override to switch:
        provider = GenericProvider("https://updates.example.com")
        channel = "beta"
        allowDowngrade = false
        allowPrerelease = true
        currentVersion = "1.2.3"        // defaults to NucleusGenerated.version
        executableType = null           // auto-detected
        httpClient = NativeHttpClient.create()

        // Optional side effects — enable either or both:
        autoCheckOnStart = true
        onUpdateReady { info ->
            // Called when an update is downloaded and ready to install.
            // Default: do nothing. Return InstallPolicy.InstallNow / OnQuit / Skip.
            InstallPolicy.OnQuit
        }
    }
}
```

Drop the block entirely and the singleton `Updater` is still configured from the manifest — you can drive it imperatively:

```kotlin
when (val r = Updater.checkForUpdates()) {
    is UpdateResult.Available -> { /* custom UI */ }
    else -> Unit
}
```

Need multiple update endpoints (e.g. beta vs stable switch)? Construct your own instance — the singleton is a convenience, not a requirement:

```kotlin
val betaUpdater = NucleusUpdater {
    provider = GenericProvider("https://updates.example.com/beta")
    channel = "beta"
}
```

### `singleInstance { }`

```kotlin
args.nucleusApplication {
    singleInstance {
        enabled = true                                              // default
        lockIdentifier = "com.example.myapp"                        // default: NucleusGenerated.appId
        lockFilesDir = Paths.get(System.getProperty("user.home"), ".myapp")

        onSecondInstance { activation ->
            // Custom handling of the relayed activation. Default: dispatch
            // to onNewIntent + the matching onDeepLink/onFileOpen handler.
            Logger.info("Second instance tried to launch with: ${activation.args.joinToString()}")
        }
    }
}
```

Set `enabled = false` to allow multiple concurrent instances.

### `autoLaunch { }`

```kotlin
args.nucleusApplication {
    autoLaunch {
        taskId = "MyCustomMsixTaskId"                  // MSIX only
        registryValueName = "MyApp"                    // Win32 HKCU\...\Run key
        executablePath = "C:\\Program Files\\MyApp\\MyApp.exe"
        autostartArgument = "--autostart"              // pass null to omit
        backgroundReason = "Keep MyApp ready at login" // Linux Flatpak portal prompt
    }
}
```

These map 1:1 to today's `AutoLaunchConfig.*` — just grouped into a DSL so they're set before the first `AutoLaunch` call automatically.

### `aotCache { }`

```kotlin
args.nucleusApplication {
    aotCache {
        trainingDuration = 60.seconds       // default 45s
        exerciseHotPaths = true             // default true — runs onAotTraining block
    }
}
```

Build-time activation stays in the manifest (`enableAotCache = true`), since the plugin needs to know whether to generate the `app.aot` file.

### `deepLink { }`

```kotlin
args.nucleusApplication {
    deepLink {
        // Second-instance relay strategy — defaults to file-based IPC via SingleInstanceManager.
        relayStrategy = RelayStrategy.FileIpc    // or Custom { … } for your own transport
        onUnhandled { uri ->
            Logger.warn("No handler matched: $uri")
        }
    }

    onDeepLink("/open/{id}") { id -> /* … */ }
}
```

### `service { }` — global service settings

```kotlin
args.nucleusApplication {
    services {
        safetyTimeout = 300.seconds      // max runtime per firing
        minInterval = 15.minutes         // floor enforced on Trigger.Periodic
    }
}
```

Individual services are still declared inline via `service(id, trigger) { … }`.

### Full escape hatch

Nothing in `args.nucleusApplication { }` removes access to the underlying singletons. You can still:

```kotlin
SingleInstanceManager.isSingleInstance(onRestoreRequest = { /* … */ })
AutoLaunchConfig.taskId = "…"
Updater = NucleusUpdater { /* fully custom */ }
```

The DSL is a more convenient front door, not a wall.

---

## Migration From Raw `main()`

Today:

```kotlin
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()
    AutoLaunch.wasStartedAtLogin(args)
    if (Platform.Current == Platform.Windows) WindowsJumpListManager.setProcessAppId()
    DeepLinkHandler.register(args) { uri -> handleDeepLink(uri) }
    if (AotRuntime.isTraining()) Thread({ Thread.sleep(45_000); exitProcess(0) }).start()

    application {
        val isFirstInstance = remember {
            SingleInstanceManager.isSingleInstance(
                onRestoreFileCreated = { DeepLinkHandler.writeUriTo(this) },
                onRestoreRequest = { DeepLinkHandler.readUriFrom(this) },
            )
        }
        if (!isFirstInstance) { exitApplication(); return@application }

        val updater = remember {
            NucleusUpdater {
                provider = GitHubProvider(owner = "myorg", repo = "myapp")  // duplicated
            }
        }
        // …
    }
}
```

After:

```kotlin
fun main(args: Array<String>) = args.nucleusApplication {
    onDeepLink("/{rest...}") { rest, _ -> handleDeepLink(rest) }
    onAotTraining { /* optional preload */ }

    content {
        // Updater is preconfigured from publish { github { } } — no strings to retype.
        Window(onCloseRequest = ::exitApplication, title = "My App") { App() }
    }
}
```

No ordered init calls. No duplicated strings. Every OS integration (jump list, dock menu, Unity launcher, notifications, tray) keeps its native shape — what you write maps 1:1 to what the OS actually does.

---

## Signature

```kotlin
fun Array<String>.nucleusApplication(block: NucleusApplicationScope.() -> Unit)
```
