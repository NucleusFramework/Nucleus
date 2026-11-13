# macOS widgets internals

`widget-macos` - the 1:1 binding of WidgetKit's app-side API (`WidgetCenter`: `reloadTimelines(ofKind:)`, `reloadAllTimelines()`, `currentConfigurations()`, `invalidateConfigurationRecommendations()`, `invalidateRelevance(ofKind:)`, `currentPushInfo`, `UserInfoKey`; `ControlCenter` on macOS 26; `WidgetInfo`, `WidgetFamily`), `DarwinNotificationCenter` (`notify(3)`), a prebuilt generic WidgetKit extension, and `MacWidgetHost`, the host of `widget-runtime`'s Glance-like API.

## Using it

```kotlin
fun main(args: Array<String>) = nucleusApplication(args) {
    widgets { widget("Counter") { CounterWidget() } }
    if (!isWidgetHostOnly()) DecoratedWindow(onCloseRequest = ::exitApplication) { … }
}
```

```kotlin
macOS {
    widgets {
        widget("Counter") {
            displayName = "Counter"
            description = "A live counter"
            families = listOf(MacWidgetFamily.Small, MacWidgetFamily.Medium)
        }
    }
}
```

Widgets need macOS 14 (interactive widgets) and a bundled app: `run` has no bundle, so `MacWidgetHost.isSupported` is false there; use `runDistributable` or an installed build.

## Architecture

WidgetKit draws widgets in a sandboxed extension process and only runs Swift. Nucleus ships one generic extension (`src/main/native/macos/extension/`), built once by `build.sh` into `nucleus/native/darwin-<arch>/nucleus-widget-extension/` of the JAR (the executable and its `Metadata.appintents`; that subdirectory is in no native-libraries manifest, so it stays in the JAR). `META-INF/nucleus/packaging-only/widget-macos` lists it: the plugin reads it from the original JAR, and leaves it out of the application's JARs when it moves the native libraries out (jpackage and the GraalVM image alike). The plugin's `prepare…MacWidgetsExtension` task wraps it into `Contents/PlugIns/NucleusWidgets.appex` with an Info.plist listing the declared widgets (`NucleusWidgets` key) and hands it to the `appExtensions` signing pipeline (jpackage, electron-builder and GraalVM paths).

- **Kinds at run time.** `WidgetBundleBuilder` only takes `if #available`, so the extension carries `Bundle1` … `Bundle16` and its `@MainActor` entry point picks the one matching the Info.plist's widget count (16 at most).
- **Store.** The app and the extension share a directory: `~/Library/Application Support/<bundle id>/NucleusWidgets`, reached by the extension through a `temporary-exception.files.home-relative-path` entitlement, or the App Group of `widgets { appGroup = "TEAMID.…" }` (required for a sandboxed app; App Review rejects temporary exceptions). The JNI library reads the store from the embedded extension's Info.plist, so both sides agree. The app's own entitlements are the app's: packaging only warns when they miss the App Group. Request files come from another process, so the host accepts only a declared kind, a WidgetKit family and a UUID-like request id before any of them becomes a store path. Layout: `WidgetStore`.
- **Rendering.** `MacWidgetHost` composes each widget, writes `renders/<kind>/<family>.json` (`WidgetTreeEncoder`, short keys) and calls `reloadTimelines(ofKind:)`; the extension's timeline reads the file and draws it with SwiftUI. Images are copied into the store first (`StoreImages`): the sandbox reaches neither the app's resources nor the network. Previews for the gallery are rendered from an empty state at every start (`previews/`).
- **Identity.** A widget without configuration intent has no instance id in WidgetKit: a `WidgetId` is `<kind>/<family>`, so two widgets of one kind and size share content and state. Removing a widget drops its state, as on Windows (seen when `pinnedWidgets()` runs, on the engine's thread): only once WidgetKit no longer lists it on two reads 30 s apart, since one transient empty answer would wipe every state. A widget off screen is not refreshed, so an update never brings its files back.
- **Clicks.** `Button`, a clickable modifier, a `Switch` and each `ChoiceField` option are `Button(intent:)` / `Toggle(isOn:intent:)` on `NucleusWidgetAction` (widget, key, signature, inputs). Its `perform()` writes `requests/<id>.json`, posts `<bundle id>.nucleus-widgets.request` (Darwin notification), starts the app with `NSWorkspace.openApplication` (not activated) when it is not running, and waits up to 15 s for `done/<id>`, so the timeline WidgetKit reloads afterwards shows the result. The host runs the action only if its key still names the same element (`WidgetActionSignatures`, shared with Windows), otherwise reports `ActionDropped`. `actionOpenUrl` is a SwiftUI `Link`. Text and number entry and customization are `widget-windows` APIs (WidgetKit has neither); a `TextField` / `NumberField` that reaches this host is left out of the render, since a field that cannot be typed in would only open the app when clicked.
- **Process lifetime.** The extension writes `launched-by-widget` before starting the app; `isWidgetHostOnly()` is true for that launch and exits after 30 s without widget events. A first render of a widget the app never drew also starts the app; a timeline reload of a drawn widget never does.

## Silent failures (and what each was)

- **The extension crashes at launch in `_EXRunningExtension._shared`** (`Failed to create running extension`). It was entered at `main`; Xcode links app extensions with `-e _NSExtensionMain` (and `-application-extension`), which sets up the ExtensionKit context first. `build.sh` does the same.
- **Clicks do nothing, chronod logs `There is no metadata for NucleusWidgetAction`** and linkd `Unable to get teamId … Rejecting invalid client due to requiresValidatedBundle`. Two causes, often stacked. Signing: AppIntents only run in a bundle signed with a Team ID (Developer ID or Apple Development); ad-hoc builds render and update from the app, but linkd rejects their clicks. Checked on the desktop: the Lab signed Apple Development runs its widget clicks. A shared bundle id (next item) gives the same metadata error once the signature is accepted. The metadata itself comes from `swiftc -emit-const-values-path` + `appintentsmetadataprocessor` (Xcode only, not the command-line tools).
- **The gallery keeps showing an old widget** after the extension changed: chronod reuses a running extension process and caches descriptors per version. Kill the `NucleusWidgets` process and bump `CFBundleVersion` (or reinstall).
- **Re-signing a running app kills it** (its pages no longer match the signature).
- Many builds sharing one bundle id (worktrees) are all registered with LaunchServices, and linkd reads the intent metadata from another copy: clicks fail with `There is no metadata for NucleusWidgetAction in <bundle id>` even in a team-signed build (seen with 15 Lab copies). Test under a unique bundle id, or `lsregister -u` the other copies. Re-registering the one under test (`lsregister -f`) is not enough.

## Debugging

- `log show --predicate 'process == "NucleusWidgets" OR (process == "chronod" AND eventMessage CONTAINS "<bundle id>")'`: descriptor queries (`query returned N widget descriptors`), timeline requests, actions.
- Crash reports: `~/Library/Logs/DiagnosticReports/NucleusWidgets-*.ips`.
- Simulate a click without the AppIntents runner: write `{"id":"x","type":"action","widget":"Kind/systemSmall","key":"a0","signature":"button:+1","inputs":"{}"}` into `requests/x.json`, then `notifyutil -p <bundle id>.nucleus-widgets.request`; `done/x` appears once the app handled it.
- The Lab probe `shell.macos-widgets` lists the widgets on screen, pushes updates from the app and logs `NucleusWidgets.events`.
