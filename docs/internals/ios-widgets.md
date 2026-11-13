# iOS widgets internals

`widget-ios` - **Kotlin Multiplatform** (`iosArm64`, `iosSimulatorArm64`): the 1:1 binding of WidgetKit's app-side API (`WidgetCenter`: `reloadTimelines(ofKind:)`, `reloadAllTimelines()`, `currentConfigurations()`, `invalidateConfigurationRecommendations()`, `invalidateRelevance(ofKind:)`; `ControlCenter` on iOS 18; `WidgetInfo`, `WidgetFamily` with the Lock Screen families), `IosWidgetHost`, the host of `widget-runtime`'s Glance-like API, and the Swift sources an app adds to its targets (`src/swift`).

## Using it

The whole API is experimental (`@ExperimentalNucleusWidgetApi`, opt-in level ERROR): opt in module-wide with `compilerOptions { optIn.add("dev.nucleusframework.widget.ExperimentalNucleusWidgetApi") }`.

Kotlin (the app's shared module, a framework that **exports** `widget-ios`):

```kotlin
fun startWidgets() = startIosWidgets {
    widget("Counter") { CounterWidget() }
}
```

```kotlin
listOf(iosArm64(), iosSimulatorArm64()).forEach {
    it.binaries.framework {
        baseName = "Shared"
        export("dev.nucleusframework:nucleus.widget-ios:<version>")
    }
}
```

Xcode:
- **App target:** call `SharedKt.startWidgets()` at launch, add `widget-ios/src/swift/NucleusWidgetCenter.swift`.
- **Widget extension target:** links the same framework. Add `widget-runtime/src/main/native/swiftui/NucleusWidgetUI.swift`, `widget-ios/src/swift/NucleusWidgetKit.swift` and `NucleusWidgetCenter.swift`, then declare the widgets:

```swift
@main struct AppWidgets: WidgetBundle {
    init() { SharedKt.startWidgets() }
    var body: some Widget {
        NucleusWidget(kind: "Counter", displayName: "Counter", families: [.systemSmall, .systemMedium])
    }
}
```

- **Both targets:** the App Group entitlement, and its id under `NucleusWidgetsAppGroup` in both Info.plists (or `startIosWidgets(appGroup = …)`).

## Architecture

An iOS app does not run in the background, so unlike macOS the widgets compose **in the widget extension**: it links the app's Kotlin framework, and Swift calls into it for every timeline and every click. The app is never needed for either.

- **Swift to Kotlin.** `NucleusWidgetKit.swift` finds `NucleusWidgetKitEntryPoints`, a Kotlin object with an exact Objective-C name (`@ObjCName(exact = true)`), through the Objective-C runtime and sends it one string request (`render` / `perform`, fields separated by U+001F). The framework must export `widget-ios` for the class to exist. `@CName` C functions were tried first: Kotlin/Native only exports them from the module compiled into the binary, never from a library.
- **Kotlin to WidgetKit.** WidgetKit is Swift-only and absent from Kotlin/Native's platform libraries. `WidgetCenter.kt` calls `+[NucleusWidgetCenter call:]`, a Swift class the app adds, through a cinterop helper (`objcbridge.def`: `NSClassFromString` + `objc_msgSend`). Without the class, `WidgetCenter.isAvailable` is false and the calls are no-ops; nothing is linked, so a framework without it still loads.
- **State.** In the App Group container, `NucleusWidgets/state/<kind>/<family>.json`, never cached: the app (`updateWidgetState`) and the extension (clicks) both write it, atomically. `refresh(id)` only reloads the kind's timelines; the extension composes on the next one.
- **Rendering.** The extension's provider asks Kotlin for the tree (`WidgetTreeEncoder`, shared with macOS) and draws it with the shared SwiftUI renderer. Images are absolute paths: app resources in the app bundle (readable from its extension), bytes and URLs written into the App Group.
- **Clicks.** `NucleusWidgetAction` (AppIntent, in the shared renderer) runs `WidgetActionSignatures.run` in Kotlin, the stale-click check shared with macOS, then saves the state; WidgetKit reloads the timeline when the intent returns.
- **Identity.** As on macOS, a `WidgetId` is `<kind>/<family>`: no instance identity without a configuration intent. Text and number entry and customization are `widget-windows` APIs, never rendered here.
- `isLaunchedByWidgetHost` / `isWidgetHostOnly()` are always false on iOS: WidgetKit never launches the app for a widget.

## E2E (simulator)

`examples/widget-ios-demo/build-app.sh` builds without an Xcode project: the Kotlin static framework (Gradle), the SwiftUI app and the extension (`swiftc`, linked like Xcode links an extension, `-e _NSExtensionMain`, with the AppIntents metadata from `appintentsmetadataprocessor`), ad-hoc signed with the App Group, installed and launched. Checked on an iPhone 17 Pro simulator (iOS 27):
- the gallery shows the preview Kotlin composed in the extension;
- **+1** on the home screen widget runs, including with the app quit;
- the app reads the state the extension wrote and lists the widget (`currentConfigurations`);
- **Push +10** from the app redraws the widget.

The simulator accepts ad-hoc clicks; a device needs a team-signed build, as on macOS. `./gradlew :widget-ios:iosSimulatorArm64Test` runs the host and the entry points against a temporary store.
